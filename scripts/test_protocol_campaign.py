"""Fast, dependency-free checks for protocol campaign generation and reports."""

import tempfile
from pathlib import Path
import unittest
import json
import os
import sys

import protocol_campaign as campaign


class ProtocolCampaignTest(unittest.TestCase):
    def test_failed_evaluator_keeps_only_bounded_output_tail(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            command = [sys.executable, "-c",
                       "import sys; print('x' * 6000 + ' failure-marker'); sys.exit(3)"]
            with self.assertRaisesRegex(campaign.EvaluatorFailure, "status 3"):
                campaign.run(command, os.environ.copy(), "test", output)
            tail = (output / "test-evaluator-tail.txt").read_bytes()
            self.assertLessEqual(len(tail), 4096)
            self.assertIn(b"failure-marker", tail)

    def test_timed_out_evaluator_keeps_early_diagnostic(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            command = [sys.executable, "-c",
                       "import time; print('before-timeout', flush=True); time.sleep(5)"]
            with self.assertRaisesRegex(campaign.EvaluatorFailure, "timeout"):
                campaign.run(command, os.environ.copy(), "test", output, timeout_seconds=0.2)
            self.assertIn(b"before-timeout", (output / "test-evaluator-tail.txt").read_bytes())

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

    def test_typed_coercion_boundaries_are_shared_exact_frames(self):
        vectors = campaign.typed_boundary_cases()
        names = {name for name, _, _, _, _ in vectors}
        self.assertIn("register-version-string", names)
        self.assertIn("register-id-number", names)
        self.assertIn("connection-version-decimal", names)
        self.assertIn("connection-id-number", names)
        self.assertIn("ping-sequence-u64-max", names)
        self.assertIn("ping-sequence-u64-overflow", names)
        self.assertIn("ping-sequence-negative-zero", names)
        self.assertIn("register-features-duplicate", names)
        self.assertIn("stream-eof-overflow", names)
        self.assertIn("stream-eof-max", names)
        for name, framed, _, _, _ in vectors:
            with self.subTest(name=name):
                self.assertEqual(int.from_bytes(framed[:4], "big"), len(framed) - 4)
                self.assertIsInstance(json.loads(framed[4:]), dict)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "connection.jsonl"
            kinds = campaign.write_corpus(path, 55, 100, "connection")
            self.assertIn("connection-version-string", kinds)
            self.assertIn("connection-id-number", kinds)
            self.assertNotIn("register-version-string", kinds)

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

    def test_state_target_includes_utf8_byte_length_boundaries(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "state.jsonl"
            kinds = campaign.write_corpus(output, 55, 100, "state")
            self.assertIn("unicode-client-id-at-limit", kinds)
            self.assertIn("unicode-client-id-over-limit", kinds)
            for line in output.read_text(encoding="utf-8").splitlines():
                case = json.loads(line)
                if not case["kind"].startswith("unicode-client-id-"):
                    continue
                frame = bytes.fromhex(case["frameHex"])
                message = json.loads(frame[4:])
                expected = 128 if case["kind"] == "unicode-client-id-at-limit" else 130
                self.assertEqual(len(message["clientInstanceId"].encode("utf-8")), expected)

    def test_feature_and_completion_cases_have_bounded_negative_models(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "state.jsonl"
            kinds = campaign.write_corpus(output, 20260925, 300, "state")
            for name in ("feature-register-valid", "feature-register-duplicate",
                         "feature-register-too-many", "stream-eof-accepted",
                         "stream-eof-pre", "stream-eof-unnegotiated", "stream-eof-late",
                         "stream-eof-duplicate", "stream-eof-short", "stream-eof-overflow",
                         "stream-eof-duplicate-bytes", "stream-eof-duplicate-id"):
                self.assertIn(name, kinds)
            cases = [json.loads(line) for line in output.read_text(encoding="utf-8").splitlines()]
            notices = {case["kind"]: case for case in cases
                       if case["kind"].startswith("stream-eof-")}
            self.assertTrue(notices["stream-eof-accepted"]["featureNegotiated"])
            self.assertFalse(notices["stream-eof-unnegotiated"]["featureNegotiated"])
            self.assertTrue(notices["stream-eof-duplicate"]["noticeAlreadySent"])
            self.assertEqual(notices["stream-eof-short"]["copiedBytes"], 5)
            self.assertEqual(notices["stream-eof-pre"]["phase"], "pre")


if __name__ == "__main__":
    unittest.main()
