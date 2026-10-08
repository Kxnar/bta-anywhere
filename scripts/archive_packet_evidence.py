#!/usr/bin/env python3
"""Freeze a completed packet/paired campaign, retaining failures and exact sources."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import subprocess


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--campaign', type=Path, required=True)
    parser.add_argument('--upstream-source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    paired = json.loads((args.campaign / 'paired-no-qlog/campaign.json').read_text())
    if paired.get('status') != 'COMPLETE' or len(paired['cells']) != 12:
        parser.error('the full 12-cell paired campaign must finish before archiving')
    args.output.mkdir(parents=True, exist_ok=False)
    index = {'schema': 1, 'source_commit_at_archive': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
             'measured_baseline_base_commit': '7226336',
             'transformations': 'Logs and JSON redact home paths. Sources retain exact bytes. Qlog is gzip-compressed with mtime=0; no event edits.',
             'files': [], 'binaries_not_redistributed': []}

    def retain(source, relative, kind, compress=False, redact=False):
        original = source.read_bytes()
        data = original
        if redact:
            text = original.decode('utf-8-sig')
            for private in (str(Path.home()), Path.home().as_posix(), json.dumps(str(Path.home()))[1:-1]):
                text = text.replace(private, '<user>')
            data = text.encode()
        if compress:
            data = gzip.compress(data, compresslevel=9, mtime=0)
        target = args.output / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        index['files'].append({'path': relative, 'kind': kind, 'bytes': len(data),
                               'sha256': sha(data), 'original_sha256': sha(original), 'original_bytes': len(original)})

    for directory in (args.campaign, args.campaign / 'paired-no-qlog', args.campaign / 'final-validation', args.campaign / 'windows-ci'):
        for source in sorted(directory.glob('*.json')):
            if '.checkpoint.' not in source.name:
                retain(source, 'results/' + source.relative_to(args.campaign).as_posix(), 'result', redact=True)
        for source in sorted(directory.glob('*.txt')) + sorted(directory.glob('*.log')):
            retain(source, 'results/' + source.relative_to(args.campaign).as_posix(), 'log', redact=True)
    for source in sorted(args.campaign.glob('*.qlog/*.qlog')):
        retain(source, 'captures/' + source.relative_to(args.campaign).as_posix() + '.gz', 'qlog', compress=True)
    for source in sorted((args.campaign / 'source-v1').iterdir()):
        if source.is_file():
            retain(source, 'source/measured/' + source.name, 'source')
    retain(args.campaign / 'baseline-source.patch', 'source/baseline-source.patch', 'source')
    for source in sorted(args.upstream_source.iterdir()):
        if source.is_file() and source.name != 'pom.xml':
            retain(source, 'source/upstream/' + source.name, 'upstream')
    for name in ('relay.exe', 'qlog-tunnel.jar', 'qlog-tunnel-v1.jar', 'candidate-075.jar', 'final-package.jar'):
        source = args.campaign / name
        index['binaries_not_redistributed'].append({'file': name, 'bytes': source.stat().st_size,
                                                   'sha256': sha(source.read_bytes()),
                                                   'usable': name != 'qlog-tunnel.jar'})
    for source in sorted((args.campaign / 'java-candidate-validation').rglob('TEST-*.xml')):
        retain(source, 'results/java-tests/' + source.relative_to(args.campaign / 'java-candidate-validation').as_posix(), 'test', redact=True)
    repo_scripts = Path(__file__).parent
    for name in ('analyze_qlog.py', 'test_analyze_qlog.py', 'reproduce_pacer_timestamp.rs',
                 'summarize_packet_campaign.py', 'plot_packet_rtt.py'):
        retain(repo_scripts / name, 'source/analysis/' + name, 'source')
    retain(repo_scripts / 'fixtures/quiche_pacer_70d6d3.rs',
           'source/analysis/fixtures/quiche_pacer_70d6d3.rs', 'upstream')
    for name in ('benchmark_jitter_diagnostics.py', 'benchmark_relay.py', 'benchmark_network.py', 'local_impairment.py'):
        retain(repo_scripts / name, 'source/final/' + name, 'source')
    (args.output / 'archive-index.json').write_text(json.dumps(index, indent=2) + '\n', encoding='utf-8')
    print(f'Archived {len(index["files"])} files; all unsuccessful cells retained.')


if __name__ == '__main__':
    main()
