# Troubleshooting

## Loopback benchmark fails

Run the short profile from [Benchmarking](benchmarking.md) after building the
release relay and shaded tunnel. Check that no other integration harness owns
the selected local ports, the JDK is x86-64, and the JSON `failure` and bounded
`diagnostics` fields identify the case. Keep raw machine-specific files in the
ignored `benchmark-results/` directory. A latency or throughput regression
needs repeat runs on the same machine before interpretation.

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

## Native QUIC library fails to load

The release JAR contains the Windows x86-64 native. Use an x86-64 Java runtime. Netty QUIC 0.0.73.Final does not publish Windows ARM64; use an x86-64 Java/BTA runtime under Windows ARM64 emulation.

## Guest receives every byte but waits for TCP EOF

A complete byte count does not prove that the half-close completed. On an
eight-stream Windows reproduction, the host's QUIC shutdown callback succeeded
but the relay did not observe a stream FIN. The exact transport cause remains
unknown. Current matching host and relay builds negotiate `streamEofBytes` and
also send an authenticated response-byte count so the relay can close guest TCP
output after the exact response has been copied. An old relay that lacks this
capability causes an actionable registration failure; upgrade the self-hosted
relay and host together. Do not bypass certificate checks or increase timeouts
to hide a missing EOF.

For a disposable synthetic reproduction, set `BTA_EOF_TRACE=1` for the relay
and `-Dbta.anywhere.traceEof=true` for the Java tunnel, then run the serial and
concurrent commands in [Building and testing](building.md) sequentially. Both
traces use the same shortened SHA-256 digest of the per-stream connection ID.
They record half-close events and byte counts without payloads or tokens, are
off by default, and stop after 4,096 lines per process. The concurrent harness
retains at most 10,000 subprocess lines when tracing is enabled. A successful
Java `quic_shutdown_complete` means the FIN was accepted locally; it does not
prove that the relay received it. A `relay_control_eof` trace records completion
through the negotiated notice after the announced byte count was copied. If
the guest still waits, retain the bounded trace privately and compare its
sanitized byte counts and connection-ID digests on both sides. Keep raw traces
private and remove network metadata before sharing a minimal excerpt.

## Information for a bug report

Include the Windows version and architecture, BTA/Babric/HalpLibe versions, network/world mode, exact failure state, sanitized server/mod logs, and steps using a disposable world. Remove tokens, private keys, public/private player data, IP addresses, and saves unless a maintainer asks for a minimal private reproduction.
