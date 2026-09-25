"""Windows process-accounting smoke for the manual protocol campaign runner."""

import os
import sys
import unittest

import protocol_long_campaign as campaign


@unittest.skipUnless(sys.platform == "win32", "Windows process API is required")
class ProtocolLongCampaignTest(unittest.TestCase):
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
