#!/usr/bin/env python3
"""Run fresh-process, matched diagnostic pairs; retain every failed cell."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--relay-binary', type=Path, required=True)
    parser.add_argument('--baseline-jar', type=Path, required=True)
    parser.add_argument('--candidate-jar', type=Path, required=True)
    parser.add_argument('--output-directory', type=Path, required=True)
    args = parser.parse_args()
    args.output_directory.mkdir(parents=True, exist_ok=False)
    harness = Path(__file__).with_name('benchmark_jitter_diagnostics.py')
    plan = {'schema': 1, 'qlog': False, 'seconds': 8, 'drain_seconds': 120,
            'streams': 8, 'block_kib': 64, 'seeds': [1701, 1702, 1703],
            'ordering': 'alternate baseline-first and candidate-first by seed',
            'sha256': {name: hashlib.sha256(path.read_bytes()).hexdigest()
                       for name, path in {'relay': args.relay_binary, 'baseline': args.baseline_jar,
                                          'candidate': args.candidate_jar, 'driver': Path(__file__),
                                          'harness': harness}.items()}, 'cells': []}
    output = args.output_directory / 'campaign.json'

    def save():
        temporary = output.with_suffix('.tmp')
        temporary.write_text(json.dumps(plan, indent=2) + '\n', encoding='utf-8')
        temporary.replace(output)

    save()
    for index, seed in enumerate(plan['seeds']):
        for condition in ('jitter-10ms-2ms', 'loss-10ms-2ms-1pct'):
            order = ('baseline', 'candidate') if index % 2 == 0 else ('candidate', 'baseline')
            for label in order:
                result = args.output_directory / f'{condition}-{seed}-{label}.json'
                jar = args.baseline_jar if label == 'baseline' else args.candidate_jar
                command = [sys.executable, str(harness), '--relay-binary', str(args.relay_binary),
                           '--tunnel-jar', str(jar), '--output', str(result), '--condition', condition,
                           '--seed', str(seed), '--seconds', '8', '--drain', '120', '--streams', '8']
                started = time.time()
                cell = {'condition': condition, 'seed': seed, 'variant': label,
                        'result': result.name, 'started_unix': started, 'status': 'RUNNING'}
                plan['cells'].append(cell)
                save()
                # Harness owns bounded transfer deadlines and process cleanup. Never
                # discard a failed cell or retry it silently to obtain a pass.
                run = subprocess.run(command, text=True, capture_output=True)
                result.with_suffix('.driver.log').write_text(run.stdout + run.stderr, encoding='utf-8')
                cell.update(returncode=run.returncode, wall_seconds=time.time() - started,
                            status=json.loads(result.read_text())['status'] if result.exists() else 'MISSING_RESULT')
                save()
                print(json.dumps(cell), flush=True)
    plan['status'] = 'COMPLETE'
    save()


if __name__ == '__main__':
    main()
