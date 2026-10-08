"""Render measured progress, transport state and every restart sample."""
import json
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

ROOT = Path(__file__).resolve().parent


def read(name):
    return json.loads((ROOT / 'results' / (name + '.json')).read_text())


def main():
    fig, axes = plt.subplots(2, 2, figsize=(12, 8), layout='constrained')
    for name, label in [('jitter-saturated', 'Jitter: deadline failure'),
                        ('jitter-saturated-180', 'Jitter: completed'),
                        ('jitter-ordered', 'Ordered jitter control'),
                        ('loss-saturated', 'Jitter + 1% loss: failure')]:
        data = read(name)
        samples = data['samples']
        axes[0, 0].plot([s['at_seconds'] for s in samples],
                       [sum(r['guest_receive_bytes'] for r in s['progress']) / 1048576 for s in samples], label=label)
    axes[0, 0].axvline(38, color='gray', linestyle=':', label='Former 38s deadline (approx.)')
    axes[0, 0].set(xlabel='Seconds from rig startup', ylabel='Observed echoed MiB', title='Progress and retained incomplete transfers')
    axes[0, 0].legend(fontsize=8)
    for name, label in [('jitter-saturated', 'Jitter: deadline failure'),
                        ('jitter-ordered', 'Ordered jitter control')]:
        rows = [r for r in read(name)['diagnostics'] if r['kind'] == 'java_connection']
        axes[0, 1].plot([r['at_seconds'] for r in rows], [r['cwnd'] / 1024 for r in rows], label=label)
    axes[0, 1].set(xlabel='Seconds from rig startup', ylabel='Java congestion window (KiB)', yscale='log', title='Native transport window shrinks under reordering')
    axes[0, 1].legend(fontsize=8)
    recovery = read('restarts-25')
    rows = [r for r in recovery['rows'] if r['status'] == 'PASS']
    axes[1, 0].scatter([r['observed_phase_seconds'] for r in rows], [r['fault_to_verified_ms'] / 1000 for r in rows])
    axes[1, 0].set(xlabel='Fault offset after registration (s)', ylabel='Fault to verified fresh request (s)', title='Every successful phase-stratified restart')
    values = sorted(r['fault_to_verified_ms'] / 1000 for r in rows)
    axes[1, 1].step(values, [(i + 1) / 25 for i in range(len(values))], where='post')
    axes[1, 1].set(xlabel='Fault to verified fresh request (s)', ylabel='Fraction of 25 trials recovered', ylim=(0, 1.05), title='Empirical recovery distribution')
    for axis in axes.flat:
        axis.grid(alpha=.2)
    fig.suptitle('Windows loopback diagnosis — independent runs; no WAN or production capacity claim', fontsize=12)
    fig.savefig(ROOT / 'diagnosis.png', dpi=160)
    plt.close(fig)


if __name__ == '__main__':
    main()
