#!/usr/bin/env python3
"""Isolate the Windows loopback echo workload without relay or tunnel processes."""

from __future__ import annotations

import argparse
import hashlib
import json
import platform
import sys
import threading
import time
import traceback
from datetime import datetime, timezone
from pathlib import Path

import benchmark_relay as benchmark


def exception_chain(error: BaseException) -> list[dict]:
    chain = []
    for _ in range(8):
        if error is None:
            break
        chain.append(benchmark.bounded_failure(error))
        error = error.__cause__ or error.__context__
    return chain


def run_direct(result: dict, runs: int = 5, seconds: float = 60, seed: int = 1701) -> None:
    block = benchmark.payload(seed, benchmark.CHUNK, 999)
    result["configuration"] = {"seed": seed, "runs": runs, "concurrency": 1,
                               "target_seconds_per_run": seconds, "socket_timeout_seconds": 30,
                               "payload_block_bytes": len(block),
                               "payload_block_sha256": hashlib.sha256(block).hexdigest()}
    result["denominators"] = {"attempted": 0, "completed": 0, "failed": 0}
    result["totals_completed_runs"] = {"guest_to_host_bytes": 0, "host_to_guest_bytes": 0}
    result["runs"] = []
    with benchmark.EchoServer(("127.0.0.1", 0), benchmark.EchoHandler) as echo:
        worker = threading.Thread(target=echo.serve_forever, name="direct-echo", daemon=True)
        worker.start()
        try:
            for index in range(runs):
                progress = benchmark.ThroughputProgress(1)
                echo.diagnostic_progress = progress
                case = {"phase": "direct_throughput", "run_index_zero_based": index,
                        "concurrency": 1, "target_seconds": seconds}
                with echo.event_lock:
                    echo.current_case = dict(case)
                result["current_case"] = case
                row = {"run_index_zero_based": index, "status": "FAILED"}
                result["runs"].append(row)
                result["denominators"]["attempted"] += 1
                stop = threading.Event()
                samples = []

                def sample():
                    while not stop.is_set():
                        samples.append({"elapsed_monotonic": time.monotonic(),
                                        "streams": progress.snapshot()})
                        if len(samples) > 90:
                            del samples[0]
                        stop.wait(1)

                sampler = threading.Thread(target=sample, name="direct-progress", daemon=True)
                sampler.start()
                try:
                    measured = benchmark.throughput_stream(echo.server_address[1], block, seconds,
                                                           30, progress=progress, stream_index=0)
                    row.update(measured)
                    row["status"] = "PASS"
                    result["denominators"]["completed"] += 1
                    for direction in result["totals_completed_runs"]:
                        result["totals_completed_runs"][direction] += measured[direction]
                except BaseException as error:
                    result["denominators"]["failed"] += 1
                    row["failure"] = benchmark.bounded_failure(error)
                    row["exception_chain"] = exception_chain(error)
                    result["failure"] = row["failure"]
                    result["failure_traceback"] = "".join(traceback.format_exception(error))[-6000:]
                    result["failure_counts"], _ = benchmark.failure_counts(error, True)
                    raise
                finally:
                    stop.set()
                    sampler.join(timeout=3)
                    row["progress_samples"] = samples
                    row["progress_final"] = progress.snapshot()
                    with echo.event_lock:
                        row["echo_events"] = list(echo.events)
                    row["echo_errors"] = list(echo.errors)
                    echo.diagnostic_progress = None
            result.pop("current_case", None)
            result["status"] = "PASS"
        finally:
            echo.shutdown()
            worker.join(timeout=5)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--runs", type=int, default=5)
    parser.add_argument("--seed", type=int, default=1701)
    args = parser.parse_args()
    if not 1 <= args.runs <= 10:
        parser.error("--runs must be between 1 and 10")
    if sys.platform != "win32" or platform.machine().lower() not in ("amd64", "x86_64"):
        parser.error("diagnostic requires Windows x86-64")
    summary = args.output.with_suffix(".md")
    if args.output.exists() or summary.exists():
        parser.error("refusing to overwrite existing diagnostic output")
    result = {"schema_version": 1, "status": "FAILED", "profile": "diagnostic-direct",
              "started_utc": datetime.now(timezone.utc).isoformat(),
              "machine": {"os": platform.platform(), "windows_build": platform.version(),
                          "architecture": platform.machine(), "cpu": platform.processor(),
                          "python": sys.version},
              "source_provenance": {"diagnostic": benchmark.source_provenance(Path(__file__).resolve()),
                                    "reused_harness": benchmark.source_provenance(Path(benchmark.__file__).resolve())},
              "source_sha256": {"diagnostic": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                                "reused_harness": hashlib.sha256(Path(benchmark.__file__).read_bytes()).hexdigest()},
              "failure_counts": {"failed_transfers": 0, "byte_mismatches": 0,
                                 "missing_eofs": 0, "timeouts": 0, "leaked_active_stream_gauges": 0}}
    started = time.monotonic()
    try:
        run_direct(result, args.runs, 60, args.seed)
    except BaseException as error:
        result.setdefault("failure", benchmark.bounded_failure(error))
        result["status"] = "FAILED"
    finally:
        result["elapsed_seconds"] = time.monotonic() - started
        result["finished_utc"] = datetime.now(timezone.utc).isoformat()
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open("x", encoding="utf-8") as output:
            output.write(json.dumps(result, indent=2) + "\n")
        lines = ["# Direct loopback diagnostic", "", f"Status: **{result['status']}**.",
                 "No relay or tunnel subprocesses were launched.",
                 "Echo workload uses byte and EOF verification; directions are not summed.",
                 f"Runs: {result.get('denominators', {})}.",
                 f"Completed-run byte totals: {result.get('totals_completed_runs', {})}."]
        if result.get("failure"):
            lines.append(f"Failure: {result['failure']}.")
        with summary.open("x", encoding="utf-8") as output:
            output.write("\n".join(lines) + "\n")
        print(f"{result['status']}: {args.output}")
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
