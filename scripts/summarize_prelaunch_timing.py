"""Summarize the opt-in synthetic pre-launch probe without changing its 5% gate."""

import argparse
import json
import statistics
from pathlib import Path


def load_runs(paths):
    result = {}
    for path in paths:
        rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
        seen = set()
        samples = {"LIVE": [], "SHOWCASE": []}
        for row in rows:
            if row.get("schemaVersion") != 1 or row.get("mode") not in samples:
                raise ValueError(f"invalid schema or mode in {path}")
            sample = row.get("sample")
            if not isinstance(sample, int) or not 0 <= sample < 35:
                raise ValueError(f"invalid sample in {path}")
            key = (row["mode"], sample)
            if key in seen or row.get("warmup") is not (sample < 5):
                raise ValueError(f"duplicate or incorrectly labelled sample in {path}")
            seen.add(key)
            elapsed = row.get("elapsedNanos")
            if not isinstance(elapsed, int) or elapsed <= 0 or row.get("worldBytes") != 2_097_152:
                raise ValueError(f"invalid timing or payload in {path}")
            if sample >= 5:
                samples[row["mode"]].append(elapsed / 1_000_000)
        if len(seen) != 70 or any(len(values) != 30 for values in samples.values()):
            raise ValueError(f"incomplete timing run in {path}")
        result[str(path)] = samples
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, action="append", required=True)
    parser.add_argument("--candidate", type=Path, action="append", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if len(args.baseline) != len(args.candidate):
        parser.error("baseline and candidate need the same number of alternating rounds")
    baseline = load_runs(args.baseline)
    candidate = load_runs(args.candidate)
    summary = {"schemaVersion": 1, "instrumentedSynthetic": True,
               "gateMaxMedianIncreasePercent": 5, "rounds": len(args.baseline),
               "baselinePaths": [str(path) for path in args.baseline],
               "candidatePaths": [str(path) for path in args.candidate], "modes": {}}
    lines = ["# Instrumented synthetic pre-launch timing", "",
             "Each round has 5 warmups and 30 measured samples per mode.",
             "The probe stops immediately before supervisor launch; it does not launch a server.", "",
             "| Mode | Baseline median | Candidate median | Increase | 5% gate |",
             "|---|---:|---:|---:|---|"]
    for mode in ("LIVE", "SHOWCASE"):
        before = [value for samples in baseline.values() for value in samples[mode]]
        after = [value for samples in candidate.values() for value in samples[mode]]
        before_median = statistics.median(before)
        after_median = statistics.median(after)
        increase = (after_median / before_median - 1) * 100
        row = {"baselineMedianMs": before_median, "candidateMedianMs": after_median,
               "increasePercent": increase, "gatePass": increase <= 5,
               "baselineMeasuredSamples": len(before), "candidateMeasuredSamples": len(after)}
        summary["modes"][mode] = row
        lines.append(f"| {mode} | {before_median:.3f} ms | {after_median:.3f} ms | "
                     f"{increase:+.2f}% | {'PASS' if row['gatePass'] else 'FAIL'} |")
    args.output.mkdir(parents=True, exist_ok=False)
    (args.output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    (args.output / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(args.output / "summary.md")


if __name__ == "__main__":
    main()
