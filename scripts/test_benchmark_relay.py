"""Fast standard-library tests for benchmark reporting and failure behavior."""

import socket
import socketserver
import threading
import unittest

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


if __name__ == "__main__":
    unittest.main()
