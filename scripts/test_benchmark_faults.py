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
