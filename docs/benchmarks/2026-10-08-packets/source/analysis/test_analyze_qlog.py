import io
import json
import unittest

from analyze_qlog import analyze


def records(*events):
    header = {'qlog_version': '0.3', 'qlog_format': 'JSON-SEQ'}
    return b''.join(b'\x1e' + json.dumps(e).encode() + b'\n' for e in (header, *events))


def packet(name, time, space, number, frames=()):
    return {'name': 'transport:packet_' + name, 'time': time,
            'data': {'header': {'packet_type': space, 'packet_number': number}, 'frames': list(frames)}}


class QlogTests(unittest.TestCase):
    def test_packet_spaces_and_sparse_metrics_are_not_conflated(self):
        data = records(
            packet('sent', 1, 'handshake', 1),
            packet('sent', 1000, '1RTT', 1, [{'frame_type': 'stream'}]),
            packet('received', 1032, '1RTT', 9, [{'frame_type': 'ack', 'acked_ranges': [[1, 1]], 'ack_delay': 2}]),
            {'name': 'recovery:metrics_updated', 'time': 1032, 'data': {'latest_rtt': 1000, 'smoothed_rtt': 800}},
            {'name': 'recovery:metrics_updated', 'time': 1040, 'data': {'congestion_window': 2400}})
        result = analyze(io.BytesIO(data))
        self.assertEqual(result['matched_rtt_updates'], 1)
        self.assertEqual(result['worst_disagreement']['logged_send_to_ack_ms'], 32)
        self.assertEqual(result['worst_disagreement']['difference_ms'], 968)
        self.assertEqual(result['max_native_smoothed_rtt_ms'], 800)

    def test_truncated_tail_is_reported_but_corrupt_complete_record_is_rejected(self):
        data = records(packet('sent', 1, '1RTT', 0))
        result = analyze(io.BytesIO(data + b'\x1e{"time":2'))
        self.assertTrue(result['truncated_final_record'])
        with self.assertRaises(ValueError):
            analyze(io.BytesIO(data + b'\x1e{"time":2\n'))

    def test_missing_send_cannot_manufacture_a_match(self):
        data = records(
            packet('received', 30, '1RTT', 2, [{'frame_type': 'ack', 'acked_ranges': [[0, 1]]}]),
            {'name': 'recovery:metrics_updated', 'time': 30, 'data': {'latest_rtt': 20}})
        result = analyze(io.BytesIO(data))
        self.assertEqual(result['acks_without_logged_send'], 1)
        self.assertEqual(result['matched_rtt_updates'], 0)
        self.assertEqual(result['unmatched_rtt_updates'], 1)


if __name__ == '__main__':
    unittest.main()
