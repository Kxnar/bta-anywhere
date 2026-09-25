# Troubleshooting

## Relay is not selectable

Relay mode is skipped until all of these are valid in `<game-directory>/config/bta-anywhere.json`: hostname, port 1–65535, non-empty access token, and a certificate path resolving to a regular file beneath the game directory. Close and reopen BTA after editing the file.

## Certificate or QUIC handshake failure

- Confirm UDP 25575 reaches the relay; opening only TCP is insufficient.
- Confirm `relayHost` matches a DNS name or IP in the certificate.
- Confirm the configured PEM is the issuer/trust anchor for the relay's served chain.
- Confirm both sides use ALPN `bta-anywhere/1` and protocol v1.
- Never work around this with a trust-all TLS configuration; none is implemented.

Use the standalone CLI's `doctor` command to distinguish native-library loading from network/TLS failures.

## Direct mode displays `<your-public-ip>`

No PCP, NAT-PMP, or UPnP gateway returned a usable public address. Causes include router support being disabled, a host firewall, multiple nested routers, ISP CGNAT, or a private/CGNAT mapping result.

Forward the selected TCP port manually to the host computer and use the actual public address, or use a self-hosted relay. Do not share the placeholder literally. Router mapping cannot bypass ISP CGNAT.

## LAN guest cannot connect

- Use the displayed private address from the same network, not `127.0.0.1`.
- Allow the current Java executable through the firewall for the intended network profile.
- Confirm the selected TCP port is listening and not occupied by another service.
- Ensure client isolation is disabled on the Wi-Fi network.
- Confirm the guest uses BTA 8.0.1 and the required gameplay mods.

## Server download fails

The only accepted archive is `bta_fabric_server_8.0.1.zip` from the official Turnip Labs release with SHA-256 `18a8dc132e9c08f9cc6928ac00cd05d2d9450fd98cb26eb8fb732455bf9011f4`. A mismatch is a hard failure. Do not rename an arbitrary server archive or change the pinned hash to make the check pass.

Delete an incomplete `.part-*` file only when BTA is closed; normal cleanup removes it automatically. Check proxy/firewall access to GitHub Releases and available disk space.

## Readiness times out

BTA Anywhere requires both a native `Done (` log line and a successful localhost TCP probe. Check the newest file under `bta-anywhere/logs/` for a crash, incompatible mod, port conflict, memory failure, or malformed configuration. Startup times out after 180 seconds.

## Mod dependency closure fails

The mod mirror excludes BTA Anywhere and metadata-marked client-only mods. The official server already provides HalpLibe. A server-compatible mod cannot depend on a missing or client-only mod. Correct the mod's environment metadata or install a server-compatible dependency; do not force-copy a known client-only JAR.

The generated `bta-anywhere/server/guest-mods.txt` lists gameplay mods guests need, with versions and SHA-256 hashes.

## World will not reopen

Do not bypass the guard while a matching managed server or supervisor is alive. Use the recovery screen and [Recovery](recovery.md). If a forced stop was needed, the journal and showcase copy are retained intentionally.

If the screen reports incomplete process identity, malformed journal data, `recovery.json.tmp`, or a live PID with a different identity, keep the original save closed. Preserve those files and the server log, then follow the manual process inspection in [Recovery](recovery.md). Do not stop a process merely because its PID appears in the journal. A partial backup or showcase copy is a diagnostic artifact, never a source for automatic restore.

## Native QUIC library fails to load

The release JAR contains the Windows x86-64 native. Use an x86-64 Java runtime. Netty QUIC 0.0.73.Final does not publish Windows ARM64; use an x86-64 Java/BTA runtime under Windows ARM64 emulation.

## Information for a bug report

Include the Windows version and architecture, BTA/Babric/HalpLibe versions, network/world mode, exact failure state, sanitized server/mod logs, and steps using a disposable world. Remove tokens, private keys, public/private player data, IP addresses, and saves unless a maintainer asks for a minimal private reproduction.
