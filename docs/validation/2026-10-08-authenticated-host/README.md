# Packaged client and authenticated host attempt

The bundled mod launched in a fresh Prism instance, created a disposable world,
started its managed BTA server, and successfully authenticated the host account.
This resolves the first attempt's unauthenticated-host uncertainty. It does not
complete the two-client multiplayer demonstration.

## Reproducible setup

- Windows x86-64, Prism Launcher 11.1.1 and Microsoft OpenJDK 21.0.12.1.
- Official [Turnip-Labs modded BTA 8.0.1 instance](https://github.com/Turnip-Labs/bta-fabric-instance-repo/releases/download/v8.0.1/bta_fabric_instance_8.0.1.zip),
  SHA-256 `10a30d3d44cd2db8741609a2c93774f8b681aa93dbf7d5237fa719e08cb2ee13`.
  It includes Babric `0.18.4-bta.11`, HalpLibe `6.2.0+8.0.1` and Mod Menu.
- A new instance named `BTA Anywhere - Disposable Host Validation`; no existing
  worlds were copied. Client heap was 1536 MiB. The instance selected Java 21
  explicitly and added 21 to `patches/net.minecraft.json`'s compatible majors.
- Bundled `bta-anywhere-0.1.0+bta8.0.1.jar`, SHA-256
  `cd665c624104ce66361199742b2cd63936f77b16a127abcd13472e507c9f1be7`.
  This was the final packaged candidate from the transport investigation;
  checkout head during the attempt was `f4af6393a817c07774a2fec452a0219f2ed16875`.
- Prism supplied its existing signed-in account through its normal launch flow.
  No credentials were extracted or injected. A second account was unavailable.

The [setup guide](../../setup.md) gives the user procedure. The archived
[preparation script](source/prepare-authenticated-host.py) records the exact local
profile changes; its redacted machine paths must be adapted before reuse. The
[preparation receipt](results/preparation.json) records archive and mod hashes.

## Observed outcome, 8 October 2026

1. The title menu showed BTA 8.0.1 and seven loaded mods. A new **New World**
   generated successfully, with displayed seed `-9014675340994939384`. The
   attempted automated custom-name text entry did not populate the field, so
   the default name was used in this empty save directory.
2. **Host World** used live-world mode, LAN, whitelist enabled, eight configured
   slots, a 2048 MiB server heap and port 25565. No guest was invited. These are
   configuration values, not a multiplayer capacity measurement.
3. The mod installed the pinned server distribution, SHA-256
   `18a8dc132e9c08f9cc6928ac00cd05d2d9450fd98cb26eb8fb732455bf9011f4`.
   Windows Firewall displayed an OpenJDK permission prompt, which the agent did
   not act on. Visual game interaction remained blocked by that prompt.
4. Despite that prompt, the [server log](results/managed-server.txt) reached
   `Done` at 21:55:28 local time and recorded the host's login over loopback at
   21:55:29. The [client excerpt](results/client-login-excerpt.txt) independently
   records the logged-in session and successful receipt of 687 server recipes.
   `online-mode=true` and `white-list=true` are retained in
   [server.properties](results/server.properties). This is logged host-login
   evidence, not visual evidence of shared play.
5. The test was stopped through the normal authenticated supervisor STOP
   protocol after checking both process identities. The
   [cleanup receipt](results/cleanup.txt) reports `STOPPED clean=true`, with both
   supervisor and server exited; the server log records chunk saving.
   This was an external control-channel cleanup, not the in-game Stop Hosting
   acceptance step. The isolated client was left open at the pending Windows
   prompt for user intervention.

The server also logged an unrecognised survival gamemode warning and an unknown
sound warning; both remain in the archive. No conclusion about their cause is
claimed. The readiness probe's disconnected loopback connection precedes the
successful host login and is retained as well.

Guest join, shared block changes, disconnect/reconnect, relay gameplay, world
reopening and a short successful demo remain unperformed. Handle the Windows
prompt, provide a second distinct signed-in client, and follow the acceptance
sequence in the setup guide. Never describe this attempt as a completed
multiplayer demonstration.

## Verification and privacy

[CI run 37841902013](https://github.com/Kxnar/bta-anywhere/actions/runs/37841902013)
passed all four jobs at `f4af6393a817c07774a2fec452a0219f2ed16875`, including the
archive verifiers, timestamp reproducer and explicit native-command exit checks.
Its [saved response](results/ci.json) identifies the tested head and job outcomes.
Later documentation-only changes do not change that tested source claim.

Published logs replace the home directory and account name with placeholders.
The client excerpt is allowlisted; full client/launcher logs, account files,
session launch arguments, supervisor tokens and world data are not published.
The raw local attempt remains in the dedicated Prism instance. Run
`python docs/validation/2026-10-08-authenticated-host/verify_archive.py` to check
the published byte hashes. Hash verification does not assert journey completion.
