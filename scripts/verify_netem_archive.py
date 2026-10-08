#!/usr/bin/env python3
"""Verify exact Linux evidence bytes and independently rederive its summary."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    root = parser.parse_args().archive
    manifest = json.loads((root / 'manifest.json').read_text())
    for item in manifest['files']:
        data = (root / item['path']).read_bytes()
        assert len(data) == item['bytes'] and hashlib.sha256(data).hexdigest() == item['sha256'], item['path']
    spec = importlib.util.spec_from_file_location('frozen_report', root / 'source/report_netem.py')
    report = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(report)
    rows = []
    for path in sorted((root / 'results').rglob('*-session-*.json')):
        result = json.loads(path.read_text())
        for key, name in (('driver', 'benchmark_netem.py'), ('workload', 'benchmark_relay.py')):
            assert result['sha256'][key] == hashlib.sha256((root / 'source/scripts' / name).read_bytes()).hexdigest(), path
        rows.append(report.summarize(result))
    summary = json.loads((root / 'summary.json').read_text())
    assert rows == summary['sessions'] and len(rows) == 9
    assert {(r['condition'], r['session']) for r in rows} == {(c, n) for c in ('clean', 'jitter', 'loss') for n in range(3)}
    for path in (root / 'results').rglob('environment.json'):
        environment = json.loads(path.read_text())
        assert environment['status'] == 'PASS' and not environment['cleanup_errors']
        if environment['condition'] != 'clean':
            for value in environment['qdisc_statistics'].values():
                qdiscs = [r for r in json.loads(value) if r['kind'] == 'netem']
                assert len(qdiscs) == 1 and qdiscs[0]['packets'] > 0
                assert qdiscs[0]['options']['limit'] == 1000
                assert qdiscs[0]['options']['rate']['rate'] == 2_500_000
                if environment['condition'] == 'loss':
                    assert qdiscs[0]['options']['loss-random']['loss'] == .01 and qdiscs[0]['drops'] > 0
    print(f'Verified {len(manifest["files"])} files and all nine independent Linux sessions.')


if __name__ == '__main__':
    main()
