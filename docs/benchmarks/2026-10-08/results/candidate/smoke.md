# BTA Anywhere loopback benchmark

Status: **PASS**; profile: `smoke`; schema: 9.

Windows x86-64, release relay and shaded tunnel.
Harness source: `70adbce3c274ebe0a19f27bdf4cfa4e647cba239` (dirty=False).
Relay artifact source: `70adbce3c274ebe0a19f27bdf4cfa4e647cba239` (dirty=False).
Tunnel artifact source: `70adbce3c274ebe0a19f27bdf4cfa4e647cba239` (dirty=False).
Artifact SHA-256: relay `ac9f10a8ab606a5aa24c8f3586fb31765bf818af86b7aece1fcba9d8e210e105`; tunnel `7c9160947275629778a5a3a18e820bea9991474c01f7dee20daa7b2a5de10303`.
Source revisions are inferred from the containing Git checkouts; the artifact hashes identify the measured files.

Latency is transaction completion time: TCP connection, request, verified reply and EOF.
Warmups are excluded. p99 is exploratory and remains in JSON.

| Streams | Size | Mode | Direct n | Direct p50 ms | Direct p95 ms | Relay n | Relay p50 ms | Relay p95 ms | Paired delta n | Paired delta p95 ms |
|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 1024 | request_response | 3 | 15.60 | 15.63 | 3 | 3.92 | 12.08 | 3 | 1.88 |
| 1 | 1024 | half_close | 3 | 14.73 | 15.49 | 3 | 14.66 | 17.66 | 3 | 2.17 |
| 1 | 65536 | request_response | 3 | 0.61 | 21.69 | 3 | 30.01 | 41.78 | 3 | 40.66 |
| 1 | 65536 | half_close | 3 | 15.36 | 15.87 | 3 | 20.41 | 42.22 | 3 | 27.73 |
| 1 | 1048576 | request_response | 3 | 4.69 | 8.63 | 3 | 32.74 | 35.18 | 3 | 30.51 |
| 1 | 1048576 | half_close | 3 | 4.17 | 22.31 | 3 | 27.57 | 35.52 | 3 | 23.42 |
| 8 | 1024 | request_response | 24 | 1.94 | 12.92 | 24 | 4.56 | 12.58 | 24 | 3.71 |
| 8 | 1024 | half_close | 24 | 1.80 | 2.16 | 24 | 3.51 | 3.93 | 24 | 2.20 |
| 8 | 65536 | request_response | 24 | 2.13 | 2.64 | 24 | 14.66 | 16.42 | 24 | 14.15 |
| 8 | 65536 | half_close | 24 | 2.15 | 2.45 | 24 | 13.21 | 15.94 | 24 | 13.87 |
| 8 | 1048576 | request_response | 24 | 26.88 | 29.49 | 24 | 148.99 | 189.16 | 24 | 163.68 |
| 8 | 1048576 | half_close | 24 | 23.14 | 25.42 | 24 | 158.87 | 190.06 | 24 | 165.23 |

Paired delta is relay minus direct for each matched stream and wave; its p95 is calculated from those differences, rather than subtracting the two p95 values.

| Streams | Direct G→H MiB/s | Relay G→H MiB/s | Direct H→G MiB/s | Relay H→G MiB/s |
|---:|---:|---:|---:|---:|
| 1 | 684.84 | 58.90 | 684.84 | 58.90 |
| 8 | 1500.26 | 26.74 | 1500.26 | 26.74 |

Throughput uses an echo workload. Each direction is reported separately; the two directions are not summed into an independent full-duplex capacity claim.
Results are synthetic loopback observations, not WAN or player capacity.
The local benchmark config permits 10,000 accepts/minute; production defaults are unchanged.
CPU/memory samples, reconnect, EOF/byte checks, soak details and failure diagnostics are in JSON.

Tunnel-process restart: available=True; old port=55741; new port=55742; endpoint retained=False; completion=568.48 ms.
Tunnel-link UDP drop: 55 s; recovered=True; same bridge-backed endpoint=True; resume event observed=True; completion=58055.01 ms.
