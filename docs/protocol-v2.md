# Experimental encrypted relay protocol v2

This is the current Workstream 3 CLI/relay interface. It is implemented in the candidate branch and has passed a local synthetic join/security smoke, live BTA 8.0.1 server status and first-handshake checks, and two disposable offline-mode joins by the actual BTA 8.0.1 client. Shared v2 invitation, AUTH, frame, and connection-header vectors feed Java and Rust where those structures are implemented; the relay treats inner TLS records as opaque. Relay-side confidentiality inspection and deferred performance/soak evidence remain open. [Protocol v1](protocol.md) remains the released legacy mode.

## Mode and transport

The host tunnel offers only QUIC ALPN `bta-anywhere/2` and sends a framed `register` control message with `version: 2`, an access token, a client-instance ID, and optional resume token. It requests no v1 `streamEofBytes` feature. The relay binds the resulting session to the encrypted mode. A resume using the other mode fails; an encrypted host never falls back to v1. Control JSON framing, heartbeat, and registration limits otherwise follow v1.

The allocated public TCP listener requires TLS 1.3 and ALPN `bta-anywhere-relay/1` before forwarding any guest bytes. It presents the relay's existing certificate identity. The guest companion checks its invited SHA-256 SPKI pin and relay DNS/IP name. A plain BTA TCP connection to this listener fails at the outer TLS handshake.

For each accepted guest, the relay opens a host QUIC stream and writes a length-prefixed `ConnectionOpen` JSON object with `version: 2`, the registered `sessionId`, a connection ID, and the guest address. The host checks the version and session before starting inner TLS. The relay then copies opaque inner TLS records in both directions with transport backpressure; it does not parse the inner handshake, authorization, or game frames. TCP and QUIC half-closes remain directional.

## Inner TLS and application frames

The companion starts a second TLS 1.3 connection inside the outer connection. Its peer is the host tunnel, which presents a fresh in-memory certificate for that hosting session. The companion checks the host's invited SPKI pin and inner ALPN `bta-anywhere-guest/1`. A new host process generates a new key and invalidates old invitations.

Inside inner TLS, each frame is one type byte, a two-byte unsigned big-endian payload length, then that many bytes. Payloads are at most 16 KiB; `AUTH` is at most 2 KiB. Defined types are `1 AUTH`, `2 AUTH_OK`, `3 DATA`, `4 FIN`, and `5 ERROR`. `AUTH_OK` and `FIN` have empty bodies. `FIN` half-closes game output after prior data without discarding the reverse direction. Malformed types, lengths, frames before `AUTH`, and truncated bodies fail closed.

The first frame must be `AUTH` containing strict UTF-8 JSON. It names application `version: 1`, `invitationId`, `hostSessionId`, `intent` (`status` or `join`), and the matching capability. Status adds `statusVariant` (`basic` or `icon`). A status authorization permits only a constructed, bounded BTA status request to the local server; it does not consume join access or allow game bytes. A join authorization atomically consumes its capability before the host opens a local server socket. The host replies `AUTH_OK` after that socket connects, then bridges framed game bytes. Denials are generic.

`BTAE1:` invitations are canonical unpadded Base64URL of strict JSON, capped at 2 KiB. They bind a random host-session ID, random invitation ID, host and relay SPKI pins, relay DNS/TCP endpoint, absolute display expiry, and independent 256-bit status and join capabilities. The host stores digests of capabilities and uses its monotonic clock for admission expiry, at most ten minutes. It allows at most 64 outstanding invitations, ten status requests per invitation per minute, and eight active encrypted guest streams including unauthenticated streams. Each stream must finish authorization within ten seconds. Host stop, process restart, session/port change, and explicit revoke invalidate invitations.

The invitation is a bearer credential and must arrive through an authenticated outside channel. The relay sees addresses, timing, volume, and TLS record sizes, and can deny service. The status and join capabilities do not replace BTA online mode or the whitelist. See [Encrypted guest join](encrypted-guest.md) for setup and current test coverage.
