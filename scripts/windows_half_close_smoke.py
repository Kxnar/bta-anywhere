"""Repeated, serial Windows half-close smoke test.

This intentionally exercises only the production Rust relay (Quinn), the shaded
Java tunnel (Netty QUIC), and one local echo socket.  It leaves authentication
rejection, quotas, reconnects, relay restarts, and concurrent streams to the
full cross-language end-to-end test.
"""

from __future__ import annotations

import argparse
import collections
import contextlib
import http.client
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
    """Echo only after the guest write half has closed, then close our write half."""

    def handle(self) -> None:
        received = bytearray()
        while True:
            chunk = self.request.recv(64 * 1024)
            if not chunk:
                break
            received.extend(chunk)
        self.request.sendall(received)
        self.request.shutdown(socket.SHUT_WR)


class EchoServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True
    request_queue_size = 8


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--relay-binary", required=True, type=Path)
    parser.add_argument("--tunnel-jar", required=True, type=Path)
    parser.add_argument("--java", default="java")
    parser.add_argument("--iterations", type=int, default=100)
    parser.add_argument("--event-loop-threads", type=int, default=1)
    parser.add_argument("--payload-bytes", type=int, default=45_076)
    parser.add_argument("--timeout-seconds", type=float, default=5.0)
    arguments = parser.parse_args()
    if not 1 <= arguments.iterations <= 10_000:
        parser.error("--iterations must be between 1 and 10000")
    if not 1 <= arguments.event_loop_threads <= 64:
        parser.error("--event-loop-threads must be between 1 and 64")
    minimum_payload_bytes = len(f"wave-{arguments.iterations - 1}-connection-0:".encode("ascii"))
    if arguments.payload_bytes < minimum_payload_bytes:
        parser.error(
            f"--payload-bytes must be at least {minimum_payload_bytes} to contain every iteration label"
        )
    if not 1.0 <= arguments.timeout_seconds <= 30.0:
        parser.error("--timeout-seconds must be between 1 and 30")
    return arguments


def start_reader(
    stream: object,
    label: str,
    lines: collections.deque[str],
    events: queue.Queue[tuple[str, str]] | None = None,
) -> threading.Thread:
    def read() -> None:
        assert hasattr(stream, "readline")
        for raw in iter(stream.readline, ""):
            line = raw.rstrip("\r\n")
            lines.append(f"{label}: {line}")
            if events is not None:
                events.put((label, line))

    thread = threading.Thread(target=read, name=f"half-close-{label}", daemon=True)
    thread.start()
    return thread


def stop_process(process: subprocess.Popen[str] | None, graceful: bool = False) -> None:
    if process is None or process.poll() is not None:
        return
    if graceful and process.stdin is not None:
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


def wait_ready(timeout: float) -> None:
    deadline = time.monotonic() + timeout
    last_error: BaseException | None = None
    while time.monotonic() < deadline:
        try:
            connection = http.client.HTTPConnection("127.0.0.1", 9090, timeout=1)
            connection.request("GET", "/readyz")
            response = connection.getresponse()
            response.read()
            connection.close()
            if response.status == 200:
                return
        except (OSError, http.client.HTTPException) as failure:
            last_error = failure
        time.sleep(0.1)
    raise TimeoutError(f"relay did not become ready: {last_error}")


def metrics() -> str:
    connection = http.client.HTTPConnection("127.0.0.1", 9090, timeout=2)
    connection.request("GET", "/metrics")
    response = connection.getresponse()
    body = response.read().decode("utf-8")
    connection.close()
    return body


def metric_value(body: str, metric: str) -> float:
    match = re.search(rf"^{re.escape(metric)}\s+([0-9.eE+-]+)$", body, re.MULTILINE)
    if match is None:
        raise AssertionError(f"missing relay metric {metric}")
    return float(match.group(1))


def wait_for_no_active_connections(label: str, guest_port: int, timeout: float) -> None:
    deadline = time.monotonic() + timeout
    active = float("nan")
    while time.monotonic() < deadline:
        active = metric_value(metrics(), "bta_anywhere_active_connections")
        if active == 0:
            return
        time.sleep(0.05)
    raise TimeoutError(
        f"label={label} guest_source_port={guest_port} relay_active_connections={active} task_exit=missing"
    )


def wait_for_public_port(tunnel: subprocess.Popen[str], events: queue.Queue[tuple[str, str]], timeout: float) -> int:
    deadline = time.monotonic() + timeout
    endpoint = re.compile(r"^Public endpoint: (?:\[[^]]+]|[^:]+):(\d+)$")
    while time.monotonic() < deadline:
        if tunnel.poll() is not None:
            raise RuntimeError(f"Netty tunnel exited before registration ({tunnel.returncode})")
        try:
            source, line = events.get(timeout=0.2)
        except queue.Empty:
            continue
        if source == "tunnel-out":
            match = endpoint.match(line)
            if match:
                return int(match.group(1))
    raise TimeoutError("Netty tunnel did not publish a public endpoint")


def one_half_close(public_port: int, iteration: int, payload_bytes: int, timeout: float) -> tuple[str, int, int]:
    # The label intentionally matches the temporary Java bridge trace recognizer.
    label = f"wave-{iteration}-connection-0"
    prefix = label.encode("ascii") + b":"
    if payload_bytes < len(prefix):
        raise ValueError(f"payload size {payload_bytes} is too small for label {label!r}")
    payload = prefix + bytes([65 + iteration % 26]) * (payload_bytes - len(prefix))
    with socket.create_connection(("127.0.0.1", public_port), timeout=timeout) as guest:
        guest.settimeout(timeout)
        guest_port = guest.getsockname()[1]
        guest.sendall(payload)
        guest.shutdown(socket.SHUT_WR)
        received = bytearray()
        while len(received) < len(payload):
            try:
                chunk = guest.recv(64 * 1024)
            except TimeoutError as failure:
                raise TimeoutError(
                    f"label={label} guest_source_port={guest_port} bytes={len(received)}/{len(payload)} EOF=missing"
                ) from failure
            if not chunk:
                raise AssertionError(
                    f"label={label} guest_source_port={guest_port} bytes={len(received)}/{len(payload)} EOF=early"
                )
            received.extend(chunk)
        try:
            eof = guest.recv(1)
        except TimeoutError as failure:
            raise TimeoutError(
                f"label={label} guest_source_port={guest_port} bytes={len(received)}/{len(payload)} EOF=missing"
            ) from failure
        if eof:
            raise AssertionError(f"label={label} guest_source_port={guest_port} received bytes after expected payload")
    if bytes(received) != payload:
        raise AssertionError(f"label={label} guest_source_port={guest_port} byte-exact assertion failed")
    return label, guest_port, len(payload)


def main() -> int:
    arguments = parse_arguments()
    relay_binary = arguments.relay_binary.resolve()
    tunnel_jar = arguments.tunnel_jar.resolve()
    if not relay_binary.is_file() or not tunnel_jar.is_file():
        raise FileNotFoundError("--relay-binary and --tunnel-jar must both be existing files")

    relay: subprocess.Popen[str] | None = None
    tunnel: subprocess.Popen[str] | None = None
    readers: list[threading.Thread] = []
    lines: collections.deque[str] = collections.deque(maxlen=2_000)
    events: queue.Queue[tuple[str, str]] = queue.Queue()
    with tempfile.TemporaryDirectory(prefix="bta-anywhere-half-close-") as temporary:
        work = Path(temporary)
        development = work / "relay"
        subprocess.run([str(relay_binary), "init-dev", "--output", str(development)], check=True, timeout=20)
        config = development / "relay.toml"
        text = config.read_text(encoding="utf-8")
        for before, after in (
            ("tcp_port_end = 30100", "tcp_port_end = 30000"),
            ("max_connections_per_session = 8", "max_connections_per_session = 1"),
            ("max_accepts_per_minute = 30", "max_accepts_per_minute = 10000"),
        ):
            if before not in text:
                raise AssertionError(f"development config did not contain {before!r}")
            text = text.replace(before, after)
        config.write_text(text, encoding="utf-8")

        echo = EchoServer(("127.0.0.1", 0), EchoHandler)
        echo_thread = threading.Thread(target=echo.serve_forever, name="half-close-echo", daemon=True)
        echo_thread.start()
        try:
            relay = subprocess.Popen(
                [str(relay_binary), "run", "--config", str(config)], stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, bufsize=1,
            )
            assert relay.stdout is not None and relay.stderr is not None
            readers += [start_reader(relay.stdout, "relay-out", lines), start_reader(relay.stderr, "relay-err", lines)]
            wait_ready(20)
            tunnel = subprocess.Popen(
                [arguments.java, f"-Dio.netty.eventLoopThreads={arguments.event_loop_threads}",
                 "-jar", str(tunnel_jar), "expose", "--relay", "127.0.0.1:25575", "--ca", str(development / "trust.pem"),
                 "--token-file", str(development / "access.token"), "--local", f"127.0.0.1:{echo.server_address[1]}",
                 "--client-id", "cross-impl-half-close-repro"],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, bufsize=1,
            )
            assert tunnel.stdout is not None and tunnel.stderr is not None
            readers += [
                start_reader(tunnel.stdout, "tunnel-out", lines, events),
                start_reader(tunnel.stderr, "tunnel-err", lines),
            ]
            public_port = wait_for_public_port(tunnel, events, 30)
            for iteration in range(arguments.iterations):
                try:
                    label, guest_port, payload_bytes = one_half_close(
                        public_port, iteration, arguments.payload_bytes, arguments.timeout_seconds
                    )
                    wait_for_no_active_connections(label, guest_port, arguments.timeout_seconds)
                    print(f"PASS iteration={iteration} label={label} guest_source_port={guest_port} bytes={payload_bytes} EOF=observed")
                except BaseException as failure:
                    with contextlib.suppress(Exception):
                        snapshot = metrics()
                        print("FAIL relay_metrics=" + repr({
                            "active": metric_value(snapshot, "bta_anywhere_active_connections"),
                            "guest_to_host": metric_value(snapshot, "bta_anywhere_bytes_guest_to_host_total"),
                            "host_to_guest": metric_value(snapshot, "bta_anywhere_bytes_host_to_guest_total"),
                        }))
                    print("FAIL bounded relay and tunnel output follows.")
                    raise RuntimeError(f"iteration={iteration} failed") from failure
            print(f"PASS serial Windows half-close smoke: iterations={arguments.iterations} event_loop_threads={arguments.event_loop_threads}")
            return 0
        except BaseException:
            stop_process(tunnel, graceful=True)
            tunnel = None
            stop_process(relay)
            relay = None
            for reader in readers:
                reader.join(timeout=2)
            if lines:
                print("\n".join(lines))
            raise
        finally:
            stop_process(tunnel, graceful=True)
            stop_process(relay)
            echo.shutdown()
            echo.server_close()
            echo_thread.join(timeout=5)


if __name__ == "__main__":
    raise SystemExit(main())
