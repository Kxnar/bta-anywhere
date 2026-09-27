# Development setup

Windows x86-64, JDK 21, and Rust 1.85+ are needed.

```powershell
.\gradlew.bat --no-daemon check build
cargo test --locked --all
cargo build --locked --package bta-anywhere-relay
```

To run the mod in a development client:

```powershell
.\gradlew.bat :bta-mod:runClient
```

Use a disposable world.
