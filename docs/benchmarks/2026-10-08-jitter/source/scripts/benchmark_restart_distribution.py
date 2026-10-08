"""Stratified restart phases measured from fresh session registration."""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import random
import time

import benchmark_relay as b
from benchmark_network import NetworkRig, restart


def phase_schedule(seed=1701):
    phases = [.5, 3.5, 6.5, 9.5, 12.5] * 5
    random.Random(seed).shuffle(phases)
    return phases


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--relay-binary', type=Path, required=True)
    parser.add_argument('--tunnel-jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists() or args.output.with_suffix('.checkpoint.json').exists():
        parser.error('use a fresh output path')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    result = {'schema': 1, 'status': 'RUNNING', 'rows': [], 'phase_schedule_seconds': phase_schedule(),
              'phase_reference': 'Python observation of registration; heartbeat interval 15 seconds',
              'topology': 'Windows loopback zero-impairment userspace UDP bridge',
              'sha256': {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in {
                  'relay': args.relay_binary, 'tunnel': args.tunnel_jar, 'harness': Path(__file__),
                  'network': Path(__file__).with_name('benchmark_network.py'),
                  'workload': Path(b.__file__), 'bridge': Path(__file__).with_name('local_impairment.py')}.items()}}
    try:
        for trial, phase in enumerate(phase_schedule()):
            logs = collections.deque(maxlen=60)
            row = {'trial': trial, 'target_phase_seconds': phase, 'status': 'RUNNING'}
            result['rows'].append(row)
            b.checkpoint_result(result, args.output)
            try:
                with NetworkRig(args.relay_binary.resolve(), args.tunnel_jar.resolve(), 'java', logs) as rig:
                    registered = next(e['observed_at'] for e in rig.timeline if e['phase'] == 'registered')
                    time.sleep(max(0, registered + phase - time.monotonic()))
                    row['observed_phase_seconds'] = time.monotonic() - registered
                    row.update(restart(rig))
                    row['status'] = 'PASS'
                    row['bridge'] = rig.bridge.snapshot()
            except Exception as error:
                row['status'] = 'FAILED'
                row['error'] = b.bounded_failure(error)
                row['logs'] = list(logs)
                if 'rig' in locals():
                    row['timeline'] = list(rig.timeline)
            b.checkpoint_result(result, args.output)
            print(json.dumps({k: v for k, v in row.items() if k not in ('timeline', 'logs', 'bridge')}), flush=True)
        result['status'] = 'PASS' if all(r['status'] == 'PASS' for r in result['rows']) else 'FAILED'
    finally:
        b.checkpoint_result(result, args.output)
        args.output.write_text(json.dumps(result, indent=2), encoding='utf-8')
    return 0 if result['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
