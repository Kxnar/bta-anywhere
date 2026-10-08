# BTA Anywhere loopback benchmark

Status: **FAILED**; profile: `full`; schema: 8.

Windows x86-64, release relay and shaded tunnel.
Harness source: `5e04359fae7b1bbe2be2104f5e61a2f3980fd12a` (dirty=True).
Relay artifact source: `5e04359fae7b1bbe2be2104f5e61a2f3980fd12a` (dirty=True).
Tunnel artifact source: `5e04359fae7b1bbe2be2104f5e61a2f3980fd12a` (dirty=True).
Artifact SHA-256: relay `ac9f10a8ab606a5aa24c8f3586fb31765bf818af86b7aece1fcba9d8e210e105`; tunnel `5f758ea359ab3f165aa0dbd89f98bbf96e7be70042a14cd49ca0aea6e252c4ff`.
Source revisions are inferred from the containing Git checkouts; the artifact hashes identify the measured files.

| Streams | Size | Mode | Direct p95 ms | Relay p95 ms | Relay added p95 ms |
|---:|---:|---|---:|---:|---:|
| 1 | 1024 | request_response | 15.78 | 17.53 | 2.79 |
| 1 | 1024 | half_close | 15.63 | 16.73 | 15.33 |
| 1 | 65536 | request_response | 24.07 | 42.81 | 42.26 |
| 1 | 65536 | half_close | 15.84 | 38.49 | 37.89 |
| 1 | 1048576 | request_response | 32.94 | 46.34 | 28.40 |
| 1 | 1048576 | half_close | 30.76 | 35.36 | 29.13 |
| 8 | 1024 | request_response | 2.37 | 3.24 | 1.51 |
| 8 | 1024 | half_close | 2.41 | 2.91 | 1.14 |
| 8 | 65536 | request_response | 2.77 | 14.08 | 11.65 |
| 8 | 65536 | half_close | 2.53 | 12.44 | 10.30 |
| 8 | 1048576 | request_response | 28.75 | 202.81 | 180.05 |
| 8 | 1048576 | half_close | 26.44 | 206.56 | 183.14 |

| Streams | Direct G→H MiB/s | Relay G→H MiB/s | Direct H→G MiB/s | Relay H→G MiB/s |
|---:|---:|---:|---:|---:|

Results are synthetic loopback observations, not WAN or player capacity.
The local benchmark config permits 10,000 accepts/minute; production defaults are unchanged.
CPU/memory samples, reconnect, EOF/byte checks, soak details and failure diagnostics are in JSON.

Failure: ConcurrentTransferError: throughput concurrency=1 run=4 path='direct': stream=0 RuntimeError: throughput socket failed after 42.326s sent=33076674560 received=33076281344: ConnectionResetError: [WinError 10054] An existing connection was forcibly closed by the remote host
Failed case: `{"concurrency": 1, "path": "direct", "phase": "throughput", "run_index_zero_based": 4, "stream_count": 1, "target_seconds": 60.0}`.
Failed transfers: 1; byte mismatches: 0; missing EOFs: 0; timeouts: 0; leaked active stream gauges: 0.
Failed stream indices: 0.
