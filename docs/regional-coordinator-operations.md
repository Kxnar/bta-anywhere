# Regional coordinator: Windows operator setup and recovery

This Workstream 4 service allocates new sessions among operator-owned relays.
Existing guest TCP connections stay on their relay; a reconnect resumes only
the same live relay session. A new relay choice requires a new allocation and,
for encrypted guests, a new invitation. No public fleet is provided by this
repository. The [functional review exception](functional-merge-exception.md)
defers performance and soak evidence without changing those gates.

## Trust and port layout

Give each relay a stable unique ID, its own coordinator credential, a relay
access token, a TLS certificate, and two **disjoint** TCP ranges. The static
range preserves existing Relay registrations; the managed range is used only
after signed-ticket verification and coordinator redemption. The host keeps
an allowlist of relay IDs, endpoints, relay certificates, and relay tokens.
The coordinator returns only a relay ID, reserved port, expiry, and signed
ticket; it cannot replace the host's trust anchors or relay credentials.

Use a dedicated non-administrator Windows account for the coordinator. Grant
it read access to its TLS key, Ed25519 PKCS#8 signing key, host credential,
relay credential files, and write access to the SQLite database directory.
Keep the coordinator HTTPS endpoint and its unauthenticated `/metrics` path
behind an operator firewall; give hosts and relays access only to that endpoint.
The relay's HTTP admin listener remains loopback-only. One coordinator
process owns the local database; do not put it on a shared network filesystem
or run active-active replicas. Likewise, run only one relay process for each
relay ID and credential: two processes with the same identity can send
conflicting full inventories, and there is no relay-instance fencing.

## Build and disposable local setup

From the W4 worktree on Windows:

```powershell
cargo build --locked --release --package bta-anywhere-coordinator --package bta-anywhere-relay
.\gradlew.bat --no-daemon :tunnel-client:shadowJar
.\target\release\bta-anywhere-coordinator.exe init-dev --dir .dev\coordinator
.\target\release\bta-anywhere-coordinator.exe public-key --config .dev\coordinator\coordinator.toml
.\target\release\bta-anywhere-relay.exe init-dev --output .dev\relay
```

`init-dev` refuses an existing directory and creates disposable localhost TLS,
ticket signing, and credential files. Do not reuse them in production. In a
real installation, obtain the HTTPS certificate from the operator's CA,
generate a protected Ed25519 PKCS#8 signing key and separate high-entropy
printable host and per-relay credentials, then give the relay the public key
reported by `public-key`. Distribute certificates and secrets through the
operator's authenticated provisioning channel. The coordinator and relay
read their credential files; neither configuration needs plaintext tokens.
After writing the production configuration and before the first start, create
the empty database **once**:

```powershell
.\target\release\bta-anywhere-coordinator.exe init-db --config 'C:\ProgramData\BTA Anywhere\coordinator\coordinator.toml'
```

`init-db` refuses an existing database. Normal `serve` refuses a missing or
uninitialized database instead of silently replacing lost lease state.

The generated `coordinator.toml` shows the exact absolute paths. Its relevant
fields are:

```toml
bind = "127.0.0.1:30450"
database = "C:/ProgramData/BTA Anywhere/coordinator/coordinator.sqlite"
tlsCertFile = "C:/ProgramData/BTA Anywhere/coordinator/fullchain.pem"
tlsKeyFile = "C:/ProgramData/BTA Anywhere/coordinator/tls-key.pem"
hostCredentialFile = "C:/ProgramData/BTA Anywhere/coordinator/host.token"
signingKeyFile = "C:/ProgramData/BTA Anywhere/coordinator/signing-key.pk8"
keyId = "operator-key-1"
heartbeatIntervalMillis = 5000
maxProbeAgeMillis = 10000

[[relays]]
id = "eu-relay-1"
credentialFile = "C:/ProgramData/BTA Anywhere/coordinator/eu-relay-1.token"
firstManagedPort = 30500
lastManagedPort = 30599
maxCapacity = 100
allowLegacy = true
allowEncrypted = true
```

Set `bind` to the coordinator's reachable HTTPS interface for a multi-machine
fleet, and restrict that interface with the host/relay firewall allowlist.
Every configured relay must have a distinct ID and managed range local to
that relay. The coordinator admits allocations only after a fresh
authenticated relay heartbeat and reconciliation. The relay's optional TOML
section uses its own ID and credential, the coordinator CA, the public ticket
key in canonical base64, and a managed range disjoint from `tcp_port_start`
through `tcp_port_end`:

```toml
[coordinator]
relay_id = "eu-relay-1"
url = "https://coordinator.example.net:30450"
credential_path = "C:/ProgramData/BTA Anywhere/relay/eu-relay-1.token"
ca_certificate_path = "C:/ProgramData/BTA Anywhere/relay/coordinator-ca.pem"
managed_port_start = 30500
managed_port_end = 30599
signing_keys = { "operator-key-1" = "replace-with-base64-raw-32-byte-public-key" }
```

Open the relay's UDP QUIC port and both intended TCP ranges in the Windows
and provider firewalls. Start the coordinator before the relays:

```powershell
.\target\release\bta-anywhere-coordinator.exe serve --config .dev\coordinator\coordinator.toml
.\target\release\bta-anywhere-relay.exe run --config .dev\relay\relay.toml
```

Run each command in its own service account/session. A failed initial
heartbeat leaves managed registration unavailable; static registration still
uses the original path. The coordinator's `/metrics` reports active,
quarantined, and expired lease counts without identity labels. Alert on a
stale relay heartbeat, repeated allocation/redeem rejection, quarantined
leases that do not clear after reconciliation, and database or signing-key
errors.

## Host allowlist and fallback

Create a local JSON file with 1–64 trusted relay entries. Relative file paths
are resolved beside the JSON file:

```json
{
  "relays": [
    {
      "relayId": "eu-relay-1",
      "endpoint": "eu-relay.example.net:25575",
      "trustedCertificate": "eu-relay-trust.pem",
      "accessTokenFile": "eu-relay-access.token"
    }
  ]
}
```

Start the CLI with a locally configured coordinator CA and host credential:

```powershell
java -jar tunnel-client\build\libs\bta-anywhere-tunnel-0.1.0-all.jar expose `
  --coordinator https://coordinator.example.net:30450 `
  --coordinator-ca coordinator-ca.pem `
  --coordinator-token-file host-coordinator.token `
  --relay-allowlist relays.json --local 127.0.0.1:25565
```

Use `expose-encrypted` for the encrypted guest path. The client probes each
allowlisted relay with certificate-verified QUIC and sends fresh host-to-relay
RTT samples. The coordinator filters health, mode, and capacity, then selects
the lowest median RTT with stable relay-ID tie order. This measures the host
path, not the guest path. The CLI prints whether a session used coordinated,
static fallback, or static selection.

An unavailable coordinator can use a separate, explicitly configured static
destination by adding **all three** `--fallback-relay`, `--fallback-ca`, and
`--fallback-token-file` options. Authentication, malformed response, unknown
relay ID, ticket mismatch, and downgrade errors do not trigger fallback.
Without those options, a new session fails with an actionable error. A live
session remains pinned to its chosen relay and port during reconnect.

## Backup, rotation, and recovery

Create a consistent SQLite snapshot through the coordinator's online backup
command. The output must be a new file; it refuses overwrite:

```powershell
.\target\release\bta-anywhere-coordinator.exe backup `
  --config .dev\coordinator\coordinator.toml `
  --output .dev\coordinator-backup-20260927.sqlite
```

Back up the matching configuration, signing key, TLS key, and credential files
through the operator's protected secret backup process. A raw copy of a live
SQLite file without its WAL is not a consistent backup. Test restore with a
disposable database. To rotate a signing key, provision the new public key on
each relay first, switch the coordinator key ID and private key, and keep the
old relay verification key until every old ticket has expired (currently at
most 30 seconds). Rotate coordinator bearer credentials in a controlled
restart window and update the matching clients/relays together.

After coordinator restart, leases remain durable and new allocations wait
for authenticated relay reconciliation. A live relay reports its complete
bound-port inventory; an absent listener is released only after that report.
If a close or coordinator contact is uncertain, the port remains unavailable
or quarantined until reconciliation. Do not delete the database or clear
leases merely to free a port. Stop the relay and inspect its actual listener,
lease, and process state before manual intervention.

For an ordinary coordinator outage, keep existing guest sessions running and
use the explicit static fallback for new sessions if configured. Stop new
coordinated starts before restoring a database and its matching signing key;
bring up one coordinator owner, let every relay heartbeat and reconcile, then
resume allocations. If the database or signing key is lost, stop coordinated
starts, inspect each relay's managed listeners, and reconstruct a consistent
lease/key state before issuing new tickets. There is no automatic empty-state
reset, and an old encrypted invitation must be revoked and replaced if a new
allocation moves the host to another relay.
