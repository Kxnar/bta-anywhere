"""Fast, dependency-free checks for protocol campaign generation and reports."""

import tempfile
from pathlib import Path
import unittest

import protocol_campaign as campaign


class ProtocolCampaignTest(unittest.TestCase):
    def test_seed_reproduces_the_same_corpus(self):
        with tempfile.TemporaryDirectory() as directory:
            first = Path(directory) / "first.jsonl"
            second = Path(directory) / "second.jsonl"
            self.assertEqual(campaign.write_corpus(first, 1234, 100),
                             campaign.write_corpus(second, 1234, 100))
            self.assertEqual(first.read_bytes(), second.read_bytes())

    def test_exact_frame_boundaries_are_in_shared_generator(self):
        cases = {name: data for name, data, _, _, _ in campaign.boundary_cases()}
        for name, expected_length in (("length-zero", 0), ("length-one", 1),
                                      ("length-65536-valid-json", 65536),
                                      ("length-65537-reject", 65537)):
            with self.subTest(name=name):
                data = cases[name]
                self.assertEqual(int.from_bytes(data[:4], "big"), expected_length)
                self.assertEqual(len(data) - 4, expected_length)

    def test_comparison_reports_typed_failure_separately(self):
        rust = {"id": 0, "accepted": True, "semantic": {"type": "ping"},
                "typedAccepted": False, "typedSemantic": None, "stateOutcome": "syntax_rejected"}
        java = {"id": 0, "accepted": True, "semantic": {"type": "ping"},
                "typedAccepted": True, "typedSemantic": {"type": "ping"}, "stateOutcome": "pong"}
        report = campaign.compare_results([rust], [java], ["repeated-field"])
        self.assertEqual(report["mismatchCount"], 1)
        self.assertEqual(report["framingMismatchCount"], 0)
        self.assertEqual(report["typedMismatchCount"], 1)

    def test_comparison_rejects_missing_or_out_of_order_results(self):
        with self.assertRaises(ValueError):
            campaign.compare_results([], [], ["valid-ping"])
        with self.assertRaises(ValueError):
            campaign.compare_results([{"id": 1}], [{"id": 0}], ["valid-ping"])


if __name__ == "__main__":
    unittest.main()
