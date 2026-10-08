| Streams | Before MiB/s | After MiB/s | Change | Before range | After range |
|---:|---:|---:|---:|---:|---:|
| 1 | 53.16 | 53.24 | +0.2% | 52.58–53.44 | 52.70–54.40 |
| 2 | 43.69 | 48.93 | +12.0% | 42.14–44.05 | 48.57–48.97 |
| 4 | 29.99 | 39.34 | +31.2% | 29.40–30.26 | 38.74–39.53 |
| 8 | 19.65 | 27.04 | +37.6% | 19.64–21.17 | 26.99–27.24 |
| 16 | 17.91 | 22.11 | +23.5% | 17.69–18.62 | 19.18–22.21 |

Medians and full ranges of three repetitions; no confidence interval is implied.

| Eight-stream CPU seconds per verified MiB | Before | After |
|---|---:|---:|
| relay | 0.0535 | 0.0360 |
| tunnel | 0.1072 | 0.0368 |
| python | 0.0764 | 0.0348 |

| Restart condition | Build | Fault to verified recovery, median (range), s | Detection, median, s | First retry, median, s | Final registration, median, ms |
|---|---|---:|---:|---:|---:|
| bridge-zero | before | 33.19 (33.09–48.52) | 30.04 | 1.01 | 213.7 |
| bridge-zero | after | 5.37 (4.88–18.15) | 1.91 | 1.21 | 214.1 |
| delay-10ms | after | 18.72 (4.90–19.31) | 14.96 | 1.02 | 312.8 |
| jitter-10ms-2ms | after | 18.70 (5.07–19.31) | 14.75 | 1.11 | 369.1 |
| loss-10ms-2ms-1pct | after | 6.01 (5.38–18.95) | 1.94 | 1.14 | 359.2 |

Matched zero-impairment bridge restart median reduction: **83.8%**.

| Matrix condition | Streams | Successful cells | Median verified MiB/s of successes | Transaction p50 / p95 range, ms |
|---|---:|---:|---:|---:|
| bridge-zero | 1 | 2/2 | 22.872 | 2.5–3.9 / 21.1–21.2 |
| bridge-zero | 8 | 2/2 | 14.779 | 9.1–9.3 / 10.8–12.8 |
| delay-10ms | 1 | 2/2 | 1.115 | 30.7–44.5 / 45.0–46.3 |
| delay-10ms | 8 | 2/2 | 1.382 | 44.6–44.9 / 45.5–46.7 |
| jitter-10ms-2ms | 1 | 2/2 | 0.290 | 45.5–46.1 / 46.4–46.8 |
| jitter-10ms-2ms | 8 | 0/2 | FAILED | 45.0–46.3 / 77.3–87.9 |
| loss-10ms-2ms-1pct | 1 | 2/2 | 0.137 | 37.1–45.3 / 45.0–62.4 |
| loss-10ms-2ms-1pct | 8 | 0/2 | FAILED | 45.0–45.1 / 71.7–78.5 |

Failed throughput cells are not zero throughput and are not included in successful-rate medians.
Latency was measured before saturation; a successful latency phase does not make a failed throughput cell pass.

Full sample counts, bridge counters, achieved queue delays and every recovery attempt are in the linked JSON.
