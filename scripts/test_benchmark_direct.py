"""Evidence-retention regressions for direct-only isolation."""
import unittest
from unittest import mock

import benchmark_direct as direct


class DirectTests(unittest.TestCase):
    def test_failure_retains_completed_runs_totals_and_final_progress(self):
        result = {"status": "FAILED"}
        measured = {"seconds": 60, "guest_to_host_bytes": 123, "host_to_guest_bytes": 123}
        cause = ConnectionResetError("observed reset")
        failure = RuntimeError("throughput failed")
        failure.__cause__ = cause
        with mock.patch.object(direct.benchmark, "throughput_stream", side_effect=[measured, failure]):
            with self.assertRaises(RuntimeError):
                direct.run_direct(result, runs=5)
        self.assertEqual(result["denominators"], {"attempted": 2, "completed": 1, "failed": 1})
        self.assertEqual(result["totals_completed_runs"]["guest_to_host_bytes"], 123)
        self.assertEqual(result["runs"][0]["status"], "PASS")
        self.assertEqual(result["runs"][1]["exception_chain"][1]["type"], "ConnectionResetError")
        self.assertEqual(len(result["runs"][1]["progress_final"]), 1)
        self.assertEqual(result["failure_counts"]["failed_transfers"], 1)

    def test_success_uses_original_full_duration_and_no_rig(self):
        result = {"status": "FAILED"}
        measured = {"seconds": 60, "guest_to_host_bytes": 123, "host_to_guest_bytes": 123}
        with mock.patch.object(direct.benchmark, "throughput_stream", return_value=measured) as transfer, \
             mock.patch.object(direct.benchmark, "Rig", side_effect=AssertionError("no relay rig")):
            direct.run_direct(result)
        self.assertEqual(transfer.call_count, 5)
        self.assertTrue(all(call.args[2:4] == (60, 30) for call in transfer.call_args_list))
        self.assertEqual(result["status"], "PASS")
        self.assertEqual(result["denominators"]["completed"], 5)


if __name__ == "__main__":
    unittest.main()
