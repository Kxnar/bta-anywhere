# Explicit relay stream completion (design)

**Status:** Implemented candidate fix for an observed Windows missing-EOF failure. It remains on `codex/eof-control` for review; this document does not imply merge approval or that the roadmap gates have passed.

## Observation and user value

On a synthetic concurrent half-close transfer, the guest received every response byte but waited indefinitely for TCP EOF. Same-run Java event tracing showed local EOF and a successful QUIC output-shutdown callback. The Java-side QLOG recorded all response bytes and a zero-length application-to-transport send, but no outgoing FIN packet for one stream while seven sibling streams sent FIN. A Netty 4.2.18 Windows upgrade also failed the concurrent EOF test twice. Raw diagnostics remain in ignored `.dev/` directories; no game world was involved. The exact library defect remains unknown.

The user-visible goal is that a finished response closes the guest TCP output after all bytes arrive, even when the host QUIC library does not emit a FIN. A missing, stale, or malformed completion notice must fail closed or leave the connection incomplete; it must never silently truncate verified game data.

## Negotiated v1 extension

The host registration requests `features: ["streamEofBytes"]`. A supporting relay returns the same feature in `registered`. A new host requires this acknowledgement before reporting the tunnel active; an old relay yields an actionable upgrade error. An old host may continue using a new relay with the original native-FIN behavior. This is an explicit compatibility migration within protocol v1, not an unannounced fallback. Existing LAN, Direct, and self-hosted Relay modes remain available; no public relay is introduced.

For each guest QUIC bidirectional stream, the relay already sends a random `connectionId` in `ConnectionOpen`. After the local service's TCP input reports EOF, the host waits for its final QUIC data write and output-shutdown callback. It then sends exactly one authenticated control frame `{ "type": "streamEof", "connectionId": "...", "bytes": N }`, where `N` is the number of response bytes submitted successfully to that QUIC stream. The host bounds queued notices and closes its QUIC connection on a notice-write failure, making pending guests disconnect rather than wait for unconfirmed EOF. It never logs the raw connection ID or payload.

The relay associates notices only with active streams in the same authenticated session. It keeps at most one entry per admitted guest stream, bounded by the configured active-connection quota. It copies QUIC response bytes to guest TCP and counts completed writes. Native QUIC EOF remains a valid completion. A notice permits guest TCP output shutdown **only after exactly `N` bytes have been copied**. A notice count below bytes already copied, a premature native EOF relative to an already received notice, a duplicate, an invalid frame, or excess data observed before closure fails that stream. Late notices for already finished streams are ignored without retaining new state. Connection cancellation removes the entry. No new payload buffer, frame size, guest limit, or automatic retry is introduced. The relay deliberately does not wait for FIN after `N`: it therefore cannot detect bytes a buggy or dishonest host sends later. The host is already trusted in legacy Relay mode; this mechanism is not end-to-end integrity against the host.

The relay operator and authenticated host can already read and modify guest traffic in legacy Relay mode; this extension does not add guest encryption or change that trust boundary. It does not change world ownership, backup restore, process identity, TLS verification, archive verification, path confinement, quotas, or secret redaction.

## Verification and rollback

Shared Rust/Java protocol vectors must cover feature negotiation and typed `streamEof` fields. Rust tests must cover exact count, notice before/after bytes, native FIN, short/excess data, duplicate/stale/wrong-session notices, cancellation cleanup, and bounded active state. Java tests must cover one notice per local EOF, correct byte count, failed control write, old-relay capability rejection, and no secret logging. Then run release builds, serial and concurrent integration sequentially, five clean Windows integrations, unchanged full benchmark cases and same-machine comparison, and a two-hour eight-stream soak. Retain raw JSON, toolchain, machine, commit, commands and failure counts. No benchmark threshold or default limit may be changed after the result. The separate throughput stall must be measured independently.

Rollback is to leave this branch unmerged; if later merged, rolling back requires deploying the prior matched host/relay pair. A host that requires `streamEofBytes` must not silently run against a relay that omits it.
