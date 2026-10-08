import socket
import time
import unittest
from unittest import mock

from local_impairment import ImpairmentBridge


class ImpairmentTests(unittest.TestCase):
    def test_icmp_reset_during_relay_restart_does_not_kill_bridge(self):
        bridge = ImpairmentBridge(12345)
        bridge.stop.set()
        bridge.thread.join(2)
        bridge.stop.clear()
        calls = 0

        def receive(_):
            nonlocal calls
            calls += 1
            if calls == 1:
                raise ConnectionResetError('Windows UDP ICMP port unreachable')
            bridge.stop.set()
            raise BlockingIOError()

        try:
            with mock.patch.object(bridge, 'socket') as sock, mock.patch('local_impairment.select.select') as ready:
                sock.recvfrom.side_effect = receive
                ready.return_value = ([sock], [], [])
                bridge._run()
            self.assertEqual(bridge.counters['icmp_resets_ignored'], 1)
            self.assertIsNone(bridge.error)
            self.assertEqual(calls, 2)
        finally:
            bridge.close()

    def test_forwards_both_directions_with_delay(self):
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as relay, socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as guest:
            relay.bind(('127.0.0.1', 0))
            relay.settimeout(2)
            guest.settimeout(2)
            bridge = ImpairmentBridge(relay.getsockname()[1], delay_ms=20)
            try:
                started = time.monotonic()
                guest.sendto(b'payload', ('127.0.0.1', bridge.port))
                data, peer = relay.recvfrom(100)
                self.assertEqual(data, b'payload')
                relay.sendto(data, peer)
                self.assertEqual(guest.recvfrom(100)[0], b'payload')
                self.assertGreaterEqual(time.monotonic() - started, .038)
            finally:
                bridge.close()
            self.assertEqual(bridge.counters['forwarded'], 2)

    def test_total_loss_and_queue_overflow_are_distinct(self):
        bridge = ImpairmentBridge(12345, delay_ms=500, max_packets=1)
        bridge.stop.set()
        bridge.thread.join(2)
        try:
            bridge._enqueue(b'a', bridge.relay, time.monotonic())
            bridge._enqueue(b'b', bridge.relay, time.monotonic())
            self.assertEqual(bridge.queued_bytes, 1)
            self.assertEqual(bridge.counters['overflow_dropped'], 1)
            bridge.loss = 1
            bridge._enqueue(b'c', bridge.relay, time.monotonic())
            self.assertEqual(bridge.counters['random_dropped'], 1)
        finally:
            bridge.close()

    def test_outage_discards_instead_of_building_replay_queue(self):
        bridge = ImpairmentBridge(12345)
        bridge.stop.set()
        bridge.thread.join(2)
        try:
            bridge.interrupt(.1)
            bridge._enqueue(b'old', bridge.relay, time.monotonic())
            self.assertEqual(bridge.queue, [])
            self.assertEqual(bridge.counters['outage_dropped'], 1)
        finally:
            bridge.close()


if __name__ == '__main__':
    unittest.main()
