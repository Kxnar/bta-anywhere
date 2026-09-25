"""Fast standard-library tests for benchmark reporting and failure behavior."""

import socket
import socketserver
import threading
import unittest
import math

import benchmark_relay as benchmark


class WrongReply(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.recv(1024)
        self.request.sendall(b"wrong")
        self.request.shutdown(socket.SHUT_WR)


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
                benchmark.run_soak(None, 0, duration)


if __name__ == "__main__":
    unittest.main()
