"""Fast, dependency-free checks for protocol campaign generation and reports."""

import tempfile
from pathlib import Path
import unittest
import json

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

    def test_nonempty_output_directory_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "campaign"
            campaign.prepare_output_directory(output)
            (output / "summary.json").write_text("old evidence", encoding="utf-8")
            with self.assertRaises(FileExistsError):
                campaign.prepare_output_directory(output)
            self.assertEqual((output / "summary.json").read_text(encoding="utf-8"), "old evidence")

    def test_first_failure_corpus_keeps_only_named_frames(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "all.jsonl"
            selected = Path(directory) / "first-failures.jsonl"
            rows = [{"id": index, "frameHex": f"{index:02x}"} for index in range(5)]
            source.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")
            campaign.write_first_failures(source, selected, {1, 3})
            self.assertEqual([json.loads(line) for line in selected.read_text(encoding="utf-8").splitlines()],
                             [rows[1], rows[3]])

    def test_comparison_omits_large_semantics_from_bounded_diagnostics(self):
        large = {"payload": "x" * 65536}
        rust = {"id": 0, "accepted": True, "semantic": large,
                "typedAccepted": False, "typedSemantic": None, "stateOutcome": "syntax_rejected"}
        java = {"id": 0, "accepted": False, "semantic": None,
                "typedAccepted": False, "typedSemantic": None, "stateOutcome": "syntax_rejected"}
        report = campaign.compare_results([rust], [java], ["boundary"])
        self.assertEqual(report["mismatchCount"], 1)
        self.assertLess(len(json.dumps(report)), 1024)

    def test_comparison_counts_all_mismatches_but_keeps_only_first_fifty(self):
        rust = [{"id": index, "accepted": True, "semantic": {"n": index},
                 "typedAccepted": True, "typedSemantic": {"n": index},
                 "stateOutcome": "accept"} for index in range(100)]
        java = [{**row, "accepted": False, "typedAccepted": False} for row in rust]
        report = campaign.compare_results(rust, java, ["case"] * 100)
        self.assertEqual(report["mismatchCount"], 100)
        self.assertEqual(report["framingMismatchCount"], 100)
        self.assertEqual(report["typedMismatchCount"], 100)
        self.assertEqual(len(report["mismatches"]), 50)
        self.assertEqual(report["mismatches"][-1]["id"], 49)

    def test_unknown_target_group_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                campaign.write_corpus(Path(directory) / "bad.jsonl", 1, 10, "missing")

    def test_connection_target_includes_negative_headers(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "connections.jsonl"
            kinds = campaign.write_corpus(output, 55, 100, "connection")
            self.assertIn("valid-connection", kinds)
            self.assertIn("connection-duplicate-session", kinds)
            self.assertIn("connection-truncated", kinds)
            self.assertIn("connection-invalid-utf8", kinds)
            for line in output.read_text(encoding="utf-8").splitlines():
                self.assertEqual(json.loads(line)["target"], "connection")


if __name__ == "__main__":
    unittest.main()
