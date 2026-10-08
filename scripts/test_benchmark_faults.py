"""Fast local/unit checks; no release executable, JAR, or performance run required."""
import tempfile
import json
import threading
import time
import unittest
from pathlib import Path
from unittest import mock

import benchmark_faults as faults
import benchmark_relay as bench


class FakeSocket:
    def __init__(self, chunks):
        self.chunks = iter(chunks)

    def recv(self, _):
        return next(self.chunks, b"")


class FaultTests(unittest.TestCase):
    def test_incremental_exact_and_early_eof(self):
        row = {"verified_bytes": 0}
        faults.receive_exact(FakeSocket([b"ab", b"cd"]), b"abcd", row)
        self.assertEqual(row["verified_bytes"], 4)
        with self.assertRaisesRegex(AssertionError, "early EOF"):
            faults.receive_exact(FakeSocket([b"a"]), b"ab", {"verified_bytes": 0})

    def test_corruption_explicitly_recorded(self):
        row = {"verified_bytes": 0}
        with self.assertRaisesRegex(AssertionError, "byte mismatch"):
            faults.receive_exact(FakeSocket([b"x"]), b"a", row)
        self.assertTrue(row["byte_mismatch"])

    def test_new_stream_recovery_requires_transfer_success(self):
        with mock.patch.object(faults, "exact_probe", side_effect=[bench.MissingEofError("missing EOF"), 1]):
            row = faults.recovery_probe(1234, 1701, time.monotonic() + 2)
        self.assertEqual(row["attempts"], 2)
        self.assertTrue(row["new_stream_byte_exact"])
        self.assertTrue(row["new_stream_eof"])
        self.assertEqual(row["attempt_failures"][0]["type"], "MissingEofError")

    def test_recovery_cannot_hide_corruption_by_retry(self):
        with mock.patch.object(faults, "exact_probe", side_effect=[AssertionError("byte mismatch"), 1]):
            row = faults.recovery_probe(1234, 1701, time.monotonic() + 2)
        self.assertFalse(row["available"])
        self.assertTrue(row["byte_mismatch"])
        self.assertEqual(row["attempts"], 1)

    def test_receive_total_deadline_expires_despite_readable_data(self):
        sock = mock.Mock()
        sock.recv.side_effect = [b"a", b"b", b"c"]
        with mock.patch.object(faults.time, "monotonic", side_effect=[1, 2, 4]):
            with self.assertRaisesRegex(TimeoutError, "total deadline"):
                faults.receive_exact(sock, b"abc", {"verified_bytes": 0}, deadline=3)
        self.assertEqual(sock.recv.call_count, 2)

    def make_observed_live(self, terminal_seconds):
        clock = [0.0]
        live = object.__new__(faults.LiveStream)
        live.deadline = 90.0
        live.record = {"status": "RUNNING", "eof": False, "post_fault_verified_bytes": 0,
                       "forced_local_close": False, "observation_complete": False}
        live.sock = mock.Mock()
        live.record_lock = threading.Lock()
        live.forced = threading.Event()
        live.stop = threading.Event()
        live.post_fault = threading.Event()

        def advance(seconds):
            clock[0] += seconds
            if clock[0] >= terminal_seconds:
                live.record.update(status="FAILED", natural_terminal_observed=True,
                                   observation_complete=True, terminal_outcome="remote_eof_or_reset")

        live.done = mock.Mock()
        live.done.wait.side_effect = advance
        live.thread = mock.Mock()
        live.thread.is_alive.side_effect = lambda: clock[0] < terminal_seconds and not live.sock.close.called
        live.thread.join.side_effect = advance
        return live, clock

    def test_observation_waits_for_natural_terminal_beyond_old_five_second_join(self):
        live, clock = self.make_observed_live(30)
        with mock.patch.object(faults.time, "monotonic", side_effect=lambda: clock[0]):
            row = live.finish(90)
        self.assertEqual(clock[0], 30)
        self.assertTrue(row["observation_complete"])
        self.assertTrue(row["natural_terminal_observed"])
        self.assertFalse(row["forced_local_close"])
        live.sock.shutdown.assert_not_called()
        self.assertTrue(row["worker_terminated"])

    def test_expired_observation_records_forced_close_and_snapshot_is_frozen(self):
        live, clock = self.make_observed_live(100)
        with mock.patch.object(faults.time, "monotonic", side_effect=lambda: clock[0]):
            row = live.finish(5)
        self.assertTrue(row["forced_local_close"])
        self.assertFalse(row["observation_complete"])
        self.assertEqual(row["terminal_outcome"], "forced_local_close")
        live.record["status"] = "late worker mutation"
        self.assertEqual(row["status"], "FAILED")
        self.assertEqual(row["observation_deadline_monotonic"], 5)

    def test_nonterminating_worker_is_failed_and_detached_from_snapshot(self):
        live, clock = self.make_observed_live(100)
        live.thread.is_alive.side_effect = lambda: True
        with mock.patch.object(faults.time, "monotonic", side_effect=lambda: clock[0]):
            row = live.finish(5)
        self.assertFalse(row["worker_terminated"])
        self.assertFalse(row["observation_complete"])
        live.record["late_nested_error"] = {"message": "late"}
        self.assertNotIn("late_nested_error", row)

    def test_unexpected_local_worker_error_is_not_natural_interruption(self):
        sock = mock.Mock()
        sock.recv.return_value = bench.payload(1701, 4096, 901)
        with mock.patch.object(faults.socket, "create_connection", return_value=sock):
            live = faults.LiveStream(1234, 1701)
        sock.sendall.side_effect = TypeError("unexpected implementation error")
        live.run()
        self.assertEqual(live.record["terminal_outcome"], "unexpected_local_error")
        self.assertFalse(live.record["natural_terminal_observed"])
        self.assertFalse(live.record["observation_complete"])

    def test_truncated_existing_observation_fails_matrix_despite_fresh_recovery(self):
        rig = mock.Mock(spec=bench.Rig, public_port=1234, relay=None, tunnel=None)
        rig.restart_tunnel_process.return_value = {"available": True}
        rig.metric.return_value = 0
        live = mock.Mock()
        live.finish.return_value = {"observation_complete": False, "forced_local_close": True,
                                    "worker_terminated": True}

        def recovered(_port, _seed, _deadline, record):
            record.update(available=True)

        with mock.patch.object(faults, "LiveStream", return_value=live), \
                mock.patch.object(faults, "process_snapshot", return_value={}), \
                mock.patch.object(faults, "recovery_probe", side_effect=recovered):
            row = faults.run_fault(rig, "tunnel_process_termination", 1701, {"cases": []})
        self.assertEqual(row["status"], "FAILED")
        self.assertTrue(row["new_stream_recovery"]["available"])
        self.assertIn("observation_failure", row)

    def test_cli_rejects_unbounded_observation(self):
        for value in ("nan", "inf", "0", "181"):
            with self.subTest(value=value), self.assertRaises(SystemExit):
                faults.parse_args(["--relay-binary", "r", "--tunnel-jar", "t", "--output", "o",
                                   "--existing-stream-observation-seconds", value])

    def test_recovery_progress_survives_cancellation(self):
        record = {}
        with mock.patch.object(faults, "exact_probe", side_effect=KeyboardInterrupt()):
            with self.assertRaises(KeyboardInterrupt):
                faults.recovery_probe(1234, 1701, time.monotonic() + 2, record)
        self.assertEqual(record["attempts"], 1)
        self.assertFalse(record["available"])

    def test_probe_surplus_and_missing_eof(self):
        for final, failure in ((b"!", AssertionError), (TimeoutError("timed out"), bench.MissingEofError)):
            with self.subTest(final=final):
                sock = mock.MagicMock()
                sock.__enter__.return_value = sock
                sock.recv.side_effect = [b"abc", final]
                with mock.patch.object(faults.socket, "create_connection", return_value=sock):
                    with self.assertRaises(failure):
                        faults.exact_probe(1234, b"abc", 2)

    def test_slow_receiver_partial_successes_retained(self):
        success = {"stream": 0, "status": "PASS"}
        failure = bench.ConcurrentTransferError("slow", [(1, KeyboardInterrupt())],
                                                [{"stream": 0, "result": success}])
        result = {"cases": []}
        with mock.patch.object(faults, "process_snapshot", return_value={}), \
                mock.patch.object(bench, "run_concurrent", side_effect=failure):
            with self.assertRaises(bench.ConcurrentTransferError):
                faults.run_slow_receivers(mock.Mock(), 1701, result)
        self.assertEqual(result["cases"][0]["streams"], [success])
        self.assertEqual(result["cases"][0]["stream_failures"][0]["type"], "KeyboardInterrupt")

    def test_failed_fault_setup_retains_case(self):
        rig = mock.Mock(spec=bench.Rig, public_port=1234, relay=None, tunnel=None)
        rig.metric.return_value = 0
        result = {"cases": []}
        with mock.patch.object(faults, "LiveStream", side_effect=ConnectionError("refused")):
            row = faults.run_fault(rig, "tunnel_process_termination", 1701, result)
        self.assertEqual(result["cases"], [row])
        self.assertEqual(row["status"], "FAILED")
        self.assertEqual(row["failure"]["type"], "ConnectionError")
        rig.assert_idle.assert_called_once()

    def test_surplus_response_is_corruption(self):
        sock = mock.MagicMock()
        sock.recv.side_effect = [b"abc", b"!"]
        with mock.patch.object(faults.socket, "create_connection", return_value=sock):
            sock.__enter__.return_value = sock
            row = faults.slow_receiver(1234, b"abc", 0)
        self.assertEqual(row["status"], "FAILED")
        self.assertTrue(row["byte_mismatch"])

    def test_cancellation_writes_failed_record(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            relay, tunnel = directory / "r.exe", directory / "t.jar"
            relay.write_bytes(b"relay")
            tunnel.write_bytes(b"tunnel")
            output = directory / "result.json"
            with mock.patch.object(faults.sys, "platform", "win32"), \
                    mock.patch.object(faults.platform, "machine", return_value="AMD64"), \
                    mock.patch.object(bench, "source_provenance", return_value={}), \
                    mock.patch.object(bench, "machine_info", side_effect=KeyboardInterrupt()):
                status = faults.main(["--relay-binary", str(relay), "--tunnel-jar", str(tunnel), "--output", str(output)])
            self.assertEqual(status, 1)
            record = json.loads(output.read_text())
            self.assertEqual(record["failure"]["type"], "KeyboardInterrupt")
            self.assertEqual(record["status"], "FAILED")
            with self.assertRaises(SystemExit):
                faults.main(["--relay-binary", str(relay), "--tunnel-jar", str(tunnel), "--output", str(output)])

    def test_file_hash_and_cli(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "artifact"
            path.write_bytes(b"abc")
            self.assertEqual(faults.file_hash(path), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        args = faults.parse_args(["--relay-binary", "r.exe", "--tunnel-jar", "t.jar", "--output", "o.json"])
        self.assertEqual(args.seed, 1701)

    def test_live_stream_survival_is_separate_from_new_recovery(self):
        with bench.EchoServer(("127.0.0.1", 0), bench.EchoHandler) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            live = faults.LiveStream(server.server_address[1], 1701)
            try:
                live.start()
                live.restored.set()
                self.assertTrue(live.post_fault.wait(2))
                row = live.finish()
                self.assertTrue(row["existing_stream_survived"])
                self.assertTrue(row["eof"])
                self.assertGreater(row["post_fault_verified_bytes"], 0)
            finally:
                live.finish()
                server.shutdown()
                thread.join(2)

    def test_slow_receiver_verifies_eof(self):
        with bench.EchoServer(("127.0.0.1", 0), bench.EchoHandler) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                row = faults.slow_receiver(server.server_address[1], bench.payload(1701, 8192, 0), 0)
                self.assertEqual(row["status"], "PASS")
                self.assertTrue(row["eof"])
                self.assertEqual(row["verified_bytes"], 8192)
            finally:
                server.shutdown()
                thread.join(2)


if __name__ == "__main__":
    unittest.main()
