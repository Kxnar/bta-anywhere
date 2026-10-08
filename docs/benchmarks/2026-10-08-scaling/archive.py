"""Archive the selected campaign with path redaction and an integrity inventory.

Run only after all selected measurements finish. Original files stay untouched.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
from datetime import datetime, timezone
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent


def sha(data):
    return hashlib.sha256(data).hexdigest()


def redact(value, home):
    if isinstance(value, dict):
        return {key: redact(item, home) for key, item in value.items()}
    if isinstance(value, list):
        return [redact(item, home) for item in value]
    if isinstance(value, str):
        return value.replace(str(home), '<user>').replace(home.as_posix(), '<user>').replace(str(home).replace('\\', '\\\\'), '<user>')
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--exploration', type=Path, required=True)
    parser.add_argument('--final', type=Path, required=True)
    parser.add_argument('--java-results', type=Path, required=True)
    args = parser.parse_args()
    destination = ROOT / 'results'
    destination.mkdir(exist_ok=True)
    selected = {
        'before.json': args.exploration / 'before.json',
        'after.json': args.final / 'final-scaling.json',
        'before-recovery.json': args.final / 'before-recovery.json',
        'after-recovery.json': args.final / 'final-recovery.json',
        'network-matrix.json': args.final / 'final-matrix.json',
        'after-profile.json': args.final / 'final-profile.json',
        'reset-auth.json': args.final / 'reset-auth.json',
        'active-outages.json': args.final / 'active-outages.json',
        'intermediate-short-heartbeat-scaling.json': args.final / 'after.json',
        'intermediate-short-heartbeat-recovery.json': args.final / 'after-recovery.json',
        'failed-short-heartbeat-matrix.json': args.final / 'network-matrix.json',
        'failed-original-jitter-probe.json': args.final / 'baseline-jitter-probe.json',
        'ineffective-reset-with-normal-client-cid.json': args.final / 'reset-recovery-probe.json',
        'before-profile.json': args.exploration / 'baseline-profile-v2.json',
        'transport-profile.json': args.exploration / 'transport-profile.json',
        'python-client-process-ablation.json': args.exploration / 'process-workers-ablation.json',
        'python-client-and-echo-process-ablation.json': args.final / 'process-echo-baseline.json',
        'python-client-and-echo-process-metadata.json': args.final / 'process-echo-baseline.ablation.json',
        'single-java-loop-ablation.json': args.exploration / 'single-loop-shaded-ablation.json',
        'ineffective-unshaded-property.json': args.exploration / 'single-loop-ablation.json',
        'single-relay-worker-ablation.json': args.exploration / 'relay-one-worker-ablation.json',
        'flush-batching-ablation.json': args.exploration / 'batched-ablation.json',
        'failed-default-eight-connection-limit.json': args.exploration / 'baseline-curve.json',
        'failed-python-profiler.json': args.exploration / 'baseline-profile.json',
        'failed-udp-bridge-restart.json': args.exploration / 'baseline-recovery.json',
        'failed-onedrive-checkpoint.json': args.exploration / 'after.json',
        'machine.json': args.exploration / 'machine.json',
        'candidate-smoke.json': args.final / 'candidate-smoke.json',
    }
    # Refuse to publish a partial inventory when an expected run is missing.
    for path in selected.values():
        if not path.is_file():
            raise FileNotFoundError(path)
    index = {'archived_utc': datetime.now(timezone.utc).isoformat(),
             'transformations': ['parse JSON, redact user home paths, UTF-8/LF serialization; numerical values unchanged',
                                 'JFR copies exclude initial environment, system properties and JVM command metadata; original recordings remain local'], 'files': []}
    for name, source in selected.items():
        original = source.read_bytes()
        data = json.loads(original)
        published = (json.dumps(redact(data, Path.home()), indent=2, ensure_ascii=False) + '\n').encode('utf-8')
        (destination / name).write_bytes(published)
        index['files'].append({'name': name, 'original_sha256': sha(original), 'published_sha256': sha(published),
                               'status': data.get('status', 'metadata')})
    # Preserve original recordings locally. Published copies omit unrelated JVM
    # launch/environment metadata while retaining samples and allocation events.
    for name, source in {
        'before.jfr': args.exploration / 'baseline-profile-v2.jfr',
        'before.pstats': args.exploration / 'baseline-profile-v2.pstats',
        'after.jfr': args.final / 'final-profile.jfr',
        'after.pstats': args.final / 'final-profile.pstats',
    }.items():
        contents = source.read_bytes()
        published = contents
        if source.suffix == '.jfr':
            with tempfile.TemporaryDirectory(prefix='bta-profile-export-') as work:
                target = Path(work) / name
                subprocess.run(['jfr', 'scrub', '--exclude-events',
                    'jdk.InitialEnvironmentVariable,jdk.InitialSystemProperty,jdk.JVMInformation',
                    str(source.resolve()), str(target)], check=True, capture_output=True)
                published = target.read_bytes()
        (destination / name).write_bytes(published)
        index['files'].append({'name': name, 'original_sha256': sha(contents),
                               'published_sha256': sha(published), 'status': 'profile'})
    checks = json.loads((args.final / 'validation-final.json').read_text(encoding='utf-8-sig'))
    required_checks = {'python-tests-final', 'rust-format', 'rust-licenses', 'rust-clippy', 'rust-tests',
                       'java-check-build', 'java-doctor', 'cross-language', 'half-close', 'encrypted-join'}
    assert {check['name'] for check in checks} == required_checks
    assert all(check['exit_code'] == 0 for check in checks)
    index['validation'] = checks
    for name in sorted(required_checks | {'python-tests'}):
        source = args.final / (name + '.txt')
        # A successful formatter emits nothing, so Tee-Object creates no file.
        if not source.exists() and name != 'rust-format':
            raise FileNotFoundError(source)
        original = source.read_bytes() if source.exists() else b''
        published = redact(original.decode('utf-8-sig'), Path.home()).replace('\r\n', '\n').encode('utf-8')
        target = destination / (name + '.txt')
        target.write_bytes(published)
        index['files'].append({'name': target.name, 'original_sha256': sha(original),
                               'published_sha256': sha(published),
                               'status': 'retained initial assertion failure' if name == 'python-tests' else 'PASS'})
    suites = []
    for source in sorted(args.java_results.glob('*/xml/TEST-*.xml')):
        original = source.read_bytes()
        published = redact(original.decode('utf-8'), Path.home()).replace('<user>', '[user]').replace('\r\n', '\n').encode('utf-8')
        name = 'java-tests/' + source.name
        target = destination / name
        target.parent.mkdir(exist_ok=True)
        target.write_bytes(published)
        suite = ET.fromstring(published)
        suites.append(suite)
        index['files'].append({'name': name, 'original_sha256': sha(original), 'published_sha256': sha(published), 'status': 'test results'})
    assert suites
    index['java_tests'] = {key: sum(int(suite.get(key, 0)) for suite in suites) for key in ('tests', 'failures', 'errors', 'skipped')}
    assert index['java_tests']['failures'] == index['java_tests']['errors'] == 0
    repository = ROOT.parents[2]
    implementation = subprocess.check_output(['git', 'diff', '--', 'relay', 'tunnel-client', 'scripts'], cwd=repository)
    (ROOT / 'implementation.patch').write_bytes(implementation)
    source_paths = [
        'relay/src/reset.rs', 'scripts/benchmark_scaling.py', 'scripts/benchmark_network.py',
        'scripts/benchmark_network_matrix.py', 'scripts/benchmark_reset_auth.py',
        'scripts/benchmark_active_outages.py',
        'scripts/local_impairment.py', 'scripts/benchmark_echo_process_ablation.py',
        'scripts/benchmark_relay.py', 'scripts/test_benchmark_network.py',
        'tunnel-client/src/test/java/io/github/kxnar/btaanywhere/internal/HeartbeatDeadlineTest.java',
    ]
    source_files = []
    for name in source_paths:
        contents = (repository / name).read_bytes()
        target = ROOT / 'source' / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(contents)
        source_files.append({'path': name, 'sha256': sha(contents)})
    index['source'] = {'base_commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repository, text=True).strip(),
                       'tracked_patch_sha256': sha(implementation), 'files': source_files}
    index['historical_sources'] = []
    for source in sorted((args.exploration / 'source').glob('*.py')):
        contents = source.read_bytes()
        target = ROOT / 'source' / 'historical' / source.name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(contents)
        index['historical_sources'].append({'path': 'historical/' + source.name, 'sha256': sha(contents)})
    runtime_files = ['Cargo.toml', 'Cargo.lock', 'relay/Cargo.toml', 'relay/src/main.rs',
                     'relay/src/state.rs', 'relay/src/reset.rs', 'tunnel-client/build.gradle.kts',
                     'tunnel-client/src/main/java/io/github/kxnar/btaanywhere/internal/IncomingTunnelHandler.java',
                     'tunnel-client/src/main/java/io/github/kxnar/btaanywhere/internal/NettyTunnelSession.java']
    index['final_runtime_source_sha256'] = {name: sha((repository / name).read_bytes()) for name in runtime_files}
    index['final_artifacts'] = {name: sha(path.read_bytes()) for name, path in {
        'relay.exe': args.exploration / 'final/relay.exe',
        'tunnel.jar': args.exploration / 'final/tunnel-zero-cid.jar'}.items()}
    index['build_commands'] = ['cargo build --locked --release --package bta-anywhere-relay',
                               './gradlew.bat --no-daemon :tunnel-client:shadowJar']
    (ROOT / 'archive-index.json').write_text(json.dumps(index, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
