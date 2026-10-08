# BTA Anywhere validation status

The CV-readiness goal is active. Transport benchmarks already exist, but the
two-client multiplayer demonstration is incomplete. Keep these two claims
separate until the game journey succeeds.

| Requirement | Current evidence | Remaining work |
| --- | --- | --- |
| Host, join, play, disconnect and reconnect | [First game journey attempt](validation/2026-10-08-journey/README.md): two isolated clients launched; disposable world handed off to a server; unauthenticated host rejected | Two authenticated BTA 8.0.1 sessions; successful host and guest join; shared world action; guest disconnect/reconnect; short recorded demo; clean stop and world reopen |
| Bounded failure and transport integrity | [Loopback report](benchmarks/2026-10-08/README.md), [scaling report](benchmarks/2026-10-08-scaling/README.md), [jitter diagnosis](benchmarks/2026-10-08-jitter/README.md) | Packet-level ACK/loss/send evidence around divergent RTT; targeted matched experiments; final operating envelope and reproducer |
| Independent network validation | Local userspace impairment campaigns; WSL enumeration timed out again on 8 October | Prepare Linux-native build and isolated netem campaign; run on available infrastructure; report per-session latency, verified throughput, recovery and resource use |
| Reproducible project package | Build, setup, CI and archived synthetic workloads exist | Update setup from the successful game journey; architecture/trade-offs; integrated validation report; exact-result CV bullets |

## Transport investigation protocol

Freeze the measured Java/Rust artifacts and harness before each comparison.
First capture native QUIC packet events (the pinned Netty API exposes QLOG) and
correlate ACK ranges, send/receive times, loss declarations and RTT updates with
the existing connection telemetry. Logging overhead needs its own control.
The existence of the API does not establish that useful qlog output has yet
been captured on this machine.

Use fresh processes and independent sessions. Pair baseline and candidate with
the same nominal impairment, workload, seed, fixed deadlines and instrumentation;
alternate their order. Seeded userspace packet schedules remain dependent on
arrival timing, so a seed is not identical packet replay. Keep every failed
result and distinguish a verified successful transfer from partial progress.

Choose each targeted change from the packet evidence. Record its hypothesis,
exact source patch, expected observable consequence, matched controls and
result. After three distinct evidence-led fix experiments fail to resolve the
remaining failure, stop searching for a fix for this goal and publish the
supported operating envelope plus a precise reproducer. Existing pacing and
ordering controls narrow the trigger but are not production fixes. Do not relax
timeouts, remove byte/EOF verification, or pursue a throughput threshold.

## Completion gate

Finish only after the demonstration, validation report, retained failure/fix
evidence, reproducible campaign and two concise evidence-backed CV bullets are
complete. Infrastructure-dependent runs may remain explicitly pending under the
goal's infrastructure condition. A failed authentication attempt does not satisfy
the multiplayer demonstration requirement.
