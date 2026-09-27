# Contributing

This is still in progress. Small fixes and bug reports are welcome.

Run `.\gradlew.bat --no-daemon check` for Java changes and `cargo test --locked --all` for Rust changes. Keep `tunnel-client` independent of Minecraft, and update `protocol/test-vectors/` when the wire format changes.

Do not commit game files, worlds, credentials, or generated runtimes. For security issues, see [SECURITY.md](SECURITY.md).
