# Linux netem validation — 8 October 2026

All **nine independent sessions passed**: three clean, three with jitter, and
three with jitter plus nominal 1% loss. They completed **540 byte-verified
transactions**, **72 concurrent throughput streams**, and **9/9 relay-restart
recoveries** on their original TCP endpoints. No workload failure, missing EOF,
byte mismatch or cleanup failure was recorded.

These are synthetic kernel network-impairment measurements on GitHub-hosted
Linux runners. They establish execution beyond Windows userspace loopback;
they do not establish a physical two-machine network, NAT traversal, WAN
performance, multiplayer gameplay, or a population failure rate.

## Reproduction and provenance

[Actions run 37836851753](https://github.com/Kxnar/bta-anywhere/actions/runs/37836851753)
passed at source commit `921440abbe5d95b4cf5b40e3d1d71e944cb00446`.
The retained run metadata and full job log are in `results/run.json` and
`results/job-logs.txt`. Each condition used its own Ubuntu 24.04 runner, with
three fresh relay/tunnel processes measured sequentially. All builds and tests
finished before measurement. No Windows and Linux timing samples are pooled.

The runners reported Linux `6.17.0-1022-azure`, x86-64, glibc 2.39, Python 3.12.3,
Temurin Java 21.0.12.1 and iproute2 6.1.0. Rust 1.85 built the release relay with
the checked-in lockfile. The Java tunnel used Netty core 4.1.122 and native QUIC
0.0.75. The Linux-native JAR loaded successfully. Java verification, the three
qlog analyzer tests and all 69 benchmark tests passed in each runner before the
campaign. This tunnel-only Linux build does not test the Windows game mod.

Follow the [full setup and methodology](../../linux-netem.md). To verify this
archive without a Linux runner:

```powershell
python scripts/verify_netem_archive.py docs/benchmarks/2026-10-08-linux
```

The verifier checks each manifest hash, the exact measured harness source
against its Git blob, all successful byte/EOF claims, process cleanup, and
the presence and traffic counters of the configured netem qdiscs. `summary.json`
contains the derived results, including all six latency cells per session and
CPU deltas grouped by process PID. The original binaries are identified by
SHA-256 in every session result; they are not redistributed in this archive.

## Topology and measurement

Two disposable network namespaces are joined by a veth pair. The Rust relay
lives in one. The synthetic guest, Java tunnel and local echo server live in
the other. A protocol filter impairs QUIC UDP in both directions; guest TCP
and admin HTTP bypass netem. The host's ordinary interfaces and routes are
untouched, and both test namespaces are removed after the run.

Clean has no impairment. Jitter applies a nominal 10 ms delay, 2 ms normal
jitter and a 20 Mbit/s rate in each UDP direction. Loss adds nominal 1% random
loss. The queue limit is 1,000 packets. Kernel random schedules are unseeded,
so these are independent sessions, not identical packet replays. Final qdisc
statistics confirm traffic traversed the configured queues. Offload settings
were not captured; segmentation and scheduling can affect packet counters and
delivery timing. Treat the parameters as nominal, not exact observed loss/delay.

Each session measures ten request/reply and ten half-close transactions at
each of 1 KiB, 64 KiB and 1 MiB: 60 total. Latency includes connection setup,
request, exact reply verification and EOF. The eight throughput streams use
64 KiB blocks, an eight-second send budget and a fixed 120-second drain budget.
Throughput divides verified payload bytes by the entire concurrent phase,
including drain. Echoed directions are reported separately, not added into a
full-duplex capacity claim. The results include 200,724,480 transaction bytes
and 897,318,912 throughput bytes **in each direction**, excluding recovery probes.

## Per-session results

| Condition / session | 1 KiB request p50 (ms) | 1 MiB half-close p50 (ms) | Verified MiB/s each way | Restart recovery (s) | Relay / Java peak sampled RSS (MiB) |
| --- | ---: | ---: | ---: | ---: | ---: |
| clean / 1 | 2.98 | 24.90 | 27.15 | 3.88 | 20.4 / 158.6 |
| clean / 2 | 2.85 | 26.29 | 27.53 | 4.48 | 14.2 / 161.9 |
| clean / 3 | 2.87 | 29.25 | 26.38 | 2.97 | 15.5 / 166.6 |
| jitter / 1 | 26.45 | 915.07 | 1.65 | 6.71 | 23.4 / 139.7 |
| jitter / 2 | 26.42 | 913.97 | 1.84 | 11.88 | 21.8 / 135.1 |
| jitter / 3 | 26.92 | 913.63 | 1.54 | 12.68 | 25.5 / 149.5 |
| loss / 1 | 26.92 | 1042.61 | 0.95 | 4.55 | 12.9 / 133.3 |
| loss / 2 | 27.13 | 1113.44 | 1.05 | 3.25 | 12.6 / 124.4 |
| loss / 3 | 28.31 | 1139.19 | 0.97 | 4.75 | 12.5 / 130.1 |

Each displayed latency median has ten transactions. Per-cell p95 values are
retained in the summary but are descriptive with this small sample. The three
sessions per condition, rather than the eight simultaneous streams, provide
independent repetitions. Comparing the clean runner with the impaired runners
is descriptive: different hosted machines and run schedules are a confounder.

Restart recovery is fault-to-first-byte-verified 1 KiB request/EOF on the old
endpoint, within a fixed 100-second observation budget. Failed probes and their
denominators remain in each session JSON. Timing ranges from **2.97 to 12.68
seconds** across these nine observations. Jitter happened to recover more slowly
than the loss condition here; the sample does not support a claim that adding
loss improves recovery. Existing TCP/game sessions are not claimed to survive.

Resource samples every 500 ms record `/proc` CPU seconds and RSS. Peak sampled
relay RSS ranges from 12.5–25.5 MiB and Java RSS from 124.4–166.6 MiB. These are
observed resident sets, not process memory caps, leak tests or an allocation
bound. CPU deltas in the summary only span samples of the same PID; replacement
relays are separate entries. Samples omit CPU used before the first or after
the last sample and should not be treated as exact whole-process accounting.
Across sessions, summed observed CPU deltas range from 2.31–4.67 CPU-seconds
for the relay processes and 5.24–12.45 CPU-seconds for Java. These totals cover
different wall durations and should not be read as comparable utilisation rates.

## Scope still pending

The local WSL installation could not enumerate its distributions, but hosted
Linux infrastructure was available and completed this campaign. A physical
two-machine or deployed WAN run remains unperformed. The separate two-client
game demonstration is still awaiting authenticated client sessions; these echo
results do not replace it. See the [overall validation status](../../validation-status.md).
