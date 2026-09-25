"""Windows process-accounting smoke for the manual protocol campaign runner."""

import os
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

import protocol_long_campaign as campaign


@unittest.skipUnless(sys.platform == "win32", "Windows process API is required")
class ProtocolLongCampaignTest(unittest.TestCase):
    def test_missing_evaluator_is_recorded_as_failed_setup(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "source"
            root.mkdir()
            output = Path(directory) / "campaign"
            with (mock.patch.object(campaign.corpus, "ROOT", root),
                  mock.patch.object(campaign.corpus, "machine_metadata", return_value={}),
                  mock.patch.object(campaign.subprocess, "run"),
                  mock.patch.object(sys, "argv", ["protocol_long_campaign.py", "--output", str(output)])):
                with self.assertRaisesRegex(FileNotFoundError, "evaluator is missing"):
                    campaign.main()
            report = json.loads((output / "long-summary.json").read_text(encoding="utf-8"))
            self.assertEqual(report["status"], "failed_setup")
            self.assertIn("evaluator is missing", report["failure"])

    def test_cpu_and_peak_rss_remain_available_after_child_exit(self):
        result = campaign.run_measured([sys.executable, "-c", "print('done')"],
                                       os.environ.copy(), 512 * 1024 * 1024)
        self.assertGreaterEqual(result["cpuSeconds"], 0)
        self.assertGreater(result["peakRssBytes"], 0)

    def test_memory_ceiling_turns_growth_into_failure(self):
        with self.assertRaisesRegex(RuntimeError, "resident-memory ceiling"):
            campaign.run_measured([sys.executable, "-c", "import time; time.sleep(0.2)"],
                                  os.environ.copy(), 1)


if __name__ == "__main__":
    unittest.main()
