import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts import summarize_prelaunch_timing as timing


def write_run(path, elapsed_nanos):
    rows = []
    for sample in range(35):
        for mode in ("LIVE", "SHOWCASE"):
            rows.append({"schemaVersion": 1, "sample": sample,
                         "warmup": sample < 5, "mode": mode,
                         "worldBytes": 2_097_152, "elapsedNanos": elapsed_nanos})
    path.write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")
    return rows


class SummaryTest(unittest.TestCase):
    def test_median_and_fixed_gate(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            before = root / "baseline.jsonl"
            after = root / "candidate.jsonl"
            write_run(before, 10_000_000)
            write_run(after, 10_600_000)
            output = root / "summary"
            with patch.object(sys, "argv", ["summarize_prelaunch_timing.py",
                                            "--baseline", str(before),
                                            "--candidate", str(after),
                                            "--output", str(output)]):
                timing.main()
            result = json.loads((output / "summary.json").read_text(encoding="utf-8"))
            self.assertEqual(result["gateMaxMedianIncreasePercent"], 5)
            self.assertAlmostEqual(result["modes"]["LIVE"]["increasePercent"], 6)
            self.assertFalse(result["modes"]["LIVE"]["gatePass"])
            self.assertEqual(result["modes"]["SHOWCASE"]["baselineMeasuredSamples"], 30)

    def test_incomplete_or_mislabelled_run_fails(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "run.jsonl"
            rows = write_run(path, 10_000_000)
            path.write_text("\n".join(json.dumps(row) for row in rows[:-1]), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "incomplete"):
                timing.load_runs([path])
            rows[10]["warmup"] = True
            path.write_text("\n".join(json.dumps(row) for row in rows), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "incorrectly labelled"):
                timing.load_runs([path])


if __name__ == "__main__":
    unittest.main()
