"""Bounded, instrumented impairment diagnosis; not a throughput comparison."""
import argparse
import collections
import hashlib
import json
import os
from pathlib import Path
import threading
import time

import benchmark_relay as b
from benchmark_network import CONDITIONS, NetworkRig
from local_impairment import ImpairmentBridge


class DiagnosticRig(NetworkRig):
    def __init__(self, *args, ordered=False, **kwargs):
        super().__init__(*args, **kwargs)
        self.diagnostics = collections.deque(maxlen=20000)
        self.ordered = ordered

    def start_tunnel(self, relay_port):
        self.bridge = ImpairmentBridge(relay_port, *self.impairment, seed=self.seed,
                                       preserve_order=self.ordered)
        return super().start_tunnel(relay_port)

    def launch(self, command, label, stdin, env=None):
        if label == 'relay':
            env = dict(env or os.environ, BTA_TRANSPORT_PROFILE='1')
        if label == 'tunnel':
            command.insert(1, '-Dbta.transportProfile=true')
        return super().launch(command, label, stdin, env)

    def reader(self, stream, label):
        rig = self

        class Observed:
            def readline(self):
                line = stream.readline()
                if line.startswith('BTA_DIAGNOSTIC '):
                    rig.diagnostics.append({'observed_at': time.monotonic(),
                                            **json.loads(line.removeprefix('BTA_DIAGNOSTIC '))})
                return line

        return super().reader(Observed(), label)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--relay-binary', type=Path, required=True)
    parser.add_argument('--tunnel-jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--condition', choices=CONDITIONS, default='jitter-10ms-2ms')
    parser.add_argument('--seconds', type=float, default=8)
    parser.add_argument('--drain', type=float, default=120)
    parser.add_argument('--pace-ms', type=float, default=0)
    parser.add_argument('--block-kib', type=int, default=64)
    parser.add_argument('--streams', type=int, default=8)
    parser.add_argument('--seed', type=int, default=1701)
    parser.add_argument('--ordered', action='store_true', help='control: clamp per-direction departure times to preserve ingress order')
    args = parser.parse_args()
    if args.output.exists() or args.output.with_suffix('.checkpoint.json').exists():
        parser.error('use a fresh output path')
    if not (1 <= args.streams <= 8 and 0 < args.seconds <= 60 and 30 <= args.drain <= 180
            and 0 <= args.pace_ms <= 1000 and 1 <= args.block_kib <= 64):
        parser.error('invalid workload bounds')
    result = {'schema': 1, 'status': 'RUNNING', 'condition': args.condition,
              'seconds': args.seconds, 'drain_seconds': args.drain, 'pace_ms': args.pace_ms,
              'block_kib': args.block_kib,
              'streams': args.streams, 'seed': args.seed, 'samples': [],
              'ordered_control': args.ordered,
              'topology': 'Windows loopback userspace UDP bridge; not netem or WAN',
              'sha256': {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in {
                  'relay': args.relay_binary, 'tunnel': args.tunnel_jar, 'harness': Path(__file__),
                  'workload': Path(b.__file__), 'network': Path(__file__).with_name('benchmark_network.py'),
                  'bridge': Path(__file__).with_name('local_impairment.py')}.items()}}
    logs = collections.deque(maxlen=100)
    stop = threading.Event()
    sampler = None
    started = time.monotonic()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    try:
        with DiagnosticRig(args.relay_binary.resolve(), args.tunnel_jar.resolve(), 'java', logs,
                           impairment=CONDITIONS[args.condition], seed=args.seed, ordered=args.ordered) as rig:
            progress = b.ThroughputProgress(args.streams)
            rig.echo.diagnostic_progress = progress
            rig.echo.diagnostic_timeout = args.seconds + args.drain + 10

            def sample():
                due = time.monotonic()
                while not stop.is_set():
                    now = time.monotonic()
                    bridge = rig.bridge.snapshot()
                    bridge.pop('delay_samples_ms')
                    result['samples'].append({'at_seconds': now - started,
                        'sampler_delay_ms': max(0, now - due) * 1000,
                        'progress': progress.snapshot(), 'bridge': bridge})
                    due = time.monotonic() + .5
                    stop.wait(.5)

            sampler = threading.Thread(target=sample, daemon=True)
            sampler.start()
            b.checkpoint_result(result, args.output)
            try:
                result['transfers'] = b.run_concurrent(lambda i: b.throughput_stream(
                    rig.public_port, b.payload(1701, args.block_kib * 1024, 999), args.seconds, args.drain,
                    args.pace_ms / 1000, progress, i), args.streams)
                rig.assert_idle()
                result['status'] = 'PASS'
            finally:
                stop.set()
                sampler.join(2)
                result['progress'] = progress.snapshot()
                result['bridge'] = rig.bridge.snapshot()
                result['diagnostics'] = [{**row, 'at_seconds': row['observed_at'] - started}
                                         for row in list(rig.diagnostics)]
                result['reconnects'] = rig.observed_cli_reconnect_attempts
                result['timeline'] = list(rig.timeline)
    except BaseException as error:
        result['status'] = 'FAILED'
        result['error'] = b.bounded_failure(error)
        if isinstance(error, b.ConcurrentTransferError):
            result['partial_successes'] = error.successes
            result['failures'] = [{'stream': i, **b.bounded_failure(e)} for i, e in error.failures]
    finally:
        stop.set()
        if sampler is not None:
            sampler.join(2)
        result['wall_seconds'] = time.monotonic() - started
        result['logs'] = list(logs)
        b.checkpoint_result(result, args.output)
        args.output.write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps({key: result[key] for key in ('status', 'condition', 'pace_ms', 'wall_seconds')}))
    return 0 if result['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
