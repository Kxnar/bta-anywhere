# BTA Anywhere loopback benchmark

Status: **PASS**; profile: `smoke`; schema: 8.

Windows x86-64, release relay and shaded tunnel.
Harness source: `5e04359fae7b1bbe2be2104f5e61a2f3980fd12a` (dirty=True).
Relay artifact source: `5e04359fae7b1bbe2be2104f5e61a2f3980fd12a` (dirty=True).
Tunnel artifact source: `5e04359fae7b1bbe2be2104f5e61a2f3980fd12a` (dirty=True).
Artifact SHA-256: relay `ac9f10a8ab606a5aa24c8f3586fb31765bf818af86b7aece1fcba9d8e210e105`; tunnel `5f758ea359ab3f165aa0dbd89f98bbf96e7be70042a14cd49ca0aea6e252c4ff`.
Source revisions are inferred from the containing Git checkouts; the artifact hashes identify the measured files.

| Streams | Size | Mode | Direct p95 ms | Relay p95 ms | Relay added p95 ms |
|---:|---:|---|---:|---:|---:|
| 1 | 1024 | request_response | 0.79 | 3.84 | 3.22 |
| 1 | 1024 | half_close | 15.23 | 15.74 | 2.70 |
| 1 | 65536 | request_response | 0.63 | 17.61 | 17.07 |
| 1 | 65536 | half_close | 14.24 | 40.05 | 39.47 |
| 1 | 1048576 | request_response | 5.03 | 49.02 | 44.13 |
| 1 | 1048576 | half_close | 24.05 | 33.99 | 21.34 |
| 8 | 1024 | request_response | 2.48 | 6.73 | 4.71 |
| 8 | 1024 | half_close | 2.37 | 4.69 | 2.83 |
| 8 | 65536 | request_response | 2.49 | 17.15 | 14.92 |
| 8 | 65536 | half_close | 2.61 | 17.98 | 15.63 |
| 8 | 1048576 | request_response | 28.73 | 189.64 | 168.15 |
| 8 | 1048576 | half_close | 25.56 | 179.06 | 156.40 |

| Streams | Direct G→H MiB/s | Relay G→H MiB/s | Direct H→G MiB/s | Relay H→G MiB/s |
|---:|---:|---:|---:|---:|
| 1 | 627.68 | 60.31 | 627.68 | 60.31 |
| 8 | 1496.51 | 24.16 | 1496.51 | 24.16 |

Results are synthetic loopback observations, not WAN or player capacity.
The local benchmark config permits 10,000 accepts/minute; production defaults are unchanged.
CPU/memory samples, reconnect, EOF/byte checks, soak details and failure diagnostics are in JSON.

Tunnel-process restart: available=True; old port=51108; new port=51109; endpoint retained=False; completion=568.86 ms.
Tunnel-link UDP drop: 55 s; recovered=True; same bridge-backed endpoint=True; resume event observed=True; completion=56698.82 ms.
