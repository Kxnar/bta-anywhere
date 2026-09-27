# Proposed design: regional relay coordinator (Workstream 4)

**Status:** Functional candidate implemented in an isolated local worktree. The
TLS coordinator, durable SQLite leases, signed ticket redemption, relay exact
port binding, Java resolver, explicit static fallback, and Windows integration
checks are described in [operator setup](regional-coordinator-operations.md)
and [functional evidence](workstream4-functional-evidence.md). Performance and
long soak gates remain open under the maintainer's
[merge-review exception](functional-merge-exception.md).

**Base:** The original W4 ticket prototype started from W3 relay interface
commit `c8ebf28`. This local branch was rebased onto finalized W3 commit
`08c26ce` before implementation; neither branch was merged or pushed. The
original design branch was based on `origin/main` at
`fdc7ef45fdf99637a5f710d7d8931b63ca4e7485`.

**Target:** Windows x86-64, operator-owned relay fleet. No public relay deployment.

## Player and operator value

A host with access to several operator-owned relays should get a healthy relay
with an available port and low measured **host-to-relay** latency when starting
a new session. The coordinator allocates new sessions only. It does not move an
established vanilla TCP connection, guarantee the guest's own latency, or keep
an encrypted invitation valid when the relay endpoint or identity changes.
Existing `StaticRelayResolver` remains a supported configuration and the
fallback for an unavailable coordinator when the operator explicitly configures
a separate trusted static destination.

The coordinator is one additional Windows service, a durable local SQLite
database, a protected signing key, and an authenticated control endpoint.
Operators must provision, monitor, back up, and upgrade it. One process owns a
fleet; active-active coordinators and shared network filesystems are outside
this design.

## Existing boundaries

Today, `RelayResolver.resolve()` returns one `RelayDescriptor` containing the
host name, QUIC port, trusted certificate path, and relay access token.
`NettyTunnelSession` resolves again on reconnect, while the relay independently
binds the first available guest TCP port during registration. The relay retains
sessions and their resume tokens only in memory, with a 60-second grace window.
The coordinator must not turn a reconnect into an unrequested change of relay:
resume stays pinned to the original descriptor and port until the old session
has ended or the host explicitly starts a new allocation.

The operator provisions a stable relay ID, certificate trust anchor, token,
region label, and dedicated coordinator-managed guest-port subset for each
relay. The host's allowlist maps a returned relay ID to that local descriptor.
The coordinator never sends a new trust anchor or relay access token to a host.
Static sessions use a distinct guest-port subset. The relay remains the final
authority for per-token, per-source, per-session, and socket-bind limits.

## Control plane and trust

```text
host ---- authenticated allocation API ----> coordinator + durable leases
  |                                               ^
  | host-to-relay verified QUIC RTT probes        | authenticated heartbeat /
  |                                               | ticket redemption
  +---- ticket + existing access token ----> selected relay
                                                |
                                                +---- guest TCP listener
```

All control connections use TLS with configured peer identity checks. Relays
authenticate to the coordinator with a distinct operator-issued credential;
hosts use a scoped coordinator allocation credential. These credentials are
separate from the relay allocation token and are stored redacted. The
coordinator verifies a relay heartbeat before updating its health or capacity.
It stores only credential digests where it must compare bearer credentials.

Allocation tickets are canonical, versioned, short-lived, Ed25519-signed
records containing a random lease ID, coordinator key ID, relay ID, reserved
public port, host client-instance binding, protocol mode (`legacy` or
`encrypted`), issue time, and expiry. They contain no relay access token,
Minecraft identity, world path, or invitation. A maintained library supplies
the signature implementation; no signature or random-number primitive is
implemented in BTA code. The selected relay verifies the signature and all
bindings, then asks the coordinator to atomically redeem the lease before it
opens the reserved port. A ticket cannot be reused or redeemed at another
relay. If redemption cannot be confirmed, a new coordinator-managed session
fails closed; local same-session resume can continue under the relay's existing
resume-token rules. Key rotation retains old verification keys only through
the maximum outstanding ticket lifetime and removes them afterward.

The relay registration protocol needs an explicit `allocationTicket` feature
and ticket field. A coordinator-managed listener requires that feature and a
successful redemption; a legacy peer that ignores the field cannot access the
managed port subset. Static registration keeps the current protocol behavior
and limits. For encrypted guest mode, the ticket is bound to that mode and
cannot select legacy mode. Protocol version and feature negotiation require
specific compatibility tests before code is accepted.

## Durable lease transaction

The coordinator stores relay identity and health, a monotonic database
generation, ticket key ID, and each lease's relay ID, port, state, timestamps,
and client binding. The database has a uniqueness constraint on `(relay_id,
port)` for leases in `reserved`, `active`, or `quarantined` state. A single
transaction chooses a healthy relay and free managed port, inserts the
reservation, and commits before the signed ticket is returned. The relay
redeems with a compare-and-set transaction from `reserved` to `active`, bound
to the same relay, host, mode, port, ticket ID, and expiry. Concurrent or
replayed redemption loses the transaction and is rejected. A failed port bind
releases the lease through an authenticated relay report; until that report is
committed, the port remains unavailable.

Reservation expiry is short (proposed 30 seconds). The coordinator's clock is
authoritative for redemption. A host or relay with a skewed clock cannot
extend a ticket; if the coordinator clock moves backward across its persisted
high-water mark, allocation and redemption pause until the clock is corrected.
Forward jumps expire reservations early. The relay rejects a ticket with an
unknown key, invalid signature, wrong relay or mode before contacting the
coordinator. The coordinator also rejects tickets past its deadline. Neither
service logs ticket bodies or credentials.

An active lease remains reserved across host QUIC interruptions and the
relay's 60-second resume grace period. Heartbeats report the bound managed
ports and session generations. An authenticated close frees a port only after
the listener has stopped; an uncertain close or missing heartbeat quarantines
the lease. A relay is removed from **new** allocations after two missed
heartbeat intervals. Quarantined ports are not allocated again until the
relay reconnects, reports its current bound ports, and the coordinator
reconciles that report transactionally. On coordinator restart, it reloads
leases from the database, admits no allocations until each relay has
reconciled, and never assumes an absent heartbeat means a port is free.

SQLite transactions and the unique lease index arbitrate concurrent requests.
Only one coordinator process may use the local database at a time; startup
fails if another holds the instance lock. An interrupted commit yields either
the old or new lease state. Backup copies must use SQLite's consistent backup
operation, include signing-key recovery procedures, and be tested with a
disposable database. If the database or key is lost, the operator must stop
new coordinated allocations and reconcile all relay sessions before issuing
new tickets. There is no automatic reset that risks duplicate allocation.

## Selection and fallback

The host probes each configured relay using a certificate-verified QUIC
handshake with a short timeout, without registering a session. Probes are
rate-limited on the relay. The host submits the latest bounded probe samples
to the coordinator with its allocation request. Only allowlisted relay IDs
are eligible; failed or stale probes are ineligible. The coordinator filters
out relays with stale authenticated heartbeats, incompatible protocol mode,
or no unreserved managed port, then chooses the lowest median measured
host-to-relay RTT. Ties use a stable relay ID order. Self-reported client RTT
can affect only that client's choice, never capacity or other clients' leases.
The relay's local bind and quota checks remain authoritative even after a
reservation; a rejection releases the reservation and the host may request a
different relay once, within a bounded retry budget.

If the coordinator is unavailable before a new session starts, an explicitly
configured `StaticRelayResolver` can be used with its existing relay token and
trust anchor. The UI and CLI display whether the chosen path is coordinated
or static. A static fallback never accepts an untrusted coordinator-supplied
certificate or changes encrypted mode to legacy. If no static fallback is
configured, starting a new session fails with an actionable error. After a
session starts, the host keeps its relay descriptor for reconnection; it does
not silently reallocate to another region. If the relay process restarts and
loses its in-memory session, a fresh allocation may choose a different relay;
the old endpoint is gone and encrypted invitations must be revoked and reissued.

## Implementation sequence and validation

This section retains the planned gates and thresholds. For the present local
merge decision, the maintainer explicitly deferred performance and long
load/soak evidence, including the Workstream 0 baseline prerequisite; the
functional and 10,000-attempt concurrency-correctness gates remained required.
See [the exception](functional-merge-exception.md) for the exact scope and
remaining risk.

1. Close the Workstream 0 baseline/stability gate and record the coordinator
   scope decision. Keep the existing documented benchmark thresholds.
2. Add versioned ticket, lease-state, and authenticated API schemas with
   shared fixtures. Test signature, wrong relay/mode/host, expiry, replay,
   clock rollback, and malformed fields before relay integration.
3. Add the Windows coordinator with transactional managed-port leases and
   relay heartbeat/reconciliation. Test restart, interrupted transactions,
   stale health, clock skew, split-brain startup, and bounded state growth.
4. Add relay redemption and exact-port bind in the managed subset. Keep
   current static registration and data path unchanged. Verify port bind
   failure, quota failure, half-close, shutdown, and lease release ordering.
5. Add a resolver returning the allowlisted descriptor and opaque ticket,
   with verified RTT probes and explicit static fallback. Freeze its chosen
   relay on reconnect. Exercise the documented three-relay simulation with
   controllable RTT, capacity, failure, and restart.
6. Run the existing serial and concurrent Windows integration harnesses
   sequentially. Run only focused coordinator cases during development; the
   fixed 10,000-attempt contention, 100 requests/second for ten minutes,
   five clean integration runs, and two-hour churn/soak gates remain for
   final review. Record raw results and failures without changing thresholds.

The new metrics count healthy relays, allocations, rejections by reason,
expired and quarantined leases, redemption failures, and allocation latency.
They contain no client, session, user, address, token, or ticket labels.
Operator documentation must cover provisioning, secret rotation, health
alerts, backup/restore, upgrade, rollback to static mode, and the fact that
existing guest TCP connections cannot migrate.

## Open review decisions

- Accept the operational cost of a coordinator, durable database, and
  protected signing key for operator-owned multi-region fleets.
- Confirm that the host is allowed to use its measured RTT for its own
  selection; this measures the host path, not the guest path.
- Confirm the dedicated managed-port subset and explicit static fallback
  configuration. Shared static/coordinated ports would require a different
  concurrency model.
- Review the ticket lifetime, heartbeat interval, and clock-skew policy
  against the baseline and actual Windows prototype before implementation.

The `coordinator-protocol` crate signs and verifies canonical Ed25519 tickets
with exact relay, port, host, mode, and time bindings. The coordinator commits
reservations in SQLite before returning tickets, and the relay redeems once
before opening the exact managed port. Authenticated full-inventory heartbeats
reconcile leases after restart; a missing database or uniqueness index fails
closed. The host probes allowlisted relay endpoints through verified QUIC,
and its CLI requires locally configured coordinator trust and an explicit
static fallback. The deterministic three-relay simulation and the cross-process
Windows checks are in the [evidence](workstream4-functional-evidence.md).
The 100 requests/second for ten minutes and two-hour churn/soak remain unrun.
