# Self-hosting a relay

v0.1 has no public relay. These steps create a single-region relay on infrastructure you control.

## Requirements

- A Linux VPS with a public IPv4 address and working UDP.
- DNS pointing a hostname such as `relay.example.net` to that address.
- A TLS certificate and key for the relay hostname. The certificate file may contain the leaf followed by intermediates.
- Firewall access to UDP 25575 and the chosen TCP session range, default 30000–30100.
- Rust 1.85+ to build, or the matching release binary.
- A dedicated unprivileged service account.

Do not expose TCP 9090 to the internet. It is an unauthenticated health/metrics listener intended for loopback monitoring.

## Build and install

```text
cargo build --locked --release --package bta-anywhere-relay
sudo install -o root -g root -m 0755 target/release/bta-anywhere-relay /usr/local/bin/
sudo install -d -o bta-anywhere -g bta-anywhere -m 0750 /etc/bta-anywhere
```

Create a high-entropy token without placing it in shell arguments or logs:

```text
sudo -u bta-anywhere bta-anywhere-relay generate-token \
  --output /etc/bta-anywhere/host-1.token
```

The command prints the SHA-256 value for `access_token_hashes` but never prints the token. Deliver the token file's contents to the host through an authenticated secret channel. The relay process needs only the hash, so the plaintext token file can be moved off the relay after provisioning if no local workflow needs it.

## Configuration

Create `/etc/bta-anywhere/relay.toml`:

```toml
quic_listen = "0.0.0.0:25575"
tcp_bind_ip = "0.0.0.0"
public_host = "relay.example.net"
tcp_port_start = 30000
tcp_port_end = 30100
admin_listen = "127.0.0.1:9090"
certificate_path = "/etc/letsencrypt/live/relay.example.net/fullchain.pem"
private_key_path = "/etc/letsencrypt/live/relay.example.net/privkey.pem"
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

## systemd

Create `/etc/systemd/system/bta-anywhere-relay.service`:

```ini
[Unit]
Description=BTA Anywhere relay
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=bta-anywhere
Group=bta-anywhere
ExecStart=/usr/local/bin/bta-anywhere-relay run --config /etc/bta-anywhere/relay.toml
Restart=on-failure
RestartSec=3
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictSUIDSGID=true
LockPersonality=true
MemoryDenyWriteExecute=true
RestrictAddressFamilies=AF_INET AF_INET6
CapabilityBoundingSet=
AmbientCapabilities=

[Install]
WantedBy=multi-user.target
```

Ensure the service account can read the certificate and private key without broadening their permissions unnecessarily, then run:

```text
sudo systemctl daemon-reload
sudo systemctl enable --now bta-anywhere-relay
curl --fail http://127.0.0.1:9090/healthz
curl --fail http://127.0.0.1:9090/readyz
curl --fail http://127.0.0.1:9090/metrics
```

Apply firewall rules with your provider and OS firewall for UDP 25575 and TCP 30000–30100. Test from a genuinely external network; testing the public address from inside the same LAN can be distorted by NAT loopback support.

## Configure a host

Copy the public CA chain (never the private key) beneath the BTA game directory and configure `config/bta-anywhere.json` as shown in [Quick-start](quick-start.md). If using a publicly trusted certificate, still point the mod at the PEM trust file; v0.1 intentionally uses explicit relay trust.

## Development relay

For a one-machine test only:

```text
bta-anywhere-relay init-dev --output .dev/relay
bta-anywhere-relay run --config .dev/relay/relay.toml
```

`init-dev` creates a private test CA, its private key, a CA-signed localhost certificate, and a token. The directory is ignored by Git. Do not reuse development credentials on a public relay.

## Operations

- Scrape metrics locally and alert on readiness failure, rejected-connection spikes, session saturation, and bandwidth anomalies.
- Rotate a token by adding a new hash, restarting the relay, moving hosts to the new secret, then removing the old hash.
- Certificate renewal requires the relay to reload by restart in v0.1.
- Back up only configuration needed for reconstruction; do not back up plaintext host tokens on the relay unnecessarily.
- Add a broker only when multiple independently operated regions exist. v0.1 clients use a static descriptor.
