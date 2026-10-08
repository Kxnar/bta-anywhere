#!/usr/bin/env python3
"""Repeatable scaling experiment using the original byte/EOF-verified workload."""
import argparse
import collections
import concurrent.futures
import cProfile
import hashlib
import json
import os
from pathlib import Path
import pstats
import subprocess
import time
import traceback

import benchmark_relay as b


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def process_transfer(arguments):
    return b.throughput_stream(*arguments)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--relay-binary', type=Path, required=True)
    parser.add_argument('--tunnel-jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--streams', type=int, nargs='+', default=[1, 2, 4, 8, 16])
    parser.add_argument('--paths', nargs='+', choices=['direct', 'relay'], default=['direct', 'relay'])
    parser.add_argument('--seconds', type=float, default=15)
    parser.add_argument('--runs', type=int, default=3)
    parser.add_argument('--java-option', action='append', default=[])
    parser.add_argument('--python-profile', action='store_true')
    parser.add_argument('--jfr', action='store_true')
    parser.add_argument('--workers', choices=['threads', 'processes'], default='threads')
    parser.add_argument('--transport-profile', action='store_true')
    parser.add_argument('--relay-workers', type=int)
    args = parser.parse_args()
    if args.output.exists() or args.output.with_suffix('.checkpoint.json').exists():
        parser.error('use a fresh output path')
    if (not 0 < args.seconds <= 120 or not 1 <= args.runs <= 20
            or any(n < 1 or n > 32 for n in args.streams)
            or (args.relay_workers is not None and not 1 <= args.relay_workers <= 64)):
        parser.error('invalid workload bounds')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    logs = collections.deque(maxlen=80)
    profiles = []

    class Rig(b.Rig):
        def start_relay(self, log_level='warn'):
            config = self.work / 'relay' / 'relay.toml'
            source = config.read_text(encoding='utf-8')
            config.write_text(source.replace('max_connections_per_session = 8',
                                            'max_connections_per_session = 32'), encoding='utf-8')
            return super().start_relay(log_level)

        def launch(self, command, label, stdin, env=None):
            if label == 'relay' and args.relay_workers:
                env = dict(env or os.environ)
                env['TOKIO_WORKER_THREADS'] = str(args.relay_workers)
            if label == 'relay' and args.transport_profile:
                env = dict(env or os.environ)
                env['BTA_TRANSPORT_PROFILE'] = '1'
            if label == 'tunnel':
                command = command[:1] + args.java_option + command[1:]
                if args.jfr:
                    recording = args.output.with_suffix('.jfr').resolve().as_posix()
                    command.insert(1, f'-XX:StartFlightRecording=filename={recording},settings=profile,dumponexit=true')
            return super().launch(command, label, stdin, env)

    result = {'schema': 1, 'status': 'RUNNING', 'workload': {'streams': args.streams,
              'paths': args.paths, 'seconds': args.seconds, 'runs': args.runs, 'seed': 1701,
              'block_bytes': b.CHUNK, 'java_options': args.java_option,
              'python_profile': args.python_profile, 'jfr': args.jfr,
              'workers': args.workers,
              'transport_profile': args.transport_profile,
              'relay_workers': args.relay_workers,
              'benchmark_max_connections_per_session': 32},
              'sha256': {name: digest(path) for name, path in {
                  'relay': args.relay_binary, 'tunnel': args.tunnel_jar,
                  'harness': Path(__file__), 'workload': Path(b.__file__)}.items()},
              'source': b.source_provenance(Path(__file__)), 'rows': []}
    block = b.payload(1701, b.CHUNK, 999)
    try:
        with Rig(args.relay_binary.resolve(), args.tunnel_jar.resolve(), 'java', logs) as rig:
            for repeat in range(args.runs):
                # Reverse both dimensions on alternating repetitions to expose drift.
                for count in (args.streams if repeat % 2 == 0 else args.streams[::-1]):
                    for path in (args.paths if repeat % 2 == 0 else args.paths[::-1]):
                        result['current_case'] = dict(repeat=repeat, streams=count, path=path)
                        b.checkpoint_result(result, args.output)
                        port = rig.public_port if path == 'relay' else rig.echo.server_address[1]
                        before = {name: b.process_sample(proc) for name, proc in [('relay', rig.relay), ('tunnel', rig.tunnel)]}
                        python_before = time.process_time()
                        started = time.perf_counter()

                        def transfer(index):
                            # Python 3.14 permits only one active cProfile monitoring tool.
                            # Activate once per wave; Python 3.14's monitoring can also
                            # observe other threads, so cumulative call trees are not
                            # reliable per-thread CPU attribution. Keep raw profiles.
                            if not args.python_profile or index != 0:
                                return b.throughput_stream(port, block, args.seconds, 30)
                            profiler = cProfile.Profile()
                            try:
                                return profiler.runcall(b.throughput_stream, port, block, args.seconds, 30)
                            finally:
                                profiler.create_stats()
                                if profiler.stats:
                                    profiles.append(profiler)

                        if args.workers == 'processes':
                            with concurrent.futures.ProcessPoolExecutor(max_workers=count) as pool:
                                samples = list(pool.map(process_transfer, [(port, block, args.seconds, 30)] * count))
                        else:
                            samples = b.run_concurrent(transfer, count)
                        wall = time.perf_counter() - started
                        after = {name: b.process_sample(proc) for name, proc in [('relay', rig.relay), ('tunnel', rig.tunnel)]}
                        total = sum(s['host_to_guest_bytes'] for s in samples)
                        row = dict(repeat=repeat, streams=count, path=path, wall_seconds=wall,
                                   verified_bytes_per_direction=total, mib_s=total / wall / 1048576,
                                   legacy_mib_s=total / max(s['seconds'] for s in samples) / 1048576,
                                   cpu_seconds={name: after[name]['cpu_seconds'] - before[name]['cpu_seconds'] for name in before},
                                   memory_after=after, stream_results=samples)
                        row['cpu_seconds']['python'] = time.process_time() - python_before
                        result['rows'].append(row)
                        rig.assert_idle()
                        b.checkpoint_result(result, args.output)
                        print(json.dumps({k: row[k] for k in ['repeat', 'streams', 'path', 'mib_s', 'cpu_seconds']}), flush=True)
            if args.jfr:
                subprocess.run(['jcmd', str(rig.tunnel.pid), 'JFR.dump', 'name=1',
                                'filename=' + str(args.output.with_suffix('.jfr').resolve())], check=True, capture_output=True)
            result['status'] = 'PASS'
    except BaseException as error:
        result['status'] = 'FAILED'
        result['error'] = b.bounded_failure(error)
        result['traceback'] = traceback.format_exc()
        if isinstance(error, b.ConcurrentTransferError):
            result['partial_successes'] = error.successes
    finally:
        result['logs'] = list(logs)
        args.output.write_text(json.dumps(result, indent=2), encoding='utf-8')
        if profiles:
            stats = pstats.Stats(profiles[0])
            for profile in profiles[1:]:
                stats.add(profile)
            stats.dump_stats(str(args.output.with_suffix('.pstats')))
    return 0 if result['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
