# Security

## Trust model

The host trusts the selected relay with availability, traffic metadata, and plaintext access to guest game packets. Guests trust the host and, in Relay mode, the relay. BTA Anywhere does not make an untrusted relay safe.

The relay access token controls who may allocate relay ports. It is not a guest password. Guest authentication remains BTA online mode plus the server whitelist, both enabled by default.

## Implemented controls

- TLS 1.3 and fixed ALPN on the host tunnel, with explicit CA/certificate trust, leaf-certificate hostname verification, and no insecure mode.
- SHA-256-only relay token storage, constant-time comparisons, 256-bit session/resume/control secrets, and redacted Java secret rendering.
- Framed-message size limits before allocation and raw bounded-buffer streaming with backpressure.
- Per-token, per-host-address, per-session, and per-guest-address quotas.
- Loopback-only admin endpoint by default and sensitive-data-free metric labels.
- Official server archive pinning, a 256 MiB download cap, traversal/absolute-path/symbolic-link rejection, and staged extraction.
- World path confinement, symbolic-link rejection, disk-headroom checks, atomic backup/journal writes, and no automatic restore.
- Process recovery matching on PID, start time, and executable; supervisor commands also require a private random control token.
- Online mode, whitelist, maximum-player limit, automatic host operator entry, and warning confirmation before whitelist disablement.

## Operator responsibilities

- Keep relay access tokens, private keys, config files, game saves, backups, and supervisor control files private.
- Use a publicly trusted certificate or distribute a private CA through an authenticated channel. Never send the CA private key to hosts.
- Bind the admin endpoint to loopback or an authenticated private monitoring network.
- Run the relay as an unprivileged dedicated account and restrict filesystem permissions.
- Apply OS updates, monitor connection/rejection rates, set firewall rules, and cap process resources.
- Treat every exposed BTA server and gameplay mod as remotely reachable code.

## Known limitations

- Guest game traffic is not end-to-end encrypted and can be inspected or changed by the relay.
- v0.1 has no guest companion authentication layer, broker, multi-region routing, DDoS absorption, hole punching, or public relay service.
- Source-IP quotas are in-memory and per relay process. Restarting the process clears them.
- Session resume is in-memory. A network interruption can retain the same port during the grace window; a relay process restart creates a fresh registration, often but not contractually on the same port.
- BTA and third-party mods were not designed as a modern hardened internet service. The whitelist reduces exposure but is not a substitute for patching or isolation.
- Windows ARM64 native QUIC is unavailable upstream for the pinned Netty QUIC release; the supported Windows path is x86-64 Java, including emulation on ARM64 Windows.

## Reporting a vulnerability

Follow [SECURITY.md](../SECURITY.md). Do not put active tokens, private keys, saves, private logs, or working exploitation details in a public issue.
