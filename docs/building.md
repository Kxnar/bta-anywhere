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

## Disposable-world recovery fault simulation

The Java test suite runs one deterministic seed at every instrumented hosting boundary for Live and Showcase where applicable. It uses temporary synthetic worlds, never a player save. Run the longer file/journal fault simulation with 50 seeds and three clean repetitions:

```powershell
$env:BTA_FAULT_SEEDS = '50'
$env:BTA_FAULT_RUNS = '3'
.\gradlew.bat --no-daemon :bta-mod:test --tests io.github.kxnar.btaanywhere.mod.hosting.CrashFaultCampaignTest --rerun-tasks
```

Unset those environment variables to return to the short CI profile. The injected exceptions exercise bounded backup, copy, journal, and recovery handling. They do not establish power-loss durability or replace the five disposable-world manual scenarios in [Releasing](releasing.md). The production constructor has no injection switch; only package-private test construction can supply fault callbacks.

For a full controller transition sweep with the synthetic disposable server, run:

```powershell
$env:BTA_CONTROLLER_MATRIX = '1'
.\gradlew.bat --no-daemon :bta-mod:test --tests io.github.kxnar.btaanywhere.mod.hosting.HostControllerFaultTest --rerun-tasks
```

The normal test suite also runs representative hard-crash child JVMs. File and journal crashes retain hidden partial artifacts or an incomplete journal for inspection. The controller crash fixture stops its fake supervisor only after matching the supervisor and server PID, start time, and executable with the private control file, then sending authenticated `STOP`. It preserves the fixture if that verification fails. These tests use a fake server and a synthetic world; the BTA game-thread save/unload path and forced-shutdown scenarios still require manual disposable-world testing.
