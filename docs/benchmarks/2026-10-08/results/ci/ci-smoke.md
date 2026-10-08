# BTA Anywhere loopback benchmark

Status: **PASS**; profile: `smoke`; schema: 9.

Windows x86-64, release relay and shaded tunnel.
Harness source: `b7961e1c87d953a28818a9a78bb0223a2d3ceb5d` (dirty=False).
Relay artifact source: `b7961e1c87d953a28818a9a78bb0223a2d3ceb5d` (dirty=False).
Tunnel artifact source: `b7961e1c87d953a28818a9a78bb0223a2d3ceb5d` (dirty=False).
Artifact SHA-256: relay `817ecfc220e076a88c92d3606f2d54701c46b1e0a919cbaa046dccb34184f572`; tunnel `7c9160947275629778a5a3a18e820bea9991474c01f7dee20daa7b2a5de10303`.
Source revisions are inferred from the containing Git checkouts; the artifact hashes identify the measured files.

Latency is transaction completion time: TCP connection, request, verified reply and EOF.
Warmups are excluded. p99 is exploratory and remains in JSON.

| Streams | Size | Mode | Direct n | Direct p50 ms | Direct p95 ms | Relay n | Relay p50 ms | Relay p95 ms | Paired delta n | Paired delta p95 ms |
|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 1024 | request_response | 3 | 0.54 | 0.65 | 3 | 16.91 | 24.86 | 3 | 24.21 |
| 1 | 1024 | half_close | 3 | 0.48 | 13.80 | 3 | 15.21 | 16.03 | 3 | 13.55 |
| 1 | 65536 | request_response | 3 | 0.50 | 0.58 | 3 | 9.37 | 19.74 | 3 | 19.23 |
| 1 | 65536 | half_close | 3 | 0.49 | 0.50 | 3 | 19.90 | 28.42 | 3 | 27.95 |
| 1 | 1048576 | request_response | 3 | 18.23 | 22.80 | 3 | 47.50 | 55.94 | 3 | 37.61 |
| 1 | 1048576 | half_close | 3 | 4.08 | 30.46 | 3 | 35.25 | 42.79 | 3 | 38.73 |
| 8 | 1024 | request_response | 24 | 2.00 | 2.54 | 24 | 5.62 | 8.30 | 24 | 6.22 |
| 8 | 1024 | half_close | 24 | 2.01 | 2.44 | 24 | 4.95 | 15.08 | 24 | 13.23 |
| 8 | 65536 | request_response | 24 | 2.38 | 3.26 | 24 | 9.00 | 11.37 | 24 | 8.66 |
| 8 | 65536 | half_close | 24 | 2.47 | 3.02 | 24 | 10.21 | 19.77 | 24 | 16.82 |
| 8 | 1048576 | request_response | 24 | 21.46 | 24.88 | 24 | 148.35 | 211.23 | 24 | 192.17 |
| 8 | 1048576 | half_close | 24 | 20.16 | 22.93 | 24 | 103.36 | 157.05 | 24 | 136.27 |

Paired delta is relay minus direct for each matched stream and wave; its p95 is calculated from those differences, rather than subtracting the two p95 values.

| Streams | Direct G→H MiB/s | Relay G→H MiB/s | Direct H→G MiB/s | Relay H→G MiB/s |
|---:|---:|---:|---:|---:|
| 1 | 1715.73 | 98.46 | 1715.73 | 98.46 |
| 8 | 1750.39 | 20.00 | 1750.39 | 20.00 |

Throughput uses an echo workload. Each direction is reported separately; the two directions are not summed into an independent full-duplex capacity claim.
Results are synthetic loopback observations, not WAN or player capacity.
The local benchmark config permits 10,000 accepts/minute; production defaults are unchanged.
CPU/memory samples, reconnect, EOF/byte checks, soak details and failure diagnostics are in JSON.

Tunnel-process restart: available=True; old port=50220; new port=50221; endpoint retained=False; completion=1955.14 ms.
Tunnel-link UDP drop: 55 s; recovered=True; same bridge-backed endpoint=True; resume event observed=False; completion=60628.05 ms.
