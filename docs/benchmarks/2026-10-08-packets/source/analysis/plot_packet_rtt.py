#!/usr/bin/env python3
"""Plot native connection RTT against packet-log matched ACK timing."""
import argparse
import gzip
import json
from pathlib import Path

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt


def series(path):
    native, ack, sent = [], [], {}
    with gzip.open(path, 'rb') as stream:
        for raw in stream:
            try:
                event = json.loads(raw.lstrip(b'\x1e'))
            except json.JSONDecodeError:
                if not raw.endswith(b'\n'):
                    break
                raise
            name = event.get('name')
            if name is None:
                continue
            at, data = event['time'], event['data']
            if name == 'recovery:metrics_updated' and 'smoothed_rtt' in data:
                native.append((at / 1000, data['smoothed_rtt']))
            elif name == 'transport:packet_sent':
                h = data['header']
                sent[h['packet_type'], h['packet_number']] = at
            elif name == 'transport:packet_received':
                for frame in data.get('frames', []):
                    if frame['frame_type'] == 'ack':
                        key = data['header']['packet_type'], max(r[-1] for r in frame['acked_ranges'])
                        if key in sent:
                            ack.append((at / 1000, at - sent[key]))
    return native, ack


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    args = parser.parse_args()
    fig, axes = plt.subplots(1, 2, figsize=(10, 4.1), sharey=True)
    for axis, variant, folder in zip(axes, ('0.0.73 baseline', '0.0.75 candidate'),
                                    ('jitter-saturated-qlog-v1.qlog', 'jitter-saturated-075-qlog-1.qlog')):
        paths = list((args.archive / 'captures' / folder).glob('*.gz'))
        assert len(paths) == 1
        native, ack = series(paths[0])
        axis.plot(*zip(*native), color='#ba352b', linewidth=1.5, label='Native smoothed RTT')
        # Plot positive native-log intervals only; zero-time coalesced events
        # cannot be placed on a logarithmic axis.
        positive = [(x, y) for x, y in ack if y > 0]
        axis.scatter(*zip(*positive), s=2, alpha=.25, color='#226a8a', label='Logged send to ACK')
        axis.set(title=variant, xlabel='Seconds since capture start', yscale='log', ylim=(1, 200000))
        axis.grid(alpha=.18)
    axes[0].set_ylabel('Milliseconds (log scale)')
    axes[1].legend(loc='upper left', fontsize=8)
    fig.suptitle('Saturated jitter: native RTT divergence disappears in the upgraded capture', fontsize=12)
    fig.text(.08, .025, 'Windows userspace bridge, 10 ± 2 ms each way, 8 streams. Log timestamps are not wire timestamps.', fontsize=8)
    fig.tight_layout(rect=(0, .055, 1, .95))
    fig.savefig(args.archive / 'rtt-comparison.png', dpi=180)
    plt.close(fig)


if __name__ == '__main__':
    main()
