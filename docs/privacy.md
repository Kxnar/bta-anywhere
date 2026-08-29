# Privacy

## Relay traffic is not end-to-end encrypted

The guest-to-relay connection is BTA's ordinary TCP connection and has no general transport encryption. QUIC/TLS 1.3 encrypts only the relay-to-host hop. The relay terminates both transports and forwards the bytes, so a relay operator can observe or modify most game traffic as well as connection metadata.

Do not describe Relay mode as end-to-end encrypted. A trustworthy certificate prevents an unrelated network observer from impersonating the configured relay-to-host endpoint; it does not hide guest traffic from the relay itself.

## Data visible by mode

| Data | LAN/direct host | Relay operator | Other network observers |
| --- | --- | --- | --- |
| Guest source address | Usually visible to the host server/network | Visible | Potentially visible as metadata |
| Host public address | Visible to direct guests | Visible to relay | Visible as metadata |
| Connection timing and byte volume | Visible | Visible | Often visible |
| BTA game payload | Visible to host | Usually readable/modifiable | Guest-to-relay path is not generally encrypted |
| Relay access token | Stored by host; sent inside host QUIC | Validated as a hash after receipt | Protected on the QUIC hop |

The local BTA server receives a loopback TCP connection from the tunnel, so its own connection log does not preserve the guest's original address. The relay receives that address and includes it in the private `ConnectionOpen` header.

## Local data

The mod stores:

- relay configuration, including the plaintext access token, in `<game-directory>/config/bta-anywhere.json`;
- server runtime, mirrored server-compatible mods/configs, guest compatibility manifest, and logs under `<game-directory>/bta-anywhere/`;
- live-world backups and crash-retained showcase copies under that managed directory;
- process identity and recovery paths in `recovery.json`;
- a high-entropy supervisor control secret in a private managed control file.

POSIX files containing secrets are restricted to the owner where supported. On Windows they inherit ACLs from the user's game directory. Protect backups and logs like the original save. Server logs may contain usernames, chat, commands, addresses, or mod-specific data.

## Metrics and logs

Prometheus metric labels never contain usernames, tokens, IP addresses, session IDs, or connection IDs. Default relay logs use public ports and operational error text; they may record host or guest socket addresses where needed for abuse handling and diagnostics. Access tokens, session IDs, and resume tokens are not logged.

Operators should document their own log retention and access policy. v0.1 does not send telemetry to the BTA Anywhere project and does not provide a hosted relay.
