# Development setup

Windows x86-64, JDK 21, and Rust 1.85+ are needed.

```powershell
.\gradlew.bat --no-daemon check build
cargo test --locked --all
cargo build --locked --package bta-anywhere-relay
```

For measured relay runs, build the release executable and follow the
[benchmark procedure](benchmarking.md). Debug builds are for development only.

To run the mod in a development client:

```powershell
.\gradlew.bat :bta-mod:runClient
```

Use a disposable world.

`check` also resolves the development client's locked runtime dependencies.
Development clients launched without an authenticated session cannot join the
managed server, which enables online authentication. A multiplayer demonstration
requires two signed-in BTA 8.0.1 clients in separate game directories. See the
[retained first journey attempt](validation/2026-10-08-journey/README.md) and
[validation status](validation-status.md).
