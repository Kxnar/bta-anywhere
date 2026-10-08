"""Check integrity, measured source identity, recovery phases and successful EOFs."""
import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / 'source/scripts'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    from summarize_jitter_diagnostics import summarize
    index = json.loads((ROOT / 'archive-index.json').read_text())
    for item in index['files'] + index['sources']:
        assert sha(ROOT / item['path']) == item['sha256'], item['path']
    diagnostic_artifacts = None
    summary = json.loads((ROOT / 'summary.json').read_text())
    for path in (ROOT / 'results').glob('*.json'):
        data = json.loads(path.read_text())
        if 'sha256' not in data:
            continue
        assert summarize(data) == summary[path.stem], path.name
        sources = {'workload': 'scripts/benchmark_relay.py', 'network': 'scripts/benchmark_network.py',
                   'bridge': 'scripts/local_impairment.py', 'harness': 'scripts/benchmark_jitter_diagnostics.py'}
        if path.stem == 'jitter-saturated':
            sources.update(harness='historical/diagnostic-first.py', bridge='historical/bridge-first.py')
        if path.stem == 'restarts-25':
            sources['harness'] = 'scripts/benchmark_restart_distribution.py'
        else:
            artifacts = (data['sha256']['relay'], data['sha256']['tunnel'])
            if diagnostic_artifacts is None:
                diagnostic_artifacts = artifacts
            assert artifacts == diagnostic_artifacts, path.name
            assert {'relay', 'java_connection', 'java_stream'} <= {r['kind'] for r in data['diagnostics']}
        for key, relative in sources.items():
            assert sha(ROOT / 'source' / relative) == data['sha256'][key], (path.name, key)
        if data['status'] == 'PASS' and 'progress' in data:
            for stream in data['progress']:
                assert stream['guest_send_bytes'] == stream['guest_receive_bytes'] > 0
                assert stream['guest_read_eof'] and stream['echo_read_eof']
                assert stream['guest_error'] is None and stream['echo_error'] is None
        for partial in data.get('partial_successes', []):
            transfer = partial['result']
            assert transfer['host_to_guest_bytes'] == transfer['guest_to_host_bytes'] > 0
            assert data['progress'][partial['stream']]['guest_read_eof']
    recovery = json.loads((ROOT / 'results/restarts-25.json').read_text())
    assert len(recovery['rows']) == 25
    assert sorted(recovery['phase_schedule_seconds']) == sorted([.5, 3.5, 6.5, 9.5, 12.5] * 5)
    assert (recovery['status'] == 'PASS') == all(r['status'] == 'PASS' for r in recovery['rows'])
    for trial, row in enumerate(recovery['rows']):
        assert row['trial'] == trial
        assert row['target_phase_seconds'] == recovery['phase_schedule_seconds'][trial]
        if row['status'] == 'PASS':
            assert abs(row['observed_phase_seconds'] - row['target_phase_seconds']) < .25
    milestone = json.loads((ROOT.parent / '2026-10-08-scaling/results/after.json').read_text())
    for key in ('relay', 'tunnel'):
        assert recovery['sha256'][key] == milestone['sha256'][key]
    checks = json.loads((ROOT / 'results/validation.json').read_text())
    assert checks and all(item['exit_code'] == 0 for item in checks)
    assert {item['name'] for item in checks} == {
        'python-tests', 'rust-format', 'rust-clippy', 'rust-tests', 'java-check-build',
        'native-doctor', 'cross-language', 'half-close', 'prior-archive'}
    print(f"Verified {len(index['files'])} evidence files, {len(index['sources'])} source snapshots and 25 phase-stratified restart trials.")


if __name__ == '__main__':
    main()
