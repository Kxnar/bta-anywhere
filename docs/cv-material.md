# BTA Anywhere — evidence-backed CV material

- Built a Java/Netty–Rust/Quinn TCP relay with a **2 MiB** pending-connect cap; verified **540 transactions** across **9 Linux netem sessions**.
- Diagnosed **122 s** QUIC RTT estimates against **31 ms** logged send-to-ACK times; a native upgrade passed **48/48 streams** in **6 paired jitter/loss trials**.

These bullets describe the implemented transport and completed synthetic
validation. They do not claim a successful two-player game demonstration, public
service deployment, WAN performance or a system-wide memory bound. The full
CV-readiness goal remains open until the actual game journey and demo are complete.

## Claim audit

| Claim | Exact support | Qualification |
| --- | --- | --- |
| Java/Netty and Rust/Quinn relay | `tunnel-client` and `relay`; [architecture](architecture.md) | QUIC carries the host leg; ordinary TCP carries the guest leg |
| 2 MiB pending-connect cap | `IncomingTunnelHandler.MAX_PENDING_PAYLOAD`; [retained before/after regression](benchmarks/2026-10-08/README.md#deterministic-transport-regression-and-checks) | Per pending stream, not a whole-process RSS cap |
| 540 transactions over nine Linux sessions | [Linux report](benchmarks/2026-10-08-linux/README.md), [summary](benchmarks/2026-10-08-linux/summary.json) | Three sessions per condition; ten repetitions per size/mode cell; connection, byte check and EOF included |
| 122 s versus 31 ms | [Packet report](benchmarks/2026-10-08-packets/README.md), [exact packet correlation](benchmarks/2026-10-08-packets/results/baseline-jitter-analysis.json) | One cited packet: native latest RTT 122,414.79 ms versus 30.87 ms between native send and ACK log events; no independent wire timestamp |
| 48/48 streams in six paired trials | [Unlogged paired results](benchmarks/2026-10-08-packets/results/paired-no-qlog/campaign.json), [derived outcomes](benchmarks/2026-10-08-packets/summary.json) | Six candidate sessions, each paired with an old-version control; controls passed 1/6 sessions and 10/48 streams. Streams within a session are correlated |

For an interview, explain the stale pacing timestamp hypothesis, the unchanged
deadline and byte/EOF checks, and why the native release upgrade is a controlled
dependency comparison rather than proof that a single upstream line explains
all effects. Be explicit that the old failure is intermittent and that every
failed experiment remains available.

An alternative reliability detail is **9/9 relay-restart recoveries in 2.97–12.68
seconds** in the Linux campaign, measured by a new verified transaction on the
old endpoint. It does not mean existing TCP or game connections survived.
