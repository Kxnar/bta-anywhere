"""Fast standard-library tests for benchmark reporting and failure behavior."""

import socket
import socketserver
import threading
import unittest
import math
import time
from types import SimpleNamespace
from unittest import mock

import benchmark_relay as benchmark


class WrongReply(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.recv(1024)
        self.request.sendall(b"wrong")
        self.request.shutdown(socket.SHUT_WR)


class LateEofReply(socketserver.BaseRequestHandler):
    def handle(self):
        benchmark.read_exact(self.request, 1)
        size = int.from_bytes(benchmark.read_exact(self.request, 4), "big")
        self.request.sendall(benchmark.read_exact(self.request, size))
        time.sleep(0.2)


class BenchmarkTests(unittest.TestCase):
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
        with socketserver.ThreadingTCPServer(("127.0.0.1", 0), LateEofReply) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                with self.assertRaises(benchmark.MissingEofError) as caught:
                    benchmark.transfer(server.server_address[1], "request_response", b"valid", 0.05)
            finally:
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

        def fake_stream(port, block, seconds, timeout):
            ports.append(port)
            self.assertEqual((len(block), seconds, timeout), (65536, 60.0, 30))
            return {"seconds": 60.0, "guest_to_host_bytes": 65536,
                    "host_to_guest_bytes": 65536}

        result = {}
        with mock.patch.object(benchmark, "throughput_stream", side_effect=fake_stream), \
             mock.patch.object(benchmark, "diagnostic_case_samples",
                               side_effect=lambda _rig, report, _stop:
                               report.update(diagnostic_case_samples=[])):
            cases = benchmark.run_throughput(rig, "full", 1701, result, diagnostic_sequence=True)
        self.assertEqual(len(cases), 2)
        self.assertEqual([len(cases[0]["paths"][path]) for path in ("direct", "relay")], [5, 5])
        self.assertEqual([len(cases[1]["paths"][path]) for path in ("direct", "relay")], [1, 1])
        self.assertEqual((ports.count(100), ports.count(200)), (13, 13))
        self.assertEqual(len(result["diagnostic_case_samples"]), 0)


if __name__ == "__main__":
    unittest.main()
