"""Publish a completed diagnostic campaign, preserving failures and source hashes."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[2]
sys.path.insert(0, str(REPO / 'scripts'))
from summarize_jitter_diagnostics import summarize

NAMES = ['jitter-saturated', 'jitter-paced', 'jitter-ordered', 'loss-paced',
         'jitter-saturated-180', 'jitter-ordered-repeat', 'loss-saturated', 'restarts-25']
SOURCES = ['scripts/benchmark_jitter_diagnostics.py', 'scripts/benchmark_restart_distribution.py',
           'scripts/summarize_jitter_diagnostics.py', 'scripts/benchmark_relay.py',
           'scripts/benchmark_network.py', 'scripts/local_impairment.py',
           'scripts/test_benchmark_network.py', 'relay/src/main.rs', 'relay/src/diagnostics.rs',
           'tunnel-client/src/main/java/io/github/kxnar/btaanywhere/internal/NettyTunnelSession.java',
           'tunnel-client/src/main/java/io/github/kxnar/btaanywhere/internal/TransportDiagnostics.java']


def sha(data):
    return hashlib.sha256(data).hexdigest()


def redact(value):
    if isinstance(value, dict):
        return {k: redact(v) for k, v in value.items()}
    if isinstance(value, list):
        return [redact(v) for v in value]
    if isinstance(value, str):
        return value.replace(str(Path.home()), '<user>').replace(Path.home().as_posix(), '<user>')
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('campaign', type=Path)
    args = parser.parse_args()
    if (ROOT / 'archive-index.json').exists():
        parser.error('archive already exists')
    data = {name: json.loads((args.campaign / (name + '.json')).read_text()) for name in NAMES}
    assert all(d['status'] in ('PASS', 'FAILED') for d in data.values())
    assert len(data['restarts-25']['rows']) == 25
    index = {'base_commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
             'transformations': 'User home paths redacted; JSON reserialized as UTF-8/LF; numerical values unchanged.',
             'files': [], 'sources': []}
    summary = {}
    (ROOT / 'results').mkdir(exist_ok=True)
    for name, result in data.items():
        original = (args.campaign / (name + '.json')).read_bytes()
        published = (json.dumps(redact(result), indent=2) + '\n').encode()
        (ROOT / 'results' / (name + '.json')).write_bytes(published)
        index['files'].append({'path': 'results/' + name + '.json', 'original_sha256': sha(original),
                               'sha256': sha(published), 'status': result['status']})
        summary[name] = summarize(result)
    for relative in SOURCES:
        target = ROOT / 'source' / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        contents = (REPO / relative).read_bytes()
        target.write_bytes(contents)
        index['sources'].append({'path': 'source/' + relative, 'sha256': sha(contents)})
    for name in ['diagnostic-first.py', 'bridge-first.py']:
        contents = (args.campaign / name).read_bytes()
        target = ROOT / 'source' / 'historical' / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(contents)
        index['sources'].append({'path': 'source/historical/' + name, 'sha256': sha(contents)})
    for filename in ['validation.txt', 'validation.json', 'netem-availability.json']:
        original = (args.campaign / filename).read_bytes()
        contents = redact(original.decode('utf-8-sig')).replace('\r\n', '\n').encode()
        (ROOT / 'results' / filename).write_bytes(contents)
        index['files'].append({'path': 'results/' + filename, 'original_sha256': sha(original), 'sha256': sha(contents)})
    for name, value in [('summary.json', summary), ('archive-index.json', index)]:
        (ROOT / name).write_bytes((json.dumps(value, indent=2) + '\n').encode())


if __name__ == '__main__':
    main()
