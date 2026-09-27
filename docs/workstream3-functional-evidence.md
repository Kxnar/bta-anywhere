# Workstream 3 functional review evidence (Windows x86-64)

Candidate branch: `codex/encrypted-guest-join`. This document records the local
functional review on 27 September 2026. It is separate from the [explicitly
deferred performance and soak gates](functional-merge-exception.md). No
benchmark or long load/soak result is claimed here.

## Builds and focused checks

Run from the W3 worktree:

```powershell
cargo build --locked --release --package bta-anywhere-relay
.\gradlew.bat --no-daemon :tunnel-client:shadowJar
cargo fmt --all -- --check
python scripts\check_rust_licenses.py
cargo clippy --locked --all-targets -- -D warnings
cargo test --locked --all
.\gradlew.bat --no-daemon check build
java -jar tunnel-client\build\libs\bta-anywhere-tunnel-0.1.0-all.jar doctor
python -m unittest discover -s scripts -p test_protocol_campaign.py
python -m unittest discover -s scripts -p test_protocol_long_campaign.py
```

Results: release relay and shaded JAR built; formatting passed after commit
`3b423b3`; licence check validated 220 packages; Clippy passed; Rust unit and
corpus targets passed (22 and 12 tests); Gradle `check build` passed after the
final CLI change; native QUIC doctor passed; Python protocol campaign unit
tests passed (20 and 3 tests). The initial formatting check failed only on
the new v2 vector test's line layout and was corrected without changing its
assertions.

The deterministic cross-language smoke used:

```powershell
python scripts\protocol_campaign.py --seed 20260925 --count 100 `
  --output .dev\protocol-campaign\w3-functional-20260927-02
```

It passed with 100 cases and zero mismatches. An earlier attempt in the `-01`
output directory stopped before evaluation because Windows denied replacing
the release relay executable while a disposable relay process held it open;
the retained summary records `evaluator_failed`, not a protocol pass.

## Encrypted join/security smoke

```powershell
python scripts\encrypted_join_smoke.py `
  --relay-binary target\release\bta-anywhere-relay.exe `
  --tunnel-jar tunnel-client\build\libs\bta-anywhere-tunnel-0.1.0-all.jar
```

Passed after the final CLI change. It covers basic/icon status, rate limits,
malformed prefaces, byte-exact half-close, replay, fresh-invitation wrong
host/relay pins, tampered capability, wrong host session, expired display
time, revocation, plaintext rejection, admission before local sockets,
guest shutdown, active host disconnect, and listener cleanup. The original invitation remained
usable after all mutated-invitation attempts. An earlier test run failed
because the test used a 16-byte rather than 32-byte session ID mutation; the
test vector was fixed, then the complete smoke passed twice.

## Five clean legacy Relay integration pairs

Each of five clean runs executed the serial harness, waited for it to finish,
then executed the concurrent harness. The two processes never overlapped:

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

| Run | Serial 100 iterations | Concurrent 100 waves of eight |
| --- | --- | --- |
| 1 | PASS, byte-exact EOF | PASS, auth/quota/restart/cleanup |
| 2 | PASS, byte-exact EOF | PASS, auth/quota/restart/cleanup |
| 3 | PASS, byte-exact EOF | PASS, auth/quota/restart/cleanup |
| 4 | PASS, byte-exact EOF | PASS, auth/quota/restart/cleanup |
| 5 | PASS, byte-exact EOF | PASS, auth/quota/restart/cleanup |

These are functional integrity and concurrency checks. They are not the
deferred throughput, latency, memory, or two-hour soak measurements.

## Disposable real-client join

Two local runs around 01:09 BST on 27 September 2026 used an isolated temporary
Prism root, a fresh temporary client game directory, the offline test identity
`W3GuestTmp`, and a fresh disposable BTA 8.0.1 world bound to loopback. The
actual BTA 8.0.1 client entered the world through the encrypted companion: the
server logged that the player logged in with an entity ID and spawn position,
the client logged receipt of all 687 server recipes, and the orchestrator
observed the client remain connected at least five seconds on each run.
No client-side in-world screenshot was retained. The server was offline-mode,
so account authentication was not tested. The host, guest companion, relay,
server, and client processes were stopped after the attempts.

The one-off entry command was:

```powershell
python C:\Users\knara\AppData\Local\Temp\bta-w3-real-client-20260927\run_real_client_join.py
```

That temporary helper passed the invitation to the guest companion through
stdin and launched the BTA client main class using the isolated profile and
game directory. The redacted local summary is at
`C:\Users\knara\AppData\Local\Temp\bta-w3-real-client-20260927\real-client-join-summary.txt`.
Task setup and test took about 11 minutes (00:59–01:10 BST). Prism Launcher's
first-run wizard could not finish with an offline account, and the isolated
client initially lacked the official Beta 1.7.3 base JAR and sound libraries.
Only those verified client binaries were copied/downloaded into the temporary
runtime; no existing account or save data was used.

## Open review items

A relay-side packet/log inspection for recoverable game payload or invitation
material has not yet been recorded. Until that security check is verified,
the functional verdict is **not ready**. The deferred performance and soak
gates remain open independently.
