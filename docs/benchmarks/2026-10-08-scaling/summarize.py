"""Recompute tables from archived, byte-verified campaign JSON."""
import json
from pathlib import Path
import statistics as s

ROOT = Path(__file__).resolve().parent


def read(name):
    data = json.loads((ROOT / 'results' / name).read_text(encoding='utf-8'))
    if data['status'] != 'PASS':
        raise ValueError(f'{name} is not a completed campaign')
    return data


def main():
    before, after = read('before.json'), read('after.json')
    assert before['workload'] == after['workload'], 'workloads differ'
    for key in ('harness', 'workload'):
        assert before['sha256'][key] == after['sha256'][key], f'{key} differs'
    expected = {(run, count, path) for run in range(3) for count in (1, 2, 4, 8, 16) for path in ('direct', 'relay')}
    for data in (before, after):
        actual = {(row['repeat'], row['streams'], row['path']) for row in data['rows']}
        assert actual == expected and len(data['rows']) == len(expected)
        for row in data['rows']:
            assert row['verified_bytes_per_direction'] > 0
            assert len(row['stream_results']) == row['streams']
            assert all(stream['guest_to_host_bytes'] == stream['host_to_guest_bytes'] > 0 for stream in row['stream_results'])

    lines = ['| Streams | Before MiB/s | After MiB/s | Change | Before range | After range |',
             '|---:|---:|---:|---:|---:|---:|']
    output = {'scaling': [], 'recovery': [], 'matrix': [], 'outages': [], 'process_cpu': []}
    for count in (1, 2, 4, 8, 16):
        groups = [[row for row in data['rows'] if row['path'] == 'relay' and row['streams'] == count] for data in (before, after)]
        rates = [[row['mib_s'] for row in group] for group in groups]
        medians = list(map(s.median, rates))
        change = (medians[1] / medians[0] - 1) * 100
        java_cpu = [s.median(row['cpu_seconds']['tunnel'] / (row['verified_bytes_per_direction'] / 1048576) for row in group) for group in groups]
        output['scaling'].append({'streams': count, 'before_mib_s': medians[0], 'after_mib_s': medians[1],
                                  'change_percent': change, 'before_samples': rates[0], 'after_samples': rates[1],
                                  'java_cpu_seconds_per_mib_before': java_cpu[0], 'java_cpu_seconds_per_mib_after': java_cpu[1],
                                  'java_cpu_per_mib_reduction_percent': (1 - java_cpu[1] / java_cpu[0]) * 100})
        lines.append(f'| {count} | {medians[0]:.2f} | {medians[1]:.2f} | {change:+.1f}% | {min(rates[0]):.2f}–{max(rates[0]):.2f} | {min(rates[1]):.2f}–{max(rates[1]):.2f} |')
        for label, group in zip(('before', 'after'), groups):
            output['process_cpu'].append({'build': label, 'streams': count,
                'cpu_seconds_per_mib': {process: s.median(row['cpu_seconds'][process] / (row['verified_bytes_per_direction'] / 1048576) for row in group) for process in ('relay', 'tunnel', 'python')},
                'mean_cores': {process: s.median(row['cpu_seconds'][process] / row['wall_seconds'] for row in group) for process in ('relay', 'tunnel', 'python')}})
    lines += ['', 'Medians and full ranges of three repetitions; no confidence interval is implied.', '',
              '| Eight-stream CPU seconds per verified MiB | Before | After |', '|---|---:|---:|']
    cpu = [next(row for row in output['process_cpu'] if row['build'] == build and row['streams'] == 8)['cpu_seconds_per_mib'] for build in ('before', 'after')]
    for process in ('relay', 'tunnel', 'python'):
        lines.append(f'| {process} | {cpu[0][process]:.4f} | {cpu[1][process]:.4f} |')
    lines += ['',
              '| Restart condition | Build | Fault to verified recovery, median (range), s | Detection, median, s | First retry, median, s | Final registration, median, ms |',
              '|---|---|---:|---:|---:|---:|']
    recovery_inputs = [('before', read('before-recovery.json')), ('after', read('after-recovery.json'))]
    for build, data in recovery_inputs:
        for condition in sorted({row['condition'] for row in data['rows']}):
            rows = [row for row in data['rows'] if row['kind'] == 'restart' and row['condition'] == condition]
            values = [row['fault_to_verified_ms'] / 1000 for row in rows]
            detection = s.median(row['detection_observed_ms'] / 1000 for row in rows)
            retry = s.median(row['retry_delay_ms'] / 1000 for row in rows)
            registration = s.median(row['registration_ms'] for row in rows)
            output['recovery'].append({'build': build, 'condition': condition, 'samples_seconds': values,
                                       'median_seconds': s.median(values), 'median_detection_seconds': detection,
                                       'median_first_retry_seconds': retry, 'median_final_registration_ms': registration})
            lines.append(f'| {condition} | {build} | {s.median(values):.2f} ({min(values):.2f}–{max(values):.2f}) | {detection:.2f} | {retry:.2f} | {registration:.1f} |')
            for duration in (1, 3):
                outage_rows = [row for row in data['rows'] if row['kind'] == 'outage' and row['condition'] == condition and row['drop_seconds'] == duration]
                output['outages'].append({'build': build, 'condition': condition, 'seconds': duration, 'runs': len(outage_rows),
                                         'reconnects': sum(row['reconnects'] for row in outage_rows),
                                         'recovery_after_restore_ms': [row['recovery_after_restore_ms'] for row in outage_rows]})
    base = next(row for row in output['recovery'] if row['build'] == 'before' and row['condition'] == 'bridge-zero')
    candidate = next(row for row in output['recovery'] if row['build'] == 'after' and row['condition'] == 'bridge-zero')
    output['restart_reduction_percent'] = (1 - candidate['median_seconds'] / base['median_seconds']) * 100
    lines += ['', f"Matched zero-impairment bridge restart median reduction: **{output['restart_reduction_percent']:.1f}%**.", '',
              '| Matrix condition | Streams | Successful cells | Median verified MiB/s of successes | Transaction p50 / p95 range, ms |',
              '|---|---:|---:|---:|---:|']
    matrix = json.loads((ROOT / 'results/network-matrix.json').read_text(encoding='utf-8'))
    assert matrix['status'] == 'COMPLETE' and len(matrix['cases']) == 16
    for condition in dict.fromkeys(row['condition'] for row in matrix['cases']):
        for count in (1, 8):
            cells = [cell['result'] for cell in matrix['cases'] if cell['condition'] == condition and cell['streams'] == count]
            assert len(cells) == 2
            rates = [row['mib_s'] for cell in cells if cell['status'] == 'PASS' for row in cell['rows']]
            latency_rows = [row for cell in cells for row in cell['rows']]
            latency_rows += [cell['pending_matrix_case'] for cell in cells if 'pending_matrix_case' in cell]
            p50 = [row['latency_summary_ms']['p50'] for row in latency_rows]
            p95 = [row['latency_summary_ms']['p95'] for row in latency_rows]
            output['matrix'].append({'condition': condition, 'streams': count, 'throughput_samples_mib_s': rates,
                                     'p50_ms': p50, 'p95_ms': p95,
                                     'statuses': [cell['status'] for cell in cells],
                                     'errors': [cell.get('error') for cell in cells],
                                     'reconnects': [cell.get('reconnects') for cell in cells],
                                     'bridge_residence_p50_ms': [s.median(cell['final_bridge']['delay_samples_ms']) for cell in cells],
                                     'bridge_residence_p95_ms': [s.quantiles(cell['final_bridge']['delay_samples_ms'], n=100, method='inclusive')[94] for cell in cells],
                                     'bridge_counters': [cell['final_bridge']['counters'] for cell in cells]})
            rate_text = f'{s.median(rates):.3f}' if rates else 'FAILED'
            latency_text = f'{min(p50):.1f}–{max(p50):.1f} / {min(p95):.1f}–{max(p95):.1f}' if p50 else 'incomplete'
            lines.append(f'| {condition} | {count} | {len(rates)}/2 | {rate_text} | {latency_text} |')
    lines += ['', 'Failed throughput cells are not zero throughput and are not included in successful-rate medians.',
              'Latency was measured before saturation; a successful latency phase does not make a failed throughput cell pass.',
              '', 'Full sample counts, bridge counters, achieved queue delays and every recovery attempt are in the linked JSON.']
    (ROOT / 'summary.json').write_text(json.dumps(output, indent=2) + '\n', encoding='utf-8')
    (ROOT / 'results.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    print(json.dumps({'eight_stream_change_percent': output['scaling'][3]['change_percent'],
                      'restart_reduction_percent': output['restart_reduction_percent']}))


if __name__ == '__main__':
    main()
