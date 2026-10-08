"""Render the measured medians and every repetition; requires matplotlib."""
import json
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

ROOT = Path(__file__).resolve().parent
data = json.loads((ROOT / 'summary.json').read_text(encoding='utf-8'))
fig, axes = plt.subplots(1, 2, figsize=(11, 4.4), gridspec_kw={'width_ratios': [1.45, 1]})
colors = {'before': '#536579', 'after': '#007b74'}
for build in ('before', 'after'):
    rows = data['scaling']
    axes[0].plot(range(5), [row[f'{build}_mib_s'] for row in rows], color=colors[build],
                 label=build.capitalize(), linewidth=2)
    for index, row in enumerate(rows):
        axes[0].scatter([index - .07, index, index + .07], row[f'{build}_samples'],
                        color=colors[build], s=23, alpha=.75)
axes[0].set_xticks(range(5), ['1', '2', '4', '8', '16'])
axes[0].set_xlabel('Concurrent streams in one tunnel session')
axes[0].set_ylabel('Verified throughput per direction (MiB/s)')
axes[0].set_title('Identical 10-second echo workloads')
axes[0].legend(frameon=False)
axes[0].set_ylim(bottom=0)
for index, build in enumerate(('before', 'after')):
    row = next(row for row in data['recovery'] if row['build'] == build and row['condition'] == 'bridge-zero')
    axes[1].scatter([index - .07, index, index + .07], row['samples_seconds'], color=colors[build], s=40)
    axes[1].plot([index - .22, index + .22], [row['median_seconds']] * 2, color=colors[build], linewidth=2)
axes[1].set_xticks([0, 1], ['Before', 'After'])
axes[1].set_ylabel('Fault to byte-verified recovery (seconds)')
axes[1].set_title('Relay restarts through clean local bridge')
axes[1].set_ylim(bottom=0)
for axis in axes:
    axis.spines[['top', 'right']].set_visible(False)
    axis.grid(axis='y', alpha=.18)
fig.suptitle('BTA Anywhere · Windows loopback measurements', fontsize=14, fontweight='bold')
fig.text(.5, .01, 'Three repetitions per point/build. Dots show every sample; lines show medians. No WAN or confidence-interval claim.',
         ha='center', fontsize=9)
fig.tight_layout(rect=(0, .05, 1, .94))
fig.savefig(ROOT / 'measured-results.png', dpi=160)
fig.savefig(ROOT / 'measured-results.svg')
