#!/usr/bin/env python3
"""Verify frozen capture hashes, rederive RTT findings and check byte/EOF claims."""
import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    args = parser.parse_args()
    root = args.archive
    index = json.loads((root / 'archive-index.json').read_text())
    sys.path.insert(0, str(root / 'source/analysis'))
    from analyze_qlog import analyze
    from summarize_packet_campaign import summarize
    summary = json.loads((root / 'summary.json').read_text())
    interpretations = {}
    for file in ('baseline-jitter-analysis.json', 'remaining-qlog-analysis.json'):
        for row in json.loads((root / 'results' / file).read_text())['captures']:
            interpretations[row['file']] = row
    captures = {}
    for item in index['files']:
        path = root / item['path']
        data = path.read_bytes()
        assert len(data) == item['bytes'] and hashlib.sha256(data).hexdigest() == item['sha256'], path
        if item['kind'] == 'qlog':
            raw = gzip.decompress(data)
            assert len(raw) == item['original_bytes'] and hashlib.sha256(raw).hexdigest() == item['original_sha256'], path
            name = path.name.removesuffix('.gz')
            captures[name] = item['original_sha256']
            if name in interpretations:
                expected = dict(interpretations[name])
                del expected['file']
                assert expected.pop('sha256') == item['original_sha256']
                assert analyze(io.BytesIO(raw)) == expected, path
    cells = json.loads((root / 'results/paired-no-qlog/campaign.json').read_text())
    assert cells['status'] == 'COMPLETE' and len(cells['cells']) == 12
    seen = set()
    for cell in cells['cells']:
        key = cell['condition'], cell['seed'], cell['variant']
        assert key not in seen
        seen.add(key)
        result = json.loads((root / 'results/paired-no-qlog' / cell['result']).read_text())
        assert result['status'] == cell['status'] and result['qlog_enabled'] is False
        assert result['sha256']['tunnel'] == cells['sha256'][cell['variant']]
        assert result['seconds'] == 8 and result['drain_seconds'] == 120 and result['streams'] == 8
    source_names = {'harness': 'benchmark_jitter_diagnostics.py', 'workload': 'benchmark_relay.py',
                    'network': 'benchmark_network.py', 'bridge': 'local_impairment.py'}
    checked = 0
    for path in (root / 'results').rglob('*.json'):
        result = json.loads(path.read_text())
        if isinstance(result, dict) and 'qlog_enabled' in result:
            assert summarize(result) == summary[path.relative_to(root / 'results').as_posix()]
        if not isinstance(result, dict) or 'qlog_enabled' not in result or 'progress' not in result:
            continue
        source_directory = 'final' if 'final-validation' in path.parts else 'measured'
        for key, name in source_names.items():
            assert hashlib.sha256((root / 'source' / source_directory / name).read_bytes()).hexdigest() == result['sha256'][key], (path, key)
        for capture in result.get('qlog_files', []):
            assert captures[capture['file']] == capture['sha256']
        successes = result.get('transfers', []) if result['status'] == 'PASS' else [s['result'] for s in result.get('partial_successes', [])]
        for transfer in successes:
            assert transfer['guest_to_host_bytes'] == transfer['host_to_guest_bytes'] > 0
        if result['status'] == 'PASS':
            assert len(successes) == result['streams']
            for stream in result['progress']:
                assert stream['guest_send_bytes'] == stream['guest_receive_bytes'] == stream['echo_receive_bytes'] == stream['echo_send_bytes'] > 0
                assert all(stream[flag] for flag in ('guest_write_closed', 'guest_read_eof', 'echo_read_eof', 'echo_write_closed'))
                assert stream['guest_error'] is None and stream['echo_error'] is None
        checked += 1
    print(f'Verified {len(index["files"])} archive files, {len(captures)} captures, {checked} workloads and 12 paired cells.')


if __name__ == '__main__':
    main()
