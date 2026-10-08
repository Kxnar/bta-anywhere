#!/usr/bin/env python3
"""Run each impairment cell in a fresh rig and retain failures without stopping the matrix."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

from benchmark_network import CONDITIONS


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--relay-binary', type=Path, required=True)
    parser.add_argument('--tunnel-jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--repeats', type=int, default=2)
    parser.add_argument('--seconds', type=float, default=8)
    args = parser.parse_args()
    if args.output.exists() or not 1 <= args.repeats <= 5 or not 0 < args.seconds <= 60:
        parser.error('use a fresh output and bounded workload')
    cases = args.output.with_suffix('.cases')
    cases.mkdir(parents=True, exist_ok=False)
    result = {'schema': 1, 'status': 'RUNNING', 'cases': [], 'seconds': args.seconds,
              'repeats': args.repeats, 'isolation': 'fresh processes per cell',
              'wrapper_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
    for condition in CONDITIONS:
        for repeat in range(args.repeats):
            for streams in (1, 8):
                output = cases / f'{condition}-{repeat}-{streams}.json'
                command = [sys.executable, str(Path(__file__).with_name('benchmark_network.py')),
                           '--relay-binary', str(args.relay_binary.resolve()), '--tunnel-jar', str(args.tunnel_jar.resolve()),
                           '--output', str(output.resolve()), '--mode', 'matrix', '--conditions', condition,
                           '--repeats', '1', '--seconds', str(args.seconds), '--streams', str(streams)]
                # The child owns bounded socket/probe deadlines and cleanup. Do not
                # kill just the Python parent and orphan its relay/JVM processes.
                completed = subprocess.run(command, check=False)
                data = json.loads(output.read_text(encoding='utf-8')) if output.exists() else {'status': 'FAILED', 'error': {'message': 'child produced no JSON'}}
                result['cases'].append({'condition': condition, 'repeat': repeat, 'streams': streams,
                                        'exit_code': completed.returncode, 'result': data})
                args.output.write_text(json.dumps(result, indent=2), encoding='utf-8')
                print(f"CELL {condition} repeat={repeat} streams={streams}: {data['status']}", flush=True)
    result['status'] = 'COMPLETE'  # Completion is distinct from every cell passing.
    result['passed_cases'] = sum(case['result']['status'] == 'PASS' for case in result['cases'])
    result['failed_cases'] = len(result['cases']) - result['passed_cases']
    args.output.write_text(json.dumps(result, indent=2), encoding='utf-8')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
