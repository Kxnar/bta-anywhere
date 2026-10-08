"""Compare native RTT updates with packet-number-matched send/ACK log times.

This measures time between native log events, not a wire timestamp or a pcap.
Only complete JSON-SEQ records are used; a truncated final record is disclosed.
"""
import argparse
import collections
import hashlib
import json
from pathlib import Path


def percentile(values, fraction):
    if not values:
        return None
    values = sorted(values)
    position = (len(values) - 1) * fraction
    low = int(position)
    high = min(low + 1, len(values) - 1)
    return values[low] + (values[high] - values[low]) * (position - low)


def analyze(stream):
    counts = collections.Counter()
    sent = {}
    acked_largest = set()
    paired = []
    missing_sent = 0
    unmatched_updates = 0
    metrics = {}
    max_srtt = 0
    max_latest = 0
    preceding_ack = None
    truncated = False
    header = None
    for line_number, raw in enumerate(stream, 1):
        if not raw.strip():
            continue
        try:
            event = json.loads(raw.lstrip(b'\x1e'))
        except json.JSONDecodeError:
            if not raw.endswith(b'\n'):
                truncated = True
                break
            raise ValueError(f'malformed complete qlog record at line {line_number}') from None
        if 'qlog_version' in event:
            if header is not None or event.get('qlog_format') != 'JSON-SEQ':
                raise ValueError('expected one JSON-SEQ trace header')
            header = event
            continue
        if header is None:
            raise ValueError('missing JSON-SEQ trace header')
        name, now, data = event['name'], event['time'], event['data']
        counts[name] += 1
        if name == 'transport:packet_sent':
            h = data['header']
            sent[(h['packet_type'], h['packet_number'])] = {
                'time': now, 'line': line_number,
                'ack_eliciting': any(f['frame_type'] not in ('ack', 'padding', 'connection_close')
                                     for f in data.get('frames', [])),
            }
        elif name == 'transport:packet_received':
            preceding_ack = None
            h = data['header']
            for frame in data.get('frames', []):
                if frame['frame_type'] != 'ack':
                    continue
                largest = max(r[-1] for r in frame['acked_ranges'])
                key = (h['packet_type'], largest)
                packet = sent.get(key)
                if packet is None:
                    missing_sent += 1
                    continue
                preceding_ack = {
                    'ack_time_ms': now, 'space': key[0], 'packet_number': largest,
                    'send_time_ms': packet['time'], 'send_line': packet['line'],
                    'ack_line': line_number, 'ack_delay_ms': frame.get('ack_delay', 0),
                    'logged_send_to_ack_ms': now - packet['time'],
                    'largest_previously_acked': key in acked_largest,
                    'largest_ack_eliciting': packet['ack_eliciting'],
                }
                acked_largest.add(key)
        elif name == 'recovery:metrics_updated':
            metrics.update(data)
            max_srtt = max(max_srtt, metrics.get('smoothed_rtt', 0))
            max_latest = max(max_latest, metrics.get('latest_rtt', 0))
            if 'latest_rtt' in data:
                if preceding_ack is None or abs(preceding_ack['ack_time_ms'] - now) > .01:
                    unmatched_updates += 1
                else:
                    paired.append({**preceding_ack, 'metric_line': line_number,
                                   'native_latest_rtt_ms': data['latest_rtt'],
                                   'native_smoothed_rtt_ms': metrics.get('smoothed_rtt'),
                                   'difference_ms': data['latest_rtt'] - preceding_ack['logged_send_to_ack_ms']})
    if header is None or not counts:
        raise ValueError('no complete qlog trace events')
    worst = max(paired, key=lambda row: abs(row['difference_ms']), default=None)
    values = [row['logged_send_to_ack_ms'] for row in paired]
    return {
        'qlog_version': header['qlog_version'], 'truncated_final_record': truncated,
        'event_counts': dict(counts), 'matched_rtt_updates': len(paired),
        'unmatched_rtt_updates': unmatched_updates, 'acks_without_logged_send': missing_sent,
        'max_native_latest_rtt_ms': max_latest, 'max_native_smoothed_rtt_ms': max_srtt,
        'matched_send_to_ack_ms': {'p50': percentile(values, .5), 'p95': percentile(values, .95),
                                  'max': max(values, default=None)},
        'updates_disagreeing_over_100ms': sum(abs(r['difference_ms']) > 100 for r in paired),
        'worst_disagreement': worst, 'final_metrics': metrics,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('captures', type=Path, nargs='+')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    rows = []
    for path in args.captures:
        with path.open('rb') as stream:
            result = analyze(stream)
        with path.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        rows.append({'file': path.name, 'sha256': digest, **result})
    # Never overwrite a prior interpretation of frozen evidence.
    with args.output.open('x', encoding='utf-8', newline='\n') as stream:
        json.dump({'schema': 1, 'captures': rows}, stream, indent=2)
        stream.write('\n')
    print(json.dumps([{'file': r['file'], 'matched': r['matched_rtt_updates'],
                       'disagreements': r['updates_disagreeing_over_100ms'],
                       'max_srtt_ms': r['max_native_smoothed_rtt_ms']} for r in rows]))


if __name__ == '__main__':
    main()
