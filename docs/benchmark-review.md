# Workstream 0: benchmark evidence and review

Status on 2026-09-25: **experimental; not merge eligible**. This is a Windows
x86-64 loopback harness for the direct TCP and self-hosted relay paths. The
Workstream 0 harness itself does not alter the relay, tunnel, game mod, quotas,
or world handling. Its operator value is a repeatable way to check correctness and compare release
builds before accepting networking changes. No current performance or player
capacity claim follows from the runs below.

## Stacked review candidate

The `codex/benchmark-baseline-stacked` branch combines the Workstream 0
schema-8 harness from `codex/benchmark-baseline` (`4c65060`) with the
`streamEofBytes` relay/tunnel prerequisite from `codex/eof-control` (`3e0fe53`).
The original Workstream 0 branch and its historical results below remain
separate. The original `main` production artifacts failed to complete a
diagnostic replay of the full-profile prefix when two relayed half-close
streams delivered every reply byte but did not deliver TCP EOF. An earlier
full run also stalled during the
eight-stream relayed throughput case. These are observed failures, not proof
that they share a cause. The counted-completion change is a candidate fix for
the EOF case; it has not yet established that the full benchmark passes.

For this stacked branch, three stable, unchanged full-profile runs are still
required to establish a usable post-fix baseline. The bounded two-hour
eight-stream soak, five clean-process full integration passes, and the
same-machine candidate comparison also remain open. A short smoke pass or a
single full run cannot satisfy those gates. Record the relay and tunnel source
commits and artifact hashes in schema-8 JSON before comparing builds. Do not
pool the original `main` failure with post-fix measurements as if they were
the same baseline.

The Windows integration CI job now schedules benchmark reporting unit tests
and the short benchmark smoke after its concurrent and serial harnesses. It
reuses the release artifacts built in that job and runs the shared-port checks
sequentially. This workflow change has not yet run on CI; it does not close
the three full-run or two-hour soak gates.

## Design and invariants

The harness sends deterministic identical payloads to a synthetic TCP echo
service directly and through the Rust relay and Java tunnel. It verifies exact
bytes and TCP EOF, samples relay/tunnel CPU and memory, probes recovery, and
writes versioned JSON plus a short Markdown summary. A test-only disposable
relay config raises the per-source accept-rate setting to 10,000/minute to
allow repeated samples; the production default and eight-active-guest limit
are unchanged. The configured rate is not measured capacity. Services bind to
loopback, use disposable credentials, and do not open a BTA world.

The Workstream 0 harness does not affect production world ownership, explicit
backup restoration, process identity, TLS/archive verification, path
confinement, quotas, or secret redaction. The stacked review candidate also
contains the separate EOF data-path change under review. The harness retains at
most 160 synthetic service events and 100 redacted process log lines in memory; failed JSON contains at
most the last 50 process lines. Raw artifacts stay ignored because they can
contain machine details and private temporary paths.

## Dated run history

All listed artifacts are local ignored files under
`D:\Documents\BTA-benchmark-baseline\benchmark-results\`. Their JSON is the
source of the statuses below. Earlier exact shell invocations were not retained;
profile, commit, toolchains, configuration, and artifact hashes are in each
JSON. These were synthetic loopback runs, not a real player trial.
For schema 2-7, the `commit` field was the Git checkout from which the harness
was run; it did not identify the source checkout of each artifact argument.
The artifact SHA-256 values identify the measured files, but those older JSON
files alone cannot prove which source commit built them. Keep this limitation
when using the historical table for an interview or candidate comparison.
Every artifact in the table records the same production relay SHA-256
`8a87166e976f1bae14ea1a5a0a4ce5b3f438ef35e09a1a27961dd18ee6c37a88`
and tunnel JAR SHA-256
`4aa0c2df21179d3f08cb7a9179ae3028075c3a86c6c796369095e7418328f2c8`.
The intervening commits changed only the harness and its documentation. This
rules out a production binary change between these observations; it does not
identify the cause of the intermittent failures, and harness behavior evolved
between schemas.

| Artifact | Schema / commit | Observed result | Limit |
|---|---|---|---|
| `full-baseline-1.json` | 2 / `2a67f59` | PASS, 1,264.1 s | Older harness; no tunnel process or link-interruption case |
| `full-baseline-2.json` | 2 / `2a67f59` | PASS, 1,265.0 s | Only two unchanged runs; several direct p95 values unstable |
| `full-v3-baseline-1.json` | 3 / `90dc649` | FAILED after 719.2 s; timeout, active gauge 8 | Failing case and traceback not retained in that schema |
| `full-v4-baseline-1.json` | 4 / `a832c81` | FAILED at first eight-stream relayed throughput run; all eight socket reads timed out at about 30 s, active gauge 8 | 1.4–1.9 MiB sent per stream, only 0–64,739 reply bytes received; transport cause unresolved |
| `diagnostic-eight-relay-1.json` | 4 / `a832c81` | PASS for one isolated 60 s, eight-stream relay run | Diagnostic only |
| `diagnostic-sequence-1.json` | 5 / `d076e94` | PASS for the full prefix through the first eight-stream relay throughput run | Diagnostic only; stops before the remaining full cases |
| `diagnostic-sequence-20260925.json` | 5 / `542116d` | FAILED after 29.8 s at eight-stream, 64 KiB, relayed half-close latency wave 18 | Two streams received and verified all 65,536 reply bytes, then timed out waiting for TCP EOF; active gauge 2 |
| `smoke-v6-20260925.json` | 6 / `d7df1ce` | PASS in 109.3 s; zero reported failures, final active gauge 0, clean shutdown | Short smoke only, not a full baseline or soak |

The two schema-2 full runs each recorded zero failed transfers and a final
active gauge of zero. The eight-stream relay throughput medians were 23.29 and
21.96 MiB/s, a 5.7% difference relative to the first run. Some direct-TCP p95
latencies differed by far more than 15% (for example 1 KiB request/response:
14.86 versus 1.00 ms). A third unchanged full run was not completed. The
schema-2 reconnect used a fixed one-port relay range, so an old endpoint probe
does not establish general endpoint-retention behavior. Later schemas separate
relay restart, tunnel process restart, and temporary link interruption.

The schema-4 throughput stall and the 2026-09-25 missing-EOF failure are
different observed failure shapes. A shared underlying cause has not been
established. Earlier full-sequence stalls prompted the diagnostic profiles;
the schema-3/4 JSON records separate failed runs, although schema 3 omitted
its failing case.
No schema-5, schema-6, schema-7, or schema-8 full baseline or two-hour soak
result exists in the original Workstream 0 worktree's historical inventory.

The schema-4 sender counts of about 1.4–1.9 MiB per stream are near the Java
QUIC client's configured 2 MiB receive window per bidirectional stream and
16 MiB connection window. `IncomingTunnelHandler` uses manual reads and
requests another QUIC read after a local TCP write succeeds. A stalled read
resume or flow-control update is therefore a hypothesis to test. Sender counts
are guest TCP bytes accepted by `sendall`, not acknowledged QUIC bytes, and
that JSON lacks per-stream read/window events. No receive-window or quota
setting has been raised to test the hypothesis.

## Current reproduction and checks

At commit `542116d37cc197824668dced7ed7e89913110779`, on AC power,
Windows build `10.0.26200`, AMD64, Intel Family 6 Model 165, 20 logical cores,
16,923,529,216 bytes RAM, JDK 21.0.12.1, Rust/Cargo 1.97.1, and Python 3.14.6:

```powershell
cargo build --locked --release --package bta-anywhere-relay
.\gradlew.bat --no-daemon :tunnel-client:shadowJar
python -m unittest discover -s scripts -p test_benchmark_relay.py
python scripts\benchmark_relay.py --profile diagnostic-sequence `
  --relay-binary target\release\bta-anywhere-relay.exe `
  --tunnel-jar tunnel-client\build\libs\bta-anywhere-tunnel-0.1.0-all.jar `
  --java D:\Documents\BTA\.tools\jdk-21\bin\java.exe `
  --output benchmark-results\diagnostic-sequence-20260925.json
```

The release relay build and shaded JAR build passed; ten benchmark unit tests
passed before the reproduction. The diagnostic command exited 1 and retained
the JSON plus its generated `.md` summary. The failed run used seed 1701,
1 KiB/64 KiB/1 MiB configured payload sizes, one/eight streams, a 15 s latency
socket timeout, and the disposable 10,000/minute accept-rate override. The
traceback locates the timeout at `transfer()`'s final one-byte EOF read, after
the exact 64 KiB reply comparison. The relay and tunnel terminated cleanly.
The ignored JSON SHA-256 is
`ABD045CF70EB7BC4CD60D40DCC430EF07F19A8997E67C82A6EB5E4CE8DB63FF5`.
No benchmark-owned relay or Java process remained afterward.

The subsequent schema-6 reporting change distinguishes a verified reply with
missing EOF, counts failures per stream, retains completed latency cases, and
captures bounded synthetic-service lifecycle events on failure. It has no
production dependency or data-path change. `python -m py_compile` passed and
12 benchmark unit tests passed, including a negative test for a verified reply
whose server withholds EOF. A short schema-6 end-to-end smoke completed with:

```powershell
python scripts\benchmark_relay.py --profile smoke `
  --relay-binary target\release\bta-anywhere-relay.exe `
  --tunnel-jar tunnel-client\build\libs\bta-anywhere-tunnel-0.1.0-all.jar `
  --java D:\Documents\BTA\.tools\jdk-21\bin\java.exe `
  --output benchmark-results\smoke-v6-20260925.json
```

The smoke JSON records commit `d7df1ceb8f782ea0680fbbedeca6b07859838734`,
12 latency cases, two throughput cases, a final active gauge of zero, and
clean relay/tunnel termination. A relay restart probe completed in 35.35 s;
a fresh tunnel process obtained a different endpoint (port 59426 to 59427);
and a 55 s UDP link drop resumed the bridge-backed endpoint in 58.55 s.
These are loopback smoke observations only. The JSON SHA-256 is
`CA4E4A8183A0AE5AEEDB00EAD164B0FB08677CD3F7CF49C4A4978F6D475BFD7F`.

The first six historical raw JSON hashes, in table order, are:

```text
19D72A549E49FBAE025296583392B762709A8825EBC5C66180823B85ED2FA374
16A9EA26157A0D26EE62107B473CD8CE593431423E8C1EBE40AD3CAEBAE44F64
0594FF55A0A57BDF16825DC77EB9896882007518D4C75F7D7E25F36371D9F406
11A952C16B679B13E6882491843ABF755D75F92A0699C8209A611121343F9EF1
942D61A8E12190BE234D5A74A3E724210188B47AF8E4B4972D9E0ABB08C55B42
ECAA5E95B26F6B2DDDDC127DB66A08ED577921C9C44809D71C5955A82A52325B
```

## Schema 7 diagnostic instrumentation (2026-09-25)

The intermittent eight-stream throughput stall still has no established cause.
The benchmark harness now has diagnostic-only per-stream counters for bytes
accepted by the guest TCP socket, read by the synthetic echo server, accepted
by its reply socket, and read by the guest. It samples each stream once per
second and retains a final snapshot even when a transfer fails. EOF transitions
and error *class names* are included. This shows which side of the synthetic
path ceased making progress without recording payloads, peer addresses, or raw
exception text in the progress samples. The normal full/smoke/soak data path,
production relay, tunnel, defaults, and thresholds were not changed.

The diagnostic traffic uses a one-byte synthetic stream index so server-side
connections can be correlated without relying on accept order. The focused
profiles are for fault localization and must not be used as an unchanged
performance baseline. The JSON retains at most eight streams and 90 one-second
samples. A local unit test confirmed that an intentionally non-echoing service
produces a failed transfer with guest/server progress retained; it does not
reproduce the actual relay stall. The 16 benchmark unit tests and Python syntax
check passed. The shared-port eight-stream diagnostic has **not** been run with
this change while concurrent EOF integration work is active. Run it with the
command in `docs/benchmarking.md` before interpreting this instrumentation.

## Schema 8 artifact provenance (2026-09-25)

Alternating candidate and baseline runs may pass binaries from different
worktrees to the same benchmark script. Schema 8 therefore records separate
Git revision and dirty status for the harness script, relay executable, and
tunnel JAR, inferred from each file's containing checkout. Missing or unreadable
checkout state is explicitly `unavailable`. It also retains each artifact's
SHA-256, which identifies the exact file measured. No source checkout path or
raw Git error enters the JSON. This provenance is an inference from file
location, not a cryptographic build attestation; copied artifacts and dirty
builds need independent build records before a source-level comparison.

`python -m unittest discover -s scripts -p test_benchmark_relay.py` passed
18 tests, including a temporary Git checkout test for clean, dirty, and
outside-checkout artifacts, and a summary test with three distinct source
revisions. Python syntax and `git diff --check` also passed. The following
candidate measurements used this unchanged schema-8 harness; they do not turn
the earlier failed pre-fix run into a successful baseline.

## Schema 8 unchanged-binary candidate sequence (2026-09-25)

The harness remained at clean `4c65060`. Both full runs used byte-identical
release artifacts from the separate counted-EOF branch: relay SHA-256
`22e49b2c10da3572f3cf8c3b8e25d5a453a0f8a6121bb88a432c61541328c6e7`
and tunnel JAR SHA-256
`0a581a04f345957bdf03bc282e59b3910dae6473f64868887557f1524001d61f`.
The source checkout changed only by committing its review document between
runs; the production code and artifact hashes did not change.

| Ignored JSON artifact | Result | Interpretation |
|---|---|---|
| `full-eof-final-schema8-20260925-a.json` | PASS in 1,339.74 s; zero transfer, byte, EOF, timeout, gauge or shutdown failures | One synthetic full candidate run, not a stable baseline or before/after comparison |
| `full-eof-final-schema8-20260925-b.json` | **FAIL** after 961.52 s at eight-stream relayed throughput run index 2; all eight transfers timed out after sending 1.44–2.10 MiB each and receiving 0–100,352 bytes; active gauge 8; clean process shutdown after failure | Repeats the earlier data-flow stall shape, distinct from missing TCP EOF; blocks the three-run variance gate and soak |
| `diag-eof-final-schema8-20260925-a.json` | PASS in 63.68 s; 1,000,669,184 bytes each direction, eight byte-exact streams with both EOFs, gauge zero, clean shutdown | A single indexed diagnostic run does not clear the ordinary full-run failure |

The failed full run also had materially different one-stream direct-TCP p95
values from run 1. The 15% repeatability rule was fixed in an ignored analysis
note before run 2 completed; no threshold was changed. The stall's root cause
is not established. Raw JSON, generated Markdown, and command logs remain in
the ignored `benchmark-results/` directory. The five clean serial/concurrent
integration pairs on the separate EOF branch test a different synthetic
workload and do not override this failed full benchmark.

## Open acceptance gates

- **Full baseline:** the pre-fix schema-7 full baseline failed on missing EOF.
  The counted-EOF candidate completed one schema-8 full run, then failed the
  second unchanged run on eight-stream throughput. Diagnose that stall before
  restarting three unchanged current-schema runs; investigate any median
  throughput or p95 latency variation above 15% before comparisons.
- **Stability:** serial and concurrent integration harnesses must run
  sequentially. Five consecutive clean-process Windows integration suite
  passes are still required; this branch has not recorded them.
- **Soak:** a bounded, eight-stream two-hour soak has not passed. Review memory
  samples for growth as well as byte, EOF, active-gauge, and shutdown results.
- **Comparison:** no same-machine same-configuration baseline-versus-candidate
  performance comparison is complete. Do not treat schema-2 throughput values
  as a current baseline or change any threshold after seeing them.
- **Player utility:** this harness helps an operator validate a relay path, but
  no real BTA guest join is demonstrated by a synthetic loopback run.

The next engineering step is to resolve the relayed EOF and eight-stream
data-flow failures, then rerun the unchanged benchmark contract. Retain failed
JSON as evidence; do not raise buffer or quota defaults to produce a pass.

## Original Workstream 0 review scorecard

This scorecard describes the original Workstream 0 branch and main-derived
artifacts. The stacked candidate requires its own run results and review.

| Area | Status | Reason |
|---|---|---|
| User value | FAIL | The documented full operator task currently fails on relayed traffic |
| Scope | PASS | Windows-only, loopback, no public service or production change |
| Correctness | FAIL | Main-derived diagnostic missed EOF; unchanged counted-EOF full run 2 stalled on all eight relayed throughput streams |
| Failure safety | NOT APPLICABLE | Harness uses no world or managed server process; disposable child cleanup passed in the failed run |
| Security | PASS | Disposable credentials, loopback binding, bounded redacted diagnostics |
| Benchmarks | FAIL | Unchanged schema-8 run 2 failed; three reproducible full runs and soak absent |
| Performance | NOT APPLICABLE | No production data-path change or candidate comparison yet |
| Maintainability | PASS | Standard-library harness; long profiles are manual, unit checks are short |
| Documentation | PASS | Commands, schema, limits, evidence, and failure cases recorded here |
| Claims | PASS | Historical passes and current failures are labelled by schema and evidence |

Any FAIL blocks merge. To roll back the harness, revert the Workstream 0
commits; review the EOF prerequisite separately before reverting that data-path
change. No save, server installation, relay service, or infrastructure requires
modification for the harness. The raw artifacts remain outside Git and should
be attached to the eventual review or placed in a controlled evidence store
before merge.
