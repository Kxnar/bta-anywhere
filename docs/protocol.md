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

V1 JSON has at most 127 object/array containers along any nesting path, counting the root object. A 128th container is rejected. The defined top-level protocol property names shown in this document may each occur only once. Additive unknown properties remain permitted and ignored, including repeated unknown names; the duplicate restriction applies only to defined top-level protocol names. Java now validates these rules before Gson creates a JSON object, matching Rust's effective nesting boundary and rejection of repeated known fields. It also rejects Gson's formerly tolerated nonstandard JSON syntax. No valid v1 frame or 64 KiB size limit changes.

The current hardening tests also reject invalid UTF-8 in both directions, including bytes inside an unknown property that the typed parser would otherwise ignore. The Rust reader validates the complete bounded payload before deserializing it. The tests require a numeric, unsigned heartbeat sequence. These are v1 wire requirements; a quoted number is not a sequence number.

Escaped JSON Unicode must decode to scalar values. An unpaired high or low
surrogate is rejected in any property name or string value, including an
unknown property; a correctly paired high/low escape is accepted. The Java
streaming scan checks decoded names and strings before Gson constructs a tree,
matching Rust's string parser. This also prevents malformed UTF-16 from
reaching the Java evaluator's UTF-8 result writer.

V1 JSON numbers use `serde_json`'s finite numeric range for both known and
unknown fields. Integer literals within signed 64-bit negative and unsigned
64-bit positive bounds retain exact integer semantics, except the JSON literal
`-0`, which retains binary64 negative zero. Other numeric literals
are accepted only when they convert to a finite binary64 value; for example,
`1e-400` underflows to zero, while `1e400` and a 309-digit integer are
rejected. The Java decoder checks this bounded range before Gson builds its
tree, and the differential evaluator canonicalises finite numbers to the
same integer-or-binary64 semantics. Typed protocol fields have stricter
ranges: `version` and `publicPort` are unsigned 16-bit integers; heartbeat
`sequence` is unsigned 64-bit; `streamEof.bytes` is a nonnegative integer no
larger than Java's signed 64-bit maximum. Decimal or exponent forms cannot
stand in for those typed integer fields.

Typed v1 string fields must contain JSON strings. The `version` and `publicPort` fields consumed by the Java tunnel must be JSON unsigned integers within the Rust `u16` range; decimal, exponent, quoted, negative, and out-of-range values are rejected. This removes Gson's earlier coercion of malformed fields, including quoted versions and numeric identifiers. A peer that relied on those malformed representations will fail to interoperate after this hardening; documented valid v1 frames and the `bta-anywhere/1` version/ALPN remain unchanged. There is no plaintext or permissive fallback. The shared typed boundary vectors give both implementations the same exact frames and acceptance expectations.

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

## Validation status

The shared structural regression vectors cover duplicate known and unknown top-level properties, the 127/128-container boundary, and invalid UTF-8 in ignored fields. On 25 September 2026, release evaluators built from `29a290ba252820e81ca16adc14452b825b7145fb` reported zero differences on 10,000 fixed-seed cases under the earlier Python comparator. A manual campaign then processed 27.07 million generated cases with reported zero differences and at least 30 combined Rust/Java evaluator CPU minutes in each of framing, control, connection, and modelled state. These are historical case-count and CPU measurements; the comparator could hide JSON numeric-type differences, so they do not establish conformance for the corrected branch. The run took about 52 minutes 44 seconds on one Windows x86-64 machine. Its 7,207 CPU seconds include JVM startup, corpus reading, and result writing; they do not establish parser-only CPU hours. The state target compares a test model and does not execute the production relay state machine. Raw JSON and batch manifests are retained locally under the ignored `.dev/protocol-campaign/full-10000-after-sac-off-20260925/` and `.dev/protocol-campaign/long-after-sac-off-20260925/` directories; they must be attached to review if needed elsewhere. Later W1 changes added bounded evaluator failure tails, UTF-8 identifier length boundaries, and exact typed coercion cases shared through `protocol/test-vectors/typed-boundaries-v1.json`. The stacked EOF/W1 candidate further extends shared vectors, duplicate-field checks, and the differential state model to registration features and `streamEof` counts. The earlier long-campaign result does not cover those changes.

At stacked code commit `1b4c166a0bd00c44c658e0ede80fa719bd8cbb4e`, fixed-seed 100-case and 10,000-case cross-language runs reported zero mismatches under Python's ordinary equality. Inspection of the retained 100-case raw output revealed a hidden mismatch at case 43: Rust decoded JSON `-0` to binary64 `-0.0`, while the Java evaluator reduced it to integer `0`. Python considered those values equal. The comparator now preserves primitive types and signed zero, the Java evaluator retains negative zero, and a shared regression vector covers the literal. The earlier zero-mismatch reports are historical only; fresh 100-case, 10,000-case, and four-target long campaign results are required at the corrected commit. The separate nine-frame surrogate replay rejected six unpaired escapes and accepted three valid pairs in both implementations. Raw results and commands are indexed in [Protocol hardening stacked review](protocol-stacked-review.md).

The earlier W1 branch's release relay and tunnel passed one 100-iteration serial half-close integration and one 100-wave concurrent integration run, sequentially. A second serial run also passed, but the following concurrent run failed on its first wave: one guest received all expected bytes and then timed out waiting for EOF while the relay still reported two active connections. The five-consecutive-integration-run stability gate therefore failed on that revision; later pairs were not run. Its raw log remains in the ignored `.dev/protocol-campaign/integration-after-sac-off-20260925/` directory. The stacked candidate includes the later negotiated completion path, but its integration and performance results must be measured separately. The revised 100-case step passed locally at the stacked code commit; hosted Windows runner duration remains unmeasured. The extra bounded Rust parse and complete UTF-8 and Unicode checks may add control-frame CPU cost. A same-machine baseline/candidate performance comparison also remains open, so these results do not establish merge eligibility.
