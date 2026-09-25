"""Run the Windows protocol corpus until each target accrues 30 CPU minutes.

The four target groups are framing, control, connection, and modelled state.
The campaign stops on the first differential mismatch and records the seed.
"""

import argparse
import ctypes
from ctypes import wintypes
import json
import math
import os
from pathlib import Path
import subprocess
import sys
import threading
import time

import protocol_campaign as corpus

GROUPS = ("framing", "control", "connection", "state")
MAX_MANIFEST_BYTES = 16 * 1024 * 1024


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


def process_metrics(process: subprocess.Popen) -> tuple[float, int]:
    """Read CPU and peak RSS while the Windows process handle is still open."""
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    psapi = ctypes.WinDLL("psapi", use_last_error=True)
    kernel32.GetProcessTimes.argtypes = [wintypes.HANDLE] + [ctypes.POINTER(wintypes.FILETIME)] * 4
    kernel32.GetProcessTimes.restype = wintypes.BOOL
    psapi.GetProcessMemoryInfo.argtypes = [wintypes.HANDLE, ctypes.POINTER(MemoryCounters), wintypes.DWORD]
    psapi.GetProcessMemoryInfo.restype = wintypes.BOOL
    handle = wintypes.HANDLE(process._handle)
    creation, exit_time, kernel, user = (wintypes.FILETIME() for _ in range(4))
    if not kernel32.GetProcessTimes(handle, ctypes.byref(creation), ctypes.byref(exit_time),
                                     ctypes.byref(kernel), ctypes.byref(user)):
        raise OSError(ctypes.get_last_error(), "GetProcessTimes failed; CPU campaign cannot be certified")
    memory = MemoryCounters()
    memory.cb = ctypes.sizeof(memory)
    if not psapi.GetProcessMemoryInfo(handle, ctypes.byref(memory), memory.cb):
        raise OSError(ctypes.get_last_error(), "GetProcessMemoryInfo failed; memory campaign cannot be certified")
    if memory.PeakWorkingSetSize <= 0:
        raise OSError("GetProcessMemoryInfo returned no peak RSS")
    return filetime_seconds(kernel) + filetime_seconds(user), memory.PeakWorkingSetSize


def run_measured(command: list[str], environment: dict[str, str], max_rss: int) -> dict:
    started = time.monotonic()
    process = subprocess.Popen(command, cwd=corpus.ROOT, env=environment,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    output_tail = bytearray()

    def drain_output() -> None:
        assert process.stdout is not None
        while chunk := process.stdout.read(8192):
            output_tail.extend(chunk)
            if len(output_tail) > 4096:
                del output_tail[:-4096]

    reader = threading.Thread(target=drain_output, daemon=True)
    reader.start()
    try:
        while process.poll() is None:
            if time.monotonic() - started > 600:
                raise RuntimeError(f"evaluator exceeded 600-second per-batch timeout: {command[0]}")
            _, peak_rss = process_metrics(process)
            if peak_rss > max_rss:
                raise RuntimeError(f"evaluator exceeded {max_rss} byte peak resident-memory ceiling")
            time.sleep(0.05)
    except BaseException:
        if process.poll() is None:
            process.kill()
        raise
    finally:
        process.wait(timeout=10)
        reader.join(timeout=10)
        if reader.is_alive():
            raise RuntimeError("evaluator output pipe did not close")
        assert process.stdout is not None
        process.stdout.close()
    cpu_seconds, peak_rss = process_metrics(process)
    if process.returncode:
        diagnostic = output_tail.decode("utf-8", errors="replace")
        raise RuntimeError(f"evaluator failed ({process.returncode}): {diagnostic}")
    if peak_rss > max_rss:
        raise RuntimeError(f"evaluator exceeded {max_rss} byte peak resident-memory ceiling")
    return {"wallSeconds": time.monotonic() - started,
            "cpuSeconds": cpu_seconds, "peakRssBytes": peak_rss}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--seed", type=int, default=20260925)
    parser.add_argument("--batch-count", type=int, default=10000)
    parser.add_argument("--minutes-per-target", type=float, default=30.0)
    parser.add_argument("--max-wall-hours", type=float, default=12.0)
    parser.add_argument("--max-rss-mib", type=int, default=512)
    parser.add_argument("--max-batches-per-target", type=int, default=20000)
    args = parser.parse_args()
    if sys.platform != "win32":
        parser.error("Windows x86-64 is the supported target")
    if not 100 <= args.batch_count <= 100000:
        parser.error("--batch-count must be between 100 and 100000")
    if (not math.isfinite(args.minutes_per_target) or args.minutes_per_target <= 0
            or not math.isfinite(args.max_wall_hours) or args.max_wall_hours <= 0):
        parser.error("campaign time limits must be positive")
    if args.max_rss_mib < 256 or args.max_batches_per_target < 1:
        parser.error("RSS ceiling must be at least 256 MiB and batch ceiling must be positive")
    output = args.output.resolve()
    corpus.prepare_output_directory(output)
    metadata = corpus.machine_metadata()
    report = {"schemaVersion": 1, "seed": args.seed, "pythonVersion": sys.version.split()[0],
              "machine": metadata, "batchCount": args.batch_count,
              "minutesPerTarget": args.minutes_per_target,
              "maxWallHours": args.max_wall_hours, "maxRssMiB": args.max_rss_mib,
              "maxBatchesPerTarget": args.max_batches_per_target,
              "evaluatorTimeoutSeconds": 600, "memorySamplingSeconds": 0.05,
              "targets": {}, "status": "setup"}
    report_path = output / "long-summary.json"
    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    environment = os.environ.copy()
    cargo = "cargo.exe"
    gradle = str(corpus.ROOT / "gradlew.bat")
    classpath_file = output / "java-classpath.txt"
    rust_build_command = [cargo, "test", "--locked", "--release", "--package",
                          "bta-anywhere-relay", "--test", "protocol_corpus", "--no-run"]
    java_build_command = [gradle, "--no-daemon", ":tunnel-client:protocolCorpusClasspath",
                          f"-PprotocolCorpusClasspathOutput={classpath_file}"]
    report["setupCommands"] = {"rust": rust_build_command, "java": java_build_command}
    try:
        subprocess.run(rust_build_command, cwd=corpus.ROOT,
                       env=environment, check=True, timeout=600)
        subprocess.run(java_build_command, cwd=corpus.ROOT,
                       env=environment, check=True, timeout=600)
    except (subprocess.SubprocessError, OSError) as failure:
        report["status"] = "failed_setup"
        report["failure"] = str(failure)[:1000]
        report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        raise
    binaries = list((corpus.ROOT / "target" / "release" / "deps").glob("protocol_corpus-*.exe"))
    if not binaries:
        raise FileNotFoundError("compiled Rust corpus evaluator is missing")
    rust_binary = max(binaries, key=lambda path: path.stat().st_mtime)
    java = str(Path(environment["JAVA_HOME"]) / "bin" / "java.exe") if "JAVA_HOME" in environment else "java.exe"
    classpath = classpath_file.read_text(encoding="utf-8")
    report["rustBinarySha256"] = corpus.sha256_file(rust_binary)
    report["javaClasspathFileSha256"] = corpus.sha256_file(classpath_file)
    report["evaluatorCommands"] = {"rust": [str(rust_binary), "shared_corpus", "--exact"],
                                   "java": [java, "-Xmx256m", "-cp", "<java-classpath.txt>",
                                            "io.github.kxnar.btaanywhere.internal.ProtocolCorpusMain",
                                            "<batch-corpus>", "<batch-output>"]}
    deadline = time.monotonic() + args.max_wall_hours * 3600
    max_rss = args.max_rss_mib * 1024 * 1024
    report["status"] = "running"
    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    manifest_path = output / "batches.jsonl"
    with manifest_path.open("w", encoding="utf-8") as manifest:
        for group in GROUPS:
            cpu_total = 0.0
            batches = 0
            cases_total = 0
            while cpu_total < args.minutes_per_target * 60:
                if batches >= args.max_batches_per_target:
                    report["status"] = "failed_batch_limit"
                    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
                    return 1
                if time.monotonic() >= deadline:
                    report["status"] = "failed_wall_timeout"
                    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
                    return 1
                batch_seed = args.seed + GROUPS.index(group) * 1_000_000 + batches
                batch_dir = output / group / f"batch-{batches:05d}"
                batch_dir.mkdir(parents=True)
                input_file = batch_dir / "corpus.jsonl"
                kinds = corpus.write_corpus(input_file, batch_seed, args.batch_count, group)
                corpus_sha = corpus.sha256_file(input_file)
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
                       "cases": args.batch_count, "corpusSha256": corpus_sha,
                       "rust": rust_metrics, "java": java_metrics,
                       "mismatchCount": comparison["mismatchCount"],
                       "framingMismatchCount": comparison["framingMismatchCount"],
                       "typedMismatchCount": comparison["typedMismatchCount"],
                       "firstMismatches": mismatches[:20]}
                manifest.write(json.dumps(row, separators=(",", ":")) + "\n")
                manifest.flush()
                if manifest_path.stat().st_size > MAX_MANIFEST_BYTES:
                    report["status"] = "failed_manifest_limit"
                    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
                    return 1
                report["targets"][group] = {"cpuSeconds": cpu_total, "batches": batches,
                                            "cases": cases_total,
                                            "mismatchCount": comparison["mismatchCount"]}
                report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
                print(json.dumps({"target": group, "batches": batches,
                                  "cpuMinutes": round(cpu_total / 60, 2),
                                  "mismatches": comparison["mismatchCount"]}),
                      flush=True)
                if comparison["mismatchCount"]:
                    failures = batch_dir / "first-failures.jsonl"
                    corpus.write_first_failures(input_file, failures,
                                                {item["id"] for item in mismatches})
                    report["firstFailureCorpus"] = str(failures.relative_to(output))
                    report["status"] = "failed_conformance"
                    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
                    return 1
                if batches > 1:
                    input_file.unlink()
                    rust_file.unlink()
                    java_file.unlink()
                    batch_dir.rmdir()
    report["status"] = "complete"
    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
