"""Exercise in-flight verified requests during brief total UDP loss."""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import time

import benchmark_relay as b
from benchmark_network import NetworkRig


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--relay-binary', type=Path, required=True)
    parser.add_argument('--tunnel-jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error('use a fresh output path')
    logs = collections.deque(maxlen=80)
    result = {'status': 'RUNNING', 'rows': [], 'sha256': {
        name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in {
            'relay': args.relay_binary, 'tunnel': args.tunnel_jar, 'harness': Path(__file__),
            'bridge': Path(__file__).with_name('local_impairment.py')}.items()}}
    try:
        with NetworkRig(args.relay_binary.resolve(), args.tunnel_jar.resolve(), 'java', logs) as rig:
            initial_reconnects = rig.observed_cli_reconnect_attempts
            for seconds in (1, 3):
                for repeat in range(3):
                    before = rig.bridge.snapshot()['counters'].get('outage_dropped', 0)
                    restored = rig.bridge.interrupt(seconds)
                    elapsed = b.transfer(rig.public_port, 'request_response', b.payload(1701, 65536, repeat), 15)
                    after = rig.bridge.snapshot()['counters'].get('outage_dropped', 0)
                    if after <= before or time.monotonic() < restored:
                        raise AssertionError('outage did not intercept the live request')
                    for probe in range(12):
                        b.transfer(rig.public_port, 'request_response', b.payload(1701, 1024, probe), 5)
                        time.sleep(.5)
                    row = {'drop_seconds': seconds, 'repeat': repeat, 'dropped_datagrams': after - before,
                           'verified_request_bytes': 65536, 'request_ms_including_outage': elapsed,
                           'reconnects_so_far': rig.observed_cli_reconnect_attempts - initial_reconnects}
                    result['rows'].append(row)
                    print(json.dumps(row), flush=True)
            # Observe two original heartbeat periods for delayed reconnects.
            for probe in range(60):
                b.transfer(rig.public_port, 'request_response', b.payload(1701, 1024, probe), 5)
                time.sleep(.5)
            result['reconnects'] = rig.observed_cli_reconnect_attempts - initial_reconnects
            if result['reconnects']:
                raise AssertionError('brief total loss caused a reconnect')
            rig.assert_idle()
            result['bridge'] = rig.bridge.snapshot()
            result['status'] = 'PASS'
    except BaseException as error:
        result['status'] = 'FAILED'
        result['error'] = b.bounded_failure(error)
    finally:
        result['logs'] = list(logs)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2), encoding='utf-8')
    return 0 if result['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
