# BTA Anywhere validation status

The CV-readiness goal is active. Transport benchmarks already exist, but the
two-client multiplayer demonstration is incomplete. Keep these two claims
separate until the game journey succeeds.

| Requirement | Current evidence | Remaining work |
| --- | --- | --- |
| Host, join, play, disconnect and reconnect | [First game journey attempt](validation/2026-10-08-journey/README.md): two isolated clients launched; disposable world handed off to a server; unauthenticated host rejected | Two authenticated BTA 8.0.1 sessions; successful host and guest join; shared world action; guest disconnect/reconnect; short recorded demo; clean stop and world reopen |
| Bounded failure and transport integrity | [Packet investigation](benchmarks/2026-10-08-packets/README.md): dependency upgrade passed 6/6 matched candidate sessions and 48/48 streams; old version passed 1/6 sessions. Native-log RTT correlation, standalone timestamp reproducer, failures and exact source/binary hashes retained | No further speculative fix search is needed for this reproduced defect. Limits remain: small synthetic sample, native logs rather than wire capture, and an older unrelated direct-path reset remains unexplained |
| Independent network validation | [Linux netem report](benchmarks/2026-10-08-linux/README.md): 9/9 fresh sessions, 540 transactions, 72 throughput streams and 9/9 verified restart recoveries; latency, throughput, CPU and RSS retained | Physical two-machine/WAN/NAT validation is unperformed. Hosted kernel-netem infrastructure was available and successfully used despite the local WSL failure |
| Reproducible project package | [Setup](setup.md), [architecture and trade-offs](architecture.md), [CV bullets with claim audit](cv-material.md), Windows CI, Linux workflow, hash verifiers and versioned failure/fix archives | Complete the real game journey, record its demo, and update setup/report with its actual observed outcome |

## Transport investigation protocol

Freeze the measured Java/Rust artifacts and harness before each comparison.
First capture native QUIC packet events (the pinned Netty API exposes QLOG) and
correlate ACK ranges, send/receive times, loss declarations and RTT updates with
the existing connection telemetry. Logging overhead needs its own control.
This protocol has now been executed. Five nonempty native captures are retained,
including the smoke capture. The packet report distinguishes logged event timing
from independent wire timestamps and identifies the incomplete final record in
one failed capture.

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
