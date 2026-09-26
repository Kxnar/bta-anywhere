# Workstream 2: crash-consistency evidence and review

Status: **experimental; not merge eligible**. The implementation and historical
results in this record originate on `codex/crash-fault-injection` at `631e1f9`,
based on `05d0c2d`. The separate review candidate
`codex/crash-fault-injection-stacked` combines that branch with the negotiated
EOF candidate `codex/eof-control` at `3e0fe53`. A conflict-free source merge
does not transfer test results to the combined commit. This record
distinguishes synthetic checks from checks that still need a disposable BTA
client world. The root `HANDOFF.md` is historical and is not the source of
current Git or test status.

## Stacked candidate review boundary

The stacked branch starts at EOF commit `3e0fe53` and merges W2 commit
`631e1f9`. Git resolved the shared architecture, building, security, and
troubleshooting documentation without textual conflicts. The branches change
different production modules: EOF changes the Rust relay and Java tunnel;
W2 changes the BTA mod and supervisor. Review still needs to verify that
their combined release artifacts, lifecycle, and documentation behave as
specified. The controller-owned lease revision has since passed focused
lease/crash checks and full Gradle `check build`; those results are recorded
below. The prior 4,350-case campaign and alternating timing comparison below
are evidence for earlier code, not same-commit evidence for the current
candidate. Run the full campaign with three clean synthetic fixture runs, then
the serial and concurrent integration harnesses **sequentially** on the
stacked commit. Record a release-build same-machine baseline and candidate
comparison, and complete the five manual disposable-world interruptions
before treating this branch as merge eligible. The two-hour soak and five
consecutive full Windows integration runs remain open.

### Review-discovered launch cleanup hazard

An independent read-only review of stacked commit `115dac6` found a
fail-closed cleanup gap. The launch-intent journal was already published when
`ManagedServerProcess.start()` spawned the supervisor. If startup then threw
before returning its verified handle, `HostController.server` stayed null. A
later Stop treated that null handle as a clean stop and cleared the journal,
although a supervisor and server could still be alive. This was a source
inspection finding, not an observed player-world incident.

The corrective candidate records an in-memory launch attempt before calling
`ManagedServerProcess.start()`; the previously published launch-intent journal
remains the persistent fail-closed evidence across a client crash. Stop may
clear the journal after a known pre-launch failure, or after stopping a known
process with identity checks and authenticated control. If a launch was
attempted but no verified handle returned, Stop retains the journal and the
Live world-open guard. A focused disposable fake-server regression injects
failure after process/control identity verification but before the handle
returns in both Live and Showcase, and a paired pre-launch regression checks safe cleanup. These focused
tests and all broader stacked-commit gates must run before this correction is
counted as evidence.

A second review sweep found a separate multi-client race on `115dac6`. With
two BTA clients sharing one game directory, client B could recover and stop
client A, clear A's journal, and publish a new journal for B. A's later Stop or
JVM shutdown hook still held `ownsJournal=true` and could clear B's active
journal. No game-directory-wide lock was present; a per-world game storage
lock would not protect this shared journal. The corrective candidate therefore
acquires one stable client-lifetime OS lock before constructing the controller
or allowing world-open actions. A denied second client receives an actionable
screen and cannot open, create, or host a single-player world from that
profile. This also closes the pre-journal interval against a second
cooperating mod client. The lock contains no PID authority or secret, is not
deleted on release, and is released by Windows on process exit. External
tools, manual journal edits, and unusual network filesystem lock behavior
are not established by this guarantee. Focused unit evidence is recorded
below; two-client disposable UI evidence is still pending.

A follow-up API review found that the public constructor on `1b3a355`
accepted a caller-owned lease and checked it only once. Although the mod kept
that lease until shutdown, an external same-process caller could close its
handle while the controller remained active. The current refinement makes
`HostController.open` acquire and own the lease, with no public constructor
that accepts an externally closable lock. `close()` releases it only after a
clean stop; uncertain cleanup retains the lock until process exit. A closed
controller rejects new hosting and recovery adoption. Recovery actions run
under a controller lease guard, and already-open hosting/recovery screens
redirect to the directory error when their controller closes. The pause-menu
path checks availability before reading controller status. Focused controller
and disposable child tests passed; real client UI behavior still needs a
disposable profile check. The campaign result below is for the earlier
`1b3a355` revision.

## User value and design

Interrupted hosting now leaves recovery evidence that prevents an ambiguous
Live save from reopening. The client records launch intent before starting the
supervisor, verifies both supervisor and server identity before an authenticated
stop, and guards ordinary single-player opens during the Live handoff. Recovery
never restores a backup without the existing explicit confirmation. Showcase
uses a separate copy, so a valid Showcase journal does not block its original.

The relevant invariants are exclusive ownership of a Live save, no automatic
restore, no stop based on PID alone, path confinement, and retention of evidence
when process identity or the recovery journal is ambiguous. The fault callback
is package-private and production construction uses `HostingFaults.NONE`.

## Implementation history

| Commit | Change | Evidence boundary |
|---|---|---|
| `32b1539` | Added fault points around save, backup/copy, journal, launch, exposure, stop, and cleanup; added crash and fail-closed recovery tests. | Synthetic worlds and child JVMs; no actual game-thread save. |
| `75b76b8` | Guarded ordinary world opening during Live handoff and ambiguous recovery, including alternate save paths. | Automated guard tests; actual BTA UI still requires manual inspection. |
| `55c81bb` | Matched supervisor and server identity before authenticated stop; added bounded controller campaign and runner. | Fake managed server and synthetic world. |

The 15 named fault points cover 29 applicable Live/Showcase combinations;
`BEFORE_SHOWCASE_CLEANUP` applies only to Showcase. They include both sides of
journal publication, backup/copy publication, supervisor launch, network
exposure, and the stop/cleanup path. The campaign asserts a SHA-256 manifest of
the original synthetic world, absence of automatic restore, bounded partial
artifacts, journal/reopen policy, captured process identity, and authenticated
cleanup. A failed fixture is retained; deletion requires its disposable marker
and verified output root.

## Recorded checks

The stacked safety correction was checked with these commands on 2026-09-25:

```powershell
# Set JAVA_HOME to a local JDK 21 installation before running Gradle.
.\gradlew.bat --no-daemon :bta-mod:test `
  --tests io.github.kxnar.btaanywhere.mod.hosting.GameDirectoryLeaseTest `
  --tests io.github.kxnar.btaanywhere.mod.hosting.HostControllerHardCrashTest `
  --tests io.github.kxnar.btaanywhere.mod.hosting.HostControllerWorldOpenGuardTest `
  --rerun-tasks
.\gradlew.bat --no-daemon check build
```

The focused run passed after two compile-only attempts failed under `-Werror`
because the new try-with-resource test variables were unused. Those warnings
were corrected; neither earlier attempt executed tests. The final focused run
passed in 28 seconds. The full Gradle `check build` passed in 1 minute 16
seconds, with 37 mod tests, zero failures/errors, and four skips. The skips
were the manual controller campaign, opt-in pre-launch timing, and two Windows
symbolic-link tests unavailable to this account because it lacks link-creation
privilege. `GameDirectoryLeaseTest` ran five cases, with one symlink skip;
`HostControllerHardCrashTest` ran two methods (including both Live and
Showcase in the new failure case), and `HostControllerWorldOpenGuardTest` ran
five. The generated XML and a machine summary are retained under ignored
`.dev/workstream2-stacked/focused-checks-20260925/`. These focused and build
checks do not transfer the earlier 4,350-case result to the stacked revision,
nor do they satisfy integration, soak, release-build performance, or manual
game gates.

The controller-owned lease revision compiled and passed focused
`GameDirectoryLeaseTest` and `HostControllerHardCrashTest` on 2026-09-25:
9 tests, zero failures/errors, one Windows symlink-permission skip. The first
compile found two unused-resource warnings in the new child tests under
`-Werror`; those were corrected before any test ran. Full Gradle `check build`
then passed with 39 mod tests, zero failures/errors, and four skips (the two
opt-in campaign/timing checks and two symlink-permission checks). The lease
test verifies closed controllers cannot restart hosting or execute recovery
callbacks. The child test verifies failed cleanup retains the lease until
process exit, after which a new controller can acquire it. Minecraft UI
paths compiled but still need the two-client disposable-profile walkthrough.
The full 4,350-case campaign has not run on this revision.

On clean stacked commit `1b3a35529659c97594722b605883ce535c129a2a`,
the full deterministic controller campaign ran with the command below and
passed all 4,350 synthetic cases: 29 applicable mode/fault-point pairs,
50 seeds each, and three clean fixture repetitions of 1,450 cases. The raw
JSONL contains 4,350 passing rows and zero failures; no failed fixture was
retained. Gradle reported 21 minutes 22 seconds. The manifest records
2026-09-25 20:34:48–20:55:47 UTC and the private manifest retains machine
and toolchain details. The checks include the synthetic original-world SHA-256
manifest, recovery-journal and process-identity policy, and authenticated
fake-server cleanup. This is synthetic fault evidence, not an actual BTA
save/unload or forced-shutdown test.

```powershell
# Set JAVA_HOME to a local JDK 21 installation before running Gradle.
powershell -ExecutionPolicy Bypass -File .\scripts\run_controller_fault_campaign.ps1 -Profile full
```

Raw results for this stacked run are under ignored
`.dev/controller-fault-campaign/run-342088cbb62e4f3e98f318eed630b04c/`
in the stacked checkout. The manifest, JSONL, and summary must remain together.
The subsequent controller-owned lease factory changes production code, so
this result remains historical evidence
for `1b3a355` and does not establish the new revision's campaign gate.

| Check | Result | Raw evidence |
|---|---|---|
| Controller smoke | 29/29 applicable cases passed. | `.dev/controller-fault-campaign/run-34eb892096714104b076870be8324895/` |
| Full controller campaign | 4,350/4,350 passed: 29 combinations, 50 seeds, 3 clean fixture runs (1,450 per run). No retained failed fixture. | `.dev/controller-fault-campaign/run-faf1274520784502a9b0aab2e9203824/manifest.json`, `scenarios.jsonl`, `summary.json` |
| Mod unit/crash/guard suite, rerun 2026-09-25 | 29 tests, 0 failures, 2 skips. The opt-in full campaign is deliberately skipped in normal test runs; a Windows symlink alias test skipped because this account lacks link-creation privilege. The XML was later replaced by subsequent Gradle runs. | `.dev/workstream2-gradle-test-20260925.log` |
| Mod suite after one-write change | Gradle `:bta-mod:test --rerun-tasks` succeeded. The timing/campaign Gradle runs later replaced its XML reports, so a retained per-test count is unavailable for this specific rerun. | `.dev/workstream2-gradle-after-one-write-20260925.log` |
| Full controller campaign after one-write change | 4,350/4,350 passed on clean `f8fb80a`: 1,450 cases in each of three clean fixture runs, 0 failed, 0 retained fixtures. | `.dev/controller-fault-campaign/run-a5caa8eb09c64129ab1a5bc20d1863d6/manifest.json`, `scenarios.jsonl`, `summary.json` |
| Clean stacked campaign at `1b3a355` | 4,350/4,350 passed: 3 repetitions of 1,450; 0 failed or retained fixtures. This predates the controller-owned lease factory. | Stacked checkout `.dev/controller-fault-campaign/run-342088cbb62e4f3e98f318eed630b04c/manifest.json`, `scenarios.jsonl`, `summary.json` |
| Serial half-close, one prior run | 100/100 iterations passed. | `.dev/workstream2-integration/serial-100.log` |
| Concurrent integration, one prior run | 100 waves of eight streams passed. | `.dev/workstream2-integration/concurrent-100x8.log` |

Historical `.dev/controller-fault-campaign/` and `.dev/prelaunch-timing/`
paths below are relative to the original isolated W2 checkout, not the
stacked checkout. Retain
that ignored evidence directory privately alongside the branch review.

The first full campaign manifest records commit
`55c81bbe69f177a772d6a9e48ba310f0ab2c2358`, a clean worktree,
Windows 11 build 26200, Java 21.0.12.1, and Rust 1.97.1. The campaign ran
from 2026-09-25 02:41:09 UTC to 02:57:54 UTC. Results are ignored local files;
retain them outside Git when handing off the branch. These results are
deterministic fake-server simulations, not real-game, power-loss, or player
validation. The single prior serial and concurrent runs do not satisfy the
five-consecutive-run Windows stability gate. Another branch has observed an
intermittent missing TCP EOF in the shared relay/tunnel integration harness;
this branch has not established its cause or a fix.

The second full campaign ran at `f8fb80afaa59b1f313c885be3a245c95856d34c6`
from 2026-09-25 18:13:42 UTC to 18:31:56 UTC; its Gradle command reported
18m 30s. It used the same Windows build, CPU, memory, JDK, and Rust toolchain
as the first campaign. The raw JSONL has exactly 4,350 rows, each marked
passed, with 1,450 rows per repetition. The result directory contains no
retained failed fixture. This closes the synthetic campaign gate for the
one-write change, not the manual game or power-loss gates.

Exact local commands for the synthetic checks:

```powershell
# Set JAVA_HOME to a local JDK 21 installation before running Gradle.
.\gradlew.bat --no-daemon :bta-mod:test --rerun-tasks
powershell -ExecutionPolicy Bypass -File .\scripts\run_controller_fault_campaign.ps1 -Profile smoke
powershell -ExecutionPolicy Bypass -File .\scripts\run_controller_fault_campaign.ps1 -Profile full
```

The controller runner records its own command, machine, toolchain, commit, seed
count, and result location. Do not run the two integration harnesses together;
they share relay ports. The commands for them are in [Building](building.md).

### Preliminary pre-launch timing

An opt-in test probe now measures from the call to
`continueAfterWorldClosed()` until the `BEFORE_SUPERVISOR_LAUNCH` callback.
It uses a fresh synthetic 2 MiB world per sample, LAN mode, no mods, and a fake
installed runtime. The callback records `System.nanoTime()` and stops before
`ManagedServerProcess.start()`, so it launches no server. Validation and
fixture creation are outside the interval. Both Live and Showcase receive five
warmups and 30 measured samples per round. This is an **instrumented synthetic
pre-launch measure**, not a release-build or real-game startup measurement.

The historical `05d0c2d4c54a0525316aa2bdd2621d73f0c20456` baseline has
no fault seam. A detached baseline checkout receives the exact test-only
source patch in `scripts/prelaunch-baseline-instrumentation.patch` plus the
shared `HostingFaults.java` and `PrelaunchTimingTest.java` probe. The branch's
production constructor continues to use `HostingFaults.NONE`; the timing test
calls only its package-private constructor. To reproduce from a **fresh,
clean, detached** historical worktree:

```powershell
$baselineCheckout = Join-Path (Split-Path (Get-Location) -Parent) 'BTA-crash-perf-baseline'
# Set JAVA_HOME to a local JDK 21 installation before running the probe.
git worktree add --detach $baselineCheckout 05d0c2d
powershell -ExecutionPolicy Bypass -File .\scripts\prepare_prelaunch_baseline.ps1 `
  -BaselineCheckout $baselineCheckout
powershell -ExecutionPolicy Bypass -File .\scripts\run_prelaunch_timing.ps1 `
  -BaselineCheckout $baselineCheckout `
  -JavaHome $env:JAVA_HOME -Rounds 3
```

Run that only during a quiet machine window; the runner alternates baseline and
candidate and retains raw JSONL, per-run Gradle logs, machine/commit manifest,
and JSON/Markdown summary under ignored `.dev/prelaunch-timing/run-*`.
`scripts/summarize_prelaunch_timing.py` validates all 70 rows per run and
retains the original 5% threshold. The detached historical checkout is
deliberately dirty solely for the identical probe; never describe it as an
unmodified baseline binary.

One exploratory pair on 2026-09-25 passed the probe itself but overlapped an
independent relay EOF diagnostic load, so **it is confounded and cannot decide
the gate**. The preliminary medians were Live 29.036 ms baseline versus
32.503 ms candidate (+11.94%), and Showcase 15.515 ms versus 18.782 ms
(+21.05%). Raw rows are at `.dev/prelaunch-timing/candidate-1.jsonl` and the
detached checkout's `.dev/prelaunch-timing/baseline-1.jsonl`; the generated
summary is `.dev/prelaunch-timing/preliminary-summary/summary.md`. Both
preliminary increases exceed 5%. Repeat in an alternating quiet window before
concluding whether the gate fails. The added launch-intent journal publication
may be a safety cost, but that is an inference, not a measured cause; a larger
cost needs a separate documented safety justification and review approval.

Three quiet alternating rounds on 2026-09-25 at candidate `9fd7e32` provide
the first usable **instrumented synthetic** comparison. All six Gradle probe
runs succeeded and each wrote 70 rows. The retained directory is
`.dev/prelaunch-timing/run-a8501e3959a94809a7b5f5646194ff44/`, including
the machine manifest, six raw JSONL files, six logs, and generated summary.

| Mode | Historical baseline median | `9fd7e32` median | Increase | 5% gate |
|---|---:|---:|---:|---|
| Live | 29.594 ms | 30.718 ms | +3.80% | PASS |
| Showcase | 15.729 ms | 18.279 ms | +16.21% | FAIL |

Showcase exceeded 5% in every round (+20.44%, +20.02%, +7.01%). The candidate
performed one more atomic journal publication before supervisor launch than
the historical baseline. That write is the smallest relevant extra operation
identified in the timing interval; the comparison alone does not isolate its
cost. The current experimental adjustment sets `launchIntent=true` in the
first journal publication after backup/copy and omits the redundant
pre-supervisor publication. An incomplete process identity already blocks
opening or restoring the original, regardless of the launch-intent flag.
Recording intent earlier therefore preserves the fail-closed recovery rule;
it can only increase the period in which an interrupted pre-launch setup
requires manual inspection. The post-launch publication of verified process
identity remains.

At candidate `a10a2f5`, the mod test suite passed after the change and the
same alternating synthetic probe ran three quiet rounds with a clean candidate
worktree. All six probe runs passed. Raw evidence is at
`.dev/prelaunch-timing/run-0e4719784f0d4c53b1460b3bbb1ac58d/`.

| Mode | Historical baseline median | `a10a2f5` median | Increase | 5% synthetic probe |
|---|---:|---:|---:|---|
| Live | 30.036 ms | 29.685 ms | -1.17% | PASS |
| Showcase | 16.184 ms | 16.324 ms | +0.86% | PASS |

Showcase round changes were +10.27%, +3.68%, and -5.89%; retain this spread
alongside the pooled median. The improvement after removing the extra
publication supports that operation as the likely cause of the earlier
slowdown, but the test did not isolate write time and remains synthetic.
The full 4,350-case fault campaign was repeated for the same production code
at `f8fb80a` after the evidence document changed; all scenarios passed as
recorded above.

## Outstanding gates and exact manual procedure

No disposable BTA game profile/world was designated for this review. The five
real-game interruptions and the release matrix in [Releasing](releasing.md)
remain **unavailable**, not passed. Before running them, create a new temporary
game profile and a new disposable world under it. Record the absolute game
directory and a pre-test SHA-256 manifest of that world. Verify the profile
contains no user save before launch. Use only the official pinned server
archive through the normal verified installer. Preserve sanitized logs,
recovery files, and a post-test manifest for each scenario.

Run each of these as a separate new disposable-world scenario in both Live and
Showcase where applicable:

1. Terminate the BTA client during the save/handoff or managed session.
2. Terminate the recorded supervisor after verifying PID, start time, and
   executable; never choose a process from PID alone.
3. Terminate the recorded server child with the same identity checks.
4. Force a Windows shutdown during a managed session, then inspect recovery
   after reboot before opening the world.
5. Place malformed or truncated recovery data only in the disposable profile
   while all matching managed processes are stopped; verify the recovery screen
   fails closed and gives an actionable inspection path.

For every scenario, record whether the original world is unchanged unless a
Live server write completed, whether client and server were ever simultaneous
owners, the state of partial backup/copy and `recovery.json[.tmp]`, exact
process identities and control-file match, blocked/allowed recovery actions,
and whether any restore required confirmation. Do not manually clear an
ambiguous journal merely to make a scenario pass. Do not commit worlds,
archives, private control files, tokens, or raw private logs.

The definitive median **pre-server-launch orchestration** comparison against
the historical baseline remains open. The probe above uses a test callback to
end the interval and a test-only patch in the historical checkout; it is not
release-build or real-game evidence. A release-build equivalent is needed
before review. A median increase above 5% blocks review unless a separate
safety justification is accepted. Relay throughput is not a substitute for
this measure. The data-path common benchmark and two-hour soak gates also
remain open until the benchmark foundation and EOF investigation are complete.

## Review scorecard

This scorecard reports the W2 source evidence and its outstanding gates. Its
PASS entries are design or earlier-branch findings, not test results for the
stacked merge commit; the stacked candidate requires its own complete review.

| Area | Result | Reason |
|---|---|---|
| User value | PASS | Interrupted sessions retain evidence and block unsafe reopen/stop actions. |
| Scope | PASS | Windows x86-64, existing LAN/Direct/self-hosted Relay preserved. |
| Correctness | FAIL | Synthetic checks pass; five consecutive full integration runs are absent. |
| Failure safety | FAIL | Synthetic oracle passes; five real-game interruption scenarios are absent. |
| Security | PASS | Identity matching and authenticated stop were strengthened; no auto restore. |
| Benchmarks | FAIL | Earlier same-machine instrumented synthetic timing is retained, but release-build and stacked-commit comparisons are absent. |
| Performance | FAIL | The 5% synthetic probe passed on earlier W2 code; the stacked release-build gate is unmeasured. |
| Maintainability | PASS | Short CI matrix; long 4,350-case campaign is opt-in and bounded. |
| Documentation | FAIL | Manual game scenarios have a procedure but no completed evidence. |
| Claims | PASS | This record labels synthetic, measured, prior, skipped, and unavailable checks. |

Any FAIL blocks merge. Roll back this branch by keeping `main` unchanged; do
not merge it. If a deployed version ever needed rollback, stop exposure and
verify both managed process identities before replacing the mod. Preserve
recovery journals and backups for manual inspection; rollback must never edit
or restore a world automatically.

## Interview-safe summary

Implemented and tested a fail-closed hosting recovery path on Windows. A
test-only fault seam exercised 15 lifecycle boundaries across Live and
Showcase; 4,350 synthetic controller scenarios passed with byte-for-byte
original-world manifests and authenticated process cleanup. This is evidence
for the tested failure model, not proof of real-game crash or power-loss
recovery. The branch is experimental until manual disposable-world and
performance gates pass.
