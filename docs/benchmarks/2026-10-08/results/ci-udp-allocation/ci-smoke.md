# BTA Anywhere loopback benchmark

Status: **PASS**; profile: `smoke`; schema: 9.

Windows x86-64, release relay and shaded tunnel.
Harness source: `800bdccb7e185a8e95ab31c46a772aab0541b1c5` (dirty=False).
Relay artifact source: `800bdccb7e185a8e95ab31c46a772aab0541b1c5` (dirty=False).
Tunnel artifact source: `800bdccb7e185a8e95ab31c46a772aab0541b1c5` (dirty=False).
Artifact SHA-256: relay `f4842cf65e73cd75c6bc88824452a10e61491b9df6e93cfaa7c03814cc45aaeb`; tunnel `7c9160947275629778a5a3a18e820bea9991474c01f7dee20daa7b2a5de10303`.
Source revisions are inferred from the containing Git checkouts; the artifact hashes identify the measured files.

Latency is transaction completion time: TCP connection, request, verified reply and EOF.
Warmups are excluded. p99 is exploratory and remains in JSON.

| Streams | Size | Mode | Direct n | Direct p50 ms | Direct p95 ms | Relay n | Relay p50 ms | Relay p95 ms | Paired delta n | Paired delta p95 ms |
|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 1024 | request_response | 3 | 1.33 | 1.49 | 3 | 9.27 | 22.07 | 3 | 20.72 |
| 1 | 1024 | half_close | 3 | 1.15 | 1.42 | 3 | 5.38 | 5.82 | 3 | 4.41 |
| 1 | 65536 | request_response | 3 | 1.04 | 1.23 | 3 | 19.81 | 45.74 | 3 | 44.51 |
| 1 | 65536 | half_close | 3 | 1.13 | 1.19 | 3 | 28.26 | 34.91 | 3 | 33.78 |
| 1 | 1048576 | request_response | 3 | 5.71 | 6.22 | 3 | 79.70 | 180.79 | 3 | 175.04 |
| 1 | 1048576 | half_close | 3 | 16.93 | 22.80 | 3 | 91.96 | 136.56 | 3 | 113.92 |
| 8 | 1024 | request_response | 24 | 4.35 | 5.97 | 24 | 10.40 | 16.22 | 24 | 11.91 |
| 8 | 1024 | half_close | 24 | 4.21 | 5.47 | 24 | 13.98 | 24.69 | 24 | 19.62 |
| 8 | 65536 | request_response | 24 | 5.27 | 5.73 | 24 | 28.61 | 39.67 | 24 | 33.95 |
| 8 | 65536 | half_close | 24 | 4.89 | 21.85 | 24 | 17.52 | 22.11 | 24 | 17.49 |
| 8 | 1048576 | request_response | 24 | 38.55 | 46.69 | 24 | 462.20 | 609.87 | 24 | 565.50 |
| 8 | 1048576 | half_close | 24 | 36.94 | 42.84 | 24 | 383.85 | 508.05 | 24 | 474.42 |

Paired delta is relay minus direct for each matched stream and wave; its p95 is calculated from those differences, rather than subtracting the two p95 values.

| Streams | Direct G→H MiB/s | Relay G→H MiB/s | Direct H→G MiB/s | Relay H→G MiB/s |
|---:|---:|---:|---:|---:|
| 1 | 657.19 | 24.47 | 657.19 | 24.47 |
| 8 | 641.29 | 5.09 | 641.29 | 5.09 |

Throughput uses an echo workload. Each direction is reported separately; the two directions are not summed into an independent full-duplex capacity claim.
Results are synthetic loopback observations, not WAN or player capacity.
The local benchmark config permits 10,000 accepts/minute; production defaults are unchanged.
CPU/memory samples, reconnect, EOF/byte checks, soak details and failure diagnostics are in JSON.

Tunnel-process restart: available=True; old port=62321; new port=62322; endpoint retained=False; completion=874.35 ms.
Tunnel-link UDP drop: 55 s; recovered=True; same bridge-backed endpoint=True; resume event observed=False; completion=59393.12 ms.
