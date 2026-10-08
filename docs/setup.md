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
