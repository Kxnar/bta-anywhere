"""Extract sample counts, not CPU percentages, from retained JFR recordings."""
import collections
import json
from pathlib import Path
import pstats
import subprocess

ROOT = Path(__file__).resolve().parent


def main():
    output = {'caveats': [
        'JFR leaf samples include native waits and are not process CPU percentages.',
        'cProfile elapsed attribution includes waits and overlapping Python 3.14 thread activity; cumulative call trees are not CPU attribution.',
        'Profiler runs are separate from unprofiled throughput comparisons.',
    ], 'builds': {}}
    for build in ('before', 'after'):
        raw = subprocess.check_output(['jfr', 'print', '--json', '--events',
            'jdk.ExecutionSample,jdk.NativeMethodSample,jdk.ObjectAllocationSample', str(ROOT / 'results' / (build + '.jfr'))])
        events = json.loads(raw)['recording']['events']
        leaf = collections.Counter()
        threads = collections.Counter()
        kinds = collections.Counter()
        allocations = collections.Counter()
        for event in events:
            kinds[event['type']] += 1
            value = event['values']
            if event['type'] == 'jdk.ObjectAllocationSample':
                allocations[value['objectClass']['name']] += value['weight']
                continue
            threads[value.get('sampledThread', {}).get('javaName', 'unknown')] += 1
            frames = (value.get('stackTrace') or {}).get('frames', [])
            if frames:
                method = frames[0]['method']
                leaf[method['type']['name'] + '.' + method['name']] += 1
        stats = pstats.Stats(str(ROOT / 'results' / (build + '.pstats')))
        calls = []
        for (file, line, name), (primitive, total, own, cumulative, callers) in sorted(stats.stats.items(), key=lambda item: item[1][2], reverse=True)[:20]:
            calls.append({'file': Path(file).name, 'line': line, 'function': name, 'calls': total,
                          'profiled_self_elapsed_seconds': own})
        output['builds'][build] = {'sample_kinds': dict(kinds), 'leaf_samples': leaf.most_common(20),
                                  'thread_samples': threads.most_common(20), 'python_calls': calls,
                                  'sampled_allocation_weights_bytes': allocations.most_common(20)}
    (ROOT / 'profile-summary.json').write_text(json.dumps(output, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
