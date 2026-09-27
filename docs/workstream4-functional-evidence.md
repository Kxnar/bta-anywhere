# Workstream 4 functional review evidence (Windows x86-64)

Candidate branch: `codex/regional-coordinator-w4`, local and stacked on
Workstream 3 commit `08c26ce`. The W4 branch head was verified at `f518c9c`
before work; its original W3 relay interface parent was `c8ebf28`. The branch
was locally rebased onto finalized W3 before integration. No push, branch
merge, deployment, or PR occurred. This review ran on 27 September 2026.

This is a **functional-only** merge decision under the maintainer's
[explicit performance/soak exception](functional-merge-exception.md). No
benchmark or long load/soak run was made or counted as passed. The original
thresholds remain in the roadmap and design. The fixed 10,000-attempt,
at-least-100-worker uniqueness check stayed in scope.

## Implemented path and commits

- `1f884b1`: canonical signed ticket prototype.
- `c5dc780`, `9050bc7`, `245fe4d`: relay ticket/feature validation,
  authenticated redemption, exact managed-port bind, reconciliation, and
  release ordering. Static v1 and encrypted v2 registration remain available.
- `a50a98a`: Java allowlisted resolver, certificate-verified QUIC RTT probe,
  HTTPS allocation, pinned reconnect, local trust/credential config, and
  explicit static fallback.
- `9d95f4f`, `905ceda`, `c6bbdb5`, `bd707fd`, `27df568`: TLS coordinator,
  transactional SQLite leases and partial unique port index, authenticated
  heartbeats, one-use redemption, full-inventory restart reconciliation,
  metrics, consistent backup, public-key provisioning, and fail-closed
  database initialization/schema checks.
- `75bae8d`: pinned a compatible URL dependency within the existing reviewed
  licence set; the licence gate itself was not changed.
- `06e5180`: reject static fallback when a coordinator TLS failure is nested
  inside a connection failure; a focused Java negative test covers it.

The coordinator chooses among fresh, compatible, nonempty relay heartbeats by
median **host-to-relay** RTT and stable relay ID. It commits a reservation
before signing. The relay verifies the ticket's signature, ID, host, mode,
port, and time, atomically redeems it, and binds that exact managed port before
acknowledging `allocationTicket`. The Java client accepts only an allowlisted
relay with locally supplied TLS trust and access token, and retains that
choice for same-session resume. Neither existing guest TCP connections nor
encrypted invitations migrate to another relay.

## Nonbenchmark build and focused gates

Commands run from the W4 worktree:

```powershell
cargo build --locked --release --package bta-anywhere-coordinator --package bta-anywhere-relay
.\gradlew.bat --no-daemon :tunnel-client:shadowJar
.\gradlew.bat --no-daemon check build
cargo fmt --all -- --check
python scripts\check_rust_licenses.py
cargo clippy --locked --all-targets -- -D warnings
cargo test --locked --all
rustup run 1.85.0 cargo check --locked --all-targets
java -jar tunnel-client\build\libs\bta-anywhere-tunnel-0.1.0-all.jar doctor
python -m unittest discover -s scripts -p test_protocol_campaign.py
python -m unittest discover -s scripts -p test_protocol_long_campaign.py
python -m py_compile scripts\regional_coordinator_smoke.py
```

All listed final gates passed. The release coordinator/relay build and Java
shadow JAR built; Gradle `check build` completed 20 tasks. Cargo tests passed:
six coordinator functional cases, three ticket protocol cases, 25 relay unit
cases, and 12 relay corpus cases. The coordinator cases include one explicit
10,000-attempt/100-worker contended uniqueness assertion, deterministic
three-relay RTT/capacity/failure/restart selection, wrong credentials and
bindings, replay, clock rollback, owner lock, backup, lost database, missing
unique index, and reconciliation. Clippy denied warnings; Rust 1.85.0
compiled all targets; the licence check validated 245 packages without a
policy change. Native QUIC doctor passed. Python protocol unit suites passed
20 and three tests. The long-campaign Python *unit tests* are short tests of
the runner, not a long campaign.

The first licence check failed because the initial HTTP URL dependency pulled
18 packages declaring an unreviewed `Unicode-3.0` expression. A compatible
`url` 2.5.0 pin removed that dependency chain; the same unchanged licence
checker then passed. This dependency pin needs future security and licence
review before an unrelated dependency refresh. The first draft of the W4
integration harness also timed out despite a successful managed registration:
its log reader only queued the legacy harness label. The harness reader was
fixed and the complete case passed; no product assertion was weakened.

The short cross-language protocol corpus ran:

```powershell
python scripts\protocol_campaign.py --seed 20260925 --count 100 `
  --output .dev\protocol-campaign\w4-functional-20260927-01
```

Result: 100 cases, zero mismatches. This is a short functional parser check,
not the separately documented long campaign.

## Five clean coordinated Windows integration runs

Each run creates a fresh disposable coordinator database, signing key, TLS
certificate, separate host/relay credentials, relay certificate/token,
allowlist, and local echo service. It starts the actual coordinator, relay,
and shaded Java CLI. The case checks legacy and encrypted managed registrations
at exactly the reserved port, byte-exact guest traffic, existing-session
traffic during coordinator outage, explicit static fallback on the separate
static range, coordinator restart with a live lease, duplicate-port refusal,
listener release/reuse, and static/managed coexistence. The encrypted case
uses the guest companion and a one-use invitation through the managed relay.
No real game client is claimed for this W4 case; W3's separate real BTA 8.0.1
join is documented in [W3 evidence](workstream3-functional-evidence.md).

```powershell
for ($run = 1; $run -le 5; $run++) {
  python scripts\regional_coordinator_smoke.py `
    --coordinator-binary target\release\bta-anywhere-coordinator.exe `
    --relay-binary target\release\bta-anywhere-relay.exe `
    --tunnel-jar tunnel-client\build\libs\bta-anywhere-tunnel-0.1.0-all.jar
  if ($LASTEXITCODE -ne 0) { throw "W4 functional integration run $run failed" }
}
```

All five **final** runs passed sequentially against the release binaries and
the rebuilt Java JAR after the TLS fallback fix. Earlier five-run passes
preceded the licence dependency and TLS error-classification fixes and are
not used as the final count. The final loop was repeated with its output saved
through `Tee-Object` to ignored local files
`.dev/w4-functional-20260927-final/run-1.log` through `run-5.log`; each file
contains the complete PASS line. These checks assert
function and correctness, not latency, throughput, memory, or sustained-load
thresholds.

## Existing Relay and encrypted compatibility

Five further clean pairs ran the serial relay harness to completion before
starting the concurrent harness; the pair was repeated sequentially. The
unchanged static registration path was used, with no coordinator configured:

```powershell
python scripts\windows_half_close_smoke.py `
  --relay-binary target\release\bta-anywhere-relay.exe `
  --tunnel-jar tunnel-client\build\libs\bta-anywhere-tunnel-0.1.0-all.jar `
  --iterations 100 --event-loop-threads 1
python scripts\cross_language_e2e.py `
  --relay-binary target\release\bta-anywhere-relay.exe `
  --tunnel-jar tunnel-client\build\libs\bta-anywhere-tunnel-0.1.0-all.jar `
  --concurrency-waves 100
```

| Pair | Serial 100 EOF iterations | Concurrent 100 waves of eight |
| --- | --- | --- |
| 1 | PASS | PASS |
| 2 | PASS | PASS |
| 3 | PASS | PASS |
| 4 | PASS | PASS |
| 5 | PASS | PASS |

Each concurrent pass also checked authentication, static port exhaustion,
slow-client quota, byte-exact streams, shutdown, and relay restart recovery.
These are concurrency-correctness checks and do not count as deferred
performance or soak benchmarks. One additional serial-then-concurrent pair
passed after the final Java TLS fallback fix. The W3 encrypted guest/security
smoke also passed again on the final W4 release relay and JAR:

```powershell
python scripts\encrypted_join_smoke.py `
  --relay-binary target\release\bta-anywhere-relay.exe `
  --tunnel-jar tunnel-client\build\libs\bta-anywhere-tunnel-0.1.0-all.jar
```

It rejected replay, wrong pins/capability/session, expiry, revocation,
plaintext/downgrade attempts, and malformed prefaces before local sockets;
it checked active disconnect and cleanup. LAN and Direct code paths were not
changed in W4; Gradle `check build` passed, but no separate live LAN/Direct
player session was run in this functional review.

## Remaining risk and verdict

**Yes: W4 is functionally ready for local merge review against W3's stable
relay interface.** This is not a deployment or performance verdict. The
100-allocation-requests-per-second/ten-minute run and two-hour churn/soak
remain unrun and unpassed, as do the wider Workstream 0 baseline measurements.
Unknown performance regression, resource growth, and sustained-churn failure
remain risks. The three-relay case is deterministic service simulation; the
cross-process Windows test uses one relay, so geographic network behaviour is
not measured. Operators must enforce one relay process per ID/credential:
duplicate processes with the same identity are not fenced and can report
conflicting inventories. The coordinator is single-owner and rejects an
incomplete/missing database; explicit reinitialization after data loss must
follow manual relay inspection and reconciliation.
