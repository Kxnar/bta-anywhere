# Self-hosting a relay on Windows

v0.1 has no public relay and supports Windows x86-64 only. These steps run a single-region relay on a Windows machine you control.

## Requirements

- A Windows x86-64 computer or Windows Server VPS with a public IPv4 address and working UDP.
- DNS pointing a hostname such as `relay.example.net` to that address.
- A TLS certificate and private key for the relay hostname. The certificate file may contain the leaf followed by intermediates.
- Windows Defender Firewall and any provider firewall open for UDP 25575 and the chosen TCP session range, default 30000–30100.
- Rust 1.85+ to build, or the Windows x86-64 release executable.
- A dedicated non-administrator Windows account for unattended operation.

Do not expose TCP 9090 to the internet. It is an unauthenticated health/metrics listener intended for loopback monitoring.

## Build and install

Build from a normal PowerShell session:

```powershell
cargo build --locked --release --package bta-anywhere-relay
```

In an elevated PowerShell session, create installation and data directories and copy the executable:

```powershell
$installDir = 'C:\Program Files\BTA Anywhere'
$dataDir = 'C:\ProgramData\BTA Anywhere'
New-Item -ItemType Directory -Force -Path $installDir, $dataDir
Copy-Item .\target\release\bta-anywhere-relay.exe "$installDir\bta-anywhere-relay.exe"
```

Restrict the data directory through Windows Security properties so only Administrators and the dedicated relay account can read it.

Generate a high-entropy token without placing it in command arguments or logs:

```powershell
& "$installDir\bta-anywhere-relay.exe" generate-token --output "$dataDir\host-1.token"
```

The command prints the SHA-256 value for `access_token_hashes` but never prints the token. Deliver the token file's contents to the host through an authenticated secret channel. The relay needs only the hash; move the plaintext token off the relay after provisioning if no local workflow needs it.

## Configuration

Create `C:\ProgramData\BTA Anywhere\relay.toml`:

```toml
quic_listen = "0.0.0.0:25575"
tcp_bind_ip = "0.0.0.0"
public_host = "relay.example.net"
tcp_port_start = 30000
tcp_port_end = 30100
admin_listen = "127.0.0.1:9090"
certificate_path = "C:/ProgramData/BTA Anywhere/fullchain.pem"
private_key_path = "C:/ProgramData/BTA Anywhere/private-key.pem"
access_token_hashes = ["replace-with-the-printed-64-character-sha256"]
max_sessions_per_token = 2
max_sessions_per_ip = 3
max_connections_per_session = 8
max_accepts_per_minute = 30
heartbeat_seconds = 15
lease_seconds = 90
resume_grace_seconds = 60
```

Paths relative to the config file are resolved against its directory. The port range must not include zero and the lease must cover at least two heartbeat intervals.

## Firewall and first run

In an elevated PowerShell session:

```powershell
New-NetFirewallRule -DisplayName 'BTA Anywhere relay QUIC' -Direction Inbound -Action Allow -Protocol UDP -LocalPort 25575
New-NetFirewallRule -DisplayName 'BTA Anywhere relay sessions' -Direction Inbound -Action Allow -Protocol TCP -LocalPort '30000-30100'
& 'C:\Program Files\BTA Anywhere\bta-anywhere-relay.exe' run --config 'C:\ProgramData\BTA Anywhere\relay.toml'
```

From another PowerShell window on the relay machine:

```powershell
Invoke-WebRequest http://127.0.0.1:9090/healthz -UseBasicParsing
Invoke-WebRequest http://127.0.0.1:9090/readyz -UseBasicParsing
Invoke-WebRequest http://127.0.0.1:9090/metrics -UseBasicParsing
```

Apply matching rules in any cloud-provider firewall. Test from a genuinely external network; testing the public address inside the same LAN can be distorted by NAT loopback support.

For unattended operation, run the same command under the dedicated account with Task Scheduler or an approved Windows service manager, configure restart-on-failure, and grant that account read access only to the executable, configuration, certificate, and private key it needs. The repository does not install a Windows service automatically.

## Configure a host

Copy a public PEM trust bundle containing the relay leaf followed by its issuer chain—never the private key—beneath the BTA game directory and configure `config/bta-anywhere.json` as shown in [Quick-start](quick-start.md). Even with a publicly trusted certificate, v0.1 intentionally requires an explicit relay trust file.

## Development relay

For a one-machine test only:

```powershell
.\target\release\bta-anywhere-relay.exe init-dev --output .dev\relay
.\target\release\bta-anywhere-relay.exe run --config .dev\relay\relay.toml
```

`init-dev` creates a private test CA, its private key, a CA-signed localhost certificate, a client `trust.pem` bundle, and a token. The directory is ignored by Git. Do not reuse development credentials on a public relay.

## Operations

- Scrape metrics locally and alert on readiness failure, rejected-connection spikes, session saturation, and bandwidth anomalies.
- Rotate a token by adding a new hash, restarting the relay, moving hosts to the new secret, then removing the old hash.
- Certificate renewal requires a relay restart in v0.1.
- Back up only configuration needed for reconstruction; do not retain plaintext host tokens unnecessarily.
- A Windows restart should stop the relay before replacing its executable or configuration.
