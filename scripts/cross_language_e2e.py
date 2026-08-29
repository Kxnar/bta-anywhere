#!/usr/bin/env python3
"""Exercise the Rust relay and shaded Java tunnel against a byte-exact TCP echo service."""

from __future__ import annotations

import argparse
import concurrent.futures
import contextlib
import http.client
import os
import queue
import re
import socket
import socketserver
import subprocess
import tempfile
import threading
import time
from pathlib import Path


class EchoHandler(socketserver.BaseRequestHandler):
    def handle(self) -> None:
        trace_id = self.server.trace_open(self.client_address)
        received = bytearray()
        while True:
            chunk = self.request.recv(64 * 1024)
            if not chunk:
                break
            received.extend(chunk)
            self.server.trace_update(trace_id, "reading", received)
            if len(received) > 2 * 1024 * 1024:
                raise RuntimeError("test client exceeded the echo-service safety limit")
        self.server.trace_update(trace_id, "responding", received)
        try:
            self.request.sendall(received)
            self.request.shutdown(socket.SHUT_WR)
            self.server.trace_update(trace_id, "complete", received)
        except OSError:
            # Quota/slow-client cases intentionally close test guests early.
            self.server.trace_update(trace_id, "peer-closed", received)


class ThreadingEchoServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True
    # Keep the synthetic local service above the relay's eight-guest session limit.
    # socketserver's default backlog is five and made the relay load test intermittent.
    request_queue_size = 64

    def __init__(self, *args: object, **kwargs: object) -> None:
        super().__init__(*args, **kwargs)
        self.trace_lock = threading.Lock()
        self.trace_sequence = 0
        self.traces: dict[int, dict[str, object]] = {}

    def trace_open(self, remote: tuple[str, int]) -> int:
        with self.trace_lock:
            self.trace_sequence += 1
            trace_id = self.trace_sequence
            self.traces[trace_id] = {
                "remote": f"{remote[0]}:{remote[1]}",
                "state": "connected",
                "bytes": 0,
                "label": "",
            }
            return trace_id

    def trace_update(self, trace_id: int, state: str, received: bytearray) -> None:
        label_bytes = bytes(received[:32]).partition(b":")[0]
        label = label_bytes.decode("ascii", errors="replace") if label_bytes else ""
        with self.trace_lock:
            trace = self.traces.get(trace_id)
            if trace is not None:
                trace.update(state=state, bytes=len(received), label=label)

    def reset_traces(self) -> None:
        with self.trace_lock:
            self.traces.clear()

    def trace_snapshot(self) -> list[dict[str, object]]:
        with self.trace_lock:
            return [dict(trace_id=trace_id, **details) for trace_id, details in self.traces.items()]


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--relay-binary", required=True, type=Path)
    parser.add_argument("--tunnel-jar", required=True, type=Path)
    parser.add_argument("--java", default="java")
    parser.add_argument("--concurrency-waves", type=int, default=1)
    arguments = parser.parse_args()
    if not 1 <= arguments.concurrency_waves <= 100:
        parser.error("--concurrency-waves must be between 1 and 100")
    return arguments


def start_reader(
    stream: object,
    label: str,
    lines: list[str],
    events: queue.Queue[tuple[str, str]],
) -> threading.Thread:
    def read() -> None:
        assert hasattr(stream, "readline")
        for raw in iter(stream.readline, ""):
            line = raw.rstrip("\r\n")
            lines.append(f"{label}: {line}")
            events.put((label, line))

    thread = threading.Thread(target=read, name=f"e2e-{label}", daemon=True)
    thread.start()
    return thread


def wait_http(path: str, expected_status: int = 200, timeout: float = 20.0) -> str:
    deadline = time.monotonic() + timeout
    last_error: BaseException | None = None
    while time.monotonic() < deadline:
        try:
            connection = http.client.HTTPConnection("127.0.0.1", 9090, timeout=1)
            connection.request("GET", path)
            response = connection.getresponse()
            body = response.read().decode("utf-8")
            connection.close()
            if response.status == expected_status:
                return body
        except (OSError, http.client.HTTPException) as failure:
            last_error = failure
        time.sleep(0.1)
    raise TimeoutError(f"admin endpoint {path} did not become ready: {last_error}")


def stop_process(process: subprocess.Popen[str] | None, graceful_stdin: bool = False) -> None:
    if process is None or process.poll() is not None:
        return
    if graceful_stdin and process.stdin is not None:
        with contextlib.suppress(OSError):
            process.stdin.write("stop\n")
            process.stdin.flush()
        try:
            process.wait(timeout=10)
            return
        except subprocess.TimeoutExpired:
            pass
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


def echo_round_trip(public_port: int, payload: bytes, timeout: float = 15.0) -> None:
    with socket.create_connection(("127.0.0.1", public_port), timeout=timeout) as guest:
        guest.settimeout(timeout)
        guest.sendall(payload)
        guest.shutdown(socket.SHUT_WR)
        received = bytearray()
        while True:
            try:
                chunk = guest.recv(64 * 1024)
            except TimeoutError as failure:
                raise TimeoutError(
                    f"received {len(received)} of {len(payload)} bytes before the stream stalled"
                ) from failure
            if not chunk:
                break
            received.extend(chunk)
    if received != payload:
        raise AssertionError(f"byte mismatch: sent {len(payload)}, received {len(received)}")


def wait_for_tunnel(public_port: int, timeout: float = 90.0) -> None:
    deadline = time.monotonic() + timeout
    last_error: BaseException | None = None
    while time.monotonic() < deadline:
        try:
            echo_round_trip(public_port, b"relay-restart-probe", timeout=3)
            return
        except (OSError, TimeoutError, AssertionError) as failure:
            last_error = failure
            time.sleep(0.25)
    raise TimeoutError(f"tunnel did not recover: {last_error}")


def metric_value(body: str, metric: str) -> float:
    match = re.search(rf"^{re.escape(metric)}\s+([0-9.eE+-]+)$", body, re.MULTILINE)
    if match is None:
        raise AssertionError(f"metric {metric} has no unlabelled sample")
    return float(match.group(1))


def wait_metric(metric: str, minimum: float, timeout: float = 10.0) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if metric_value(wait_http("/metrics"), metric) >= minimum:
            return
        time.sleep(0.1)
    raise TimeoutError(f"metric {metric} did not reach {minimum}")


def wait_metric_equal(metric: str, expected: float, timeout: float = 10.0) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if metric_value(wait_http("/metrics"), metric) == expected:
            return
        time.sleep(0.1)
    raise TimeoutError(f"metric {metric} did not become {expected}")


def main() -> int:
    arguments = parse_arguments()
    relay_binary = arguments.relay_binary.resolve()
    tunnel_jar = arguments.tunnel_jar.resolve()
    if not relay_binary.is_file() or not tunnel_jar.is_file():
        raise FileNotFoundError("relay binary and tunnel JAR must exist")

    relay: subprocess.Popen[str] | None = None
    tunnel: subprocess.Popen[str] | None = None
    tunnel_readers: list[threading.Thread] = []
    output_lines: list[str] = []
    events: queue.Queue[tuple[str, str]] = queue.Queue()

    with tempfile.TemporaryDirectory(prefix="bta-anywhere-e2e-") as temporary:
        working = Path(temporary)
        development = working / "relay"
        subprocess.run(
            [str(relay_binary), "init-dev", "--output", str(development)],
            check=True,
            text=True,
            capture_output=True,
            timeout=20,
        )
        relay_config = development / "relay.toml"
        configuration = relay_config.read_text(encoding="utf-8")
        if "tcp_port_end = 30100" not in configuration:
            raise AssertionError("development config did not contain the expected port range")
        configuration = configuration.replace("tcp_port_end = 30100", "tcp_port_end = 30000")
        if arguments.concurrency_waves > 1:
            expected_rate = "max_accepts_per_minute = 30"
            if expected_rate not in configuration:
                raise AssertionError("development config did not contain the expected source rate limit")
            configuration = configuration.replace(expected_rate, "max_accepts_per_minute = 10000")
        relay_config.write_text(configuration, encoding="utf-8")

        echo = ThreadingEchoServer(("127.0.0.1", 0), EchoHandler)
        echo_thread = threading.Thread(target=echo.serve_forever, name="e2e-echo", daemon=True)
        echo_thread.start()
        local_port = echo.server_address[1]

        def launch_relay() -> subprocess.Popen[str]:
            process = subprocess.Popen(
                [str(relay_binary), "run", "--config", str(relay_config)],
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                bufsize=1,
            )
            assert process.stdout is not None and process.stderr is not None
            start_reader(process.stdout, "relay-out", output_lines, events)
            start_reader(process.stderr, "relay-err", output_lines, events)
            wait_http("/readyz")
            return process

        try:
            relay = launch_relay()

            bad_token = working / "bad.token"
            bad_token.write_text("deliberately-invalid-test-token\n", encoding="utf-8")
            try:
                rejected = subprocess.run(
                    [
                        arguments.java,
                        "-jar",
                        str(tunnel_jar),
                        "expose",
                        "--relay",
                        "127.0.0.1:25575",
                        "--ca",
                        str(development / "trust.pem"),
                        "--token-file",
                        str(bad_token),
                        "--local",
                        f"127.0.0.1:{local_port}",
                    ],
                    text=True,
                    capture_output=True,
                    timeout=20,
                )
            except subprocess.TimeoutExpired as failure:
                output = f"{failure.stdout or ''}{failure.stderr or ''}"
                raise AssertionError(f"invalid-token client did not terminate; output: {output}") from failure
            if rejected.returncode == 0 or "authentication_failed" not in (rejected.stdout + rejected.stderr):
                raise AssertionError("relay did not reject an invalid access token")
            if "deliberately-invalid-test-token" in (rejected.stdout + rejected.stderr):
                raise AssertionError("access token leaked into Java CLI output")

            tunnel = subprocess.Popen(
                [
                    arguments.java,
                    "-Dbtaanywhere.bridgeTrace=true",
                    "-jar",
                    str(tunnel_jar),
                    "expose",
                    "--relay",
                    "127.0.0.1:25575",
                    "--ca",
                    str(development / "trust.pem"),
                    "--token-file",
                    str(development / "access.token"),
                    "--local",
                    f"127.0.0.1:{local_port}",
                    "--client-id",
                    "cross-language-e2e",
                ],
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                bufsize=1,
            )
            assert tunnel.stdout is not None and tunnel.stderr is not None
            tunnel_readers.append(start_reader(tunnel.stdout, "tunnel-out", output_lines, events))
            tunnel_readers.append(start_reader(tunnel.stderr, "tunnel-err", output_lines, events))

            endpoint_pattern = re.compile(r"^Public endpoint: (?:\[[^]]+]|[^:]+):(\d+)$")
            deadline = time.monotonic() + 30
            public_port: int | None = None
            while time.monotonic() < deadline and public_port is None:
                if tunnel.poll() is not None:
                    raise RuntimeError(f"tunnel exited before registration with {tunnel.returncode}")
                try:
                    label, line = events.get(timeout=0.25)
                except queue.Empty:
                    continue
                if label == "tunnel-out":
                    match = endpoint_pattern.match(line)
                    if match:
                        public_port = int(match.group(1))
            if public_port is None:
                raise TimeoutError("Java tunnel did not publish an endpoint")

            echo_round_trip(public_port, b"initial-byte-exact-probe" * 2_048)

            rejected_before = metric_value(wait_http("/metrics"), "bta_anywhere_rejected_total")
            exhausted_lines: list[str] = []
            exhausted = subprocess.Popen(
                [
                    arguments.java,
                    "-jar",
                    str(tunnel_jar),
                    "expose",
                    "--relay",
                    "127.0.0.1:25575",
                    "--ca",
                    str(development / "trust.pem"),
                    "--token-file",
                    str(development / "access.token"),
                    "--local",
                    f"127.0.0.1:{local_port}",
                    "--client-id",
                    "port-exhaustion-e2e",
                ],
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                bufsize=1,
            )
            assert exhausted.stdout is not None and exhausted.stderr is not None
            start_reader(exhausted.stdout, "exhausted-out", exhausted_lines, events)
            start_reader(exhausted.stderr, "exhausted-err", exhausted_lines, events)
            try:
                wait_metric("bta_anywhere_rejected_total", rejected_before + 1)
                if any("Public endpoint:" in line for line in exhausted_lines):
                    raise AssertionError("a second tunnel acquired a port from an exhausted one-port pool")
            finally:
                stop_process(exhausted)

            slow_guests: list[socket.socket] = []
            try:
                for _ in range(8):
                    guest = socket.create_connection(("127.0.0.1", public_port), timeout=5)
                    guest.settimeout(5)
                    slow_guests.append(guest)
                wait_metric("bta_anywhere_active_connections", 8)
                with socket.create_connection(("127.0.0.1", public_port), timeout=5) as ninth:
                    ninth.settimeout(5)
                    ninth.sendall(b"quota-probe")
                    try:
                        response = ninth.recv(1)
                    except OSError:
                        response = b""
                    if response:
                        raise AssertionError("ninth slow guest was not rejected at the session quota")
            finally:
                for guest in slow_guests:
                    with contextlib.suppress(OSError):
                        guest.shutdown(socket.SHUT_RDWR)
                    guest.close()
            wait_metric_equal("bta_anywhere_active_connections", 0)

            for wave in range(arguments.concurrency_waves):
                echo.reset_traces()
                payloads = [
                    (
                        f"wave-{wave}-connection-{index}:".encode("ascii")
                        + os.urandom(32 * 1024 + index * 4096)
                    )
                    for index in range(8)
                ]
                with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
                    futures = [pool.submit(echo_round_trip, public_port, payload) for payload in payloads]
                    for index, future in enumerate(futures):
                        try:
                            future.result(timeout=20)
                        except Exception as failure:
                            metrics = wait_http("/metrics")
                            diagnostics = {
                                "active": metric_value(metrics, "bta_anywhere_active_connections"),
                                "guest_to_host": metric_value(metrics, "bta_anywhere_bytes_guest_to_host_total"),
                                "host_to_guest": metric_value(metrics, "bta_anywhere_bytes_host_to_guest_total"),
                                "echo": echo.trace_snapshot(),
                            }
                            raise RuntimeError(
                                f"concurrent byte-exact wave {wave} stream {index} failed; "
                                f"diagnostics={diagnostics}"
                            ) from failure
                wait_metric_equal("bta_anywhere_active_connections", 0)

            metrics = wait_http("/metrics")
            for metric in (
                "bta_anywhere_connections_total",
                "bta_anywhere_bytes_guest_to_host_total",
                "bta_anywhere_bytes_host_to_guest_total",
            ):
                if metric not in metrics:
                    raise AssertionError(f"missing metric {metric}")
            for forbidden_label in ("username=", "token=", "source_ip=", "session_id="):
                if forbidden_label in metrics:
                    raise AssertionError(f"sensitive metrics label found: {forbidden_label}")

            stop_process(relay)
            relay = None
            time.sleep(1.0)
            relay = launch_relay()
            wait_for_tunnel(public_port)

            stop_process(tunnel, graceful_stdin=True)
            if tunnel.returncode != 0:
                raise RuntimeError(f"tunnel did not stop cleanly: {tunnel.returncode}")
            tunnel = None
            print(
                "Cross-language QUIC test passed: authentication, port exhaustion, slow-client quota, "
                f"{arguments.concurrency_waves} wave(s) of 8 byte-exact streams, clean shutdown, "
                "and relay restart recovery."
            )
            return 0
        except BaseException:
            stop_process(tunnel, graceful_stdin=True)
            tunnel = None
            for reader in tunnel_readers:
                reader.join(timeout=2)
            if output_lines:
                print("\n".join(output_lines[-1_000:]))
            raise
        finally:
            stop_process(tunnel, graceful_stdin=True)
            stop_process(relay)
            echo.shutdown()
            echo.server_close()
            echo_thread.join(timeout=5)


if __name__ == "__main__":
    raise SystemExit(main())
