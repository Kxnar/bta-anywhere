# Building and testing

v0.1 builds and tests on Windows x86-64 only.

## Toolchains

- Gradle wrapper 9.3.1, run on JDK 21.
- Java source and bytecode release 17.
- Rust 1.85 minimum; CI deliberately uses 1.85.0.
- Git for version control and Python 3 for the cross-language integration test.

Dependency versions are locked by `Cargo.lock` and Gradle lockfiles. The Gradle wrapper distribution has a pinned SHA-256. No Minecraft, BTA, save, server-distribution, token, or generated certificate file belongs in Git.

## Windows portable JDK

PowerShell users can install the exact Temurin 21 build used during initial development into the ignored `.tools` directory:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\bootstrap-jdk.ps1
$env:JAVA_HOME = (Resolve-Path .\.tools\jdk-21)
.\gradlew.bat --version
```

The bootstrap script downloads Temurin 21.0.12.1+1 for Windows x86-64 and verifies SHA-256 `f9d6e191ab098c0d416e7d588a24420a8621cd2f4720dab2459b8b7b2d2d8b4e` before extraction.

## Automated gates

```powershell
cargo fmt --all -- --check
python scripts\check_rust_licenses.py
cargo clippy --locked --all-targets -- -D warnings
cargo test --locked --all
.\gradlew.bat --no-daemon check build
```

Build outputs are:

- `target/release/bta-anywhere-relay.exe`
- `tunnel-client/build/libs/bta-anywhere-tunnel-0.1.0-all.jar`
- `bta-mod/build/libs/bta-anywhere-0.1.0+bta8.0.1.jar`

Run the shaded-native Windows smoke test:

```powershell
java -jar tunnel-client/build/libs/bta-anywhere-tunnel-0.1.0-all.jar doctor
```

## Local full-stack test

Build the release relay and tunnel CLI, then let the standard-library integration harness create a private development CA/token, start an echo service, open eight concurrent streams, verify byte-exact half-closes and metrics, restart the relay, and verify recovery:

```powershell
cargo build --locked --release --package bta-anywhere-relay
.\gradlew.bat --no-daemon :tunnel-client:shadowJar
python scripts\cross_language_e2e.py `
  --relay-binary target\release\bta-anywhere-relay.exe `
  --tunnel-jar tunnel-client\build\libs\bta-anywhere-tunnel-0.1.0-all.jar `
  --java .tools\jdk-21\bin\java.exe `
  --concurrency-waves 100
```

`--concurrency-waves` accepts 1-100 waves of eight simultaneous byte-exact streams. CI uses the maximum 100-wave stress gate.

## Windows half-close smoke test

Run the serial smoke test to validate the complete Windows relay-to-tunnel path.
It starts temporary development credentials, exposes one local echo socket
through the production tunnel, and sends exactly one labelled bidirectional
stream at a time. Each iteration asserts byte-exact payload delivery, TCP EOF,
and relay task cleanup before the next connection.

```powershell
python scripts\windows_half_close_smoke.py `
  --relay-binary target\release\bta-anywhere-relay.exe `
  --tunnel-jar tunnel-client\build\libs\bta-anywhere-tunnel-0.1.0-all.jar `
  --java .tools\jdk-21\bin\java.exe `
  --iterations 100 --event-loop-threads 1
```

`--payload-bytes` is the complete labelled transfer length and defaults to the
45,076-byte regression size. Failure output includes the iteration label, guest
source port, relay metrics, and bounded process logs.

## Protocol conformance campaign

On Windows x86-64, run the seeded short corpus after setting JDK 21 as `JAVA_HOME`:

```powershell
python scripts\protocol_campaign.py --seed 20260925 --count 10000 `
  --output .dev\protocol-campaign\seed-20260925
```

The command runs the Rust release test evaluator and Java test evaluator against identical framed bytes. It writes `corpus.jsonl`, both raw outcome files, and `summary.json` beneath the ignored output directory. The summary separates framing/JSON equivalence from typed control/header and modelled state/authentication decisions. It exits nonzero for any difference. The corpus includes exact 0, 1, 65,536, and 65,537 byte boundaries, truncation, invalid UTF-8, duplicate and unknown fields, deep nesting, invalid authentication, wrong version, and wrong registration phase. The evaluator has a 600-second timeout per process; the 64 KiB wire cap is unchanged.

`python -m unittest discover -s scripts -p test_protocol_campaign.py` checks deterministic generation, exact frame boundaries, and failure aggregation without launching either runtime. A short cross-language CI profile is proposed but not wired into the existing workflow while the documented conformance differences remain unresolved; the raw 10,000-case command currently exits nonzero.

For a long manual campaign, run:

```powershell
python scripts\protocol_long_campaign.py --seed 20260925 `
  --minutes-per-target 30 --batch-count 10000 --max-wall-hours 12 `
  --max-rss-mib 512 --output .dev\protocol-campaign\long-20260925
```

This measures evaluator process CPU time for framing, typed control, connection headers, and modelled state separately; completion requires at least 30 CPU minutes in each target. Each batch has a 600-second timeout, Java uses a 256 MiB heap, and the runner fails if a process exceeds the configured peak resident-memory ceiling. `long-summary.json` and `batches.jsonl` record seeds, case counts, CPU and wall time, memory, and bounded mismatch details. The command stops on the first mismatch and retains its raw batch. Do not count a stopped campaign as completed CPU-hours. Only the short corpus belongs in ordinary CI; a full campaign is manual or scheduled. Rerun both campaigns after the structural-rule change; the earlier 556-mismatch result is historical and cannot establish the candidate's current conformance.

## Manual tunnel test

```powershell
bta-anywhere-relay init-dev --output .dev/relay
bta-anywhere-relay run --config .dev/relay/relay.toml
python -m http.server 8000 --bind 127.0.0.1
java -jar bta-anywhere-tunnel-0.1.0-all.jar expose `
  --relay localhost:25575 `
  --ca .dev/relay/trust.pem `
  --token-file .dev/relay/access.token `
  --local 127.0.0.1:8000
```

`init-dev` keeps the CA-only certificate in `ca.pem` and writes `trust.pem` for clients. The client
bundle contains the pinned localhost leaf followed by the development CA; it is public certificate
material, not a private key. Regenerate the whole development directory when the leaf expires.

Open the printed relay TCP endpoint from another terminal. Type `stop` in the CLI to close it cleanly.

## BTA development launch

`.\gradlew.bat :bta-mod:runClient` downloads development-only BTA artifacts through Loom and opens a client. Use a disposable game directory/world. Do not publish anything from `run/` or the Gradle/Loom caches.

Before a release, complete the manual matrix in [Releasing](releasing.md). Automated tests do not prove that every third-party gameplay mod is dedicated-server compatible.
