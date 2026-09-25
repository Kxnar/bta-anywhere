# BTA Anywhere protocol v1

This document defines the host-to-relay protocol. Guest-to-relay traffic is unmodified BTA TCP.

## Transport

- QUIC over UDP with TLS 1.3.
- ALPN: `bta-anywhere/1`.
- The client must validate the relay certificate against an explicitly configured trust anchor and match the configured relay host against the leaf certificate's DNS or IP subject alternative name. There is no certificate-verification bypass.
- The first client-initiated bidirectional stream is the control stream.
- A relay-created bidirectional stream represents one guest TCP connection.

Integers in the framing layer are unsigned network-byte-order values. JSON numbers used by v1 fit safely in both Rust and Java integer types.

## Framing

Every JSON message is:

```text
0               31 32
+-----------------+-----------------------------+
| JSON byte count | UTF-8 JSON object           |
| 4 bytes, big-endian, unsigned                 |
+-----------------+-----------------------------+
```

The JSON payload may be at most 65,536 bytes. An oversized length is rejected before allocating its declared payload. Invalid, empty, or non-object JSON is rejected. Shared byte-exact examples are committed under `protocol/test-vectors/` and consumed by both implementations.

## Control messages

The client must send `register` first:

```json
{
  "type": "register",
  "version": 1,
  "accessToken": "secret value",
  "clientInstanceId": "stable opaque identifier",
  "features": ["streamEofBytes"],
  "resumeToken": "optional prior resume token"
}
```

The relay answers:

```json
{
  "type": "registered",
  "sessionId": "random opaque value",
  "publicHost": "relay.example.net",
  "publicPort": 30000,
  "resumeToken": "new random opaque value",
  "features": ["streamEofBytes"],
  "leaseSeconds": 90
}
```

The relay rotates the resume token after every successful registration or resume. A resume requires the same access-token hash and client-instance ID. Source-address changes are permitted but observable to the relay. The host requests reliable stream completion with `features`; the relay acknowledges only requested, supported features. The current host requires `streamEofBytes` in the response before it reports an active tunnel. An older relay that omits the feature must be upgraded. An older host may still register with the current relay using the original native-FIN behavior.

Heartbeat messages are:

```json
{"type":"ping","sequence":42}
```

```json
{"type":"pong","sequence":42}
```

The client normally sends a ping every 15 seconds. The relay expires a control stream that is silent for the lease duration. The client reconnects if three heartbeat intervals pass without a pong.

A clean client shutdown sends:

```json
{"type":"close","reason":"client shutdown"}
```

An error response is:

```json
{
  "type": "error",
  "code": "authentication_failed",
  "message": "access token was not accepted",
  "retryable": false
}
```

Defined v1 error codes include `unsupported_version`, `authentication_failed`, `registration_required`, `registration_rejected`, `resume_rejected`, and `already_registered`. Unknown error codes must be handled according to `retryable`, not string-matched as permanent by default. A `resume_rejected` response makes the Java client clear its stale session data before registering again.

## Guest streams

For each accepted guest, the relay opens a bidirectional QUIC stream and writes one framed header:

```json
{
  "version": 1,
  "sessionId": "registered session value",
  "connectionId": "random opaque value",
  "remoteAddress": "203.0.113.9:49152"
}
```

Unframed BTA bytes begin immediately after the header. The tunnel verifies the version, session ID, non-empty connection ID, and parseable remote socket address before connecting to the local target. The remote address is metadata for diagnostics/protocol evolution; v0.1 does not inject it into BTA, and the local server sees the tunnel connection as loopback.

EOF in either TCP direction becomes the corresponding QUIC stream half-close. Closing one direction must not discard buffered bytes in the other direction. When `streamEofBytes` was negotiated, the host also sends one authenticated control message after its local TCP input reaches EOF, its final QUIC data write succeeds, and its QUIC output-shutdown callback succeeds:

```json
{"type":"streamEof","connectionId":"random opaque value","bytes":65536}
```

`bytes` is the nonnegative count of response bytes submitted to that guest stream, capped at Java's signed 64-bit maximum. The relay accepts the notice only for an active connection ID in that authenticated session. It closes the guest TCP output only after exactly that many bytes have been copied to guest TCP, or on native QUIC FIN when no conflicting notice exists. A short native stream, excess bytes observed before closure, duplicate notice, or contradictory active notice fails the affected stream. A notice for an already finished stream has no effect. The relay does not wait for FIN after copying the announced count and therefore cannot detect bytes sent later by a buggy or dishonest host; legacy Relay mode already trusts that host. Active notice state is bounded by the existing per-session guest limit; the host bounds queued notices. Connection IDs and payloads are excluded from normal logs. A failed control write closes the host's QUIC connection so affected guests disconnect instead of waiting for unconfirmed EOF.

## Authentication and identifiers

- Relay access tokens should contain 256 bits of randomness. The relay configuration stores SHA-256 values, not plaintext tokens.
- Authentication comparisons are constant-time.
- Session IDs and resume tokens are independently generated 256-bit random values.
- Access tokens, session IDs, and resume tokens must never be logged.
- Tokens authorize relay capacity; they do not authenticate guests to the BTA server. Online mode and the BTA whitelist perform guest access control.

## Compatibility

Protocol changes that alter framing or unnegotiated required semantics require a new protocol version and ALPN. Additive optional JSON properties may be ignored by v1 implementations. `streamEofBytes` is an explicitly negotiated v1 capability: a host that requests it must not silently continue if the acknowledgement is absent. A broker, multi-region selection, UDP game transport, and hostname multiplexing are outside v1.
