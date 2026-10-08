#!/usr/bin/env python3
"""Archive and report independent Linux sessions from a completed Actions run."""
import argparse
import hashlib
import json
from pathlib import Path
import statistics
import subprocess


def quantile(values, fraction):
    ordered = sorted(values)
    position = (len(ordered) - 1) * fraction
    lo = int(position)
    return ordered[lo] + (ordered[min(lo + 1, len(ordered) - 1)] - ordered[lo]) * (position - lo)


def summarize(row):
    assert row['status'] in ('PASS', 'FAILED')
    transactions = row['transactions']
    completed = [r for r in transactions if r['status'] == 'PASS']
    through = row.get('throughput', [])
    if row['status'] == 'PASS':
        assert len(completed) == 60 and len(through) == 8 and not row['failures']
        assert row['cleanup']['tunnel_clean'] and row['cleanup']['relay_clean'] and not row['cleanup']['errors']
        assert not any(p['running'] for p in row['lifecycle']['owned_process_exits'])
        for stream in row['progress']:
            assert stream['guest_send_bytes'] == stream['guest_receive_bytes'] == stream['echo_receive_bytes'] == stream['echo_send_bytes'] > 0
            assert all(stream[f] for f in ('guest_write_closed', 'guest_read_eof', 'echo_read_eof', 'echo_write_closed'))
            assert stream['guest_error'] is None and stream['echo_error'] is None
        assert row['recovery']['old_endpoint_verified'] and row['recovery']['status'] == 'PASS'
    for stream in through:
        assert stream['guest_to_host_bytes'] == stream['host_to_guest_bytes'] > 0
    cells = {}
    for mode in ('request_response', 'half_close'):
        for size in (1024, 65536, 1048576):
            times = [r['latency_ms'] for r in completed if r['mode'] == mode and r['bytes_each_direction'] == size]
            cells[f'{mode}-{size}'] = {'completed': len(times), 'p50_ms': quantile(times, .5) if times else None,
                                     'p95_ms': quantile(times, .95) if times else None}
    resources = {}
    for name in ('relay', 'tunnel'):
        samples = [r[name] for r in row['resources'] if name in r and 'pid' in r[name]]
        per_pid = {}
        for sample in samples:
            per_pid.setdefault(str(sample['pid']), []).append(sample['cpu_seconds'])
        resources[name] = {'peak_sampled_rss_bytes': max((r['rss_bytes'] for r in samples), default=None),
                           'observed_cpu_seconds_by_pid': {pid: max(times) - min(times) for pid, times in per_pid.items()}}
    verified_bytes = sum(r['guest_to_host_bytes'] for r in through)
    return {'condition': row['condition'], 'session': row['session'], 'status': row['status'],
            'transaction_attempts': len(transactions), 'completed_transactions': len(completed),
            'transaction_bytes_each_direction': sum(r['bytes_each_direction'] for r in completed),
            'transaction_cells': cells, 'completed_throughput_streams': len(through),
            'throughput_bytes_each_direction': verified_bytes,
            'verified_mib_per_second_each_direction': verified_bytes / 1048576 / row['throughput_wall_seconds'] if through else None,
            'throughput_wall_seconds': row.get('throughput_wall_seconds'),
            'recovery': row.get('recovery'), 'resources': resources}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('download', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    run = json.loads((args.download / 'run.json').read_text(encoding='utf-8-sig'))
    assert run['status'] == 'completed'
    args.output.mkdir(parents=True, exist_ok=False)
    manifest = {'schema': 1, 'run_id': run['databaseId'], 'source_commit': run['headSha'], 'files': []}

    def retain(relative, data):
        path = args.output / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        manifest['files'].append({'path': relative, 'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data)})

    rows = []
    for path in sorted(args.download.rglob('*')):
        if path.is_file():
            retain('results/' + path.relative_to(args.download).as_posix(), path.read_bytes())
            if '-session-' in path.name:
                rows.append(summarize(json.loads(path.read_text())))
    assert len(rows) == 9
    source_paths = ['scripts/benchmark_netem.py', 'scripts/benchmark_relay.py', '.github/workflows/netem.yml',
                    'settings.gradle.kts', 'build.gradle.kts', 'tunnel-client/build.gradle.kts',
                    'gradle/libs.versions.toml', 'tunnel-client/gradle.lockfile', 'Cargo.lock']
    for relative in source_paths:
        retain('source/' + relative, subprocess.check_output(['git', 'show', run['headSha'] + ':' + relative]))
    retain('source/report_netem.py', Path(__file__).read_bytes())
    summary = {'schema': 1, 'run': run['url'], 'source_commit': run['headSha'], 'sessions': rows,
               'totals': {'sessions': len(rows), 'passed_sessions': sum(r['status'] == 'PASS' for r in rows),
                          'completed_transactions': sum(r['completed_transactions'] for r in rows),
                          'completed_throughput_streams': sum(r['completed_throughput_streams'] for r in rows),
                          'transaction_bytes_each_direction': sum(r['transaction_bytes_each_direction'] for r in rows),
                          'throughput_bytes_each_direction': sum(r['throughput_bytes_each_direction'] for r in rows)}}
    retain('summary.json', (json.dumps(summary, indent=2) + '\n').encode())
    (args.output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    table = ['| Condition / session | 1 KiB request p50 (ms) | 1 MiB half-close p50 (ms) | Verified MiB/s each way | Restart recovery (s) | Relay / Java peak sampled RSS (MiB) |',
             '| --- | ---: | ---: | ---: | ---: | ---: |']
    for row in rows:
        assert row['status'] == 'PASS'  # A failed campaign needs an explicit failure table, not a success-only report.
        table.append(f'| {row["condition"]} / {row["session"] + 1} | '
                     f'{row["transaction_cells"]["request_response-1024"]["p50_ms"]:.2f} | '
                     f'{row["transaction_cells"]["half_close-1048576"]["p50_ms"]:.2f} | '
                     f'{row["verified_mib_per_second_each_direction"]:.2f} | '
                     f'{row["recovery"]["fault_to_verified_ms"] / 1000:.2f} | '
                     f'{row["resources"]["relay"]["peak_sampled_rss_bytes"] / 1048576:.1f} / '
                     f'{row["resources"]["tunnel"]["peak_sampled_rss_bytes"] / 1048576:.1f} |')
    (args.output / 'session-table.md').write_text('\n'.join(table) + '\n', encoding='utf-8')
    print(json.dumps(summary['totals']))
    print('\n'.join(table))


if __name__ == '__main__':
    main()
