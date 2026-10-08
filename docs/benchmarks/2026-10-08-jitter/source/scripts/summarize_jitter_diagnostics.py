"""Summarize diagnostic evidence without turning failed transfers into throughput."""
import argparse
import json
from pathlib import Path
import benchmark_relay as b


def summarize(data):
    if 'phase_schedule_seconds' in data:
        successes = [r for r in data['rows'] if r['status'] == 'PASS']
        return {'status': data['status'], 'completed_trials': len(data['rows']),
                'successes': len(successes),
                'minimum_ms': min((r['fault_to_verified_ms'] for r in successes), default=None),
                'maximum_ms': max((r['fault_to_verified_ms'] for r in successes), default=None),
                'recovery_ms': b.summary([r['fault_to_verified_ms'] for r in successes]) if successes else None,
                'by_phase': {str(phase): b.summary([r['fault_to_verified_ms'] for r in successes
                    if r['target_phase_seconds'] == phase]) for phase in sorted(set(data['phase_schedule_seconds']))
                    if any(r['target_phase_seconds'] == phase for r in successes)}}
    result = {key: data[key] for key in ('status', 'condition', 'pace_ms', 'block_kib', 'wall_seconds')}
    result['ordered_control'] = data.get('ordered_control', False)
    result['reconnects'] = data.get('reconnects')
    result['bridge_counters'] = data.get('bridge', {}).get('counters')
    result['end_progress'] = data.get('progress')
    if data.get('transfers'):
        result['verified_bytes_per_direction'] = sum(r['host_to_guest_bytes'] for r in data['transfers'])
        result['max_transfer_seconds'] = max(r['seconds'] for r in data['transfers'])
    samples = data.get('samples', [])
    if samples:
        first = samples[0]['at_seconds']
        result['progress_at_38_seconds'] = min(samples, key=lambda r: abs(r['at_seconds'] - first - 38))
        maximum_gap = 0
        previous = None
        last_progress = first
        for sample in samples:
            received = sum(s['guest_receive_bytes'] for s in sample['progress'])
            if previous is not None and received > previous:
                maximum_gap = max(maximum_gap, sample['at_seconds'] - last_progress)
                last_progress = sample['at_seconds']
            previous = received
        maximum_gap = max(maximum_gap, samples[-1]['at_seconds'] - last_progress)
        result['max_sampled_aggregate_receive_gap_seconds'] = maximum_gap
        result['per_stream_receive_gap_seconds'] = []
        for i in range(len(samples[0]['progress'])):
            previous = 0
            last_progress = first
            gaps = []
            for sample in samples:
                stream = sample['progress'][i]
                if stream['guest_receive_bytes'] > previous:
                    gaps.append(sample['at_seconds'] - last_progress)
                    last_progress = sample['at_seconds']
                previous = stream['guest_receive_bytes']
                if stream['guest_read_eof']:
                    break
            gaps.append(sample['at_seconds'] - last_progress)
            result['per_stream_receive_gap_seconds'].append(max(gaps))
    for kind in ('relay', 'java_connection'):
        rows = [r for r in data.get('diagnostics', []) if r['kind'] == kind]
        if rows:
            result[kind] = {'samples': len(rows), 'first': rows[0], 'last': rows[-1],
                'cwnd': b.summary([r['cwnd'] for r in rows]),
                'loop_delay_us': b.summary([r['loop_delay_us'] for r in rows])}
    rows = [r for r in data.get('diagnostics', []) if r['kind'] == 'java_stream']
    if rows:
        result['java_streams'] = {'samples': len(rows),
            'zero_capacity_samples': sum(r['capacity'] == 0 for r in rows),
            'peak_pending_write_bytes_per_stream': max(r['pending_write_bytes'] for r in rows)}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('inputs', type=Path, nargs='+')
    args = parser.parse_args()
    print(json.dumps({p.name: summarize(json.loads(p.read_text())) for p in args.inputs}, indent=2))


if __name__ == '__main__':
    main()
