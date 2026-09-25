# Workstream 0: benchmark evidence and review

Status on 2026-09-25: **experimental; not merge eligible**. This is a Windows
x86-64 loopback harness for the existing direct TCP and self-hosted relay paths.
It does not alter the relay, tunnel, game mod, quotas, or world handling. Its
operator value is a repeatable way to check correctness and compare release
builds before accepting networking changes. No current performance or player
capacity claim follows from the runs below.

## Design and invariants

The harness sends deterministic identical payloads to a synthetic TCP echo
service directly and through the Rust relay and Java tunnel. It verifies exact
bytes and TCP EOF, samples relay/tunnel CPU and memory, probes recovery, and
writes versioned JSON plus a short Markdown summary. A test-only disposable
relay config raises the per-source accept-rate setting to 10,000/minute to
allow repeated samples; the production default and eight-active-guest limit
are unchanged. The configured rate is not measured capacity. Services bind to
loopback, use disposable credentials, and do not open a BTA world.

Production world ownership, explicit backup restoration, process identity,
TLS/archive verification, path confinement, quotas, and secret redaction are
unaffected because this branch changes only the benchmark script, its unit
tests, and documentation. The harness retains at most 160 synthetic service
events and 100 redacted process log lines in memory; failed JSON contains at
most the last 50 process lines. Raw artifacts stay ignored because they can
contain machine details and private temporary paths.

## Dated run history

All listed artifacts are local ignored files under
`D:\Documents\BTA-benchmark-baseline\benchmark-results\`. Their JSON is the
source of the statuses below. Earlier exact shell invocations were not retained;
profile, commit, toolchains, configuration, and artifact hashes are in each
JSON. These were synthetic loopback runs, not a real player trial.

| Artifact | Schema / commit | Observed result | Limit |
|---|---|---|---|
| `full-baseline-1.json` | 2 / `2a67f59` | PASS, 1,264.1 s | Older harness; no tunnel process or link-interruption case |
| `full-baseline-2.json` | 2 / `2a67f59` | PASS, 1,265.0 s | Only two unchanged runs; several direct p95 values unstable |
| `full-v3-baseline-1.json` | 3 / `90dc649` | FAILED after 719.2 s; timeout, active gauge 8 | Failing case and traceback not retained in that schema |
| `full-v4-baseline-1.json` | 4 / `a832c81` | FAILED at first eight-stream relayed throughput run; all eight socket reads timed out at about 30 s, active gauge 8 | 1.4–1.9 MiB sent per stream, only 0–64,739 reply bytes received; transport cause unresolved |
| `diagnostic-eight-relay-1.json` | 4 / `a832c81` | PASS for one isolated 60 s, eight-stream relay run | Diagnostic only |
| `diagnostic-sequence-1.json` | 5 / `d076e94` | PASS for the full prefix through the first eight-stream relay throughput run | Diagnostic only; stops before the remaining full cases |
| `diagnostic-sequence-20260925.json` | 5 / `542116d` | FAILED after 29.8 s at eight-stream, 64 KiB, relayed half-close latency wave 18 | Two streams received and verified all 65,536 reply bytes, then timed out waiting for TCP EOF; active gauge 2 |

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
No schema-5 full baseline or two-hour soak result exists in this worktree.

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
whose server withholds EOF. No schema-6 end-to-end benchmark has completed yet.

The older raw JSON hashes, in table order, are:

```text
19D72A549E49FBAE025296583392B762709A8825EBC5C66180823B85ED2FA374
16A9EA26157A0D26EE62107B473CD8CE593431423E8C1EBE40AD3CAEBAE44F64
0594FF55A0A57BDF16825DC77EB9896882007518D4C75F7D7E25F36371D9F406
11A952C16B679B13E6882491843ABF755D75F92A0699C8209A611121343F9EF1
942D61A8E12190BE234D5A74A3E724210188B47AF8E4B4972D9E0ABB08C55B42
ECAA5E95B26F6B2DDDDC127DB66A08ED577921C9C44809D71C5955A82A52325B
```

## Open acceptance gates

- **Full baseline:** current schema has no successful full run. Run the
  unchanged release baseline three times, alternating with a candidate when
  one exists. Investigate and explain any median throughput or p95 latency
  variation above 15% before performance comparisons.
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

## Review scorecard

| Area | Status | Reason |
|---|---|---|
| User value | FAIL | The documented full operator task currently fails on relayed traffic |
| Scope | PASS | Windows-only, loopback, no public service or production change |
| Correctness | FAIL | Current diagnostic fails missing EOF; current full gate absent |
| Failure safety | NOT APPLICABLE | Harness uses no world or managed server process; disposable child cleanup passed in the failed run |
| Security | PASS | Disposable credentials, loopback binding, bounded redacted diagnostics |
| Benchmarks | FAIL | Three reproducible current full baselines and soak absent |
| Performance | NOT APPLICABLE | No production data-path change or candidate comparison yet |
| Maintainability | PASS | Standard-library harness; long profiles are manual, unit checks are short |
| Documentation | PASS | Commands, schema, limits, evidence, and failure cases recorded here |
| Claims | PASS | Historical passes and current failures are labelled by schema and evidence |

Any FAIL blocks merge. Rollback is to revert the benchmark-only branch commits;
no save, server installation, relay service, or infrastructure requires
modification. The raw artifacts remain outside Git and should be attached to
the eventual review or placed in a controlled evidence store before merge.
