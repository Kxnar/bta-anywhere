# BTA Anywhere loopback benchmark

Status: **FAILED**; profile: `smoke`; schema: 9.

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

Paired delta is relay minus direct for each matched stream and wave; its p95 is calculated from those differences, rather than subtracting the two p95 values.

| Streams | Direct G→H MiB/s | Relay G→H MiB/s | Direct H→G MiB/s | Relay H→G MiB/s |
|---:|---:|---:|---:|---:|

Throughput uses an echo workload. Each direction is reported separately; the two directions are not summed into an independent full-duplex capacity claim.
Results are synthetic loopback observations, not WAN or player capacity.
The local benchmark config permits 10,000 accepts/minute; production defaults are unchanged.
CPU/memory samples, reconnect, EOF/byte checks, soak details and failure diagnostics are in JSON.

Failure: KeyboardInterrupt: intentional cancellation cleanup probe
Failed case: `{"phase": "intentional_cancellation"}`.
Failed transfers: 0; byte mismatches: 0; missing EOFs: 0; timeouts: 0; leaked active stream gauges: 0.
