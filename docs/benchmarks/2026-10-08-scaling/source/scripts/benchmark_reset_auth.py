#!/usr/bin/env python3
"""Verify that forged reset-shaped packets do not disconnect the owned local tunnel."""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import random
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
    result = {'status': 'RUNNING', 'forged_packets': 0, 'verified_probes': 0,
              'sha256': {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in {
                  'relay': args.relay_binary, 'tunnel': args.tunnel_jar, 'harness': Path(__file__)}.items()}}
    logs = collections.deque(maxlen=50)
    try:
        with NetworkRig(args.relay_binary.resolve(), args.tunnel_jar.resolve(), 'java', logs) as rig:
            before = rig.observed_cli_reconnect_attempts
            rng = random.Random(4201)
            assert rig.bridge.peer is not None
            for _ in range(64):
                # Short-header shape, fixed bit set, random unauthenticated suffix.
                # Sent from the same loopback socket as the legitimate relay path.
                packet = bytes([0x40]) + rng.randbytes(63)
                rig.bridge.socket.sendto(packet, rig.bridge.peer)
                result['forged_packets'] += 1
                b.transfer(rig.public_port, 'request_response', b.payload(1701, 1024, 0), 5)
                result['verified_probes'] += 1
            time.sleep(3)
            b.transfer(rig.public_port, 'request_response', b.payload(1701, 1024, 1), 5)
            result['verified_probes'] += 1
            result['reconnects'] = rig.observed_cli_reconnect_attempts - before
            if result['reconnects']:
                raise AssertionError('forged reset-shaped packets caused reconnect')
            rig.assert_idle()
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
