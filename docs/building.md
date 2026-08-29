# Building and testing

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

The bootstrap script downloads Temurin 21.0.12.1+1 for Windows x86-64 and verifies SHA-256 `f9d6e191ab098c0d416e7d588a24420a8621cd2f4720dab2459b8b7b2d2d8b4e` before extraction. Other platforms should install a trusted JDK 21 distribution normally.

## Automated gates

```text
cargo fmt --all -- --check
python3 scripts/check_rust_licenses.py
cargo clippy --locked --all-targets -- -D warnings
cargo test --locked --all
./gradlew --no-daemon check build
```

On Windows, replace `./gradlew` with `.\gradlew.bat`. Build outputs are:

- `relay/../target/release/bta-anywhere-relay[.exe]`
- `tunnel-client/build/libs/bta-anywhere-tunnel-0.1.0-all.jar`
- `bta-mod/build/libs/bta-anywhere-0.1.0+bta8.0.1.jar`

Run the shaded-native smoke test on every target OS:

```text
java -jar tunnel-client/build/libs/bta-anywhere-tunnel-0.1.0-all.jar doctor
```

## Local full-stack test

Build the release relay and tunnel CLI, then let the standard-library integration harness create a private development CA/token, start an echo service, open eight concurrent streams, verify byte-exact half-closes and metrics, restart the relay, and verify recovery:

```text
cargo build --locked --release --package bta-anywhere-relay
./gradlew --no-daemon :tunnel-client:shadowJar
python3 scripts/cross_language_e2e.py \
  --relay-binary target/release/bta-anywhere-relay \
  --tunnel-jar tunnel-client/build/libs/bta-anywhere-tunnel-0.1.0-all.jar \
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

On Windows, supply `target\release\bta-anywhere-relay.exe` and use the portable JDK's `java.exe` with `--java` when Java is not on `PATH`.

## Manual tunnel test

```text
bta-anywhere-relay init-dev --output .dev/relay
bta-anywhere-relay run --config .dev/relay/relay.toml
python3 -m http.server 8000 --bind 127.0.0.1
java -jar bta-anywhere-tunnel-0.1.0-all.jar expose \
  --relay localhost:25575 \
  --ca .dev/relay/trust.pem \
  --token-file .dev/relay/access.token \
  --local 127.0.0.1:8000
```

`init-dev` keeps the CA-only certificate in `ca.pem` and writes `trust.pem` for clients. The client
bundle contains the pinned localhost leaf followed by the development CA; it is public certificate
material, not a private key. Regenerate the whole development directory when the leaf expires.

Open the printed relay TCP endpoint from another terminal. Type `stop` in the CLI to close it cleanly.

## BTA development launch

`./gradlew :bta-mod:runClient` downloads development-only BTA artifacts through Loom and opens a client. Use a disposable game directory/world. Do not publish anything from `run/` or the Gradle/Loom caches.

Before a release, complete the manual matrix in [Releasing](releasing.md). Automated tests do not prove that every third-party gameplay mod is dedicated-server compatible.
