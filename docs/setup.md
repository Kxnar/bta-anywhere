# Development setup

Windows x86-64, JDK 21, and Rust 1.85+ are needed.
The standalone Java tunnel and Rust relay can also be built on Linux x86-64;
see the [Linux build and netem procedure](linux-netem.md). The game mod remains
Windows-targeted.

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

## Disposable multiplayer test

Prepare two separate launcher instances with BTA 8.0.1, Babric Loader
`0.18.4-bta.11`, HalpLibe `6.2.0+8.0.1` and JDK 21. The mod artifact is
`bta-mod/build/libs/bta-anywhere-0.1.0+bta8.0.1.jar`; put that bundled JAR in the
instance's `mods` directory. The `-thin.jar` is not the self-contained mod.
The [official BTA installation guide](https://www.betterthanadventure.net/installation-guide/)
describes the base Prism/MultiMC installation. A plain BTA instance still needs
the compatible loader before it can load this mod.

Sign in through the launcher with two distinct Minecraft accounts. Launch each
instance once and exit so its own configuration is created. Use fresh game
directories, not an existing save collection. The current managed-server setup
requires online authentication; arbitrary development usernames do not satisfy
that requirement.

For the first LAN check, create a new disposable world, open **Host World**, use
LAN mode, and invite the other account's exact username with the whitelist
enabled. Wait for the host to join its managed server. On the same machine the
guest uses `127.0.0.1:<selected game port>`; a second machine uses the host's LAN
address. Record the actual address and topology used.

For a local relay check, build and initialise a fresh relay directory:

```powershell
cargo build --locked --release --package bta-anywhere-relay
.\target\release\bta-anywhere-relay.exe init-dev --output .dev\demo-relay
```

In the generated `.dev/demo-relay/relay.toml`, set `quic_listen` to
`127.0.0.1:25575` and `tcp_bind_ip` to `127.0.0.1`. Keep `public_host = "localhost"`
for this one-machine test. Copy `trust.pem` into the host game's `config`
directory as `relay-trust.pem`, then edit the host's existing
`config/bta-anywhere.json` while the game is closed:

```json
{
  "clientInstanceId": "KEEP-THE-EXISTING-INSTANCE-ID",
  "relayHost": "127.0.0.1",
  "relayPort": 25575,
  "relayTrustedCertificate": "config/relay-trust.pem",
  "relayAccessToken": "CONTENTS-OF-THE-LOCAL-access.token-FILE"
}
```

Keep the token local and omit it from recordings and published logs. The
trusted-certificate path must remain inside the game directory. Start the relay
in a separate terminal:

```powershell
.\target\release\bta-anywhere-relay.exe run --config .dev\demo-relay\relay.toml
```

Launch the host, select **Relay** mode and start hosting. The guest joins the
public TCP endpoint displayed by the mod, not the QUIC port 25575. The host
still joins its managed game server locally. A relay hosted on another machine
needs a certificate valid for that machine's hostname/IP and reachable UDP and
public TCP listeners; the loopback development certificate is not that setup.

Demonstration acceptance sequence:

1. Record server-ready state and both clients in the same disposable world.
2. Make a distinctive block change and show the other client observing it.
3. Disconnect the guest, reconnect through the same displayed endpoint, and
   show the persisted change from the guest again.
4. Stop hosting, confirm the dedicated server stops, reopen the original world,
   and verify the expected saved state.
5. Save a short recording, redacted logs, exact mod/relay hashes, setup and any
   failed attempts together. State whether each join was LAN, loopback relay,
   or a remote relay.

This is the repeatable procedure for the remaining acceptance test. The current
archive does not yet show its successful completion.
