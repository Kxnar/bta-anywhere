# BTA Anywhere loopback benchmark

Status: **PASS**; profile: `full`; schema: 9.

Windows x86-64, release relay and shaded tunnel.
Harness source: `b7961e1c87d953a28818a9a78bb0223a2d3ceb5d` (dirty=False).
Relay artifact source: `b7961e1c87d953a28818a9a78bb0223a2d3ceb5d` (dirty=False).
Tunnel artifact source: `b7961e1c87d953a28818a9a78bb0223a2d3ceb5d` (dirty=False).
Artifact SHA-256: relay `ac9f10a8ab606a5aa24c8f3586fb31765bf818af86b7aece1fcba9d8e210e105`; tunnel `7c9160947275629778a5a3a18e820bea9991474c01f7dee20daa7b2a5de10303`.
Source revisions are inferred from the containing Git checkouts; the artifact hashes identify the measured files.

Latency is transaction completion time: TCP connection, request, verified reply and EOF.
Warmups are excluded. p99 is exploratory and remains in JSON.

| Streams | Size | Mode | Direct n | Direct p50 ms | Direct p95 ms | Relay n | Relay p50 ms | Relay p95 ms | Paired delta n | Paired delta p95 ms |
|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 1024 | request_response | 30 | 14.64 | 15.68 | 30 | 16.48 | 17.85 | 30 | 15.72 |
| 1 | 1024 | half_close | 30 | 15.60 | 15.81 | 30 | 15.69 | 16.66 | 30 | 14.14 |
| 1 | 65536 | request_response | 30 | 15.19 | 15.83 | 30 | 18.42 | 44.58 | 30 | 42.22 |
| 1 | 65536 | half_close | 30 | 14.47 | 15.40 | 30 | 17.11 | 26.05 | 30 | 17.73 |
| 1 | 1048576 | request_response | 30 | 20.00 | 41.07 | 30 | 34.43 | 47.56 | 30 | 32.03 |
| 1 | 1048576 | half_close | 30 | 18.96 | 37.01 | 30 | 32.78 | 44.60 | 30 | 35.55 |
| 8 | 1024 | request_response | 240 | 1.96 | 2.52 | 240 | 2.58 | 3.12 | 240 | 1.36 |
| 8 | 1024 | half_close | 240 | 1.97 | 2.81 | 240 | 2.46 | 2.91 | 240 | 1.25 |
| 8 | 65536 | request_response | 240 | 2.23 | 2.77 | 240 | 9.87 | 12.73 | 240 | 10.78 |
| 8 | 65536 | half_close | 240 | 2.15 | 2.60 | 240 | 10.00 | 12.46 | 240 | 10.24 |
| 8 | 1048576 | request_response | 240 | 26.37 | 30.23 | 240 | 159.63 | 194.12 | 240 | 166.71 |
| 8 | 1048576 | half_close | 240 | 23.48 | 28.56 | 240 | 166.94 | 202.59 | 240 | 179.69 |

Paired delta is relay minus direct for each matched stream and wave; its p95 is calculated from those differences, rather than subtracting the two p95 values.

| Streams | Direct G→H MiB/s | Relay G→H MiB/s | Direct H→G MiB/s | Relay H→G MiB/s |
|---:|---:|---:|---:|---:|
| 1 | 788.80 | 59.34 | 788.80 | 59.34 |
| 8 | 1409.77 | 24.68 | 1409.77 | 24.68 |

Throughput uses an echo workload. Each direction is reported separately; the two directions are not summed into an independent full-duplex capacity claim.
Results are synthetic loopback observations, not WAN or player capacity.
The local benchmark config permits 10,000 accepts/minute; production defaults are unchanged.
CPU/memory samples, reconnect, EOF/byte checks, soak details and failure diagnostics are in JSON.

Tunnel-process restart: available=True; old port=57559; new port=57560; endpoint retained=False; completion=566.58 ms.
Tunnel-link UDP drop: 55 s; recovered=True; same bridge-backed endpoint=True; resume event observed=True; completion=57345.63 ms.
