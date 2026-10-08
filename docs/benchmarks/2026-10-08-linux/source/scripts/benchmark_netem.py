#!/usr/bin/env python3
"""Linux-only QUIC campaign in two disposable namespaces; requires root.

Guest and echo workload run in the host namespace, relay in the peer namespace.
Only UDP traverses netem: guest-to-relay TCP and readiness HTTP are unshaped.
This is a kernel impairment experiment on one machine, not a WAN or game demo.
"""
from __future__ import annotations

import argparse
import collections
import contextlib
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import threading
import time

import benchmark_relay as b

RELAY_IP = '10.203.0.1'
HOST_IP = '10.203.0.2'
CONDITIONS = {
    'clean': [],
    'jitter': ['delay', '10ms', '2ms', 'distribution', 'normal', 'rate', '20mbit'],
    'loss': ['delay', '10ms', '2ms', 'distribution', 'normal', 'loss', 'random', '1%', 'rate', '20mbit'],
}


def command(args):
    return subprocess.run(args, check=True, text=True, capture_output=True, timeout=30).stdout.strip()


def save(path, result):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    temporary.replace(path)


def process_sample(process):
    # /proc field 2 can contain spaces or parentheses. Fields below start at 3.
    fields = Path(f'/proc/{process.pid}/stat').read_text().rsplit(')', 1)[1].split()
    return {'pid': process.pid, 'cpu_seconds': (int(fields[11]) + int(fields[12])) / os.sysconf('SC_CLK_TCK'),
            'rss_bytes': int(fields[21]) * os.sysconf('SC_PAGE_SIZE')}


class NetemRig(b.Rig):
    def __init__(self, *args, relay_namespace, **kwargs):
        super().__init__(*args, **kwargs)
        self.relay_namespace = relay_namespace
        self.configured = False

    def launch(self, args, label, stdin, env=None):
        if label == 'relay':
            args = ['ip', 'netns', 'exec', self.relay_namespace, *args]
        elif label == 'tunnel':
            args = list(args)
            args[args.index('--relay') + 1] = f'{RELAY_IP}:{self.quic_port}'
        return super().launch(args, label, stdin, env)

    def start_relay(self, log_level='info'):
        if not self.configured:
            directory = self.work / 'relay'
            config = directory / 'relay.toml'
            config.write_text(config.read_text().replace('127.0.0.1', RELAY_IP)
                              .replace('public_host = "localhost"', f'public_host = "{RELAY_IP}"'))
            # A fresh private test CA/certificate with the actual namespace IP.
            # Keep normal certificate and hostname verification enabled.
            command(['openssl', 'req', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:P-256',
                     '-nodes', '-days', '1', '-subj', '/CN=bta-netem-test',
                     '-addext', f'subjectAltName=IP:{RELAY_IP}',
                     '-keyout', str(directory / 'server-key.pem'), '-out', str(directory / 'server.pem')])
            shutil.copyfile(directory / 'server.pem', directory / 'trust.pem')
            self.configured = True
        self.relay = self.launch([str(self.relay_binary), 'run', '--config', str(self.work / 'relay/relay.toml')],
                                 'relay', subprocess.DEVNULL, dict(os.environ, RUST_LOG=log_level))

        def ready():
            if self.relay.poll() is not None:
                raise RuntimeError(f'relay exited during startup: {self.relay.returncode}')
            return bool(b.http_get(self.admin_port, '/readyz', RELAY_IP))

        b.wait_until(ready, 20, 'namespaced relay ready')

    def metric(self, name):
        return b.metric(b.http_get(self.admin_port, '/metrics', RELAY_IP), name)


def run_session(args, index):
    path = args.output_directory / f'{args.condition}-session-{index}.json'
    if path.exists():
        raise FileExistsError(path)
    logs = collections.deque(maxlen=150)
    rig = NetemRig(args.relay_binary, args.tunnel_jar, args.java, logs, relay_namespace=args.relay_namespace)
    row = {'schema': 1, 'status': 'RUNNING', 'session': index, 'condition': args.condition,
           'seed': 1701 + index, 'transactions': [], 'resources': [], 'failures': [],
           'workload': {'transaction_timeout_seconds': 15, 'transaction_repeats_per_cell': 10,
                        'streams': 8, 'throughput_send_seconds': 8, 'drain_seconds': 120,
                        'restart_deadline_seconds': 100},
           'topology': __doc__.strip(), 'netem_seed': None,
           'netem_seed_note': 'kernel random impairment; independent runs, not identical packet replay',
           'sha256': {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in {
               'relay': args.relay_binary, 'tunnel': args.tunnel_jar, 'driver': Path(__file__),
               'workload': Path(b.__file__)}.items()}}
    started = time.monotonic()
    stop = threading.Event()
    sampler = None
    phase = ['startup']

    def sample():
        while not stop.is_set():
            samples = {}
            for name in ('relay', 'tunnel'):
                process = getattr(rig, name)
                if process is not None and process.poll() is None:
                    try:
                        samples[name] = process_sample(process)
                    except (OSError, ValueError) as error:
                        samples[name] = {'error': b.bounded_failure(error)}
            row['resources'].append({'at_seconds': time.monotonic() - started, 'phase': phase[0], **samples})
            stop.wait(.5)

    save(path, row)
    try:
        with rig:
            sampler = threading.Thread(target=sample, daemon=True)
            sampler.start()
            phase[0] = 'transactions'
            for mode in ('request_response', 'half_close'):
                for size in b.SIZES:
                    for repeat in range(10):
                        cell = {'mode': mode, 'bytes_each_direction': size, 'repeat': repeat, 'status': 'RUNNING'}
                        row['transactions'].append(cell)
                        save(path, row)
                        cell.update(latency_ms=b.transfer(rig.public_port, mode, b.payload(1701 + index, size, repeat),
                                                          15, host=RELAY_IP), status='PASS')
                        save(path, row)
            rig.assert_idle()
            phase[0] = 'throughput'
            progress = b.ThroughputProgress(8)
            rig.echo.diagnostic_progress = progress
            rig.echo.diagnostic_timeout = 138
            throughput_start = time.monotonic()
            try:
                row['throughput'] = b.run_concurrent(lambda stream: b.throughput_stream(
                    rig.public_port, b.payload(1701, 65536, 999), 8, 120, progress=progress,
                    stream_index=stream, host=RELAY_IP), 8)
            except b.ConcurrentTransferError as error:
                row['throughput_partial_successes'] = error.successes
                row['failures'].extend({'phase': 'throughput', 'stream': stream, **b.bounded_failure(failure)}
                                       for stream, failure in error.failures)
            finally:
                row['throughput_wall_seconds'] = time.monotonic() - throughput_start
                row['progress'] = progress.snapshot()
                save(path, row)
            rig.assert_idle()
            if rig.echo.errors:
                row['failures'].append({'phase': 'pre_restart_echo', 'errors': list(rig.echo.errors)})
            phase[0] = 'relay_restart'
            fault_at = time.monotonic()
            if not b.stop_process(rig.relay):
                raise RuntimeError('relay did not terminate')
            rig.relay = None
            time.sleep(1)
            rig.start_relay()
            ready_at = time.monotonic()
            attempts = []
            recovered = False
            while time.monotonic() < fault_at + 100:
                try:
                    latency = b.transfer(rig.public_port, 'request_response', b.payload(1701, 1024, 0),
                                         min(1, fault_at + 100 - time.monotonic()), host=RELAY_IP)
                    attempts.append({'status': 'PASS', 'latency_ms': latency})
                    recovered = True
                    break
                except (OSError, AssertionError) as error:
                    if 'byte mismatch' in str(error):
                        raise
                    attempts.append({'status': 'FAILED', **b.bounded_failure(error)})
                    time.sleep(.1)
            row['recovery'] = {'status': 'PASS' if recovered else 'FAILED', 'attempts': attempts,
                               'fault_to_verified_ms': (time.monotonic() - fault_at) * 1000 if recovered else None,
                               'relay_ready_ms': (ready_at - fault_at) * 1000,
                               'old_endpoint_verified': recovered}
            if not recovered:
                raise TimeoutError('no verified recovery within fixed 100-second window')
            rig.assert_idle()
            row['echo_errors_including_restart_probes'] = list(rig.echo.errors)
            row['status'] = 'PASS' if not row['failures'] else 'FAILED'
    except BaseException as error:
        row['status'] = 'FAILED'
        row['failures'].append({'phase': phase[0], **b.bounded_failure(error)})
    finally:
        stop.set()
        if sampler:
            sampler.join(2)
        row.update(wall_seconds=time.monotonic() - started, logs=list(logs), lifecycle=rig.lifecycle_snapshot(),
                   cleanup={'tunnel_clean': getattr(rig, 'tunnel_clean', False),
                            'relay_clean': getattr(rig, 'relay_clean', False),
                            'errors': getattr(rig, 'cleanup_errors', [])})
        if (not row['cleanup']['tunnel_clean'] or not row['cleanup']['relay_clean']
                or row['cleanup']['errors'] or any(item['running'] for item in row['lifecycle']['owned_process_exits'])):
            row['status'] = 'FAILED'
        save(path, row)
    print(json.dumps({'session': index, 'condition': args.condition, 'status': row['status']}), flush=True)
    return row['status'] == 'PASS'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--relay-binary', type=Path, required=True)
    parser.add_argument('--tunnel-jar', type=Path, required=True)
    parser.add_argument('--java', default='java')
    parser.add_argument('--output-directory', type=Path, required=True)
    parser.add_argument('--condition', choices=CONDITIONS, required=True)
    parser.add_argument('--sessions', type=int, default=3)
    parser.add_argument('--relay-namespace', help=argparse.SUPPRESS)
    args = parser.parse_args()
    if sys.platform != 'linux' or os.geteuid() != 0:
        parser.error('requires Linux root: run only on a disposable test runner')
    if not 1 <= args.sessions <= 10:
        parser.error('sessions must be 1..10')
    args.relay_binary = args.relay_binary.resolve(strict=True)
    args.tunnel_jar = args.tunnel_jar.resolve(strict=True)
    args.output_directory = args.output_directory.resolve()
    if args.relay_namespace:
        if not re.fullmatch(r'bta-netem-\d+-r', args.relay_namespace):
            parser.error('invalid owned namespace')
        outcomes = [run_session(args, index) for index in range(args.sessions)]
        return 0 if all(outcomes) else 1
    args.output_directory.mkdir(parents=True, exist_ok=False)
    names = [f'bta-netem-{os.getpid()}-r', f'bta-netem-{os.getpid()}-h']
    created = []
    metadata = {'schema': 1, 'status': 'RUNNING', 'platform': platform.platform(),
                'python': sys.version, 'condition': args.condition, 'netem': CONDITIONS[args.condition],
                'sessions': args.sessions, 'commands': [], 'cleanup_errors': []}

    def run(cmd):
        metadata['commands'].append(cmd)
        save(args.output_directory / 'environment.json', metadata)
        return command(cmd)

    try:
        metadata['java'] = subprocess.run([args.java, '-version'], capture_output=True, text=True, check=True).stderr
        metadata['iproute2'] = command(['tc', '-V'])
        for name in names:
            run(['ip', 'netns', 'add', name])
            created.append(name)
            run(['ip', '-n', name, 'link', 'set', 'lo', 'up'])
        # Both interfaces are born inside our namespaces, so host interfaces,
        # routes and qdiscs are never modified, even if setup fails partway.
        run(['ip', '-n', names[0], 'link', 'add', 'bta0', 'type', 'veth', 'peer', 'name', 'bta0', 'netns', names[1]])
        for name, address in zip(names, (RELAY_IP, HOST_IP)):
            run(['ip', '-n', name, 'addr', 'add', address + '/24', 'dev', 'bta0'])
            run(['ip', '-n', name, 'link', 'set', 'bta0', 'up'])
            if CONDITIONS[args.condition]:
                prefix = ['ip', 'netns', 'exec', name, 'tc']
                run(prefix + ['qdisc', 'add', 'dev', 'bta0', 'root', 'handle', '1:', 'prio',
                              'bands', '3', 'priomap', *(['1'] * 16)])
                run(prefix + ['qdisc', 'add', 'dev', 'bta0', 'parent', '1:3', 'handle', '30:',
                              'netem', 'limit', '1000', *CONDITIONS[args.condition]])
                run(prefix + ['filter', 'add', 'dev', 'bta0', 'parent', '1:', 'protocol', 'ip',
                              'prio', '1', 'u32', 'match', 'ip', 'protocol', '17', '0xff', 'flowid', '1:3'])
        worker = ['ip', 'netns', 'exec', names[1], sys.executable, str(Path(__file__).resolve()),
                  '--relay-binary', str(args.relay_binary), '--tunnel-jar', str(args.tunnel_jar),
                  '--java', args.java, '--output-directory', str(args.output_directory),
                  '--condition', args.condition, '--sessions', str(args.sessions), '--relay-namespace', names[0]]
        result = subprocess.run(worker)
        metadata['worker_returncode'] = result.returncode
        metadata['qdisc_statistics'] = {name: command(['ip', 'netns', 'exec', name, 'tc', '-s', '-j',
                                                     'qdisc', 'show', 'dev', 'bta0']) for name in names}
        metadata['status'] = 'PASS' if result.returncode == 0 else 'FAILED'
    except BaseException as error:
        metadata.update(status='FAILED', error=b.bounded_failure(error))
    finally:
        for name in reversed(created):
            try:
                # A crashed worker may leave children; kill only processes still
                # assigned to namespaces created by this invocation.
                for pid in command(['ip', 'netns', 'pids', name]).split():
                    with contextlib.suppress(ProcessLookupError):
                        os.kill(int(pid), 9)
                command(['ip', 'netns', 'delete', name])
            except BaseException as error:
                metadata['cleanup_errors'].append(b.bounded_failure(error))
        if metadata['cleanup_errors']:
            metadata['status'] = 'FAILED'
        save(args.output_directory / 'environment.json', metadata)
    return 0 if metadata['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
