# Quick-start

This guide is for a host using the released BTA mod. Relay operators should also read [Self-hosting](self-hosting.md).

## Before the first session

1. Back up anything irreplaceable and start with a disposable world. v0.1 has automated safety checks, but its manual compatibility matrix still depends on the installed mod set and machine.
2. Use Better Than Adventure! 8.0.1 with the Babric loader profile.
3. Install HalpLibe 6.2.0+8.0.1 and the BTA Anywhere mod JAR in the client `mods` directory.
4. Make sure the volume containing the game directory has at least the selected world's size plus 512 MiB free.
5. If the OS firewall asks about Java, allow the network scope you intend to use. Do not expose the relay's admin port publicly.

Guests use BTA 8.0.1 and enter the displayed address in Multiplayer. They do not need BTA Anywhere. They do need every gameplay/content mod listed in the managed server's `guest-mods.txt`.

## Start hosting

1. Open the single-player world.
2. Press Escape and select **Host World**.
3. Select a world mode:
   - **Live world** creates a full ZIP backup, closes the client world, and serves the original save. The newest five successful backups for that world are retained.
   - **Showcase copy** copies the world and serves the copy. A clean stop deletes the copy; a crash retains it for inspection or recovery.
4. Select a network mode:
   - **LAN** binds the server to all interfaces and displays a local address.
   - **Direct** asks the router for a one-hour TCP mapping in PCP, NAT-PMP, then UPnP order. The lease is renewed halfway through. If no public mapping is available, follow the displayed manual-forwarding instruction.
   - **Relay** binds the game server to loopback and opens the configured reverse tunnel. The button skips Relay when configuration is incomplete.
5. Enter invited usernames, maximum players, memory in MiB, and the game TCP port. The host is automatically added to the operator list; the host and invited users are added to the whitelist.
6. Leave the whitelist enabled. Disabling it requires a warning confirmation and allows anyone who learns the address to try joining; online mode remains enabled.
7. Select **Start Hosting**. On first use, confirm the official server download. BTA Anywhere verifies the pinned SHA-256 before extracting it.
8. Wait through validation, save/close, backup or copy, server startup, readiness, exposure, and reconnect. Share the address only after the screen reports **Hosting is active**.

The client and dedicated server never intentionally hold the same save open. The host plays through the dedicated server just like every other player.

## Stop hosting

Use **Manage Hosting** in the pause menu and select **Stop Hosting**. BTA Anywhere stops new exposure first, sends `stop` to the supervised server, waits up to 30 seconds, and terminates only as a last resort. It then reopens the original world when safe.

Leaving the managed multiplayer world or exiting BTA also stops the managed server. Unattended headless hosting is not supported in v0.1.

## Relay configuration

Relay mode has no default endpoint. Close BTA and edit `<game-directory>/config/bta-anywhere.json`:

```json
{
  "clientInstanceId": "keep-the-generated-value",
  "relayHost": "relay.example.net",
  "relayPort": 25575,
  "relayTrustedCertificate": "config/bta-anywhere-relay-ca.pem",
  "relayAccessToken": "your-private-access-token"
}
```

`relayTrustedCertificate` is resolved beneath the game directory and must name a PEM trust bundle.
Include the configured relay leaf certificate followed by its issuer chain; the development relay
generates this as `trust.pem`. There is no insecure certificate-verification switch. Treat the JSON
file as a secret because v0.1 stores the relay token in it.

## What to send guests

Send the connection address and `bta-anywhere/server/guest-mods.txt`. Do not send your relay token, CA private key, recovery journal, save, or the managed server archive.
