"""Run the Windows protocol corpus until each target accrues 30 CPU minutes.

The four target groups are framing, control, connection, and modelled state.
The campaign stops on the first differential mismatch and records the seed.
"""

import argparse
import ctypes
from ctypes import wintypes
import json
import os
from pathlib import Path
import subprocess
import sys
import time

import protocol_campaign as corpus

GROUPS = ("framing", "control", "connection", "state")


class MemoryCounters(ctypes.Structure):
    _fields_ = [("cb", wintypes.DWORD), ("PageFaultCount", wintypes.DWORD),
                ("PeakWorkingSetSize", ctypes.c_size_t), ("WorkingSetSize", ctypes.c_size_t),
                ("QuotaPeakPagedPoolUsage", ctypes.c_size_t),
                ("QuotaPagedPoolUsage", ctypes.c_size_t),
                ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t),
                ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
                ("PagefileUsage", ctypes.c_size_t), ("PeakPagefileUsage", ctypes.c_size_t)]


def filetime_seconds(value: wintypes.FILETIME) -> float:
    return ((value.dwHighDateTime << 32) | value.dwLowDateTime) / 10_000_000


def run_measured(command: list[str], environment: dict[str, str], max_rss: int) -> dict:
    started = time.monotonic()
    process = subprocess.Popen(command, cwd=corpus.ROOT, env=environment,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    try:
        output, _ = process.communicate(timeout=600)
    except subprocess.TimeoutExpired:
        process.kill()
        process.communicate()
        raise RuntimeError(f"evaluator exceeded 600-second per-batch timeout: {command[0]}")
    creation, exit_time, kernel, user = (wintypes.FILETIME() for _ in range(4))
    if not ctypes.windll.kernel32.GetProcessTimes(int(process._handle), ctypes.byref(creation),
                                                   ctypes.byref(exit_time), ctypes.byref(kernel),
                                                   ctypes.byref(user)):
        raise OSError("GetProcessTimes failed; CPU campaign cannot be certified")
    memory = MemoryCounters()
    memory.cb = ctypes.sizeof(memory)
    if not ctypes.windll.psapi.GetProcessMemoryInfo(int(process._handle), ctypes.byref(memory),
                                                     memory.cb):
        raise OSError("GetProcessMemoryInfo failed; memory campaign cannot be certified")
    if process.returncode:
        raise RuntimeError(f"evaluator failed ({process.returncode}): {output[-4000:]}")
    if memory.PeakWorkingSetSize > max_rss:
        raise RuntimeError(f"evaluator exceeded {max_rss} byte peak resident-memory ceiling")
    return {"wallSeconds": time.monotonic() - started,
            "cpuSeconds": filetime_seconds(kernel) + filetime_seconds(user),
            "peakRssBytes": memory.PeakWorkingSetSize}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--seed", type=int, default=20260925)
    parser.add_argument("--batch-count", type=int, default=10000)
    parser.add_argument("--minutes-per-target", type=float, default=30.0)
    parser.add_argument("--max-wall-hours", type=float, default=12.0)
    parser.add_argument("--max-rss-mib", type=int, default=512)
    args = parser.parse_args()
    if sys.platform != "win32":
        parser.error("Windows x86-64 is the supported target")
    if not 100 <= args.batch_count <= 100000:
        parser.error("--batch-count must be between 100 and 100000")
    if args.minutes_per_target <= 0 or args.max_wall_hours <= 0:
        parser.error("campaign time limits must be positive")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    environment = os.environ.copy()
    cargo = "cargo.exe"
    gradle = str(corpus.ROOT / "gradlew.bat")
    classpath_file = output / "java-classpath.txt"
    subprocess.run([cargo, "test", "--locked", "--release", "--package", "bta-anywhere-relay",
                    "--test", "protocol_corpus", "--no-run"], cwd=corpus.ROOT,
                   env=environment, check=True, timeout=600)
    subprocess.run([gradle, "--no-daemon", ":tunnel-client:protocolCorpusClasspath",
                    f"-PprotocolCorpusClasspathOutput={classpath_file}"], cwd=corpus.ROOT,
                   env=environment, check=True, timeout=600)
    binaries = list((corpus.ROOT / "target" / "release" / "deps").glob("protocol_corpus-*.exe"))
    if not binaries:
        raise FileNotFoundError("compiled Rust corpus evaluator is missing")
    rust_binary = max(binaries, key=lambda path: path.stat().st_mtime)
    java = str(Path(environment["JAVA_HOME"]) / "bin" / "java.exe") if "JAVA_HOME" in environment else "java.exe"
    classpath = classpath_file.read_text(encoding="utf-8")
    deadline = time.monotonic() + args.max_wall_hours * 3600
    max_rss = args.max_rss_mib * 1024 * 1024
    report = {"schemaVersion": 1, "seed": args.seed, "minutesPerTarget": args.minutes_per_target,
              "maxWallHours": args.max_wall_hours, "maxRssMiB": args.max_rss_mib,
              "targets": {}, "status": "running"}
    report_path = output / "long-summary.json"
    manifest_path = output / "batches.jsonl"
    with manifest_path.open("w", encoding="utf-8") as manifest:
        for group in GROUPS:
            cpu_total = 0.0
            batches = 0
            cases_total = 0
            while cpu_total < args.minutes_per_target * 60:
                if time.monotonic() >= deadline:
                    report["status"] = "failed_wall_timeout"
                    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
                    return 1
                batch_seed = args.seed + GROUPS.index(group) * 1_000_000 + batches
                batch_dir = output / group / f"batch-{batches:05d}"
                batch_dir.mkdir(parents=True)
                input_file = batch_dir / "corpus.jsonl"
                kinds = corpus.write_corpus(input_file, batch_seed, args.batch_count, group)
                rust_file = batch_dir / "rust-results.jsonl"
                java_file = batch_dir / "java-results.jsonl"
                environment["BTA_PROTOCOL_CORPUS"] = str(input_file)
                environment["BTA_PROTOCOL_RUST_OUTPUT"] = str(rust_file)
                try:
                    rust_metrics = run_measured([str(rust_binary), "shared_corpus", "--exact"],
                                                environment, max_rss)
                    java_metrics = run_measured([java, "-Xmx256m", "-cp", classpath,
                                                 "io.github.kxnar.btaanywhere.internal.ProtocolCorpusMain",
                                                 str(input_file), str(java_file)], environment, max_rss)
                    comparison = corpus.compare_results(corpus.read_results(rust_file),
                                                        corpus.read_results(java_file), kinds)
                except (RuntimeError, OSError, ValueError) as failure:
                    report["status"] = "failed_evaluator"
                    report["failure"] = {"target": group, "batch": batches,
                                         "error": str(failure)[:1000]}
                    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
                    raise
                mismatches = comparison["mismatches"]
                cpu_total += rust_metrics["cpuSeconds"] + java_metrics["cpuSeconds"]
                batches += 1
                cases_total += args.batch_count
                row = {"target": group, "batch": batches, "seed": batch_seed,
                       "cases": args.batch_count, "rust": rust_metrics, "java": java_metrics,
                       "mismatchCount": comparison["mismatchCount"],
                       "framingMismatchCount": comparison["framingMismatchCount"],
                       "typedMismatchCount": comparison["typedMismatchCount"],
                       "firstMismatches": mismatches[:20]}
                manifest.write(json.dumps(row, separators=(",", ":")) + "\n")
                manifest.flush()
                report["targets"][group] = {"cpuSeconds": cpu_total, "batches": batches,
                                            "cases": cases_total,
                                            "mismatchCount": comparison["mismatchCount"]}
                report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
                print(json.dumps({"target": group, "batches": batches,
                                  "cpuMinutes": round(cpu_total / 60, 2),
                                  "mismatches": comparison["mismatchCount"]}),
                      flush=True)
                if comparison["mismatchCount"]:
                    report["status"] = "failed_conformance"
                    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
                    return 1
                if batches > 1:
                    input_file.unlink()
                    rust_file.unlink()
                    java_file.unlink()
    report["status"] = "complete"
    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
