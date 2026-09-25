# Release process

A release is allowed only after automated gates, disposable-world manual gates, attribution review, and artifact inspection. The tag workflow publishes binaries; it never downloads or bundles the official BTA server archive.

## 1. Prepare the tree

```powershell
git status --short
cargo fmt --all -- --check
python scripts\check_rust_licenses.py
cargo clippy --locked --all-targets -- -D warnings
cargo test --locked --all
.\gradlew.bat --no-daemon clean check build
```

Run `scripts/cross_language_e2e.py` and `scripts/windows_half_close_smoke.py` as documented in [Building](building.md), and run the shaded CLI `doctor` on Windows CI.

## 2. Manual disposable-world matrix

Record the Windows version, architecture, Java runtime, BTA profile, and mod list with each result.

- [ ] Live mode preserves inventory, position, achievements, player UUID data, and world state after hosting and reopening.
- [ ] Showcase mode leaves a pre-session hash/tree snapshot of the original world byte-for-byte unchanged.
- [ ] A second stock BTA 8.0.1 client joins over LAN.
- [ ] A second client joins over Direct where a test router provides a public mapping; otherwise verify the explicit manual-forwarding fallback.
- [ ] A second client joins through a relay from a genuinely external network.
- [ ] Gameplay-mod guest requirements match `guest-mods.txt`; client-only mods are not copied to the server.
- [ ] Bad server checksum, low disk space, occupied port, incompatible mod, and readiness timeout fail before unsafe ownership.
- [ ] Server crash, tunnel crash, client crash, relay restart, and forced shutdown all leave a recoverable journal/artifact set.
- [ ] At no point do the single-player client and dedicated server hold the same save open.
- [ ] Clean stop removes only a showcase copy; crash retains it.
- [ ] Confirmed restore moves the current save aside and never runs while a recorded process matches.

For crash-consistency fault-injection changes, additionally run the full synthetic fault campaign documented in [Building](building.md) three times with clean temporary directories, then complete five distinct manual disposable-world interruptions: client termination, supervisor termination, server termination, forced Windows shutdown, and malformed recovery data. Record the original-world manifest, journal/copy/backup state, process identities, and recovery-screen actions for each. Automated fake-server tests do not check these boxes.

Also check the two-client game-directory lock with a new disposable profile as
described in [Building](building.md). A second client must be denied without
clearing the first client's journal or allowing an ordinary single-player
open. After the first client exits, the second may acquire the OS lock on
restart, but must still obey any recovery journal.

Do not check a box based only on a unit test. Attach sanitized logs and the disposable test procedure to the release notes or tracking issue.

## 3. Inspect artifacts

Inspect the final JARs and relay binaries, not the thin/dev JARs:

```text
java -jar tunnel-client/build/libs/bta-anywhere-tunnel-0.1.0-all.jar doctor
jar tf bta-mod/build/libs/bta-anywhere-0.1.0+bta8.0.1.jar
jar tf tunnel-client/build/libs/bta-anywhere-tunnel-0.1.0-all.jar
```

Verify:

- [ ] the mod metadata says `btaanywhere`, `0.1.0+bta8.0.1`, client environment, and only Kenar Narayaka as author;
- [ ] native QUIC files and upstream licence material are present;
- [ ] `LICENSE_bta-anywhere` and `THIRD_PARTY_NOTICES_bta-anywhere.md` are present;
- [ ] no Minecraft classes, BTA/server archive, save, token, private key, generated certificate, runtime directory, or log is present;
- [ ] the README prominently says Direct is router-dependent and Relay is self-hosted with no public service;
- [ ] the privacy docs say guest-to-relay traffic is not generally encrypted.

## 4. Audit identity and history

Use the repository-local identity:

```text
git config user.name "kenar"
git config user.email "k.narayaka@gmail.com"
powershell -ExecutionPolicy Bypass -File .\scripts\audit-attribution.ps1
git log --format='%B'
git log --format=fuller
```

Commit bodies must contain no agent authorship or generation trailer. The CI attribution job repeats this check over fetched history.

## 5. Publish

Push `main` and wait for every CI job to pass. Then create an annotated tag whose message contains only the human release description:

```text
git tag -a v0.1.0 -m "BTA Anywhere v0.1.0"
git push origin v0.1.0
```

The release workflow builds:

- the BTA mod JAR;
- the Java tunnel fat JAR;
- the Windows x86-64 relay executable;
- `SHA256SUMS`, `LICENSE`, `THIRD_PARTY_NOTICES.md`, and the generated Rust dependency inventory and full notice text bundle.

After publication, download every asset from GitHub, verify `SHA256SUMS`, run the CLI doctor, inspect the mod metadata again, and confirm the release contains no game/server archive. A release is not complete merely because the workflow uploaded files.
