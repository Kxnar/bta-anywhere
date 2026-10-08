#!/usr/bin/env python3
"""Local UDP impairment and timed restart experiments; all endpoints are loopback."""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import re
import time
import traceback

import benchmark_relay as b
from local_impairment import ImpairmentBridge

CONDITIONS = {
    'bridge-zero': (0, 0, 0),
    'delay-10ms': (10, 0, 0),
    'jitter-10ms-2ms': (10, 2, 0),
    'loss-10ms-2ms-1pct': (10, 2, .01),
}


class NetworkRig(b.Rig):
    def __init__(self, *args, impairment=(0, 0, 0), seed=1701, **kwargs):
        super().__init__(*args, **kwargs)
        self.impairment, self.seed = impairment, seed
        self.timeline = collections.deque(maxlen=500)

    def start_relay(self, log_level='info'):
        return super().start_relay(log_level)

    def launch(self, command, label, stdin, env=None):
        if label == 'tunnel':
            command.insert(1, '-Dbta.lifecycleTrace=true')
        return super().launch(command, label, stdin, env)

    def reader(self, stream, label):
        rig = self

        class Observed:
            def readline(self):
                line = stream.readline()
                now = time.monotonic()
                match = re.match(r'BTA_LIFECYCLE (\w+) (-?\d+)', line)
                if match:
                    rig.timeline.append({'phase': match[1], 'java_nanos': int(match[2]), 'observed_at': now})
                elif label == 'tunnel-err' and line.startswith('Relay unavailable; retrying in '):
                    delay = re.search(r'retrying in (\d+) ms', line)
                    rig.timeline.append({'phase': 'retry_notice', 'delay_ms': int(delay[1]), 'observed_at': now})
                return line

        return super().reader(Observed(), label)

    def start_tunnel(self, relay_port):
        if self.bridge is None:
            self.bridge = ImpairmentBridge(relay_port, *self.impairment, seed=self.seed)
        port = super().start_tunnel(self.bridge.port)
        # stdout/stderr have separate readers; consume the initial registration
        # trace before a restart clears the timeline, avoiding a stale phase.
        b.wait_until(lambda: any(event['phase'] == 'registered' for event in self.timeline),
                     5, 'initial lifecycle registration trace (requires instrumented JAR)')
        return port


def probe_until(rig, deadline):
    attempts = 0
    while time.monotonic() < deadline:
        attempts += 1
        try:
            b.transfer(rig.public_port, 'request_response', b.payload(1701, 1024, 0), 1)
            return time.monotonic(), attempts
        except (OSError, AssertionError):
            time.sleep(.1)
    raise TimeoutError('no verified recovery within observation window')


def restart(rig):
    rig.timeline.clear()
    started = time.monotonic()
    if not b.stop_process(rig.relay):
        raise RuntimeError('relay did not stop')
    rig.relay = None
    time.sleep(1)
    rig.start_relay()
    ready = time.monotonic()
    recovered, attempts = probe_until(rig, started + 100)
    timeline = list(rig.timeline)
    phases = {}
    for event in timeline:
        if 'java_nanos' in event:
            phases.setdefault(event['phase'], event)
    row = {'kind': 'restart', 'fault_to_verified_ms': (recovered - started) * 1000,
           'relay_ready_ms': (ready - started) * 1000, 'attempts': attempts,
           'timeline': [{**event, 'observed_after_fault_ms': (event['observed_at'] - started) * 1000} for event in timeline]}
    if all(name in phases for name in ['failure_detected', 'connect_start', 'registered']):
        detected, connect, registered = (phases[name] for name in ['failure_detected', 'connect_start', 'registered'])
        last_connect = [event for event in timeline if event['phase'] == 'connect_start'][-1]
        row.update(detection_observed_ms=(detected['observed_at'] - started) * 1000,
                   retry_delay_ms=(connect['java_nanos'] - detected['java_nanos']) / 1e6,
                   registration_ms=(registered['java_nanos'] - last_connect['java_nanos']) / 1e6,
                   detection_to_registered_ms=(registered['java_nanos'] - detected['java_nanos']) / 1e6,
                   connection_attempts=len([event for event in timeline if event['phase'] == 'connect_start']))
    rig.assert_idle()
    return row


def short_outage(rig, seconds):
    before = rig.observed_cli_reconnect_attempts
    started = time.monotonic()
    restored = rig.bridge.interrupt(seconds)
    # Heartbeats remain active; fresh verified probes begin after the gate restores.
    time.sleep(seconds)
    recovered, attempts = probe_until(rig, started + 60)
    # Two heartbeat intervals for the candidate, then check for delayed reconnects.
    for _ in range(12):
        b.transfer(rig.public_port, 'request_response', b.payload(1701, 1024, 1), 5)
        time.sleep(.5)
    rig.assert_idle()
    return {'kind': 'outage', 'drop_seconds': seconds,
            'recovery_after_restore_ms': (recovered - restored) * 1000,
            'attempts': attempts, 'reconnects': rig.observed_cli_reconnect_attempts - before}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--relay-binary', type=Path, required=True)
    parser.add_argument('--tunnel-jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--mode', choices=['matrix', 'recovery'], required=True)
    parser.add_argument('--conditions', nargs='+', choices=list(CONDITIONS), default=list(CONDITIONS))
    parser.add_argument('--repeats', type=int, default=3)
    parser.add_argument('--seconds', type=float, default=10)
    parser.add_argument('--streams', type=int, nargs='+', choices=[1, 8], default=[1, 8])
    args = parser.parse_args()
    if args.output.exists() or args.output.with_suffix('.checkpoint.json').exists():
        parser.error('use a fresh output path')
    if not 1 <= args.repeats <= 10 or not 0 < args.seconds <= 60:
        parser.error('invalid workload bounds')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    logs = collections.deque(maxlen=100)
    result = {'schema': 1, 'status': 'RUNNING', 'mode': args.mode,
              'topology': 'Windows loopback UDP userspace bridge; NOT Linux tc netem or a WAN',
              'repeats': args.repeats, 'throughput_seconds': args.seconds,
              'sha256': {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in {
                  'relay': args.relay_binary, 'tunnel': args.tunnel_jar,
                  'harness': Path(__file__), 'workload': Path(b.__file__),
                  'bridge': Path(__file__).with_name('local_impairment.py')}.items()}, 'rows': []}
    try:
        for condition in args.conditions:
            with NetworkRig(args.relay_binary.resolve(), args.tunnel_jar.resolve(), 'java', logs,
                            impairment=CONDITIONS[condition]) as rig:
                if args.mode == 'recovery':
                    for repeat in range(args.repeats):
                        result['current_case'] = {'condition': condition, 'repeat': repeat, 'kind': 'restart'}
                        b.checkpoint_result(result, args.output)
                        row = {'condition': condition, 'repeat': repeat, **restart(rig)}
                        result['rows'].append(row)
                        b.checkpoint_result(result, args.output)
                        print(json.dumps(row), flush=True)
                    for seconds in (1, 3):
                        for repeat in range(args.repeats):
                            result['current_case'] = {'condition': condition, 'repeat': repeat, 'kind': 'outage', 'seconds': seconds}
                            b.checkpoint_result(result, args.output)
                            row = {'condition': condition, 'repeat': repeat, **short_outage(rig, seconds)}
                            result['rows'].append(row)
                            b.checkpoint_result(result, args.output)
                            print(json.dumps(row), flush=True)
                else:
                    for repeat in range(args.repeats):
                        for count in args.streams:
                            result['current_case'] = {'condition': condition, 'repeat': repeat, 'streams': count, 'kind': 'matrix'}
                            b.checkpoint_result(result, args.output)
                            latencies = []
                            for wave in range(12):
                                samples = b.run_concurrent(lambda i: b.transfer(rig.public_port, 'request_response', b.payload(1701, 1024, i), 10), count)
                                if wave >= 2:
                                    latencies.extend(samples)
                            result['pending_matrix_case'] = {'condition': condition, 'repeat': repeat,
                                'streams': count, 'transaction_ms': latencies, 'latency_summary_ms': b.summary(latencies)}
                            b.checkpoint_result(result, args.output)
                            start = time.perf_counter()
                            samples = b.run_concurrent(lambda _: b.throughput_stream(rig.public_port, b.payload(1701, b.CHUNK, 999), args.seconds, 30), count)
                            elapsed = time.perf_counter() - start
                            total = sum(s['host_to_guest_bytes'] for s in samples)
                            rig.assert_idle()
                            row = {'condition': condition, 'impairment': CONDITIONS[condition], 'repeat': repeat,
                                   'streams': count, 'transaction_ms': latencies, 'latency_summary_ms': b.summary(latencies),
                                   'verified_bytes_per_direction': total, 'wall_seconds': elapsed,
                                   'mib_s': total / elapsed / 1048576, 'bridge': rig.bridge.snapshot(),
                                   'reconnects': rig.observed_cli_reconnect_attempts}
                            result['rows'].append(row)
                            result.pop('pending_matrix_case', None)
                            b.checkpoint_result(result, args.output)
                            print(json.dumps({k: row[k] for k in ['condition', 'repeat', 'streams', 'mib_s', 'latency_summary_ms', 'reconnects']}), flush=True)
                result.setdefault('bridges', []).append({'condition': condition, **rig.bridge.snapshot()})
        result['status'] = 'PASS'
    except BaseException as error:
        result['status'] = 'FAILED'
        result['error'] = b.bounded_failure(error)
        result['traceback'] = traceback.format_exc()
        if isinstance(error, b.ConcurrentTransferError):
            result['partial_successes'] = error.successes
    finally:
        if 'rig' in locals() and rig.bridge is not None:
            result['final_bridge'] = rig.bridge.snapshot()
            result['final_timeline'] = list(rig.timeline)
            result['reconnects'] = rig.observed_cli_reconnect_attempts
        result['logs'] = list(logs)
        args.output.write_text(json.dumps(result, indent=2), encoding='utf-8')
    return 0 if result['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
