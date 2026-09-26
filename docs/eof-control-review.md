# Counted stream completion: review evidence

**Status, 25 September 2026:** experimental branch `codex/eof-control` with
production code at `3e0fe53d12b04e36cffec241d28e6ee582050c9d`; do not
merge. Five clean Windows integration pairs and one full benchmark pass exist,
but the second unchanged full run failed in eight-stream relay throughput.
The earlier missing-EOF failure is distinct from this sustained-transfer stall.
Repeatability, soak, and a valid full before/after comparison remain blocked.

## User value and mechanism

On Windows, a guest sometimes received every byte through eight concurrent
relay streams but waited for TCP EOF. Same-run tracing showed a successful
host QUIC shutdown callback and all bytes sent, without a FIN recorded for the
affected stream. A Netty 4.2 Windows upgrade experiment also failed the
concurrent EOF test twice. The exact transport cause is unproved.

The host and relay now negotiate `streamEofBytes`. After the local service
reaches input EOF, the host's final stream write and output shutdown succeed,
the host sends one authenticated notice with the response byte count. The
relay closes guest TCP output after copying exactly that count, even if a FIN
is absent. An old host still works with the new relay; a new host rejects an
old relay with an upgrade message. LAN, Direct, and self-hosted legacy Relay
remain available. No public relay or guest encryption is introduced.

The relay tracks only admitted active connection IDs in their authenticated
session, using the existing active-guest quota as the bound. It uses a fixed
16 KiB response buffer, counts completed partial guest TCP writes, and wakes
blocked writes when a notice is invalidated. The Java tunnel bounds queued
notices at 64. A notice-write failure closes that stream's owning QUIC
connection, not a newer connection after reconnect. The host never certifies
completion from an abrupt local socket close without input EOF.

## Threat and failure model

The relay already trusts the host for plaintext game responses. The notice
does not provide integrity against a malicious or faulty host: after exactly
the announced bytes, the relay closes without waiting for FIN and cannot
detect bytes sent later. It rejects an invalid count, duplicate active
notice, short native stream, or excess bytes observed before closure. A stale
connection ID is ignored without retaining state; a notice cannot affect
another authenticated session. Missing acknowledgement stops the new host
before it reports an active endpoint. Secrets and raw connection IDs are not
in normal logs or metrics. This change does not touch world ownership,
restoration, process identity, TLS/archive verification, path confinement,
or configured guest limits.

## Reproduction and verification

The candidate was built with release Rust and a shaded Windows x86-64 Java
tunnel. The retained full-profile loopback JSON records Windows build
`10.0.26200`, 20 logical cores, 16,923,529,216 bytes RAM, AC power,
JDK `21.0.12.1`, Rust/Cargo `1.97.1`, Python `3.14.6`, seed `1701`,
1/8 streams, 1 KiB/64 KiB/1 MiB payloads, five warm-up and 30 measured
latency waves, five 60-second throughput runs per path/concurrency, and the
disposable 10,000 accepts/minute benchmark override. That override is not a
measured capacity or a production default. All services bind to loopback.

| Evidence | Result | Limit |
|---|---|---|
| Pre-fix schema-7 full baseline | FAIL after 27.50 s: two of eight 64 KiB half-close streams verified all response bytes but missed EOF | No complete baseline performance result |
| Pre-fix isolated eight-stream diagnostic | PASS, 1,244,659,712 bytes each direction in 60 s | One instrumented diagnostic run |
| Earlier counted-EOF candidate isolated diagnostic | PASS, 1,181,351,936 bytes each direction in 60 s | One instrumented run; 5.09% below paired baseline, not a performance-gate result |
| Earlier counted-EOF candidate full profile | PASS in 1,339.12 s; zero transfer failures, mismatches, missing EOFs, timeouts, or leaked gauges; eight-stream relay throughput median 19.08 MiB/s | Pre-review-fix binaries; no same-machine full baseline |
| Final `3e0fe53` serial + concurrent integration | Five consecutive clean pairs PASS: each serial run used 100 labelled half-closes; each concurrent run used 100 waves of eight byte-exact streams, plus authentication, quotas, shutdown and relay restart | Synthetic loopback, not a real-player trial or two-hour soak |
| Final `3e0fe53` schema-8 full profile, run 1 | PASS in 1,339.74 s; zero transfer failures, mismatches, missing EOFs, timeouts, or leaked gauges; eight-stream relay throughput median 18.50 MiB/s; process-restart endpoint changed, 55-second link drop resumed the bridge-backed endpoint | One of three unchanged runs; no valid pre-fix full baseline; no soak |
| Same release binaries, schema-8 full profile, run 2 | **FAIL** after 961.52 s at the third eight-stream relayed throughput run: all eight guest sockets timed out after sending 1.44–2.10 MiB each and receiving 0–100,352 bytes; active gauge was 8; no byte mismatch or missing EOF was reported; both processes shut down cleanly after failure | Same artifact hashes and harness as run 1; direct one-stream p95 also varied materially; this blocks the three-run repeatability and soak sequence |
| Same release binaries, isolated eight-stream diagnostic after run 2 | PASS in 63.68 s; 1,000,669,184 bytes verified in each direction, all eight streams observed both EOFs, active gauge zero, clean shutdown | One diagnostic run with an indexed first byte; it does not erase the failed ordinary full workload or establish a cause |
| Mixed version checks | Three old-host/new-relay serial half-closes PASS; new-host/old-relay registration fails with upgrade instruction | Focused compatibility checks |

During the final full profile, the relay used 575.03 process CPU seconds and
peaked at 24.73 MiB working set; the tunnel used 945.97 process CPU seconds
and peaked at 419.75 MiB. Both processes terminated cleanly. These are
single-run synthetic loopback measurements and have no accepted before/after
memory comparison yet.

The earlier full-profile relay and tunnel hashes were
`a28615a1fa0b098da0c6c7d1debbaef5769d411b4c1c2d8f9afe89968c2f72f8`
and `f4b88f4b20a51d5406d48024a44d90c312f5e7154f081a7a0d9297b045b60732`.
They predate the backpressure and reconnect fixes. The final release relay
hash is `22e49b2c10da3572f3cf8c3b8e25d5a453a0f8a6121bb88a432c61541328c6e7`;
the final tunnel JAR hash is
`0a581a04f345957bdf03bc282e59b3910dae6473f64868887557f1524001d61f`.
The binary hashes, not the benchmark harness checkout's commit field, identify
which source revision each older cross-worktree run used. Schema 8 separately
records harness source `4c65060` and both binary checkouts at `3e0fe53`.
It marked the binary checkout dirty because this untracked review document
was present during the run; the recorded artifact hashes match the release
binaries built before that document was created. Checkout inference is not
build attestation.

Commands run on the final revision include:

```powershell
cargo fmt --all -- --check
python scripts\check_rust_licenses.py
cargo clippy --locked --all-targets -- -D warnings
cargo test --locked --all
.\gradlew.bat --no-daemon check build
cargo build --locked --release --package bta-anywhere-relay
python scripts\windows_half_close_smoke.py --relay-binary target\release\bta-anywhere-relay.exe --tunnel-jar tunnel-client\build\libs\bta-anywhere-tunnel-0.1.0-all.jar --java D:\Documents\BTA\.tools\jdk-21\bin\java.exe --iterations 100 --event-loop-threads 1
python scripts\cross_language_e2e.py --relay-binary target\release\bta-anywhere-relay.exe --tunnel-jar tunnel-client\build\libs\bta-anywhere-tunnel-0.1.0-all.jar --java D:\Documents\BTA\.tools\jdk-21\bin\java.exe --concurrency-waves 100
```

The final two Python commands were run sequentially five times, each with
fresh relay/tunnel processes. Focused Java tests also passed for stale
generation, wrong connection parent, failed notice write, exact response
count, and repeated local EOF; Rust tests passed 17/17 including invalid
notice under guest TCP backpressure. The full Java/mod build passed. No tests
were retried to turn a failure into a pass. The earlier Netty upgrade branch
remains a failed experiment.

Raw local artifacts are ignored and must remain private: EOF integration
logs under `.dev/eof-control/final-*.log` in this worktree; schema-7 benchmark
JSON and Markdown named `full-baseline-schema7-20260925-a`,
`full-eof-candidate-schema7-20260925-a`, and the two
`diag-*-schema7-20260925-a` pairs in the benchmark worktree's
`benchmark-results/`. The same ignored directory also retains schema-8
`full-eof-final-schema8-20260925-a` (PASS), `-b` (FAIL), and
`diag-eof-final-schema8-20260925-a` (diagnostic PASS), each with JSON, Markdown,
and command log. QLOG and detailed EOF traces are under ignored
`.dev/eof-debug/` in the diagnostic worktree. The private interview ledger
under the main checkout's ignored `.dev/engineering-evidence.md` maps those
artifacts to historical commits and distinguishes measured, failed, and open
claims. Do not commit raw logs, credentials, or machine-specific paths.

## Open gates and rollback

- Diagnose and correct the intermittent eight-stream relay throughput stall
  observed in unchanged full run 2; then restart the full three-run stability
  sequence on exact release binaries. The pre-fix full baseline also failed,
  so a performance-comparison design must be reviewed; no throughput or
  latency regression claim is made here.
- After the data path is stable, run the final two-hour eight-stream soak with
  zero integrity, EOF, cleanup, or unbounded-memory failures. Review CPU and
  resident-memory samples.
- Validate a documented disposable player/operator workflow before treating
  this as demonstrated real-game utility.
- Workstreams 0 and 1 must reconcile their harness/corpus with this prerequisite
  before their own merge review. Do not silently treat an older campaign as
  evidence for the new protocol.

Rollback while experimental is to leave this branch unmerged. If integrated
later, revert the counted-completion host and relay together or deploy a
known-matched prior pair; a new host must not silently fall back to an old
relay. No world edit or backup restoration is involved.

| Review area | Current score | Reason |
|---|---|---|
| User value | FAIL | Synthetic transfer fixed; disposable player workflow unvalidated |
| Scope | PASS | Windows-only and self-hosted modes retained |
| Correctness | FAIL | Focused checks pass, but the unchanged full benchmark failed all eight relayed throughput streams |
| Failure safety | PASS | Notice/session isolation and blocked-writer cleanup tested; world process rules untouched |
| Security | PASS | Explicit negotiation, bounds, and legacy trust model documented |
| Benchmarks | FAIL | Unchanged full run 2 failed; three-run stability, soak, and pre-fix full baseline unavailable |
| Performance | FAIL | Valid full baseline/candidate comparison unavailable |
| Maintainability | PASS | No new production dependency; CI tests remain short |
| Documentation | PASS | Protocol, architecture, security, build, troubleshooting, and rollback described |
| Claims | PASS | Exploratory, final-build, synthetic, and unrun checks are separated |

Any `FAIL` blocks merge. This scorecard is for review of the branch and does
not authorise release, deployment, push, or merge.
