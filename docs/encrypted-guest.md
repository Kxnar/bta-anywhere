# Experimental encrypted guest join (Workstream 3)

This branch adds a Windows x86-64 **CLI prototype** for a stock BTA 8.0.1 guest to join through a loopback companion. It has passed the synthetic local join/security smoke and two joins by an actual BTA 8.0.1 client into a disposable offline-mode world. The BTA mod hosting UI does not create encrypted invitations or select encrypted mode; use the CLI steps below with a separately started BTA server. This is not part of the v0.1 release.

## Start a self-hosted relay and host tunnel

For a disposable local test, build the relay and shaded tunnel JAR, then create development credentials and start the relay in separate PowerShell windows:

```powershell
cargo build --locked --package bta-anywhere-relay
.\gradlew.bat --no-daemon :tunnel-client:shadowJar
target\debug\bta-anywhere-relay.exe init-dev --output .dev\relay
target\debug\bta-anywhere-relay.exe run --config .dev\relay\relay.toml
```

Start a BTA 8.0.1 server bound to the host's loopback interface. Configure its online mode and whitelist as appropriate; the tunnel CLI does not change either setting. A temporary offline-mode server can verify a disposable local client join, but does not verify account authentication. Point the tunnel at that server; replace `25565` with its actual port:

```powershell
java -jar tunnel-client\build\libs\bta-anywhere-tunnel-0.1.0-all.jar expose-encrypted `
  --relay localhost:25575 `
  --ca .dev\relay\trust.pem `
  --token-file .dev\relay\access.token `
  --local 127.0.0.1:25565
```

The host CLI prints a `BTAE1:` invitation and its ID after registration. Share the invitation through a trusted, authenticated channel. It is a bearer secret: anyone holding it can race to use its one join. Do not put it in shell arguments, URLs, logs, screenshots, or issue reports. The CLI's `invite` command makes another invitation; `revoke <id>` invalidates both capabilities for that invitation; `stop` closes the session. Each invitation expires within ten minutes.

## Join from the guest machine

The guest uses the same shaded tunnel JAR but needs no relay CA file or allocation token. Run:

```powershell
java -jar tunnel-client\build\libs\bta-anywhere-tunnel-0.1.0-all.jar join-encrypted
```

Paste the invitation at the prompt. The companion prints the invited host SPKI SHA-256 fingerprint, relay endpoint, expiry, and a `127.0.0.1:<port>` address. Compare the host fingerprint and relay endpoint with the invitation received through your trusted channel, then add the loopback address in the ordinary BTA 8.0.1 multiplayer screen. A server-list probe uses a separate, limited status capability and does not spend the join. Clicking Join consumes the one-use join capability. A later connection, including after a disconnect, needs a fresh invitation. Keep the companion running until the game connection ends; type `stop` to close it.

The companion accepts local BTA traffic only on loopback. It validates the relay's name and invitation pin and the host's session pin, requires TLS 1.3 and the encrypted ALPN values on both hops, and never retries as plaintext. The public encrypted relay port also requires TLS; a stock game client pointed directly at that port cannot join.

## Validation and limits

The local smoke command is:

```powershell
python scripts\encrypted_join_smoke.py `
  --relay-binary target\debug\bta-anywhere-relay.exe `
  --tunnel-jar tunnel-client\build\libs\bta-anywhere-tunnel-0.1.0-all.jar
```

It starts disposable local processes and checks basic and icon status probes, status rate limiting, malformed prefaces, byte-exact join and half-close, replay, wrong host and relay pins, tampered capability, wrong host session, display expiry, revocation, plaintext rejection, active guest and host shutdown, listener cleanup, and absence of unauthorized local-server connections. Pin and capability checks use a fresh invitation and verify that the unmodified invitation still joins afterward. It does **not** measure performance or substitute for a real BTA client and server test. Run the existing serial and concurrent integration harnesses sequentially because they share relay ports.

With a disposable BTA 8.0.1 server already running on loopback, compare its direct and encrypted server-list responses and first join-handshake response:

```powershell
python scripts\encrypted_bta_status_smoke.py `
  --relay-binary target\debug\bta-anywhere-relay.exe `
  --tunnel-jar tunnel-client\build\libs\bta-anywhere-tunnel-0.1.0-all.jar `
  --local-port 25585
```

This check reached an official, hash-verified BTA 8.0.1 server on a disposable world for both status variants and the first handshake response. It does not log in a player or prove a complete game join. Keep the server bound to loopback and stop it cleanly after testing.

The [functional evidence](workstream3-functional-evidence.md) also records two complete local joins using a separate temporary BTA client game directory and offline test identity. The disposable server logged the player in; the client received the server's recipes and remained connected. Prism Launcher's first-run setup would not complete with an offline account, so this verification launched the actual BTA client main class directly from the isolated temporary profile. No client-side in-world screenshot was retained. This verifies the game protocol path, not online account authentication or the mod hosting UI.

The relay still sees addresses, timing, volume, and inner TLS record sizes, and can disconnect guests. A stolen invitation can be used first. Host and guest machines, the BTA server, and their mods still need to be trusted. See [Security](security.md), [Privacy](privacy.md), and the [accepted design and full gate list](encrypted-guest-design.md).

For this review, [performance measurements and long soak runs are explicitly deferred](functional-merge-exception.md). Their gates remain open; they are not evidence of functional or sustained-load success.
