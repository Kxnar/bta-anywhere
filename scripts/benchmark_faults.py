#!/usr/bin/env python3
"""Bounded loopback fault supplement. Existing TCP streams and fresh recovery are distinct."""
from __future__ import annotations

import argparse
import collections
import contextlib
import copy
import errno
import hashlib
import json
import math
import platform
import socket
import sys
import threading
import time
from datetime import datetime, timezone
from pathlib import Path

import benchmark_relay as bench

STREAMS = 8
DROP_SECONDS = 55
RECOVERY_SECONDS = 90
EXISTING_OBSERVATION_SECONDS = 90


def file_hash(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1048576), b""):
            digest.update(chunk)
    return digest.hexdigest()


def receive_exact(sock, expected: bytes, record: dict, delay: float = 0,
                  deadline: float | None = None) -> None:
    """Verify incrementally without retaining an unbounded response."""
    offset = 0
    while offset < len(expected):
        if deadline is not None:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("receive exceeded total deadline")
            sock.settimeout(remaining)
        chunk = sock.recv(min(4096, len(expected) - offset))
        if not chunk:
            raise AssertionError(f"early EOF after {offset}/{len(expected)} bytes")
        if chunk != expected[offset:offset + len(chunk)]:
            record["byte_mismatch"] = True
            raise AssertionError(f"byte mismatch at offset {offset}")
        offset += len(chunk)
        record["verified_bytes"] += len(chunk)
        if delay:
            time.sleep(delay)


def slow_receiver(port: int, data: bytes, index: int, timeout: float = 30) -> dict:
    row = {"stream": index, "status": "FAILED", "verified_bytes": 0,
           "payload_sha256": hashlib.sha256(data).hexdigest(), "eof": False,
           "receive_chunk_bytes": 4096, "receive_buffer_requested_bytes": 4096,
           "receive_delay_seconds": .01}
    started = time.monotonic()
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=timeout) as sock:
            remaining = started + timeout - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("slow receiver connection exceeded deadline")
            sock.settimeout(remaining)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4096)
            row["receive_buffer_actual_bytes"] = sock.getsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF)
            sock.sendall(b"R" + len(data).to_bytes(4, "big") + data)
            receive_exact(sock, data, row, .01, started + timeout)
            sock.settimeout(max(.001, started + timeout - time.monotonic()))
            if sock.recv(1):
                row["byte_mismatch"] = True
                raise AssertionError("surplus response bytes")
            row["eof"] = True
            row["status"] = "PASS"
    except Exception as error:
        row["failure"] = bench.bounded_failure(error)
    row["elapsed_seconds"] = time.monotonic() - started
    return row


class LiveStream:
    """An established TCP socket exchanging seeded blocks across one injected fault."""

    def __init__(self, port: int, seed: int):
        self.block = bench.payload(seed, 4096, 901)
        self.record = {"status": "RUNNING", "verified_bytes": 0, "eof": False,
                       "established_before_fault": False, "post_fault_verified_bytes": 0,
                       "payload_sha256": hashlib.sha256(self.block).hexdigest(),
                       "existing_stream_survived": False, "observation_complete": False,
                       "forced_local_close": False, "natural_terminal_observed": False,
                       "exchange_attempts": 1, "completed_exchanges": 0}
        self.stop = threading.Event()
        self.restored = threading.Event()
        self.post_fault = threading.Event()
        self.done = threading.Event()
        self.forced = threading.Event()
        self.record_lock = threading.Lock()
        self.thread = None
        self.deadline = time.monotonic() + DROP_SECONDS + RECOVERY_SECONDS + 30
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=5)
        self.sock.settimeout(5)
        try:
            self.sock.sendall(b"T" + self.block)
            receive_exact(self.sock, self.block, self.record, deadline=time.monotonic() + 5)
            self.record["established_before_fault"] = True
            self.record["completed_exchanges"] = 1
            self.sock.settimeout(DROP_SECONDS + RECOVERY_SECONDS + 10)
        except BaseException:
            self.sock.close()
            raise

    def start(self):
        self.thread = threading.Thread(target=self.run, name="fault-existing-stream", daemon=True)
        self.thread.start()

    def run(self):
        # Publish a completed snapshot under the same lock used by finish. No partial
        # receive counters can race with serialization if forced shutdown fails.
        with self.record_lock:
            record = copy.deepcopy(self.record)
        try:
            while not self.stop.is_set():
                # Only an exchange started after forwarding restoration counts as survival.
                after = self.restored.is_set()
                remaining = self.deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError("existing stream exceeded total observation deadline")
                self.sock.settimeout(remaining)
                record["exchange_attempts"] += 1
                self.sock.sendall(self.block)
                receive_exact(self.sock, self.block, record,
                              deadline=self.deadline)
                record["completed_exchanges"] += 1
                if after:
                    record["post_fault_verified_bytes"] += len(self.block)
                    self.post_fault.set()
                self.stop.wait(.1)
            self.sock.shutdown(socket.SHUT_WR)
            self.sock.settimeout(max(.001, self.deadline - time.monotonic()))
            if self.sock.recv(1):
                record["byte_mismatch"] = True
                raise AssertionError("surplus response bytes")
            record.update(eof=True, status="PASS", terminal_outcome="clean_eof",
                          natural_terminal_observed=True, observation_complete=True)
        except Exception as error:
            record["status"] = "FAILED"
            record["failure"] = bench.bounded_failure(error)
            if self.forced.is_set():
                record["terminal_outcome"] = "forced_local_close"
            elif isinstance(error, TimeoutError):
                record["terminal_outcome"] = "observation_deadline_expired"
            elif record.get("byte_mismatch"):
                record.update(terminal_outcome="byte_mismatch", observation_complete=True)
            elif ((isinstance(error, AssertionError) and str(error).startswith("early EOF"))
                  or (isinstance(error, OSError) and (error.errno in (errno.ECONNRESET, errno.ECONNABORTED, errno.EPIPE)
                       or getattr(error, "winerror", None) in (10053, 10054)))):
                record.update(terminal_outcome="remote_eof_or_socket_reset", natural_terminal_observed=True,
                              observation_complete=True)
            else:
                record["terminal_outcome"] = "unexpected_local_error"
        finally:
            self.sock.close()
            record["terminal_observed_monotonic"] = time.monotonic()
            # Observation metadata is parent-owned; never replace it from the worker.
            record.pop("forced_local_close", None)
            record.pop("observation_deadline_monotonic", None)
            with self.record_lock:
                self.record.update(record)
            self.done.set()

    def finish(self, observation_deadline: float | None = None):
        deadline = self.deadline if observation_deadline is None else observation_deadline
        with self.record_lock:
            self.record["observation_deadline_monotonic"] = deadline
        if self.thread:
            # Await either a natural interruption or a verified post-fault exchange.
            # A five-second join would truncate the relay's 30-second QUIC idle timer.
            while self.thread.is_alive() and not self.post_fault.is_set():
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    break
                self.done.wait(min(1, remaining))
            self.stop.set()
            while self.thread.is_alive() and time.monotonic() < deadline:
                self.thread.join(min(1, max(0, deadline - time.monotonic())))
            if self.thread.is_alive():
                self.forced.set()
                with self.record_lock:
                    self.record["forced_local_close"] = True
                    self.record["forced_local_close_monotonic"] = time.monotonic()
                with contextlib.suppress(OSError):
                    self.sock.shutdown(socket.SHUT_RDWR)
                self.sock.close()
                self.thread.join(2)
                with self.record_lock:
                    self.record.update(status="FAILED", terminal_outcome="forced_local_close",
                                       observation_complete=False, natural_terminal_observed=False,
                                       observation_failure={"type": "TimeoutError", "message": "existing stream observation deadline expired; local socket forcibly closed"})
            with self.record_lock:
                self.record["worker_terminated"] = not self.thread.is_alive()
                if not self.record["worker_terminated"]:
                    self.record["observation_complete"] = False
        else:
            self.stop.set()
            self.sock.close()
        with self.record_lock:
            self.record["existing_stream_survived"] = (
                self.record["status"] == "PASS" and self.record["eof"]
                and self.record["post_fault_verified_bytes"] > 0)
            return copy.deepcopy(self.record)


def exact_probe(port: int, data: bytes, timeout: float) -> None:
    """Same half-close protocol as transfer, with one absolute attempt deadline."""
    deadline = time.monotonic() + timeout
    with socket.create_connection(("127.0.0.1", port), timeout=timeout) as sock:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("probe connection exceeded deadline")
        sock.settimeout(remaining)
        sock.sendall(b"H" + data)
        sock.shutdown(socket.SHUT_WR)
        receive_exact(sock, data, {"verified_bytes": 0}, deadline=deadline)
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise bench.MissingEofError("missing EOF before absolute probe deadline")
        sock.settimeout(remaining)
        try:
            if sock.recv(1):
                raise AssertionError("byte mismatch: surplus response bytes")
        except socket.timeout as error:
            raise bench.MissingEofError("missing EOF before absolute probe deadline") from error


def recovery_probe(port: int, seed: int, deadline: float, record: dict | None = None) -> dict:
    data = bench.payload(seed, 65536, 902)
    row = {} if record is None else record
    row.update({"available": False, "new_stream_byte_exact": False, "new_stream_eof": False,
           "attempts": 0, "failed_attempts": 0, "payload_sha256": hashlib.sha256(data).hexdigest(),
           "payload_bytes": len(data), "attempt_failures": []})
    started = time.monotonic()
    while time.monotonic() < deadline:
        row["attempts"] += 1
        try:
            exact_probe(port, data, min(3, max(.001, deadline - time.monotonic())))
            row.update(available=True, new_stream_byte_exact=True, new_stream_eof=True)
            break
        except (OSError, TimeoutError, AssertionError) as error:
            row["failed_attempts"] += 1
            row["attempt_failures"].append(bench.bounded_failure(error))
            row["attempt_failures"] = row["attempt_failures"][-8:]
            if "byte mismatch" in str(error).lower() or "surplus" in str(error).lower():
                row["byte_mismatch"] = True
                break
            time.sleep(min(.2, max(0, deadline - time.monotonic())))
    row["elapsed_seconds"] = time.monotonic() - started
    return row


def run_fault(rig, kind: str, seed: int, result: dict,
              observation_seconds: float = EXISTING_OBSERVATION_SECONDS) -> dict:
    row = {"scenario": kind, "status": "FAILED", "existing_stream": {}, "new_stream_recovery": {},
           "endpoint_before_fault": rig.public_port, "process_before": process_snapshot(rig)}
    result["cases"].append(row)  # Retain partial evidence even if setup or injection fails.
    live = None
    try:
        live = LiveStream(rig.public_port, seed)
        row["existing_stream"] = live.record
        row["fault_onset_monotonic"] = time.monotonic()
        reference = row["fault_onset_monotonic"] + (DROP_SECONDS if kind == "udp_drop_55_seconds" else 0)
        row["existing_stream_observation"] = {"configured_seconds": observation_seconds,
                                               "reference": "forwarding_gate_restoration" if kind == "udp_drop_55_seconds" else "fault_onset",
                                               "reference_monotonic": reference,
                                               "deadline_monotonic": reference + observation_seconds,
                                               "initial_worker_deadline_monotonic": reference + observation_seconds}
        live.deadline = reference + observation_seconds
        live.start()
        if kind == "tunnel_process_termination":
            row["process_restart"] = rig.restart_tunnel_process()
            if not row["process_restart"]["available"]:
                raise AssertionError("tunnel process restart unavailable")
        elif kind == "udp_drop_55_seconds":
            assert rig.bridge is not None
            before = rig.bridge.dropped
            rig.bridge.drop_for(DROP_SECONDS)
            reference = rig.bridge.drop_until
            row["existing_stream_observation"]["reference_monotonic"] = reference
            row["existing_stream_observation"]["deadline_monotonic"] = reference + observation_seconds
            live.deadline = reference + observation_seconds
            row["existing_stream_observation"]["deadline_rebased_to_actual_gate"] = True
            # No fresh probe can accidentally count a pre-drop exchange as recovery.
            row["udp_drop"] = {"configured_seconds": DROP_SECONDS,
                               "scheduled_gate_seconds": rig.bridge.drop_until - rig.bridge.drop_started,
                               "drop_onset_monotonic": rig.bridge.drop_started,
                               "forwarding_gate_deadline_monotonic": rig.bridge.drop_until,
                               "drop_wait_finished_monotonic": rig.bridge.drop_wait_finished,
                               "dropped_datagrams": rig.bridge.dropped - before}
        else:
            raise ValueError(kind)
        row["forwarding_available_monotonic"] = time.monotonic()
        row["endpoint_after_fault"] = rig.public_port
        row["endpoint_retained"] = row["endpoint_before_fault"] == rig.public_port
        live.restored.set()
        deadline = time.monotonic() + RECOVERY_SECONDS
        recovery_probe(rig.public_port, seed, deadline, row["new_stream_recovery"])
        completed = time.monotonic()
        available = row["new_stream_recovery"]["available"]
        row["new_stream_recovery"]["completion_from_fault_onset_seconds"] = completed - row["fault_onset_monotonic"] if available else None
        restoration = (rig.bridge.drop_until if kind == "udp_drop_55_seconds"
                       else row["forwarding_available_monotonic"])
        row["new_stream_recovery"]["completion_from_forwarding_gate_seconds"] = completed - restoration if available else None
        if not row["new_stream_recovery"]["available"]:
            raise AssertionError("fresh byte-exact/EOF recovery unavailable within window")
        row["status"] = "PASS"
    except Exception as error:
        row["failure"] = bench.bounded_failure(error)
    finally:
        if live:
            row["existing_stream"] = live.finish(live.deadline)
            row["existing_stream"]["observation_elapsed_from_fault_onset_seconds"] = time.monotonic() - row["fault_onset_monotonic"]
            terminal = row["existing_stream"].get("terminal_observed_monotonic")
            row["existing_stream"]["natural_terminal_from_fault_onset_seconds"] = (terminal - row["fault_onset_monotonic"]
                if terminal is not None and row["existing_stream"].get("natural_terminal_observed") else None)
        row["process_after"] = process_snapshot(rig)
        if row["existing_stream"].get("byte_mismatch"):
            row["status"] = "FAILED"
        if live and not row["existing_stream"].get("observation_complete"):
            row["status"] = "FAILED"
            row["observation_failure"] = row["existing_stream"].get("observation_failure", {"type": "TimeoutError", "message": "existing stream observation incomplete"})
        try:
            idle_started = time.monotonic()
            rig.assert_idle()
            row["active_connections_final"] = rig.metric(bench.ACTIVE_METRIC)
        except Exception as error:
            row["status"] = "FAILED"
            row["idle_failure"] = bench.bounded_failure(error)
        finally:
            row["idle_observation_seconds"] = time.monotonic() - idle_started
    return row


def markdown(result: dict) -> str:
    lines = ["# Loopback fault supplement", "", f"Status: **{result['status']}**", "",
             "Existing-stream interruption is an observed outcome; fresh recovery requires exact bytes and EOF.",
             "", "| Case | Status | Existing stream survived | New stream bytes and EOF |",
             "|---|---|---|---|"]
    for row in result["cases"]:
        lines.append(f"| {row['scenario']} | {row['status']} | {row.get('existing_stream', {}).get('existing_stream_survived', 'n/a')} | {row.get('new_stream_recovery', {}).get('available', 'n/a')} |")
    return "\n".join(lines) + "\n"


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--relay-binary", type=Path, required=True)
    parser.add_argument("--tunnel-jar", type=Path, required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--seed", type=int, default=1701)
    parser.add_argument("--existing-stream-observation-seconds", type=float, default=EXISTING_OBSERVATION_SECONDS,
                        help="natural existing-stream observation budget, 1 to 180 seconds; UDP counts from gate restoration")
    args = parser.parse_args(argv)
    if not math.isfinite(args.existing_stream_observation_seconds) or not 1 <= args.existing_stream_observation_seconds <= 180:
        parser.error("--existing-stream-observation-seconds must be between 1 and 180")
    return args


def process_snapshot(rig) -> dict:
    row = {"time_monotonic": time.monotonic()}
    for role in ("relay", "tunnel"):
        process = getattr(rig, role)
        try:
            row[role] = {"exit_code": process.poll(), **bench.process_sample(process)}
        except Exception as error:
            row[role] = {"failure": bench.bounded_failure(error)}
    return row


def run_slow_receivers(rig, seed: int, result: dict) -> dict:
    row = {"scenario": "eight_slow_receivers", "status": "FAILED", "streams": [],
           "attempted_transfers": STREAMS,
           "process_before": process_snapshot(rig), "process_samples": []}
    result["cases"].append(row)
    stop = threading.Event()
    samples = collections.deque(maxlen=65)

    def sample():
        while not stop.wait(.5):
            samples.append(process_snapshot(rig))

    sampler = threading.Thread(target=sample, name="fault-slow-receiver-sampler", daemon=True)
    sampler.start()
    try:
        try:
            row["streams"] = bench.run_concurrent(
                lambda index: slow_receiver(rig.public_port, bench.payload(seed, 1048576, index), index), STREAMS)
        except bench.ConcurrentTransferError as error:
            row["streams"] = [item["result"] for item in error.successes]
            row["stream_failures"] = [{"stream": index, **bench.bounded_failure(failed)}
                                      for index, failed in error.failures]
            raise
        rig.assert_idle()
        row["active_connections_final"] = rig.metric(bench.ACTIVE_METRIC)
        row["status"] = "PASS" if all(item["status"] == "PASS" for item in row["streams"]) else "FAILED"
    finally:
        stop.set()
        sampler.join(3)
        row["process_samples"] = list(samples)
        row["process_after"] = process_snapshot(rig)
    return row


def main(argv=None) -> int:
    args = parse_args(argv)
    if args.output.exists() or args.output.with_suffix(".md").exists():
        raise SystemExit("output already exists; choose a fresh path to preserve earlier evidence")
    if sys.platform != "win32" or platform.machine().lower() not in ("amd64", "x86_64"):
        raise SystemExit("benchmark requires Windows x86-64")
    logs = collections.deque(maxlen=100)
    result = {"schema_version": 2, "profile": "fault-matrix", "status": "FAILED", "cases": [],
              "started_utc": datetime.now(timezone.utc).isoformat(),
              "configuration": {"seed": args.seed, "slow_receivers": STREAMS,
                                "udp_drop_seconds": DROP_SECONDS, "recovery_window_seconds": RECOVERY_SECONDS,
                                "existing_stream_observation_seconds": args.existing_stream_observation_seconds}}
    rig = None
    try:
        relay, tunnel = args.relay_binary.resolve(), args.tunnel_jar.resolve()
        result["artifacts"] = {"relay_sha256": file_hash(relay), "tunnel_sha256": file_hash(tunnel),
                               "fault_harness_sha256": file_hash(Path(__file__)),
                               "shared_harness_sha256": file_hash(Path(bench.__file__))}
        result["source_provenance"] = {"harness": bench.source_provenance(Path(__file__).resolve()),
                                       "relay": bench.source_provenance(relay), "tunnel": bench.source_provenance(tunnel)}
        result["machine"] = bench.machine_info(args.java)
        rig = bench.Rig(relay, tunnel, args.java, logs)
        with rig:
            run_slow_receivers(rig, args.seed, result)
            run_fault(rig, "tunnel_process_termination", args.seed, result, args.existing_stream_observation_seconds)
            run_fault(rig, "udp_drop_55_seconds", args.seed, result, args.existing_stream_observation_seconds)
        result["clean_shutdown"] = {"relay": rig.relay_clean, "tunnel": rig.tunnel_clean}
        result["status"] = "PASS" if all(row["status"] == "PASS" for row in result["cases"]) and all(result["clean_shutdown"].values()) else "FAILED"
    except BaseException as error:
        result["failure"] = bench.bounded_failure(error)
    finally:
        if rig is not None and hasattr(rig, "lifecycle_snapshot"):
            result.update(rig.lifecycle_snapshot())
            result["reconnect_counter_meaning"] = "observed CLI retry attempts, not completed reconnects"
        if rig is not None and hasattr(rig, "relay_clean"):
            result["clean_shutdown"] = {"relay": rig.relay_clean, "tunnel": rig.tunnel_clean}
            result["process_exits"] = {role: getattr(rig, role).returncode if getattr(rig, role) else None
                                       for role in ("relay", "tunnel")}
        result["diagnostics"] = list(logs)[-50:]
        attempted = sum(row.get("attempted_transfers", 0) + row.get("new_stream_recovery", {}).get("attempts", 0) for row in result["cases"])
        successful = sum(sum(stream["status"] == "PASS" for stream in row.get("streams", []))
                         + int(row.get("new_stream_recovery", {}).get("available", False)) for row in result["cases"])
        result["transfer_counts"] = {"attempted": attempted, "successful": successful,
                                     "failed": attempted - successful,
                                     "scope": "slow receivers and fresh recovery probes; existing stream outcomes and internal Rig setup probes recorded separately"}
        result["finished_utc"] = datetime.now(timezone.utc).isoformat()
        args.output.parent.mkdir(parents=True, exist_ok=True)
        # Immutable failed evidence: never overwrite any previous record.
        with args.output.open("x", encoding="utf-8") as output:
            json.dump(result, output, indent=2)
            output.write("\n")
        with args.output.with_suffix(".md").open("x", encoding="utf-8") as output:
            output.write(markdown(result))
        print(f"{result['status']}: {args.output}")
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
