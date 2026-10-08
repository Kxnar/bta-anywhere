"""Verify the published archive, source snapshots and cross-run workload identity."""
import hashlib
import ast
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    index = json.loads((ROOT / 'archive-index.json').read_text(encoding='utf-8'))
    for entry in index['files']:
        assert sha(ROOT / 'results' / entry['name']) == entry['published_sha256'], entry['name']
    for entry in index['source']['files']:
        assert sha(ROOT / 'source' / entry['path']) == entry['sha256'], entry['path']
    for entry in index['historical_sources']:
        assert sha(ROOT / 'source' / entry['path']) == entry['sha256'], entry['path']
    assert sha(ROOT / 'implementation.patch') == index['source']['tracked_patch_sha256']
    before = json.loads((ROOT / 'results/before.json').read_text(encoding='utf-8'))
    after = json.loads((ROOT / 'results/after.json').read_text(encoding='utf-8'))
    assert before['workload'] == after['workload']
    for key in ('harness', 'workload'):
        assert before['sha256'][key] == after['sha256'][key], key
    for file, key in (('scripts/benchmark_scaling.py', 'harness'), ('scripts/benchmark_relay.py', 'workload')):
        assert sha(ROOT / 'source' / file) == after['sha256'][key], file
    recovery_before = json.loads((ROOT / 'results/before-recovery.json').read_text(encoding='utf-8'))
    recovery_after = json.loads((ROOT / 'results/after-recovery.json').read_text(encoding='utf-8'))
    old_source = ROOT / 'source/historical/benchmark_network.py'
    new_source = ROOT / 'source/scripts/benchmark_network.py'
    assert sha(old_source) == recovery_before['sha256']['harness']
    assert sha(new_source) == recovery_after['sha256']['harness']
    for key in ('workload', 'bridge'):
        assert recovery_before['sha256'][key] == recovery_after['sha256'][key]
    definitions = [{node.name: ast.dump(node) for node in ast.parse(path.read_text()).body if isinstance(node, (ast.FunctionDef, ast.ClassDef))} for path in (old_source, new_source)]
    for name in ('NetworkRig', 'restart', 'probe_until', 'short_outage'):
        assert definitions[0][name] == definitions[1][name], name
    for name in ('after-recovery.json', 'after-profile.json', 'reset-auth.json', 'active-outages.json'):
        other = json.loads((ROOT / 'results' / name).read_text(encoding='utf-8'))
        assert other['status'] == 'PASS', name
        for key in ('relay', 'tunnel'):
            assert other['sha256'][key] == after['sha256'][key], (name, key)
    matrix = json.loads((ROOT / 'results/network-matrix.json').read_text(encoding='utf-8'))
    assert matrix['status'] == 'COMPLETE' and len(matrix['cases']) == 16
    for case in matrix['cases']:
        for key in ('relay', 'tunnel'):
            assert case['result']['sha256'][key] == after['sha256'][key], (case['condition'], key)
    print(f"Verified {len(index['files'])} result/profile files and {len(index['source']['files'])} source snapshots; failed experiments remain labelled.")


if __name__ == '__main__':
    main()
