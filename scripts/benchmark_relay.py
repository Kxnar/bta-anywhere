#!/usr/bin/env python3
"""Windows-only, loopback-only BTA Anywhere direct/relay benchmark.

No game, world, external relay, or third-party Python dependency is used.
Results are written even when an assertion or subprocess fails.
"""

from __future__ import annotations

import argparse
import collections
import concurrent.futures
import contextlib
import ctypes
import hashlib
import http.client
import json
import math
import os
import platform
import queue
import random
import re
import socket
import socketserver
import statistics
import subprocess
import sys
import tempfile
import threading
import time
from datetime import datetime, timezone
from pathlib import Path

SCHEMA_VERSION = 2
SIZES = (1024, 65536, 1048576)
ACTIVE_METRIC = "bta_anywhere_active_connections"
CHUNK = 65536


def percentile(values: list[float], percentage: float) -> float:
    if not values:
        raise ValueError("cannot aggregate an empty sample")
    ordered = sorted(values)
    position = (len(ordered) - 1) * percentage / 100
    lower = int(position)
    fraction = position - lower
    return ordered[lower] * (1 - fraction) + ordered[min(lower + 1, len(ordered) - 1)] * fraction


def summary(values: list[float]) -> dict[str, float | int]:
    return {"count": len(values), "p50": percentile(values, 50),
            "p95": percentile(values, 95), "p99": percentile(values, 99)}


def bounded_failure(error: BaseException) -> dict[str, str]:
    return {"type": type(error).__name__, "message": str(error)[:500]}


def failure_category(error: BaseException) -> str:
    message = str(error).lower()
    if "active streams to drain" in message:
        return "leaked_active_stream_gauges"
    if "byte mismatch" in message:
        return "byte_mismatches"
    if "eof" in message or "missing bytes" in message:
        return "missing_eofs"
    if isinstance(error, TimeoutError) or "timed out" in message:
        return "timeouts"
    return "failed_transfers"


def read_exact(sock: socket.socket, size: int) -> bytes:
    data = bytearray()
    while len(data) < size:
        chunk = sock.recv(min(CHUNK, size - len(data)))
        if not chunk:
            raise AssertionError(f"early EOF after {len(data)}/{size} bytes")
        data.extend(chunk)
    return bytes(data)


class EchoHandler(socketserver.BaseRequestHandler):
    def handle(self) -> None:
        self.request.settimeout(30)
        mode = read_exact(self.request, 1)
        if mode == b"R":
            length = int.from_bytes(read_exact(self.request, 4), "big")
            if length > 1048576:
                raise ValueError("benchmark request exceeds 1 MiB")
            self.request.sendall(read_exact(self.request, length))
        elif mode == b"H":
            received = bytearray()
            while chunk := self.request.recv(CHUNK):
                received.extend(chunk)
                if len(received) > 1048576:
                    raise ValueError("benchmark half-close exceeds 1 MiB")
            self.request.sendall(received)
        elif mode == b"T":
            while chunk := self.request.recv(CHUNK):
                self.request.sendall(chunk)
        else:
            raise ValueError("unknown benchmark mode")
        self.request.shutdown(socket.SHUT_WR)


class EchoServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True
    request_queue_size = 64


def payload(seed: int, size: int, index: int) -> bytes:
    return random.Random(f"{seed}:{size}:{index}").randbytes(size)


def transfer(port: int, mode: str, data: bytes, timeout: float) -> float:
    started = time.perf_counter()
    with socket.create_connection(("127.0.0.1", port), timeout=timeout) as sock:
        sock.settimeout(timeout)
        if mode == "request_response":
            sock.sendall(b"R" + len(data).to_bytes(4, "big") + data)
        elif mode == "half_close":
            sock.sendall(b"H" + data)
            sock.shutdown(socket.SHUT_WR)
        else:
            raise ValueError(mode)
        received = read_exact(sock, len(data))
        if received != data:
            raise AssertionError("byte mismatch")
        if sock.recv(1):
            raise AssertionError("missing EOF or surplus response bytes")
    return (time.perf_counter() - started) * 1000


def throughput_stream(port: int, block: bytes, seconds: float, timeout: float,
                      pace_seconds: float = 0) -> dict[str, float | int]:
    sent = 0
    received = 0
    send_error: list[BaseException] = []
    started = time.perf_counter()
    doubled_block = block + block
    with socket.create_connection(("127.0.0.1", port), timeout=timeout) as sock:
        sock.settimeout(timeout)
        sock.sendall(b"T")
        stop_at = started + seconds

        def send() -> None:
            nonlocal sent
            try:
                while time.perf_counter() < stop_at:
                    sock.sendall(block)
                    sent += len(block)
                    if pace_seconds:
                        time.sleep(pace_seconds)
                sock.shutdown(socket.SHUT_WR)
            except BaseException as error:
                send_error.append(error)
                with contextlib.suppress(OSError):
                    sock.shutdown(socket.SHUT_WR)

        sender = threading.Thread(target=send, name="benchmark-sender", daemon=True)
        sender.start()
        try:
            while chunk := sock.recv(CHUNK):
                shift = received % len(block)
                if chunk != doubled_block[shift:shift + len(chunk)]:
                    raise AssertionError(f"byte mismatch starting at offset {received}")
                received += len(chunk)
            sender.join(timeout=timeout)
            if sender.is_alive():
                raise TimeoutError("sender did not terminate")
            if send_error:
                raise send_error[0]
            if received != sent:
                raise AssertionError(f"missing bytes or EOF: sent={sent} received={received}")
        finally:
            with contextlib.suppress(OSError):
                sock.shutdown(socket.SHUT_RDWR)
            sender.join(timeout=1)
    elapsed = time.perf_counter() - started
    return {"seconds": elapsed, "guest_to_host_bytes": sent,
            "host_to_guest_bytes": received}


def run_concurrent(function, count: int) -> list:
    with concurrent.futures.ThreadPoolExecutor(max_workers=count) as pool:
        futures = [pool.submit(function, i) for i in range(count)]
        return [future.result() for future in futures]


def http_get(port: int, path: str) -> str:
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=2)
    try:
        connection.request("GET", path)
        response = connection.getresponse()
        body = response.read().decode("utf-8")
        if response.status != 200:
            raise RuntimeError(f"admin {path} returned HTTP {response.status}")
        return body
    finally:
        connection.close()


def metric(body: str, name: str) -> float:
    match = re.search(rf"^{re.escape(name)}\s+([0-9.eE+-]+)$", body, re.MULTILINE)
    if match is None:
        raise AssertionError(f"missing unlabelled metric {name}")
    return float(match.group(1))


def wait_until(check, timeout: float, description: str) -> None:
    deadline = time.monotonic() + timeout
    last: BaseException | None = None
    while time.monotonic() < deadline:
        try:
            if check():
                return
        except (OSError, http.client.HTTPException) as error:
            last = error
        time.sleep(0.1)
    raise TimeoutError(f"timed out waiting for {description}: {last}")


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


class FileTime(ctypes.Structure):
    _fields_ = [("low", ctypes.c_ulong), ("high", ctypes.c_ulong)]

    def seconds(self) -> float:
        return ((self.high << 32) | self.low) / 10_000_000


class MemoryCounters(ctypes.Structure):
    _fields_ = [("cb", ctypes.c_ulong), ("PageFaultCount", ctypes.c_ulong),
                ("PeakWorkingSetSize", ctypes.c_size_t), ("WorkingSetSize", ctypes.c_size_t),
                ("QuotaPeakPagedPoolUsage", ctypes.c_size_t), ("QuotaPagedPoolUsage", ctypes.c_size_t),
                ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t), ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
                ("PagefileUsage", ctypes.c_size_t), ("PeakPagefileUsage", ctypes.c_size_t)]


def process_sample(process: subprocess.Popen[str]) -> dict[str, float | int]:
    handle = ctypes.c_void_p(int(process._handle))  # Windows Popen handle, not a PID lookup.
    creation, exit_time, kernel, user = (FileTime() for _ in range(4))
    if not ctypes.windll.kernel32.GetProcessTimes(handle, ctypes.byref(creation), ctypes.byref(exit_time),
                                                  ctypes.byref(kernel), ctypes.byref(user)):
        raise OSError("GetProcessTimes failed")
    memory = MemoryCounters()
    memory.cb = ctypes.sizeof(memory)
    if not ctypes.windll.psapi.GetProcessMemoryInfo(handle, ctypes.byref(memory), memory.cb):
        raise OSError("GetProcessMemoryInfo failed")
    return {"cpu_seconds": kernel.seconds() + user.seconds(),
            "working_set_bytes": memory.WorkingSetSize,
            "peak_working_set_bytes": memory.PeakWorkingSetSize}


def machine_info(java: str) -> dict:
    def output(command: list[str]) -> str:
        result = subprocess.run(command, text=True, capture_output=True, timeout=15, check=True)
        return (result.stdout + result.stderr).strip().splitlines()[0]

    status = ctypes.create_string_buffer(12)
    if not ctypes.windll.kernel32.GetSystemPowerStatus(status):
        raise OSError("GetSystemPowerStatus failed")
    return {"os": platform.platform(), "windows_build": platform.version(),
            "architecture": platform.machine(), "cpu": platform.processor(),
            "logical_cores": os.cpu_count(),
            "ram_bytes": total_ram(),
            "ac_power": status.raw[0] == 1,
            "java": output([java, "-version"]), "rustc": output(["rustc", "--version"]),
            "cargo": output(["cargo", "--version"]), "python": sys.version.split()[0]}


def total_ram() -> int:
    class MemoryStatus(ctypes.Structure):
        _fields_ = [("dwLength", ctypes.c_ulong), ("dwMemoryLoad", ctypes.c_ulong),
                    ("ullTotalPhys", ctypes.c_ulonglong), ("ullAvailPhys", ctypes.c_ulonglong),
                    ("ullTotalPageFile", ctypes.c_ulonglong), ("ullAvailPageFile", ctypes.c_ulonglong),
                    ("ullTotalVirtual", ctypes.c_ulonglong), ("ullAvailVirtual", ctypes.c_ulonglong),
                    ("ullAvailExtendedVirtual", ctypes.c_ulonglong)]
    status = MemoryStatus()
    status.dwLength = ctypes.sizeof(status)
    if not ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status)):
        raise OSError("GlobalMemoryStatusEx failed")
    return status.ullTotalPhys


def stop_process(process: subprocess.Popen[str] | None, graceful: bool = False) -> bool:
    if process is None:
        return True
    if process.poll() is None and graceful and process.stdin:
        with contextlib.suppress(OSError):
            process.stdin.write("stop\n")
            process.stdin.flush()
        try:
            process.wait(timeout=10)
            return process.returncode == 0
        except subprocess.TimeoutExpired:
            pass
    forced = process.poll() is None
    if forced:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
    return process.poll() is not None and not (graceful and forced)


class Rig:
    def __init__(self, relay_binary: Path, tunnel_jar: Path, java: str, logs: collections.deque[str]):
        self.relay_binary = relay_binary
        self.tunnel_jar = tunnel_jar
        self.java = java
        self.logs = logs
        self.redactions: list[str] = []
        self.events: queue.Queue[str] = queue.Queue(maxsize=256)
        self.temporary = tempfile.TemporaryDirectory(prefix="bta-benchmark-")
        self.work = Path(self.temporary.name)
        self.relay: subprocess.Popen[str] | None = None
        self.tunnel: subprocess.Popen[str] | None = None
        self.echo: EchoServer | None = None
        self.echo_thread: threading.Thread | None = None
        ports: set[int] = set()
        while len(ports) < 3:
            ports.add(free_port())
        self.quic_port, self.admin_port, self.tcp_port = sorted(ports)
        self.public_port: int | None = None

    def reader(self, stream, label: str) -> None:
        for line in iter(stream.readline, ""):
            line = line.rstrip("\r\n")
            for secret in self.redactions:
                line = line.replace(secret, "<redacted>")
            line = line[:300]
            self.logs.append(f"{label}: {line}")
            if label == "tunnel-out":
                with contextlib.suppress(queue.Full):
                    self.events.put_nowait(line)

    def launch(self, args: list[str], label: str, stdin) -> subprocess.Popen[str]:
        process = subprocess.Popen(args, stdin=stdin, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   text=True, bufsize=1)
        assert process.stdout and process.stderr
        for stream, suffix in ((process.stdout, "out"), (process.stderr, "err")):
            threading.Thread(target=self.reader, args=(stream, f"{label}-{suffix}"), daemon=True).start()
        return process

    def start_relay(self) -> None:
        self.relay = self.launch([str(self.relay_binary), "run", "--config", str(self.work / "relay" / "relay.toml")],
                                 "relay", subprocess.DEVNULL)
        wait_until(lambda: bool(http_get(self.admin_port, "/readyz")), 20, "relay ready")

    def __enter__(self):
        try:
            return self._start()
        except BaseException:
            self.__exit__(None, None, None)
            raise

    def _start(self):
        development = self.work / "relay"
        subprocess.run([str(self.relay_binary), "init-dev", "--output", str(development)],
                       check=True, capture_output=True, text=True, timeout=20)
        self.redactions.append((development / "access.token").read_text(encoding="utf-8").strip())
        config = development / "relay.toml"
        source = config.read_text(encoding="utf-8")
        replacements = {
            'quic_listen = "0.0.0.0:25575"': f'quic_listen = "127.0.0.1:{self.quic_port}"',
            'tcp_bind_ip = "0.0.0.0"': 'tcp_bind_ip = "127.0.0.1"',
            'tcp_port_start = 30000': f'tcp_port_start = {self.tcp_port}',
            'tcp_port_end = 30100': f'tcp_port_end = {self.tcp_port}',
            'admin_listen = "127.0.0.1:9090"': f'admin_listen = "127.0.0.1:{self.admin_port}"',
            'max_accepts_per_minute = 30': 'max_accepts_per_minute = 10000',
        }
        for old, new in replacements.items():
            if old not in source:
                raise AssertionError(f"init-dev config missing expected field {old.split(' = ')[0]}")
            source = source.replace(old, new)
        config.write_text(source, encoding="utf-8")
        self.echo = EchoServer(("127.0.0.1", 0), EchoHandler)
        self.echo_thread = threading.Thread(target=self.echo.serve_forever, daemon=True)
        self.echo_thread.start()
        self.start_relay()
        self.tunnel = self.launch([self.java, "-jar", str(self.tunnel_jar), "expose",
                                   "--relay", f"127.0.0.1:{self.quic_port}",
                                   "--ca", str(development / "trust.pem"),
                                   "--token-file", str(development / "access.token"),
                                   "--local", f"127.0.0.1:{self.echo.server_address[1]}",
                                   "--client-id", "benchmark-v1"], "tunnel", subprocess.PIPE)
        pattern = re.compile(r"^Public endpoint: (?:\[[^]]+]|[^:]+):(\d+)$")
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            if self.tunnel.poll() is not None:
                raise RuntimeError(f"tunnel exited during registration: {self.tunnel.returncode}")
            try:
                line = self.events.get(timeout=0.2)
            except queue.Empty:
                continue
            match = pattern.match(line)
            if match:
                self.public_port = int(match.group(1))
                break
        if self.public_port is None:
            raise TimeoutError("tunnel did not publish a public endpoint")
        return self

    def metric(self, name: str) -> float:
        return metric(http_get(self.admin_port, "/metrics"), name)

    def assert_idle(self) -> None:
        wait_until(lambda: self.metric(ACTIVE_METRIC) == 0, 10, "active streams to drain")

    def reconnect(self, timeout: float) -> dict:
        assert self.public_port is not None
        old_port = self.public_port
        relay_before = process_sample(self.relay)
        stop_process(self.relay)
        self.relay = None
        time.sleep(1)
        started = time.perf_counter()
        self.start_relay()
        probe = payload(123, 1024, 0)
        wait_until(lambda: reconnect_probe(old_port, probe), timeout, "tunnel reconnect")
        if self.metric("bta_anywhere_active_sessions") != 1:
            raise AssertionError("expected one reconnected tunnel session")
        return {"scenario": "relay_restart_interrupts_tunnel_quic_connection",
                "completion_ms": (time.perf_counter() - started) * 1000,
                "old_endpoint_probe_succeeded": True,
                "fixed_one_port_range_limits_endpoint_retention_claim": True,
                "old_port": old_port, "relay_before_restart": relay_before,
                "relay_after_restart": process_sample(self.relay)}

    def __exit__(self, *_):
        self.tunnel_clean = stop_process(self.tunnel, graceful=True)
        self.relay_clean = stop_process(self.relay)
        if self.echo:
            self.echo.shutdown()
            self.echo.server_close()
        if self.echo_thread:
            self.echo_thread.join(timeout=5)
        self.temporary.cleanup()


def reconnect_probe(port: int, data: bytes) -> bool:
    try:
        transfer(port, "request_response", data, 3)
        return True
    except (OSError, TimeoutError, AssertionError):
        return False


def run_latency(rig: Rig, profile: str, seed: int) -> list[dict]:
    assert rig.echo and rig.public_port
    warmups, measured = (1, 3) if profile == "smoke" else (5, 30)
    cases = []
    for concurrency in (1, 8):
        for size in SIZES:
            for mode in ("request_response", "half_close"):
                case = {"concurrency": concurrency, "payload_bytes": size, "mode": mode,
                        "warmups": warmups, "paths": {}, "paired_delta_samples_ms": []}
                samples = {"direct": [], "relay": []}
                for wave in range(warmups + measured):
                    data = payload(seed, size, wave)
                    pair = {}
                    order = ("direct", "relay") if wave % 2 == 0 else ("relay", "direct")
                    for path in order:
                        port = rig.echo.server_address[1] if path == "direct" else rig.public_port
                        pair[path] = run_concurrent(lambda _: transfer(port, mode, data, 15), concurrency)
                        if path == "relay":
                            rig.assert_idle()
                    if wave >= warmups:
                        for path in ("direct", "relay"):
                            samples[path].extend(pair[path])
                        case["paired_delta_samples_ms"].extend(
                            relay - direct for direct, relay in zip(pair["direct"], pair["relay"])
                        )
                case["paths"] = {path: {"samples_ms": values, "latency_ms": summary(values)}
                                   for path, values in samples.items()}
                case["relay_added_ms"] = summary(case["paired_delta_samples_ms"])
                cases.append(case)
    return cases


def run_throughput(rig: Rig, profile: str, seed: int) -> list[dict]:
    assert rig.echo and rig.public_port
    runs, seconds = (1, 2.0) if profile == "smoke" else (5, 60.0)
    block = payload(seed, CHUNK, 999)
    cases = []
    for concurrency in (1, 8):
        case = {"concurrency": concurrency, "payload_bytes": len(block), "run_seconds_target": seconds,
                "paths": {"direct": [], "relay": []}}
        for run in range(runs):
            for path in (("direct", "relay") if run % 2 == 0 else ("relay", "direct")):
                port = rig.echo.server_address[1] if path == "direct" else rig.public_port
                samples = run_concurrent(lambda _: throughput_stream(port, block, seconds, 30), concurrency)
                elapsed = max(sample["seconds"] for sample in samples)
                row = {"seconds": elapsed,
                       "guest_to_host_bytes": sum(sample["guest_to_host_bytes"] for sample in samples),
                       "host_to_guest_bytes": sum(sample["host_to_guest_bytes"] for sample in samples)}
                row["guest_to_host_mib_s"] = row["guest_to_host_bytes"] / elapsed / 1048576
                row["host_to_guest_mib_s"] = row["host_to_guest_bytes"] / elapsed / 1048576
                case["paths"][path].append(row)
                if path == "relay":
                    rig.assert_idle()
        case["median_mib_s"] = {path: {direction: statistics.median(run[direction + "_mib_s"] for run in rows)
                                       for direction in ("guest_to_host", "host_to_guest")}
                                for path, rows in case["paths"].items()}
        cases.append(case)
    return cases


def run_soak(rig: Rig, seed: int, duration: float) -> dict:
    if not math.isfinite(duration) or not 7200 <= duration <= 14400:
        raise ValueError("soak duration must be between two and four hours")
    assert rig.public_port and rig.relay and rig.tunnel
    block = payload(seed, CHUNK, 1000)
    started = time.monotonic()
    samples = []
    totals = {"guest_to_host_bytes": 0, "host_to_guest_bytes": 0}
    while time.monotonic() - started < duration:
        remaining = duration - (time.monotonic() - started)
        streams = run_concurrent(lambda _: throughput_stream(rig.public_port, block, min(60, remaining), 30, 0.5), 8)
        for stream in streams:
            for direction in totals:
                totals[direction] += stream[direction]
        rig.assert_idle()
        samples.append({"elapsed_seconds": time.monotonic() - started,
                        "relay": process_sample(rig.relay), "tunnel": process_sample(rig.tunnel),
                        "active_connections": rig.metric(ACTIVE_METRIC)})
    return {"duration_seconds": time.monotonic() - started, "streams": 8, "totals": totals,
            "memory_samples": samples, "memory_growth_review": "PENDING_MANUAL_REVIEW",
            "active_connections_final": rig.metric(ACTIVE_METRIC)}


def markdown(result: dict) -> str:
    lines = ["# BTA Anywhere loopback benchmark", "", f"Status: **{result['status']}**; profile: `{result['profile']}`; schema: {SCHEMA_VERSION}.",
             "", f"Commit: `{result['commit']}`. Windows x86-64, release relay and shaded tunnel.", "",
             "| Streams | Size | Mode | Direct p95 ms | Relay p95 ms | Relay added p95 ms |", "|---:|---:|---|---:|---:|---:|"]
    for case in result.get("latency", []):
        lines.append(f"| {case['concurrency']} | {case['payload_bytes']} | {case['mode']} | "
                     f"{case['paths']['direct']['latency_ms']['p95']:.2f} | "
                     f"{case['paths']['relay']['latency_ms']['p95']:.2f} | {case['relay_added_ms']['p95']:.2f} |")
    lines += ["", "| Streams | Direct G→H MiB/s | Relay G→H MiB/s | Direct H→G MiB/s | Relay H→G MiB/s |",
              "|---:|---:|---:|---:|---:|"]
    for case in result.get("throughput", []):
        m = case["median_mib_s"]
        lines.append(f"| {case['concurrency']} | {m['direct']['guest_to_host']:.2f} | {m['relay']['guest_to_host']:.2f} | "
                     f"{m['direct']['host_to_guest']:.2f} | {m['relay']['host_to_guest']:.2f} |")
    lines += ["", "Results are synthetic loopback observations, not WAN or player capacity.",
              "The local benchmark config permits 10,000 accepts/minute; production defaults are unchanged.",
              "CPU/memory samples, reconnect, EOF/byte checks, soak details and failure diagnostics are in JSON."]
    if result.get("failure"):
        lines += ["", f"Failure: {result['failure']['type']}: {result['failure']['message']}"]
    return "\n".join(lines) + "\n"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", choices=("smoke", "full", "soak"), default="smoke")
    parser.add_argument("--relay-binary", required=True, type=Path)
    parser.add_argument("--tunnel-jar", required=True, type=Path)
    parser.add_argument("--java", default="java")
    parser.add_argument("--output", required=True, type=Path, help="JSON path, preferably benchmark-results/*.json")
    parser.add_argument("--seed", type=int, default=1701)
    parser.add_argument("--soak-seconds", type=float, default=7200)
    args = parser.parse_args()
    if args.profile == "soak" and (not math.isfinite(args.soak_seconds)
                                   or not 7200 <= args.soak_seconds <= 14400):
        parser.error("--soak-seconds must be between 7200 and 14400")
    return args


def main() -> int:
    args = parse_args()
    if sys.platform != "win32" or platform.machine().lower() not in ("amd64", "x86_64"):
        raise SystemExit("benchmark requires Windows x86-64")
    relay_binary, tunnel_jar = args.relay_binary.resolve(), args.tunnel_jar.resolve()
    if not relay_binary.is_file() or not tunnel_jar.is_file():
        raise SystemExit("release relay binary and shaded tunnel JAR must exist")
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    result = {"schema_version": SCHEMA_VERSION, "status": "FAILED", "profile": args.profile,
              "commit": commit, "started_utc": datetime.now(timezone.utc).isoformat(),
              "configuration": {"seed": args.seed, "sizes_bytes": SIZES, "concurrency": [1, 8],
                                "accepts_per_minute_test_override": 10000,
                                "soak_seconds": args.soak_seconds if args.profile == "soak" else None},
              "machine": machine_info(args.java),
              "artifacts": {"relay_sha256": hashlib.sha256(relay_binary.read_bytes()).hexdigest(),
                            "tunnel_sha256": hashlib.sha256(tunnel_jar.read_bytes()).hexdigest()},
              "failures": [], "failure_counts": {"failed_transfers": 0, "byte_mismatches": 0,
                                                 "missing_eofs": 0, "timeouts": 0,
                                                 "leaked_active_stream_gauges": 0},
              "latency": [], "throughput": []}
    logs: collections.deque[str] = collections.deque(maxlen=100)
    started = time.perf_counter()
    rig: Rig | None = None
    try:
        with Rig(relay_binary, tunnel_jar, args.java, logs) as rig:
            try:
                assert rig.relay and rig.tunnel
                result["initial_process_sample"] = {"relay": process_sample(rig.relay),
                                                     "tunnel": process_sample(rig.tunnel)}
                if args.profile == "soak":
                    result["soak"] = run_soak(rig, args.seed, args.soak_seconds)
                else:
                    before = {"relay": process_sample(rig.relay), "tunnel": process_sample(rig.tunnel)}
                    result["latency"] = run_latency(rig, args.profile, args.seed)
                    result["throughput"] = run_throughput(rig, args.profile, args.seed)
                    after = {"relay": process_sample(rig.relay), "tunnel": process_sample(rig.tunnel)}
                    result["measurement_process"] = {name: {
                        "cpu_seconds": after[name]["cpu_seconds"] - before[name]["cpu_seconds"],
                        "peak_working_set_bytes": after[name]["peak_working_set_bytes"],
                        "working_set_bytes_after": after[name]["working_set_bytes"]}
                        for name in ("relay", "tunnel")}
                    result["reconnect"] = rig.reconnect(90)
                rig.assert_idle()
                result["final_process_sample"] = {"relay": process_sample(rig.relay),
                                                   "tunnel": process_sample(rig.tunnel)}
                result["active_connections_final"] = rig.metric(ACTIVE_METRIC)
            except BaseException:
                with contextlib.suppress(Exception):
                    body = http_get(rig.admin_port, "/metrics")
                    result["failure_metrics"] = {
                        name: metric(body, name) for name in (
                            ACTIVE_METRIC, "bta_anywhere_bytes_guest_to_host_total",
                            "bta_anywhere_bytes_host_to_guest_total")}
                raise
        result["clean_shutdown"] = {"relay": rig.relay_clean, "tunnel": rig.tunnel_clean}
        if not all(result["clean_shutdown"].values()):
            raise AssertionError("relay or tunnel did not terminate cleanly")
        result["status"] = "PASS"
    except BaseException as error:
        result["failure"] = bounded_failure(error)
        result["failures"].append(result["failure"])
        result["failure_counts"][failure_category(error)] += 1
        result["diagnostics"] = list(logs)[-50:]
    finally:
        result["elapsed_seconds"] = time.perf_counter() - started
        result["finished_utc"] = datetime.now(timezone.utc).isoformat()
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        summary_path = args.output.with_suffix(".md")
        summary_path.write_text(markdown(result), encoding="utf-8")
        print(f"{result['status']}: {args.output} and {summary_path}")
        if result.get("failure"):
            print(result["failure"], file=sys.stderr)
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
