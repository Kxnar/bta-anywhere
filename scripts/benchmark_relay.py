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
import traceback
from datetime import datetime, timezone
from pathlib import Path

SCHEMA_VERSION = 8
SIZES = (1024, 65536, 1048576)
ACTIVE_METRIC = "bta_anywhere_active_connections"
CHUNK = 65536
MAX_DIAGNOSTIC_STREAMS = 8
MAX_DIAGNOSTIC_SAMPLES = 90
PROGRESS_COUNTERS = ("guest_send_bytes", "guest_receive_bytes",
                     "echo_receive_bytes", "echo_send_bytes")
PROGRESS_FLAGS = ("guest_connected", "guest_write_closed", "guest_read_eof",
                  "echo_connected", "echo_read_eof", "echo_write_closed")


class MissingEofError(TimeoutError):
    """The expected reply was verified but the peer did not close its write side."""


class ConcurrentTransferError(RuntimeError):
    """Retain bounded per-stream failures instead of only a truncated message."""

    def __init__(self, label: str, failures: list[tuple[int, BaseException]],
                 successes: list[dict]):
        self.failures = failures
        self.successes = successes
        details = "; ".join(f"stream={index} {type(error).__name__}: {str(error)[:250]}"
                            for index, error in failures)
        super().__init__(f"{label}: {details}")


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


def failure_counts(error: BaseException, transfer_in_progress: bool) -> tuple[dict[str, int], list[dict]]:
    counts = {"failed_transfers": 0, "byte_mismatches": 0, "missing_eofs": 0,
              "timeouts": 0, "leaked_active_stream_gauges": 0}
    failures = error.failures if isinstance(error, ConcurrentTransferError) else [(None, error)]
    details = []
    for index, failed in failures:
        category = failure_category(failed)
        if transfer_in_progress:
            counts["failed_transfers"] += 1
        if category != "failed_transfers":
            counts[category] += 1
        cause = failed
        timed_out = False
        for _ in range(8):
            if cause is None:
                break
            if isinstance(cause, TimeoutError) or "timed out" in str(cause).lower():
                timed_out = True
                break
            cause = cause.__cause__
        if category != "timeouts" and timed_out:
            counts["timeouts"] += 1
        if index is not None:
            details.append({"stream": index, **bounded_failure(failed), "category": category})
    return counts, details


def read_exact(sock: socket.socket, size: int) -> bytes:
    data = bytearray()
    while len(data) < size:
        chunk = sock.recv(min(CHUNK, size - len(data)))
        if not chunk:
            raise AssertionError(f"early EOF after {len(data)}/{size} bytes")
        data.extend(chunk)
    return bytes(data)


class ThroughputProgress:
    """Bounded, diagnostic-only byte counters; never retains payloads or addresses."""

    def __init__(self, stream_count: int):
        if not 1 <= stream_count <= MAX_DIAGNOSTIC_STREAMS:
            raise ValueError("diagnostic stream count must be between 1 and 8")
        self._lock = threading.Lock()
        self._streams = [{"stream": index, **{key: 0 for key in PROGRESS_COUNTERS},
                          **{key: False for key in PROGRESS_FLAGS},
                          "guest_error": None, "echo_error": None}
                         for index in range(stream_count)]

    def _check_index(self, index: int) -> None:
        if not isinstance(index, int) or not 0 <= index < len(self._streams):
            raise ValueError("invalid diagnostic stream index")

    def advance(self, index: int, counter: str, amount: int) -> None:
        self._check_index(index)
        if counter not in PROGRESS_COUNTERS or not isinstance(amount, int) or amount < 0:
            raise ValueError("invalid diagnostic counter update")
        with self._lock:
            self._streams[index][counter] += amount

    def mark(self, index: int, flag: str) -> None:
        self._check_index(index)
        if flag not in PROGRESS_FLAGS:
            raise ValueError("invalid diagnostic state")
        with self._lock:
            self._streams[index][flag] = True

    def error(self, index: int, role: str, failure: BaseException) -> None:
        self._check_index(index)
        if role not in ("guest", "echo"):
            raise ValueError("invalid diagnostic role")
        with self._lock:
            self._streams[index][role + "_error"] = type(failure).__name__

    def snapshot(self) -> list[dict]:
        with self._lock:
            return [dict(stream) for stream in self._streams]


def send_tracked(sock: socket.socket, data: bytes, progress: ThroughputProgress,
                 index: int, counter: str) -> None:
    remaining = memoryview(data)
    while remaining:
        count = sock.send(remaining)
        if count == 0:
            raise ConnectionError("socket send returned zero bytes")
        progress.advance(index, counter, count)
        remaining = remaining[count:]


class EchoHandler(socketserver.BaseRequestHandler):
    def handle(self) -> None:
        connection_id = self.server.next_connection_id()
        self.server.record_event(connection_id, "accepted")
        self.request.settimeout(30)
        diagnostic_index = None
        progress = self.server.diagnostic_progress
        try:
            mode = read_exact(self.request, 1)
            self.server.record_event(connection_id, "mode", mode=mode.decode("ascii", errors="replace"))
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
                first_chunk = True
                while chunk := self.request.recv(CHUNK):
                    if first_chunk:
                        self.server.record_event(connection_id, "first_payload", bytes=len(chunk))
                        first_chunk = False
                    self.request.sendall(chunk)
            elif mode == b"D":
                if progress is None:
                    raise ValueError("unexpected diagnostic throughput connection")
                diagnostic_index = read_exact(self.request, 1)[0]
                progress.mark(diagnostic_index, "echo_connected")
                while chunk := self.request.recv(CHUNK):
                    progress.advance(diagnostic_index, "echo_receive_bytes", len(chunk))
                    send_tracked(self.request, chunk, progress, diagnostic_index, "echo_send_bytes")
                progress.mark(diagnostic_index, "echo_read_eof")
            else:
                raise ValueError("unknown benchmark mode")
            self.request.shutdown(socket.SHUT_WR)
            if diagnostic_index is not None:
                progress.mark(diagnostic_index, "echo_write_closed")
            self.server.record_event(connection_id, "eof_sent")
        except BaseException as error:
            if diagnostic_index is not None and progress is not None:
                progress.error(diagnostic_index, "echo", error)
            self.server.record_event(connection_id, "error", error_type=type(error).__name__)
            raise


class EchoServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True
    request_queue_size = 64

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.errors: collections.deque[dict[str, str]] = collections.deque(maxlen=20)
        self.events: collections.deque[dict] = collections.deque(maxlen=160)
        self.event_lock = threading.Lock()
        self.connection_count = 0
        self.current_case: dict = {}
        self.diagnostic_progress: ThroughputProgress | None = None

    def next_connection_id(self) -> int:
        with self.event_lock:
            self.connection_count += 1
            return self.connection_count

    def record_event(self, connection_id: int, event: str, **details) -> None:
        with self.event_lock:
            self.events.append({"time_monotonic": time.monotonic(),
                                "connection_id": connection_id, "event": event,
                                "case": dict(self.current_case), **details})

    def handle_error(self, request, client_address) -> None:
        error = sys.exc_info()[1]
        if error is not None:
            self.errors.append(bounded_failure(error))


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
        try:
            extra = sock.recv(1)
        except socket.timeout as error:
            raise MissingEofError(f"missing EOF after {len(data)} verified reply bytes") from error
        if extra:
            raise AssertionError("byte mismatch: surplus response bytes after exact reply")
    return (time.perf_counter() - started) * 1000


def throughput_stream(port: int, block: bytes, seconds: float, timeout: float,
                      pace_seconds: float = 0, progress: ThroughputProgress | None = None,
                      stream_index: int | None = None) -> dict[str, float | int]:
    if progress is not None:
        progress._check_index(stream_index)
    sent = 0
    received = 0
    send_error: list[BaseException] = []
    started = time.perf_counter()
    doubled_block = block + block
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=timeout) as sock:
            sock.settimeout(timeout)
            if progress is None:
                sock.sendall(b"T")
            else:
                sock.sendall(b"D" + bytes([stream_index]))
                progress.mark(stream_index, "guest_connected")
            stop_at = started + seconds

            def send() -> None:
                nonlocal sent
                try:
                    while time.perf_counter() < stop_at:
                        if progress is None:
                            sock.sendall(block)
                        else:
                            send_tracked(sock, block, progress, stream_index, "guest_send_bytes")
                        sent += len(block)
                        if pace_seconds:
                            time.sleep(pace_seconds)
                    sock.shutdown(socket.SHUT_WR)
                    if progress is not None:
                        progress.mark(stream_index, "guest_write_closed")
                except BaseException as error:
                    send_error.append(error)
                    if progress is not None:
                        progress.error(stream_index, "guest", error)
                    with contextlib.suppress(OSError):
                        sock.shutdown(socket.SHUT_WR)

            sender = threading.Thread(target=send, name="benchmark-sender", daemon=True)
            sender.start()
            try:
                try:
                    while chunk := sock.recv(CHUNK):
                        if progress is not None:
                            progress.advance(stream_index, "guest_receive_bytes", len(chunk))
                        shift = received % len(block)
                        if chunk != doubled_block[shift:shift + len(chunk)]:
                            raise AssertionError(f"byte mismatch starting at offset {received}")
                        received += len(chunk)
                    if progress is not None:
                        progress.mark(stream_index, "guest_read_eof")
                except socket.timeout as error:
                    if not sender.is_alive() and not send_error and received == sent:
                        raise MissingEofError(f"missing EOF after {received} verified echoed bytes") from error
                    raise
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
    except BaseException as error:
        if progress is not None:
            progress.error(stream_index, "guest", error)
        raise RuntimeError(f"throughput socket failed after {time.perf_counter() - started:.3f}s "
                           f"sent={sent} received={received}: {type(error).__name__}: {error}") from error


def run_concurrent(function, count: int, label: str = "parallel transfer") -> list:
    with concurrent.futures.ThreadPoolExecutor(max_workers=count) as pool:
        futures = [pool.submit(function, i) for i in range(count)]
        results = [None] * count
        failures = []
        for index, future in enumerate(futures):
            try:
                results[index] = future.result()
            except BaseException as error:
                failures.append((index, error))
        if failures:
            successes = [{"stream": index, "result": value} for index, value in enumerate(results)
                         if value is not None]
            raise ConcurrentTransferError(label, failures, successes) from failures[0][1]
        return results


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


def free_port_pair() -> int:
    for _ in range(100):
        first = free_port()
        if first >= 65535:
            continue
        try:
            with socket.socket() as left, socket.socket() as right:
                left.bind(("127.0.0.1", first))
                right.bind(("127.0.0.1", first + 1))
            return first
        except OSError:
            continue
    raise RuntimeError("could not find two consecutive loopback TCP ports")


class UdpBridge:
    """One datagram at a time, with no queue, for a benchmark-owned link drop."""

    def __init__(self, relay_port: int):
        self.relay = ("127.0.0.1", relay_port)
        self.socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.socket.bind(("127.0.0.1", 0))
        self.socket.settimeout(0.2)
        self.port = self.socket.getsockname()[1]
        self.peer: tuple[str, int] | None = None
        self.drop_until = 0.0
        self.stop_event = threading.Event()
        self.forwarded = 0
        self.dropped = 0
        self.thread = threading.Thread(target=self._pump, name="benchmark-udp-bridge", daemon=True)
        self.thread.start()

    def _pump(self) -> None:
        while not self.stop_event.is_set():
            try:
                data, source = self.socket.recvfrom(65535)
            except socket.timeout:
                continue
            except OSError:
                break
            if source == self.relay:
                destination = self.peer
            else:
                self.peer = source
                destination = self.relay
            if time.monotonic() < self.drop_until:
                self.dropped += 1
                continue
            if destination is not None:
                with contextlib.suppress(OSError):
                    self.socket.sendto(data, destination)
                    self.forwarded += 1

    def drop_for(self, seconds: float) -> None:
        if not 0 < seconds <= 90:
            raise ValueError("link drop must last at most 90 seconds")
        self.drop_until = time.monotonic() + seconds
        time.sleep(seconds)

    def close(self) -> None:
        self.stop_event.set()
        self.socket.close()
        self.thread.join(timeout=2)


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


def source_provenance(artifact: Path) -> dict[str, str | bool]:
    """Infer source revision from the checkout containing a file, without retaining paths."""

    def git(directory: Path, *args: str) -> subprocess.CompletedProcess[str] | None:
        try:
            return subprocess.run(["git", "-C", str(directory), *args], text=True,
                                  capture_output=True, timeout=10, check=False)
        except (OSError, subprocess.TimeoutExpired):
            return None

    root_query = git(artifact.parent, "rev-parse", "--show-toplevel")
    if root_query is None:
        return {"status": "unavailable", "reason": "git_query_unavailable"}
    if root_query.returncode != 0 or not root_query.stdout.strip():
        return {"status": "unavailable", "reason": "not_in_git_checkout"}
    root = Path(root_query.stdout.strip())
    head_query = git(root, "rev-parse", "HEAD")
    dirty_query = git(root, "status", "--porcelain=v1", "--untracked-files=normal")
    if (head_query is None or dirty_query is None or head_query.returncode != 0
            or dirty_query.returncode != 0):
        return {"status": "unavailable", "reason": "git_query_unavailable"}
    commit = head_query.stdout.strip()
    if not re.fullmatch(r"[0-9a-fA-F]{40,64}", commit):
        return {"status": "unavailable", "reason": "source_commit_unavailable"}
    return {"status": "available", "commit": commit.lower(),
            "dirty": bool(dirty_query.stdout.strip())}


def provenance_label(source: dict | None) -> str:
    if source and source.get("status") == "available":
        return f"`{source['commit']}` (dirty={source['dirty']})"
    return f"unavailable ({(source or {}).get('reason', 'not_recorded')})"


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
    was_running = process.poll() is None
    if not was_running:
        return False
    if graceful and process.stdin:
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
        self.relay_events: queue.Queue[int] = queue.Queue(maxsize=32)
        self.temporary = tempfile.TemporaryDirectory(prefix="bta-benchmark-")
        self.work = Path(self.temporary.name)
        self.relay: subprocess.Popen[str] | None = None
        self.tunnel: subprocess.Popen[str] | None = None
        self.echo: EchoServer | None = None
        self.echo_thread: threading.Thread | None = None
        self.bridge: UdpBridge | None = None
        self.tcp_port = free_port_pair()
        ports: set[int] = set()
        while len(ports) < 2:
            candidate = free_port()
            if candidate not in (self.tcp_port, self.tcp_port + 1):
                ports.add(candidate)
        self.quic_port, self.admin_port = sorted(ports)
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
            if label.startswith("relay-") and "resumed relay session" in line:
                match = re.search(r"\bport=(\d+)\b", line)
                if match:
                    with contextlib.suppress(queue.Full):
                        self.relay_events.put_nowait(int(match.group(1)))

    def launch(self, args: list[str], label: str, stdin,
               env: dict[str, str] | None = None) -> subprocess.Popen[str]:
        process = subprocess.Popen(args, stdin=stdin, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   text=True, bufsize=1, env=env)
        assert process.stdout and process.stderr
        for stream, suffix in ((process.stdout, "out"), (process.stderr, "err")):
            threading.Thread(target=self.reader, args=(stream, f"{label}-{suffix}"), daemon=True).start()
        return process

    def start_relay(self, log_level: str = "warn") -> None:
        relay_env = dict(os.environ)
        relay_env["RUST_LOG"] = log_level
        self.relay = self.launch([str(self.relay_binary), "run", "--config", str(self.work / "relay" / "relay.toml")],
                                 "relay", subprocess.DEVNULL, relay_env)
        wait_until(lambda: bool(http_get(self.admin_port, "/readyz")), 20, "relay ready")

    def start_tunnel(self, relay_port: int) -> int:
        assert self.echo
        development = self.work / "relay"
        while not self.events.empty():
            with contextlib.suppress(queue.Empty):
                self.events.get_nowait()
        self.tunnel = self.launch([self.java, "-jar", str(self.tunnel_jar), "expose",
                                   "--relay", f"127.0.0.1:{relay_port}",
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
                return int(match.group(1))
        raise TimeoutError("tunnel did not publish a public endpoint")

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
            'tcp_port_end = 30100': f'tcp_port_end = {self.tcp_port + 1}',
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
        self.public_port = self.start_tunnel(self.quic_port)
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
        self.start_relay(log_level="info")
        probe = payload(123, 1024, 0)
        wait_until(lambda: reconnect_probe(old_port, probe), timeout, "tunnel reconnect")
        if self.metric("bta_anywhere_active_sessions") != 1:
            raise AssertionError("expected one reconnected tunnel session")
        return {"scenario": "relay_restart_interrupts_tunnel_quic_connection",
                "completion_ms": (time.perf_counter() - started) * 1000,
                "old_endpoint_probe_succeeded": True,
                "endpoint_after_reconnect_not_printed_by_cli": True,
                "old_port": old_port, "relay_before_restart": relay_before,
                "relay_after_restart": process_sample(self.relay)}

    def restart_tunnel_process(self) -> dict:
        assert self.tunnel and self.public_port is not None
        old_port = self.public_port
        started = time.perf_counter()
        if not stop_process(self.tunnel):
            raise RuntimeError("benchmark tunnel process did not terminate")
        self.tunnel = None
        self.bridge = UdpBridge(self.quic_port)
        try:
            new_port = self.start_tunnel(self.bridge.port)
            transfer(new_port, "request_response", payload(123, 1024, 1), 5)
        except (RuntimeError, TimeoutError, OSError, AssertionError) as error:
            return {"scenario": "tunnel_process_restart_with_new_in_memory_state",
                    "available": False, "old_port": old_port,
                    "old_process_terminated": True,
                    "completion_ms": (time.perf_counter() - started) * 1000,
                    "failure": bounded_failure(error)}
        self.public_port = new_port
        return {"scenario": "tunnel_process_restart_with_new_in_memory_state",
                "available": True, "old_port": old_port, "new_port": new_port,
                "old_process_terminated": True,
                "endpoint_retained": old_port == new_port,
                "completion_ms": (time.perf_counter() - started) * 1000,
                "bridge_loopback_port": self.bridge.port,
                "relay_port_range": [self.tcp_port, self.tcp_port + 1]}

    def interrupt_tunnel_link(self, drop_seconds: float = 55, timeout: float = 90) -> dict:
        assert self.bridge and self.tunnel and self.public_port is not None
        bridge_port = self.public_port
        while not self.relay_events.empty():
            with contextlib.suppress(queue.Empty):
                self.relay_events.get_nowait()
        before_dropped = self.bridge.dropped
        started = time.perf_counter()
        self.bridge.drop_for(drop_seconds)
        resumed_port = None
        available = False
        probe = payload(123, 1024, 2)
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.tunnel.poll() is not None:
                break
            with contextlib.suppress(queue.Empty):
                resumed_port = self.relay_events.get(timeout=0.2)
            if reconnect_probe(bridge_port, probe):
                available = True
                if resumed_port is None:
                    with contextlib.suppress(queue.Empty):
                        resumed_port = self.relay_events.get(timeout=2)
                break
        return {"scenario": "tunnel_link_udp_drop_with_process_alive",
                "bridge_backed_endpoint_before_drop": bridge_port,
                "endpoint_after_resume_event": resumed_port,
                "endpoint_retained_by_byte_exact_probe": available,
                "resume_event_port_matches": resumed_port == bridge_port if resumed_port is not None else None,
                "resume_observed": resumed_port is not None,
                "available": available,
                "completion_ms": (time.perf_counter() - started) * 1000,
                "configured_drop_seconds": drop_seconds,
                "dropped_datagrams": self.bridge.dropped - before_dropped,
                "forwarded_datagrams": self.bridge.forwarded,
                "bridge_queue_capacity": 0}

    def __exit__(self, *_):
        self.tunnel_clean = stop_process(self.tunnel, graceful=True)
        if self.bridge:
            self.bridge.close()
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


def run_latency(rig: Rig, profile: str, seed: int, result: dict) -> list[dict]:
    assert rig.echo and rig.public_port
    warmups, measured = (1, 3) if profile == "smoke" else (5, 30)
    cases = []
    result["latency_partial"] = cases
    for concurrency in (1, 8):
        for size in SIZES:
            for mode in ("request_response", "half_close"):
                case = {"concurrency": concurrency, "payload_bytes": size, "mode": mode,
                        "warmups": warmups, "paths": {}, "paired_delta_samples_ms": []}
                samples = {"direct": [], "relay": []}
                result["latency_in_progress"] = {"concurrency": concurrency,
                                                  "payload_bytes": size, "mode": mode,
                                                  "completed_samples_ms": samples}
                for wave in range(warmups + measured):
                    data = payload(seed, size, wave)
                    pair = {}
                    order = ("direct", "relay") if wave % 2 == 0 else ("relay", "direct")
                    for path in order:
                        port = rig.echo.server_address[1] if path == "direct" else rig.public_port
                        result["current_case"] = {"phase": "latency", "concurrency": concurrency,
                                                  "payload_bytes": size, "mode": mode,
                                                  "wave": wave, "path": path}
                        pair[path] = run_concurrent(lambda _: transfer(port, mode, data, 15), concurrency,
                                                     f"latency {concurrency=} {size=} {mode=} {wave=} {path=}")
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
    result.pop("latency_in_progress", None)
    return cases


def diagnostic_case_samples(rig: Rig, result: dict, stop: threading.Event,
                            progress: ThroughputProgress | None = None) -> None:
    assert rig.relay and rig.tunnel
    samples: collections.deque[dict] = collections.deque(maxlen=MAX_DIAGNOSTIC_SAMPLES)
    result["diagnostic_case_samples"] = samples
    while not stop.is_set():
        item = {"time_monotonic": time.monotonic()}
        if progress is not None:
            item["streams"] = progress.snapshot()
        try:
            body = http_get(rig.admin_port, "/metrics")
            item["active_connections"] = metric(body, ACTIVE_METRIC)
            for name in ("bta_anywhere_bytes_guest_to_host_total",
                         "bta_anywhere_bytes_host_to_guest_total"):
                item[name] = metric(body, name)
            item["relay"] = process_sample(rig.relay)
            item["tunnel"] = process_sample(rig.tunnel)
        except Exception as error:
            item["sample_error"] = bounded_failure(error)
        samples.append(item)
        stop.wait(1)


def run_throughput(rig: Rig, profile: str, seed: int, result: dict,
                   diagnostic_sequence: bool = False) -> list[dict]:
    assert rig.echo and rig.public_port
    runs, seconds = (1, 2.0) if profile == "smoke" else (5, 60.0)
    block = payload(seed, CHUNK, 999)
    cases = []
    for concurrency in (1, 8):
        case = {"concurrency": concurrency, "payload_bytes": len(block), "run_seconds_target": seconds,
                "paths": {"direct": [], "relay": []}}
        result["throughput_partial"] = cases + [case]
        for run in range(runs):
            for path in (("direct", "relay") if run % 2 == 0 else ("relay", "direct")):
                port = rig.echo.server_address[1] if path == "direct" else rig.public_port
                result["current_case"] = {"phase": "throughput", "concurrency": concurrency,
                                          "run_index_zero_based": run, "path": path,
                                          "target_seconds": seconds, "stream_count": concurrency}
                with rig.echo.event_lock:
                    rig.echo.current_case = dict(result["current_case"])
                observed = diagnostic_sequence and concurrency == 8 and run == 0 and path == "relay"
                stop = threading.Event()
                sampler = None
                progress = ThroughputProgress(concurrency) if observed else None
                if observed:
                    rig.echo.diagnostic_progress = progress
                    sampler = threading.Thread(target=diagnostic_case_samples,
                                               args=(rig, result, stop, progress), daemon=True)
                    sampler.start()
                try:
                    samples = run_concurrent(
                        lambda index: throughput_stream(port, block, seconds, 30,
                                                        progress=progress, stream_index=index), concurrency,
                                             f"throughput {concurrency=} {run=} {path=}")
                finally:
                    if sampler:
                        stop.set()
                        sampler.join(timeout=3)
                        result["diagnostic_case_samples"] = list(result["diagnostic_case_samples"])
                        result["diagnostic_progress_final"] = progress.snapshot()
                        rig.echo.diagnostic_progress = None
                        with rig.echo.event_lock:
                            result["diagnostic_echo_events"] = list(rig.echo.events)
                elapsed = max(sample["seconds"] for sample in samples)
                row = {"seconds": elapsed,
                       "guest_to_host_bytes": sum(sample["guest_to_host_bytes"] for sample in samples),
                       "host_to_guest_bytes": sum(sample["host_to_guest_bytes"] for sample in samples)}
                row["guest_to_host_mib_s"] = row["guest_to_host_bytes"] / elapsed / 1048576
                row["host_to_guest_mib_s"] = row["host_to_guest_bytes"] / elapsed / 1048576
                case["paths"][path].append(row)
                if path == "relay":
                    rig.assert_idle()
                if observed:
                    case["median_mib_s"] = {path_name: {
                        direction: statistics.median(item[direction + "_mib_s"] for item in rows)
                        for direction in ("guest_to_host", "host_to_guest")}
                        for path_name, rows in case["paths"].items()}
                    cases.append(case)
                    return cases
        case["median_mib_s"] = {path: {direction: statistics.median(run[direction + "_mib_s"] for run in rows)
                                       for direction in ("guest_to_host", "host_to_guest")}
                                for path, rows in case["paths"].items()}
        cases.append(case)
    return cases


def run_soak(rig: Rig, seed: int, duration: float, result: dict) -> dict:
    if not math.isfinite(duration) or not 7200 <= duration <= 14400:
        raise ValueError("soak duration must be between two and four hours")
    assert rig.public_port and rig.relay and rig.tunnel
    block = payload(seed, CHUNK, 1000)
    started = time.monotonic()
    samples = []
    totals = {"guest_to_host_bytes": 0, "host_to_guest_bytes": 0}
    while time.monotonic() - started < duration:
        remaining = duration - (time.monotonic() - started)
        result["current_case"] = {"phase": "soak", "wave": len(samples), "concurrency": 8,
                                  "target_seconds": min(60, remaining)}
        streams = run_concurrent(lambda _: throughput_stream(rig.public_port, block,
                                                              min(60, remaining), 30, 0.5), 8,
                                 f"soak wave={len(samples)}")
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


def run_diagnostic_eight_relay(rig: Rig, seed: int, result: dict) -> dict:
    assert rig.public_port and rig.echo
    block = payload(seed, CHUNK, 999)
    result["current_case"] = {"phase": "diagnostic_eight_relay", "concurrency": 8,
                              "run_index_zero_based": 0, "path": "relay", "target_seconds": 60,
                              "stream_count": 8, "payload_block_bytes": CHUNK}
    progress = ThroughputProgress(8)
    rig.echo.diagnostic_progress = progress
    stop = threading.Event()
    sampler = threading.Thread(target=diagnostic_case_samples,
                               args=(rig, result, stop, progress), daemon=True)
    sampler.start()
    try:
        streams = run_concurrent(
            lambda index: throughput_stream(rig.public_port, block, 60, 30,
                                            progress=progress, stream_index=index), 8,
            "diagnostic eight-stream relayed throughput")
    finally:
        stop.set()
        sampler.join(timeout=3)
        result["diagnostic_case_samples"] = list(result["diagnostic_case_samples"])
        result["diagnostic_progress_final"] = progress.snapshot()
        rig.echo.diagnostic_progress = None
        with rig.echo.event_lock:
            result["diagnostic_echo_events"] = list(rig.echo.events)
    rig.assert_idle()
    return {"concurrency": 8, "path": "relay", "target_seconds": 60,
            "guest_to_host_bytes": sum(stream["guest_to_host_bytes"] for stream in streams),
            "host_to_guest_bytes": sum(stream["host_to_guest_bytes"] for stream in streams),
            "active_connections_final": rig.metric(ACTIVE_METRIC)}


def markdown(result: dict) -> str:
    lines = ["# BTA Anywhere loopback benchmark", "", f"Status: **{result['status']}**; profile: `{result['profile']}`; schema: {result.get('schema_version', SCHEMA_VERSION)}.",
             "", "Windows x86-64, release relay and shaded tunnel."]
    if sources := result.get("source_provenance"):
        lines += [f"Harness source: {provenance_label(sources.get('harness'))}.",
                  f"Relay artifact source: {provenance_label(sources.get('relay'))}.",
                  f"Tunnel artifact source: {provenance_label(sources.get('tunnel'))}."]
        if artifacts := result.get("artifacts"):
            lines.append(f"Artifact SHA-256: relay `{artifacts['relay_sha256']}`; "
                         f"tunnel `{artifacts['tunnel_sha256']}`.")
        lines.append("Source revisions are inferred from the containing Git checkouts; "
                     "the artifact hashes identify the measured files.")
    else:
        lines.append(f"Legacy reported commit: `{result.get('commit', 'unavailable')}`; "
                     "artifact source revisions were not recorded.")
    lines += ["", "| Streams | Size | Mode | Direct p95 ms | Relay p95 ms | Relay added p95 ms |",
              "|---:|---:|---|---:|---:|---:|"]
    for case in result.get("latency") or result.get("latency_partial", []):
        lines.append(f"| {case['concurrency']} | {case['payload_bytes']} | {case['mode']} | "
                     f"{case['paths']['direct']['latency_ms']['p95']:.2f} | "
                     f"{case['paths']['relay']['latency_ms']['p95']:.2f} | {case['relay_added_ms']['p95']:.2f} |")
    lines += ["", "| Streams | Direct G→H MiB/s | Relay G→H MiB/s | Direct H→G MiB/s | Relay H→G MiB/s |",
              "|---:|---:|---:|---:|---:|"]
    for case in result.get("throughput") or result.get("throughput_partial", []):
        if "median_mib_s" not in case:
            continue
        m = case["median_mib_s"]
        lines.append(f"| {case['concurrency']} | {m['direct']['guest_to_host']:.2f} | {m['relay']['guest_to_host']:.2f} | "
                     f"{m['direct']['host_to_guest']:.2f} | {m['relay']['host_to_guest']:.2f} |")
    lines += ["", "Results are synthetic loopback observations, not WAN or player capacity.",
              "The local benchmark config permits 10,000 accepts/minute; production defaults are unchanged.",
              "CPU/memory samples, reconnect, EOF/byte checks, soak details and failure diagnostics are in JSON."]
    if process := result.get("tunnel_process_restart"):
        lines += ["", f"Tunnel-process restart: available={process['available']}; "
                  f"old port={process['old_port']}; new port={process.get('new_port', 'unavailable')}; "
                  f"endpoint retained={process.get('endpoint_retained', 'unknown')}; "
                  f"completion={process['completion_ms']:.2f} ms."]
    if link := result.get("tunnel_link_interruption"):
        lines += [f"Tunnel-link UDP drop: {link['configured_drop_seconds']} s; "
                  f"recovered={link['available']}; same bridge-backed endpoint="
                  f"{link['endpoint_retained_by_byte_exact_probe']}; "
                  f"resume event observed={link['resume_observed']}; "
                  f"completion={link['completion_ms']:.2f} ms."]
    if result.get("failure"):
        lines += ["", f"Failure: {result['failure']['type']}: {result['failure']['message']}"]
        if result.get("current_case"):
            lines.append(f"Failed case: `{json.dumps(result['current_case'], sort_keys=True)}`.")
        if counts := result.get("failure_counts"):
            lines.append("Failed transfers: {failed_transfers}; byte mismatches: {byte_mismatches}; "
                         "missing EOFs: {missing_eofs}; timeouts: {timeouts}; "
                         "leaked active stream gauges: {leaked_active_stream_gauges}.".format(**counts))
        if failures := result.get("stream_failures"):
            lines.append("Failed stream indices: " + ", ".join(str(item["stream"]) for item in failures) + ".")
    if streams := result.get("diagnostic_progress_final"):
        lines += ["", "Diagnostic throughput counters (TCP socket bytes, not QUIC acknowledgements):", "",
                  "| Stream | Guest sent | Echo received | Echo sent | Guest received | Guest write EOF | Echo read EOF | Echo write EOF | Guest read EOF | Errors |",
                  "|---:|---:|---:|---:|---:|---|---|---|---|---|"]
        for stream in streams[:MAX_DIAGNOSTIC_STREAMS]:
            errors = ", ".join(f"{role}: {stream[role + '_error']}" for role in ("guest", "echo")
                               if stream[role + "_error"]) or "none"
            lines.append(f"| {stream['stream']} | {stream['guest_send_bytes']} | "
                         f"{stream['echo_receive_bytes']} | {stream['echo_send_bytes']} | "
                         f"{stream['guest_receive_bytes']} | {stream['guest_write_closed']} | "
                         f"{stream['echo_read_eof']} | {stream['echo_write_closed']} | "
                         f"{stream['guest_read_eof']} | {errors} |")
    return "\n".join(lines) + "\n"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", choices=("smoke", "full", "soak", "diagnostic-eight-relay",
                                              "diagnostic-sequence"),
                        default="smoke")
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
    sources = {"method": "containing_git_checkout",
               "harness": source_provenance(Path(__file__).resolve()),
               "relay": source_provenance(relay_binary),
               "tunnel": source_provenance(tunnel_jar)}
    result = {"schema_version": SCHEMA_VERSION, "status": "FAILED", "profile": args.profile,
              "source_provenance": sources,
              "started_utc": datetime.now(timezone.utc).isoformat(),
              "configuration": {"seed": args.seed, "sizes_bytes": SIZES, "concurrency": [1, 8],
                                "accepts_per_minute_test_override": 10000,
                                "relay_log_level_during_measurement": "warn",
                                "relay_log_level_during_recovery": "info",
                                "diagnostic_sequence_stop_after": "first eight-stream relayed throughput run"
                                    if args.profile == "diagnostic-sequence" else None,
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
                    result["soak"] = run_soak(rig, args.seed, args.soak_seconds, result)
                elif args.profile == "diagnostic-eight-relay":
                    result["diagnostic_eight_relay"] = run_diagnostic_eight_relay(rig, args.seed, result)
                elif args.profile == "diagnostic-sequence":
                    result["latency"] = run_latency(rig, "full", args.seed, result)
                    result.pop("latency_partial", None)
                    result["throughput"] = run_throughput(rig, "full", args.seed, result,
                                                          diagnostic_sequence=True)
                    result.pop("throughput_partial", None)
                else:
                    before = {"relay": process_sample(rig.relay), "tunnel": process_sample(rig.tunnel)}
                    result["latency"] = run_latency(rig, args.profile, args.seed, result)
                    result.pop("latency_partial", None)
                    result["throughput"] = run_throughput(rig, args.profile, args.seed, result)
                    result.pop("throughput_partial", None)
                    after = {"relay": process_sample(rig.relay), "tunnel": process_sample(rig.tunnel)}
                    result["measurement_process"] = {name: {
                        "cpu_seconds": after[name]["cpu_seconds"] - before[name]["cpu_seconds"],
                        "peak_working_set_bytes": after[name]["peak_working_set_bytes"],
                        "working_set_bytes_after": after[name]["working_set_bytes"]}
                        for name in ("relay", "tunnel")}
                    result["reconnect"] = rig.reconnect(90)
                    result["tunnel_process_restart"] = rig.restart_tunnel_process()
                    if not result["tunnel_process_restart"]["available"]:
                        raise AssertionError("tunnel process could not re-register within 30 seconds")
                    result["tunnel_link_interruption"] = rig.interrupt_tunnel_link()
                    if not result["tunnel_link_interruption"]["available"]:
                        raise AssertionError("tunnel link did not resume within the bounded window")
                    link_result = result["tunnel_link_interruption"]
                    if not link_result["endpoint_retained_by_byte_exact_probe"]:
                        raise AssertionError("bridge-backed endpoint did not recover")
                    if link_result["resume_event_port_matches"] is False:
                        raise AssertionError("resume event port differs from bridge-backed endpoint")
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
        result.pop("current_case", None)
        result["status"] = "PASS"
    except BaseException as error:
        result["failure"] = bounded_failure(error)
        result["failure_traceback"] = "".join(traceback.format_exception(error))[-6000:]
        result["failures"].append(result["failure"])
        result["failure_counts"], stream_failures = failure_counts(
            error, isinstance(error, ConcurrentTransferError))
        if stream_failures:
            result["stream_failures"] = stream_failures
            result["stream_successes_on_failure"] = error.successes
        result["diagnostics"] = list(logs)[-50:]
        if rig is not None and rig.echo is not None:
            result["echo_errors"] = list(rig.echo.errors)
            with rig.echo.event_lock:
                result["echo_events_on_failure"] = list(rig.echo.events)
        if rig is not None and hasattr(rig, "relay_clean"):
            result["clean_shutdown_after_failure"] = {
                "relay": rig.relay_clean, "tunnel": rig.tunnel_clean}
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
