"""Plot retained Windows soak observations. Requires matplotlib and numpy."""
import argparse
import json
from pathlib import Path

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import numpy as np


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = json.loads(args.input.read_text(encoding='utf-8'))
    soak = result.get('soak') or result['soak_partial']
    samples = [s for s in soak['memory_samples'] if 'relay' in s and 'tunnel' in s]
    load_end = soak.get('load_duration_seconds', soak['duration_seconds'])
    analysis = {'status': result['status'], 'completed_waves': soak['completed_waves'],
                'load_duration_seconds': load_end,
                'cooldown_seconds': soak.get('cooldown_seconds'),
                'valid_process_samples': len(samples),
                'sample_errors': sum('sample_error' in s for s in soak['memory_samples']),
                'observed_cli_reconnect_attempts': result.get('observed_cli_reconnect_attempts'),
                'memory_review': 'Descriptive observations, not proof of absence of leaks.'}
    fig, axes = plt.subplots(3, 1, figsize=(10, 9), sharex=True, layout='constrained')
    colors = {'relay': '#0072B2', 'tunnel': '#D55E00'}
    for ax, role in zip(axes[:2], ('relay', 'tunnel')):
        times = np.array([s['elapsed_seconds'] for s in samples])
        values = np.array([s[role]['working_set_bytes'] / 1048576 for s in samples])
        ax.plot(times / 60, values, color=colors[role], linewidth=1.3)
        cooldown = [(s['elapsed_seconds'] / 60, s[role]['working_set_bytes'] / 1048576)
                    for s in samples if s['phase'] == 'cooldown']
        if cooldown:
            ax.scatter(*zip(*cooldown), color='#222222', marker='D', s=22, label='After load stops')
            ax.legend(loc='best', frameon=False)
        ax.axvline(load_end / 60, color='#666666', linestyle='--', linewidth=.8)
        ax.set_ylabel(f'{role.title()} working set (MiB)')
        ax.set_ylim(bottom=0)
        ax.grid(alpha=.18)
        warm = (times >= 900) & (times <= load_end)
        tail = (times >= max(0, load_end - 600)) & (times <= load_end)
        analysis[role] = {
            'before_load_mib': float(values[0]),
            'observed_max_mib': float(values.max()),
            'last_sample_mib': float(values[-1]),
            'after_15min_median_mib': float(np.median(values[warm])) if warm.any() else None,
            'last_10min_load_median_mib': float(np.median(values[tail])) if tail.any() else None,
            'after_15min_linear_slope_mib_per_hour':
                float(np.polyfit(times[warm] / 3600, values[warm], 1)[0]) if warm.sum() > 1 else None,
            'cpu_seconds_before_load': samples[0][role]['cpu_seconds'],
            'cpu_seconds_final': samples[-1][role]['cpu_seconds'],
            'cooldown_mib': [y for _, y in cooldown],
        }
        regular = [s for s in samples if s['phase'] == 'load']
        if len(regular) > 1:
            intervals = np.diff([s['elapsed_seconds'] for s in regular])
            cpu = np.diff([s[role]['cpu_seconds'] for s in regular])
            valid = intervals > 1
            axes[2].plot(np.array([s['elapsed_seconds'] for s in regular[1:]])[valid] / 60,
                         (cpu / intervals)[valid], label=role.title(), color=colors[role], linewidth=.9)
    axes[2].set_ylabel('CPU seconds / wall second\n(one core = 1)')
    axes[2].set_xlabel('Minutes since soak workload started')
    axes[2].set_ylim(bottom=0)
    axes[2].grid(alpha=.18)
    axes[2].legend(frameon=False)
    fig.suptitle(f"Windows loopback soak — {result['status']}\n"
                 f"8 paced streams; {load_end / 60:.1f} min load; cooldown shown separately", fontsize=13)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(args.output, dpi=160)
    plt.close(fig)
    args.output.with_suffix('.analysis.json').write_text(json.dumps(analysis, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(analysis, indent=2))


if __name__ == '__main__':
    main()
