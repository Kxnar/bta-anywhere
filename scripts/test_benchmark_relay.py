"""Fast standard-library tests for benchmark reporting and failure behavior."""

import socket
import socketserver
import subprocess
import tempfile
import threading
import unittest
import math
import shutil
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

import benchmark_relay as benchmark


class WrongReply(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.recv(1024)
        self.request.sendall(b"wrong")
        self.request.shutdown(socket.SHUT_WR)


class LateEofReply(socketserver.BaseRequestHandler):
    release = threading.Event()

    def handle(self):
        benchmark.read_exact(self.request, 1)
        size = int.from_bytes(benchmark.read_exact(self.request, 4), "big")
        self.request.sendall(benchmark.read_exact(self.request, size))
        self.release.wait(timeout=3)


class StalledDiagnosticEcho(socketserver.BaseRequestHandler):
    release = threading.Event()
    progress = None

    def handle(self):
        self.request.settimeout(1)
        header = benchmark.read_exact(self.request, 2)
        if header != b"D\x00":
            raise AssertionError("diagnostic stream header missing")
        chunk = self.request.recv(benchmark.CHUNK)
        self.progress.mark(0, "echo_connected")
        self.progress.advance(0, "echo_receive_bytes", len(chunk))
        self.release.wait(timeout=1)


class BenchmarkTests(unittest.TestCase):
    def test_stop_process_reports_already_exited_child_as_unclean(self):
        for returncode in (0, 7):
            with self.subTest(returncode=returncode):
                process = SimpleNamespace(returncode=returncode,
                                          poll=lambda: returncode, stdin=None)
                self.assertFalse(benchmark.stop_process(process))

    def test_percentile_interpolates_and_rejects_missing_samples(self):
        self.assertAlmostEqual(benchmark.percentile([1.0, 3.0, 5.0], 95), 4.8)
        self.assertEqual(benchmark.summary([1.0, 3.0])["count"], 2)
        with self.assertRaises(ValueError):
            benchmark.summary([])

    def test_failed_transfer_is_assertion_not_a_successful_measurement(self):
        with socketserver.ThreadingTCPServer(("127.0.0.1", 0), WrongReply) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                with self.assertRaises(AssertionError):
                    benchmark.transfer(server.server_address[1], "request_response", b"valid", 2)
            finally:
                server.shutdown()
                thread.join(timeout=2)

    def test_verified_reply_without_eof_is_counted_per_failed_stream(self):
        LateEofReply.release.clear()
        with socketserver.ThreadingTCPServer(("127.0.0.1", 0), LateEofReply) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                with self.assertRaises(benchmark.MissingEofError) as caught:
                    benchmark.transfer(server.server_address[1], "request_response", b"valid", 1)
            finally:
                LateEofReply.release.set()
                server.shutdown()
                thread.join(timeout=2)
        self.assertIn("5 verified reply bytes", str(caught.exception))
        error = benchmark.ConcurrentTransferError("case", [(1, caught.exception),
                                                             (3, caught.exception)], [])
        counts, streams = benchmark.failure_counts(error, True)
        self.assertEqual(counts["failed_transfers"], 2)
        self.assertEqual(counts["missing_eofs"], 2)
        self.assertEqual(counts["timeouts"], 2)
        self.assertEqual([item["stream"] for item in streams], [1, 3])
        report = benchmark.markdown({"status": "FAILED", "profile": "full", "commit": "abc",
                                     "failure": benchmark.bounded_failure(error),
                                     "failure_counts": counts, "stream_failures": streams})
        self.assertIn("missing EOFs: 2", report)
        self.assertIn("Failed stream indices: 1, 3", report)
        wrapped = RuntimeError("throughput failed: MissingEofError: missing EOF")
        wrapped.__cause__ = caught.exception
        wrapped_counts, _ = benchmark.failure_counts(
            benchmark.ConcurrentTransferError("throughput", [(0, wrapped)], []), True)
        self.assertEqual((wrapped_counts["missing_eofs"], wrapped_counts["timeouts"]), (1, 1))

    def test_failure_record_is_bounded_and_visible_in_summary(self):
        failure = benchmark.bounded_failure(RuntimeError("secret?" + "x" * 1000))
        self.assertEqual(len(failure["message"]), 500)
        report = benchmark.markdown({"status": "FAILED", "profile": "smoke", "commit": "abc",
                                     "failure": {"type": "TimeoutError", "message": "missing EOF"}})
        self.assertIn("Status: **FAILED**", report)
        self.assertIn("missing EOF", report)
        self.assertEqual(benchmark.failure_category(TimeoutError("read stalled")), "timeouts")
        self.assertEqual(benchmark.failure_category(AssertionError("byte mismatch")), "byte_mismatches")
        self.assertEqual(benchmark.failure_category(TimeoutError("active streams to drain")),
                         "leaked_active_stream_gauges")

    def test_parallel_failure_names_case_and_stream(self):
        def worker(index):
            if index == 1:
                raise TimeoutError("socket timed out")
            return index
        with self.assertRaisesRegex(benchmark.ConcurrentTransferError,
                                    "eight-stream relay run: stream=1 TimeoutError") as caught:
            benchmark.run_concurrent(worker, 2, "eight-stream relay run")
        self.assertEqual(caught.exception.successes, [{"stream": 0, "result": 0}])

    def test_failed_latency_retains_completed_case_context(self):
        rig = SimpleNamespace(echo=SimpleNamespace(server_address=("127.0.0.1", 100)),
                              public_port=200, assert_idle=lambda: None)
        result = {}
        with mock.patch.object(benchmark, "transfer", side_effect=benchmark.MissingEofError("missing EOF")):
            with self.assertRaises(benchmark.ConcurrentTransferError):
                benchmark.run_latency(rig, "smoke", 1701, result)
        self.assertEqual(result["latency_partial"], [])
        self.assertEqual(result["latency_in_progress"]["payload_bytes"], 1024)
        self.assertEqual(result["current_case"]["phase"], "latency")

    def test_generated_payload_is_reproducible(self):
        self.assertEqual(benchmark.payload(17, 1024, 4), benchmark.payload(17, 1024, 4))
        self.assertNotEqual(benchmark.payload(17, 1024, 4), benchmark.payload(17, 1024, 5))

    @unittest.skipUnless(shutil.which("git"), "Git unavailable")
    def test_artifact_provenance_uses_its_checkout_and_reports_dirty_or_unavailable(self):
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary) / "candidate"
            repo.mkdir()
            subprocess.run(["git", "init", "-q", str(repo)], check=True, capture_output=True)
            (repo / ".gitignore").write_text("target/\n", encoding="utf-8")
            tracked = repo / "source.txt"
            tracked.write_text("original", encoding="utf-8")
            subprocess.run(["git", "-C", str(repo), "add", ".gitignore", "source.txt"],
                           check=True, capture_output=True)
            subprocess.run(["git", "-C", str(repo), "-c", "user.name=Benchmark Test",
                            "-c", "user.email=benchmark@example.invalid", "commit", "-qm", "source"],
                           check=True, capture_output=True)
            binary = repo / "target" / "relay.exe"
            binary.parent.mkdir()
            binary.write_bytes(b"synthetic artifact")
            expected = subprocess.check_output(["git", "-C", str(repo), "rev-parse", "HEAD"],
                                               text=True).strip()
            clean = benchmark.source_provenance(binary)
            self.assertEqual(clean, {"status": "available", "commit": expected, "dirty": False})
            tracked.write_text("modified", encoding="utf-8")
            self.assertTrue(benchmark.source_provenance(binary)["dirty"])
            tracked.write_text("original", encoding="utf-8")
            (repo / "untracked.txt").write_text("new", encoding="utf-8")
            self.assertTrue(benchmark.source_provenance(binary)["dirty"])
            outside = Path(temporary) / "copied-relay.exe"
            outside.write_bytes(b"copy")
            unavailable = benchmark.source_provenance(outside)
            self.assertEqual(unavailable, {"status": "unavailable", "reason": "not_in_git_checkout"})
            self.assertNotIn(str(repo), str(clean) + str(unavailable))
            with mock.patch.object(benchmark.subprocess, "run", side_effect=OSError("private path")):
                self.assertEqual(benchmark.source_provenance(binary),
                                 {"status": "unavailable", "reason": "git_query_unavailable"})

    def test_markdown_distinguishes_harness_relay_and_tunnel_sources(self):
        report = benchmark.markdown({
            "status": "PASS", "profile": "smoke", "schema_version": 8,
            "source_provenance": {"method": "containing_git_checkout",
                                  "harness": {"status": "available", "commit": "a" * 40,
                                              "dirty": False},
                                  "relay": {"status": "available", "commit": "b" * 40,
                                            "dirty": True},
                                  "tunnel": {"status": "unavailable",
                                             "reason": "not_in_git_checkout"}},
            "artifacts": {"relay_sha256": "c" * 64, "tunnel_sha256": "d" * 64}})
        self.assertIn("Harness source: `" + "a" * 40 + "` (dirty=False)", report)
        self.assertIn("Relay artifact source: `" + "b" * 40 + "` (dirty=True)", report)
        self.assertIn("Tunnel artifact source: unavailable (not_in_git_checkout)", report)
        self.assertIn("relay `" + "c" * 64 + "`", report)

    def test_diagnostic_progress_is_bounded_and_rejects_invalid_updates(self):
        with self.assertRaises(ValueError):
            benchmark.ThroughputProgress(9)
        progress = benchmark.ThroughputProgress(2)
        progress.advance(1, "guest_send_bytes", 17)
        progress.mark(1, "guest_write_closed")
        progress.error(1, "guest", TimeoutError("private path and payload"))
        first = progress.snapshot()
        self.assertEqual(first[1]["guest_send_bytes"], 17)
        self.assertTrue(first[1]["guest_write_closed"])
        self.assertEqual(first[1]["guest_error"], "TimeoutError")
        self.assertNotIn("private", str(first))
        first[1]["guest_send_bytes"] = 0
        self.assertEqual(progress.snapshot()[1]["guest_send_bytes"], 17)
        for operation in (lambda: progress.advance(2, "guest_send_bytes", 1),
                          lambda: progress.advance(1, "bad", 1),
                          lambda: progress.advance(1, "guest_send_bytes", -1),
                          lambda: progress.mark(1, "bad"),
                          lambda: progress.error(1, "bad", ValueError())):
            with self.assertRaises(ValueError):
                operation()

    def test_diagnostic_stream_tracks_both_ends_without_payloads(self):
        progress = benchmark.ThroughputProgress(1)
        with benchmark.EchoServer(("127.0.0.1", 0), benchmark.EchoHandler) as server:
            server.diagnostic_progress = progress
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                result = benchmark.throughput_stream(server.server_address[1], b"private" * 1024,
                                                     0.02, 2, 0.01, progress, 0)
            finally:
                server.shutdown()
                thread.join(timeout=2)
        stream = progress.snapshot()[0]
        self.assertGreater(result["guest_to_host_bytes"], 0)
        self.assertEqual(stream["guest_send_bytes"], stream["echo_receive_bytes"])
        self.assertEqual(stream["echo_send_bytes"], stream["guest_receive_bytes"])
        self.assertEqual(stream["guest_receive_bytes"], result["host_to_guest_bytes"])
        self.assertTrue(all(stream[flag] for flag in benchmark.PROGRESS_FLAGS))
        self.assertNotIn("private", str(stream))

    def test_diagnostic_timeout_retains_last_per_stream_progress(self):
        progress = benchmark.ThroughputProgress(1)
        StalledDiagnosticEcho.release.clear()
        StalledDiagnosticEcho.progress = progress
        with socketserver.ThreadingTCPServer(("127.0.0.1", 0), StalledDiagnosticEcho) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                with self.assertRaises(RuntimeError) as caught:
                    benchmark.throughput_stream(server.server_address[1], b"x" * benchmark.CHUNK,
                                                0.1, 0.2, 0.1, progress, 0)
            finally:
                StalledDiagnosticEcho.release.set()
                server.shutdown()
                thread.join(timeout=2)
        stream = progress.snapshot()[0]
        self.assertIn("timed out", str(caught.exception))
        self.assertGreater(stream["guest_send_bytes"], 0)
        self.assertGreater(stream["echo_receive_bytes"], 0)
        self.assertEqual(stream["echo_send_bytes"], 0)
        self.assertEqual(stream["guest_receive_bytes"], 0)
        report = benchmark.markdown({"status": "FAILED", "profile": "diagnostic-eight-relay",
                                     "commit": "abc", "failure": benchmark.bounded_failure(caught.exception),
                                     "diagnostic_progress_final": progress.snapshot()})
        self.assertIn("Guest sent | Echo received | Echo sent | Guest received", report)
        self.assertIn("| 0 |", report)

    def test_summary_distinguishes_process_and_link_recovery(self):
        report = benchmark.markdown({
            "status": "PASS", "profile": "smoke", "commit": "abc",
            "tunnel_process_restart": {"available": True, "old_port": 1000,
                                       "new_port": 1001, "endpoint_retained": False,
                                       "completion_ms": 12.0},
            "tunnel_link_interruption": {"configured_drop_seconds": 55,
                                         "available": True,
                                         "endpoint_retained_by_byte_exact_probe": True,
                                         "resume_observed": True,
                                         "completion_ms": 56000.0}})
        self.assertIn("endpoint retained=False", report)
        self.assertIn("same bridge-backed endpoint=True", report)

    def test_udp_bridge_forwards_and_drops_without_queued_replay(self):
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as relay:
            relay.bind(("127.0.0.1", 0))
            relay.settimeout(1)
            bridge = benchmark.UdpBridge(relay.getsockname()[1])
            try:
                with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as client:
                    client.bind(("127.0.0.1", 0))
                    client.settimeout(0.3)
                    client.sendto(b"before", ("127.0.0.1", bridge.port))
                    data, source = relay.recvfrom(100)
                    self.assertEqual(data, b"before")
                    relay.sendto(b"reply", source)
                    self.assertEqual(client.recvfrom(100)[0], b"reply")
                    bridge.drop_until = benchmark.time.monotonic() + 0.1
                    client.sendto(b"dropped", ("127.0.0.1", bridge.port))
                    with self.assertRaises(socket.timeout):
                        relay.recvfrom(100)
                    self.assertGreaterEqual(bridge.dropped, 1)
            finally:
                bridge.close()

    def test_reconnect_attempt_counter_survives_diagnostic_ring_truncation(self):
        import collections
        import io
        import queue
        rig = SimpleNamespace(lifecycle_lock=threading.Lock(), observed_cli_reconnect_attempts=0,
                              redactions=[], logs=collections.deque(maxlen=2),
                              events=queue.Queue(), relay_events=queue.Queue())
        lines = ["Relay unavailable; retrying in 1000 ms"] * 7 + ["unrelated"] * 3
        benchmark.Rig.reader(rig, io.StringIO("\n".join(lines) + "\n"), "tunnel-err")
        self.assertEqual(rig.observed_cli_reconnect_attempts, 7)
        self.assertEqual(list(rig.logs), ["tunnel-err: unrelated"] * 2)
        benchmark.Rig.reader(rig, io.StringIO(lines[0] + "\n"), "tunnel-out")
        self.assertEqual(rig.observed_cli_reconnect_attempts, 7)

    def test_lifecycle_snapshot_retains_replaced_process_exit_without_private_paths(self):
        rig = SimpleNamespace(lifecycle_lock=threading.Lock(), observed_cli_reconnect_attempts=3,
                              owned_processes=[{"label": "relay", "started_utc": "earlier",
                                                "process": SimpleNamespace(poll=lambda: 0)},
                                               {"label": "relay", "started_utc": "later",
                                                "process": SimpleNamespace(poll=lambda: None)}])
        snapshot = benchmark.Rig.lifecycle_snapshot(rig)
        self.assertEqual(snapshot["observed_cli_reconnect_attempts"], 3)
        self.assertEqual([item["returncode"] for item in snapshot["owned_process_exits"]], [0, None])
        self.assertNotIn("process", snapshot["owned_process_exits"][0])

    def test_summary_labels_latency_counts_and_paired_delta(self):
        report = benchmark.markdown({"status": "PASS", "profile": "full", "latency": [{
            "concurrency": 8, "payload_bytes": 1024, "mode": "half_close",
            "paths": {"direct": {"latency_ms": {"count": 240, "p50": 1, "p95": 3}},
                      "relay": {"latency_ms": {"count": 240, "p50": 2, "p95": 5}}},
            "relay_added_ms": {"count": 240, "p95": 4}}]})
        self.assertIn("transaction completion time", report)
        self.assertIn("Direct n | Direct p50 ms | Direct p95 ms", report)
        self.assertIn("Paired delta n | Paired delta p95 ms", report)
        self.assertIn("| 240 | 1.00 | 3.00 | 240 | 2.00 | 5.00 | 240 | 4.00 |", report)
        self.assertIn("p99 is exploratory", report)
        self.assertIn("not summed", report)

    def test_soak_summary_retains_failure_counts_and_pending_review(self):
        report = benchmark.markdown({"status": "FAILED", "profile": "soak",
                                     "soak_partial": {"completed_waves": 3, "duration_seconds": 180,
                                                      "memory_samples": [{}, {}]},
                                     "failure_counts": {"failed_transfers": 2}})
        self.assertIn("Soak completed waves: 3", report)
        self.assertIn("failed transfers: 2", report)
        self.assertIn("PENDING_MANUAL_REVIEW", report)

    def test_recovery_probe_rejects_corruption_as_non_retryable(self):
        for message in ("byte mismatch", "byte mismatch: surplus response bytes after exact reply"):
            with self.subTest(message=message), \
                 mock.patch.object(benchmark, "transfer", side_effect=AssertionError(message)):
                with self.assertRaisesRegex(AssertionError, "byte mismatch"):
                    benchmark.reconnect_probe(123, b"expected")
        with mock.patch.object(benchmark, "transfer", side_effect=AssertionError("early EOF")):
            self.assertFalse(benchmark.reconnect_probe(123, b"expected"))

    def test_startup_failure_preserves_original_when_cleanup_also_fails(self):
        class BrokenRig:
            def _start(self):
                raise ValueError("startup original")
            def __exit__(self, exception_type, *_):
                self.exception_type = exception_type
                if exception_type is None:
                    raise RuntimeError("cleanup mask")
        rig = BrokenRig()
        with self.assertRaisesRegex(ValueError, "startup original"):
            benchmark.Rig.__enter__(rig)
        self.assertIs(rig.exception_type, ValueError)

    def test_continuous_valid_reply_cannot_extend_absolute_drain_deadline(self):
        class EndlessReply(socketserver.BaseRequestHandler):
            def handle(self):
                self.request.settimeout(1)
                benchmark.read_exact(self.request, 1)
                try:
                    while self.request.recv(benchmark.CHUNK):
                        pass
                    while True:
                        self.request.sendall(b"x" * benchmark.CHUNK)
                except OSError:
                    pass

        with socketserver.ThreadingTCPServer(("127.0.0.1", 0), EndlessReply) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            started = benchmark.time.monotonic()
            try:
                with self.assertRaisesRegex(RuntimeError, "absolute throughput drain deadline"):
                    benchmark.throughput_stream(server.server_address[1], b"x" * benchmark.CHUNK,
                                                0.02, 0.5)
                self.assertLess(benchmark.time.monotonic() - started, 3)
            finally:
                server.shutdown()
                thread.join(timeout=2)

    def test_latency_retains_first_path_when_second_path_fails(self):
        rig = SimpleNamespace(echo=SimpleNamespace(server_address=("127.0.0.1", 100)),
                              public_port=200, assert_idle=lambda: None)
        result = {}
        failure = benchmark.ConcurrentTransferError("second path", [(0, TimeoutError("stalled"))], [])
        # Warmup direct/relay, then measured relay succeeds and direct fails.
        with mock.patch.object(benchmark, "run_concurrent", side_effect=[[1], [2], [3], failure]):
            with self.assertRaises(benchmark.ConcurrentTransferError):
                benchmark.run_latency(rig, "smoke", 1701, result)
        progress = result["latency_in_progress"]
        self.assertEqual(progress["completed_samples_ms"]["relay"], [3])
        self.assertEqual(progress["wave_progress"][1]["paths"]["relay"]["status"], "COMPLETE")
        self.assertEqual(progress["denominators"]["relay"], {
            "attempted": 2, "completed": 2, "failed": 0,
            "warmup_attempted": 1, "warmup_completed": 1, "warmup_failed": 0})
        self.assertEqual(progress["denominators"]["direct"]["failed"], 1)

    def test_throughput_failed_run_records_individual_denominators(self):
        echo = SimpleNamespace(server_address=("127.0.0.1", 100), event_lock=threading.Lock(),
                               current_case={}, events=[])
        rig = SimpleNamespace(echo=echo, public_port=200, assert_idle=lambda: None)
        failure = benchmark.ConcurrentTransferError("failed", [(0, TimeoutError("stall"))], [])
        result = {}
        with mock.patch.object(benchmark, "run_concurrent", side_effect=failure):
            with self.assertRaises(benchmark.ConcurrentTransferError):
                benchmark.run_throughput(rig, "smoke", 1701, result)
        self.assertEqual(result["throughput_partial"][0]["denominators"]["direct"], {
            "attempted": 1, "completed": 0, "failed": 1,
            "warmup_attempted": 0, "warmup_completed": 0, "warmup_failed": 0})

    def test_cleanup_attempts_remaining_resources_after_stop_failure(self):
        calls = []
        def action(name):
            return lambda: calls.append(name)
        rig = SimpleNamespace(tunnel=object(), relay=object(),
                              bridge=SimpleNamespace(close=action("bridge")),
                              echo=SimpleNamespace(shutdown=action("echo shutdown"),
                                                   server_close=action("echo close")),
                              echo_thread=SimpleNamespace(join=lambda **_: calls.append("thread")),
                              temporary=SimpleNamespace(cleanup=action("temporary")))
        with mock.patch.object(benchmark, "stop_process", side_effect=[OSError("stop failed"), True]):
            with self.assertRaisesRegex(RuntimeError, "cleanup failed"):
                benchmark.Rig.__exit__(rig)
        self.assertEqual(calls, ["bridge", "echo shutdown", "echo close", "thread", "temporary"])
        self.assertEqual(rig.cleanup_errors[0]["resource"], "tunnel")
        self.assertTrue(rig.relay_clean)

    def test_final_only_checkpoint_failure_cannot_pass_soak(self):
        clock = [0.0]
        rig = SimpleNamespace(public_port=200, relay=object(), tunnel=object(),
                              assert_idle=lambda: None, metric=lambda _: 0)
        result = {}
        def transfer(*_):
            clock[0] = 7200
            return [{"guest_to_host_bytes": 1, "host_to_guest_bytes": 1}] * 8
        def checkpoint(record, _output):
            if record["soak_partial"]["memory_samples"][-1]["phase"] == "final":
                raise OSError("final disk failure")
        with mock.patch.object(benchmark.time, "monotonic", side_effect=lambda: clock[0]), \
             mock.patch.object(benchmark.time, "sleep", side_effect=lambda seconds: clock.__setitem__(0, clock[0] + seconds)), \
             mock.patch.object(benchmark, "run_concurrent", side_effect=transfer), \
             mock.patch.object(benchmark, "soak_sample", side_effect=lambda _rig, _started, phase: {"phase": phase}), \
             mock.patch.object(benchmark, "checkpoint_result", side_effect=checkpoint):
            with self.assertRaisesRegex(RuntimeError, "checkpoint failed"):
                benchmark.run_soak(rig, 1701, 7200, result, Path("unused.json"))
        self.assertEqual(result["soak_partial"]["completed_waves"], 1)

    def test_sender_deadline_begins_after_delayed_thread_start(self):
        import time
        with benchmark.EchoServer(("127.0.0.1", 0), benchmark.EchoHandler) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            original_start = threading.Thread.start

            def delayed_start(worker):
                if worker.name == "benchmark-sender":
                    time.sleep(0.06)
                return original_start(worker)

            try:
                with mock.patch.object(threading.Thread, "start", delayed_start):
                    measured = benchmark.throughput_stream(server.server_address[1],
                                                          b"x" * benchmark.CHUNK, 0.02, 2)
                self.assertGreater(measured["guest_to_host_bytes"], 0)
                self.assertEqual(measured["guest_to_host_bytes"], measured["host_to_guest_bytes"])
            finally:
                server.shutdown()
                thread.join(timeout=2)

    def test_udp_timing_distinguishes_gate_restore_from_delayed_observer(self):
        bridge = SimpleNamespace()
        with mock.patch.object(benchmark.time, "monotonic", side_effect=[100.0, 102.5]), \
             mock.patch.object(benchmark.time, "sleep"):
            benchmark.UdpBridge.drop_for(bridge, 2)
        self.assertEqual(bridge.drop_started, 100.0)
        self.assertEqual(bridge.drop_restored, 102.0)
        self.assertEqual(bridge.drop_wait_finished, 102.5)

    def test_udp_recovery_excludes_late_resume_log_observation(self):
        import queue
        clock = [10.0]

        class LateEvents:
            def empty(self):
                return True

            def get(self, timeout):
                if timeout == 0.2:
                    raise queue.Empty()
                clock[0] += 2
                return 123

        bridge = SimpleNamespace(dropped=0, forwarded=0, drop_started=1.0,
                                 drop_restored=2.0, drop_wait_finished=2.0,
                                 drop_for=lambda _: None)
        rig = SimpleNamespace(bridge=bridge, tunnel=SimpleNamespace(poll=lambda: None),
                              public_port=123, relay_events=LateEvents())
        with mock.patch.object(benchmark.time, "monotonic", side_effect=lambda: clock[0]), \
             mock.patch.object(benchmark.time, "perf_counter", side_effect=lambda: clock[0]), \
             mock.patch.object(benchmark, "reconnect_probe", return_value=True):
            measured = benchmark.Rig.interrupt_tunnel_link(rig, 1, 3)
        self.assertEqual(measured["availability_monotonic"], 10.0)
        self.assertEqual(measured["recovery_after_forwarding_restored_ms"], 8000.0)
        self.assertEqual(measured["recovery_after_drop_onset_ms"], 9000.0)
        self.assertEqual(measured["observation_window_ms"], 2000.0)
        self.assertEqual(measured["completion_ms"], 2000.0)

    def test_failed_udp_recovery_has_null_recovery_timing(self):
        import queue
        bridge = SimpleNamespace(dropped=0, forwarded=0, drop_started=1.0,
                                 drop_restored=2.0, drop_wait_finished=2.5,
                                 drop_for=lambda _: None)
        rig = SimpleNamespace(bridge=bridge, tunnel=SimpleNamespace(poll=lambda: 1),
                              public_port=123, relay_events=queue.Queue())
        measured = benchmark.Rig.interrupt_tunnel_link(rig, 1, 3)
        self.assertFalse(measured["available"])
        self.assertIsNone(measured["recovery_after_forwarding_restored_ms"])
        self.assertGreaterEqual(measured["observation_window_ms"], 0)
        self.assertEqual(measured["observer_wakeup_delay_seconds"], 0.5)

    def test_checkpoint_failure_is_observable_and_fails_soak(self):
        rig = SimpleNamespace(public_port=200, relay=object(), tunnel=object(),
                              assert_idle=lambda: None)
        result = {}
        with mock.patch.object(benchmark, "soak_sample", return_value={}), \
             mock.patch.object(benchmark, "checkpoint_result", side_effect=OSError("disk full")):
            with self.assertRaisesRegex(RuntimeError, "checkpoint failed"):
                benchmark.run_soak(rig, 1701, 7200, result, Path("unused.json"))
        self.assertEqual(result["soak_partial"]["checkpoint_error"]["type"], "OSError")

    def test_checkpoint_retains_latest_progress_without_final_output(self):
        import json
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "soak.json"
            benchmark.checkpoint_result({"status": "FAILED", "completed_waves": 1}, output)
            benchmark.checkpoint_result({"status": "FAILED", "completed_waves": 2}, output)
            self.assertEqual(json.loads(output.with_suffix(".checkpoint.json").read_text())[
                "completed_waves"], 2)
            self.assertFalse(output.exists())
            self.assertFalse(output.with_suffix(".checkpoint.json.tmp").exists())

    def test_soak_failure_retains_completed_wave_and_samples(self):
        rig = SimpleNamespace(public_port=200, relay=object(), tunnel=object(),
                              assert_idle=lambda: None)
        result = {}
        streams = [{"guest_to_host_bytes": 123, "host_to_guest_bytes": 123}] * 8
        failure = benchmark.ConcurrentTransferError("wave", [(0, TimeoutError("stalled"))],
                                                    [{"stream": 1, "result": streams[1]}])
        with mock.patch.object(benchmark, "run_concurrent", side_effect=[streams, failure]), \
             mock.patch.object(benchmark, "soak_sample", return_value={"phase": "sample"}):
            with self.assertRaises(benchmark.ConcurrentTransferError):
                benchmark.run_soak(rig, 1701, 7200, result)
        soak = result["soak_partial"]
        self.assertEqual(soak["completed_waves"], 1)
        self.assertEqual(soak["totals"]["guest_to_host_bytes"], 984)
        self.assertGreaterEqual(len(soak["memory_samples"]), 3)
        self.assertEqual(soak["failed_wave_successes"], failure.successes)

    def test_main_metadata_failure_writes_failed_outputs(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            relay, tunnel = directory / "relay.exe", directory / "tunnel.jar"
            relay.write_bytes(b"relay")
            tunnel.write_bytes(b"tunnel")
            output = directory / "failed.json"
            args = SimpleNamespace(profile="smoke", relay_binary=relay, tunnel_jar=tunnel,
                                   output=output, java="missing-java", seed=1701, soak_seconds=7200)
            with mock.patch.object(benchmark, "parse_args", return_value=args), \
                 mock.patch.object(benchmark.sys, "platform", "win32"), \
                 mock.patch.object(benchmark.platform, "machine", return_value="AMD64"), \
                 mock.patch.object(benchmark, "machine_info", side_effect=OSError("missing java")):
                self.assertEqual(benchmark.main(), 1)
                import json
                result = json.loads(output.read_text())
                self.assertEqual(result["current_case"]["phase"], "metadata")
                self.assertEqual(len(result["artifacts"]["harness_sha256"]), 64)
                self.assertTrue(output.with_suffix(".md").exists())
                with self.assertRaisesRegex(SystemExit, "overwrite"):
                    benchmark.main()

    def test_soak_rejects_unbounded_duration_before_starting_processes(self):
        for duration in (math.inf, math.nan, 7199, 14401):
            with self.subTest(duration=duration), self.assertRaises(ValueError):
                    benchmark.run_soak(None, 0, duration, {})

    def test_echo_trace_is_bounded_and_has_no_payload(self):
        with benchmark.EchoServer(("127.0.0.1", 0), benchmark.EchoHandler) as server:
            server.current_case = {"phase": "unit"}
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                benchmark.transfer(server.server_address[1], "request_response", b"private", 2)
            finally:
                server.shutdown()
                thread.join(timeout=2)
            events = list(server.events)
            self.assertEqual([event["event"] for event in events],
                             ["accepted", "mode", "eof_sent"])
            self.assertEqual(events[0]["case"], {"phase": "unit"})
            self.assertNotIn("private", str(events))
            for index in range(200):
                server.record_event(1000 + index, "bounded")
            self.assertEqual(len(server.events), 160)

    def test_diagnostic_sequence_keeps_full_load_and_stops_at_first_eight_relay(self):
        echo = SimpleNamespace(server_address=("127.0.0.1", 100), event_lock=threading.Lock(),
                               current_case={}, events=[])
        rig = SimpleNamespace(echo=echo, public_port=200, assert_idle=lambda: None)
        ports = []

        def fake_stream(port, block, seconds, timeout, **_kwargs):
            ports.append(port)
            self.assertEqual((len(block), seconds, timeout), (65536, 60.0, 30))
            return {"seconds": 60.0, "guest_to_host_bytes": 65536,
                    "host_to_guest_bytes": 65536}

        result = {}
        with mock.patch.object(benchmark, "throughput_stream", side_effect=fake_stream), \
             mock.patch.object(benchmark, "diagnostic_case_samples",
                               side_effect=lambda _rig, report, _stop, _progress:
                               report.update(diagnostic_case_samples=[])):
            cases = benchmark.run_throughput(rig, "full", 1701, result, diagnostic_sequence=True)
        self.assertEqual(len(cases), 2)
        self.assertEqual([len(cases[0]["paths"][path]) for path in ("direct", "relay")], [5, 5])
        self.assertEqual([len(cases[1]["paths"][path]) for path in ("direct", "relay")], [1, 1])
        self.assertEqual((ports.count(100), ports.count(200)), (13, 13))
        self.assertEqual(len(result["diagnostic_case_samples"]), 0)
        self.assertEqual(len(result["diagnostic_progress_final"]), 8)
        self.assertIsNone(echo.diagnostic_progress)

    def test_focused_diagnostic_retains_final_progress_on_concurrent_failure(self):
        echo = SimpleNamespace(event_lock=threading.Lock(), events=[], diagnostic_progress=None)
        rig = SimpleNamespace(echo=echo, public_port=200, assert_idle=lambda: None)
        result = {}

        def fake_stream(_port, _block, _seconds, _timeout, **kwargs):
            progress = kwargs["progress"]
            index = kwargs["stream_index"]
            progress.advance(index, "guest_send_bytes", 65536)
            if index == 3:
                raise TimeoutError("socket timed out")
            return {"guest_to_host_bytes": 65536, "host_to_guest_bytes": 65536}

        with mock.patch.object(benchmark, "throughput_stream", side_effect=fake_stream), \
             mock.patch.object(benchmark, "diagnostic_case_samples",
                               side_effect=lambda _rig, report, _stop, _progress:
                               report.update(diagnostic_case_samples=[])):
            with self.assertRaises(benchmark.ConcurrentTransferError):
                benchmark.run_diagnostic_eight_relay(rig, 1701, result)
        self.assertEqual(len(result["diagnostic_progress_final"]), 8)
        self.assertEqual(result["diagnostic_progress_final"][3]["guest_send_bytes"], 65536)
        self.assertIsNone(echo.diagnostic_progress)


if __name__ == "__main__":
    unittest.main()
