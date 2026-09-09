# BTA Anywhere

BTA Anywhere turns a Better Than Adventure! 8.0.1 single-player world into a temporary dedicated server, then exposes it over LAN, automatic router mapping, or a self-hosted QUIC relay. Friends join with an ordinary BTA 8.0.1 client; they do not install BTA Anywhere, although their gameplay mods must match the host.

> [!IMPORTANT]
> BTA Anywhere does not operate a public relay. Direct mode depends on the host's router and ISP. Relay mode works only after you configure a relay you trust.

v0.1 supports Windows x86-64 only. On Windows ARM64, use an x86-64 Java/BTA runtime under Windows emulation.

## Hosting modes

| Mode | Address shown | Best for | Important limitation |
| --- | --- | --- | --- |
| LAN | Local network address | Players on the same network | Not reachable from the public internet |
| Direct | Router's public mapping | Internet hosting without a relay | PCP, NAT-PMP, or UPnP and a usable public address must be available; otherwise manual TCP forwarding is required |
| Relay | Allocated relay host and TCP port | NATs that cannot accept inbound traffic | Requires a self-hosted relay; guest-to-relay game traffic is not generally encrypted |

## Install and host

1. Install the BTA 8.0.1 Babric profile and HalpLibe 6.2.0+8.0.1.
2. Download `bta-anywhere-0.1.0+bta8.0.1.jar` from [GitHub Releases](https://github.com/Kxnar/bta-anywhere/releases) and place it in the client's `mods` directory.
3. Open a disposable single-player world first. Press Escape, select **Host World**, choose **Live world** or **Showcase copy**, enter the invited usernames, and choose a network mode.
4. Confirm the official BTA server download on first use. The archive is accepted only when its SHA-256 is `18a8dc132e9c08f9cc6928ac00cd05d2d9450fd98cb26eb8fb732455bf9011f4`.
5. Wait for the host to reconnect through `127.0.0.1`, then share the displayed address.

Live mode closes the client world before the dedicated server opens it and creates a timestamped backup. Showcase mode runs an isolated copy and leaves the original world untouched. See the [full quick-start](docs/quick-start.md) and [recovery guide](docs/recovery.md) before using a valuable world.

## What v0.1 includes

- A Rust 2024 relay using Tokio, Quinn QUIC, per-session public TCP ports, hashed access tokens, quotas, leases, health checks, and Prometheus metrics.
- A Minecraft-independent Java 17 tunnel API and CLI with bounded Netty bridges, half-close support, reconnect/resume, explicit CA trust, and a Windows x86-64 native QUIC transport.
- A client-only Babric/HalpLibe mod with safe world handoff, atomic backups, showcase copies, official-server verification, mod mirroring, readiness detection, authenticated process supervision, and crash recovery.

Netty QUIC 0.0.73.Final does not publish a Windows ARM64 native classifier, so every v0.1 artifact targets Windows x86-64.

## Build Instructions

Use JDK 21 to run Gradle, Java release 17 for compiled code, and Rust 1.85 or newer:

```powershell
.\gradlew.bat check build
cargo fmt --all -- --check
cargo clippy --locked --all-targets -- -D warnings
cargo test --locked --all
```

Install the pinned, checksum-verified portable Temurin build with `scripts/bootstrap-jdk.ps1`. Complete setup and integration-test commands are in [Building](docs/building.md).

## Security and privacy

Relay mode is not end-to-end encrypted. The guest connects to the relay using BTA's ordinary TCP protocol; QUIC/TLS 1.3 protects only the relay-to-host hop. A relay operator can observe guest addresses, timing and volume metadata, and most game traffic. Read [Privacy](docs/privacy.md) and [Security](docs/security.md) before operating or trusting a relay.

## Documentation

- [Quick-start](docs/quick-start.md)
- [Building and testing](docs/building.md)
- [Architecture](docs/architecture.md)
- [Protocol v1](docs/protocol.md)
- [Self-hosting a relay](docs/self-hosting.md)
- [Recovery](docs/recovery.md)
- [Troubleshooting](docs/troubleshooting.md)
- [Release process](docs/releasing.md)

Licensed under [Apache-2.0](LICENSE). Dependency and design-reference acknowledgements are in [Third-party notices](THIRD_PARTY_NOTICES.md).
