#!/usr/bin/env python3
"""Summarize complete or failed diagnostic cells without hiding denominators."""
import argparse
import json
from pathlib import Path


def summarize(result):
    completed = result.get('transfers', []) if result['status'] == 'PASS' else [r['result'] for r in result.get('partial_successes', [])]
    diagnostics = result.get('diagnostics', [])
    native = [r['rtt_native'] / 1e6 for r in diagnostics if r.get('kind') == 'java_connection']
    relay = [r['rtt_us'] / 1000 for r in diagnostics if r.get('kind') == 'relay']
    return {'status': result['status'], 'condition': result.get('condition'), 'qlog': result.get('qlog_enabled'),
            'seed': result.get('seed'), 'requested_streams': result.get('streams'),
            'attempted_streams': result['streams'] if 'progress' in result else 0, 'completed_streams': len(completed),
            'failed_streams': len(result.get('failures', [])), 'wall_seconds': result['wall_seconds'],
            'completed_bytes_each_direction': sum(row['guest_to_host_bytes'] for row in completed),
            'max_native_rtt_ms': max(native, default=None), 'max_relay_rtt_ms': max(relay, default=None),
            'failure_types': [row['type'] for row in result.get('failures', [])]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('results', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    rows = {}
    for path in sorted(args.results.rglob('*.json')):
        result = json.loads(path.read_text())
        if isinstance(result, dict) and 'qlog_enabled' in result:
            rows[path.relative_to(args.results).as_posix()] = summarize(result)
    with args.output.open('x', encoding='utf-8') as stream:
        json.dump(rows, stream, indent=2)
        stream.write('\n')
    print(json.dumps(rows, indent=2))


if __name__ == '__main__':
    main()
