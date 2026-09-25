# Protocol hardening stacked on reliable stream completion

Status: implementation candidate, not merge ready. This branch combines
`codex/eof-control` at `3e0fe53` with the independent Workstream 1 branch at
`231ea83`. It does not change the LAN, Direct, world recovery, or process
ownership paths. No release, relay service, or encrypted guest mode is claimed.

## Design and player value

The relay and host keep the existing v1 length-prefixed JSON wire format and
64 KiB frame limit. Both reject invalid UTF-8, non-object frames, excessive
JSON nesting, duplicate defined top-level fields, and malformed typed fields.
The defined fields now include `features` and `bytes`, so the negotiated
`streamEofBytes` completion path receives the same duplicate protection as
registration and connection headers. Valid v1 frames and unknown additive
fields remain accepted. A malformed completion notice cannot be made
ambiguous by repeating its byte count or connection ID.

Both cross-language evaluators consume the same generated framed corpus and
canonicalise registration features as a sorted set, absent features as an
empty set, and completion counts as exact nonnegative integers. The model
checks pre-registration, unnegotiated, late, duplicate, and short completion
notices. It is a test model: production relay state and session-generation
behaviour still require unit and integration tests. The existing bounded
frame, active-stream, pending-notice, and process-resource limits remain.

## Threat and failure model

- Unauthenticated or malformed control data must not allocate unbounded
  memory, claim a stream's completion, or open a guest connection.
- A completion notice is useful only after explicit feature negotiation and
  for an active connection ID in the authenticated relay session. The relay's
  production path retains those checks from the EOF prerequisite branch.
- The host continues to fail registration when an older relay does not
  acknowledge the completion feature. There is no plaintext/encrypted mode
  change or silent downgrade.
- Parser agreement establishes conformance on tested inputs; it is not a
  security proof or a substitute for running the production relay state
  machine under faults.

## Shared evidence and pending gates

The earlier W1 release evaluators built from `29a290ba252820e81ca16adc14452b825b7145fb`
agreed on 10,000 fixed-seed cases and on 27.07 million generated cases in a
four-target manual campaign. The 7,207 recorded CPU seconds include evaluator
startup and result handling. Raw results were retained locally in ignored
`.dev/protocol-campaign/full-10000-after-sac-off-20260925/` and
`.dev/protocol-campaign/long-after-sac-off-20260925/` on the W1 worktree.
These results precede the W1 typed-boundary correction and this stacked
candidate; they are historical evidence, not a pass for this branch.

Required for this candidate: Rust and Java tests, Python generator tests,
100-case CI smoke, at least 10,000 fixed-seed cross-language cases, the
documented four-target long campaign, five clean sequential serial/concurrent
integration pairs, alternating same-machine release baseline/candidate
performance results, and the applicable two-hour soak. Record every failed,
skipped, flaky, or unavailable result. Full raw outputs belong in an ignored
`.dev/` directory or review attachment, with no live credentials or private
logs committed. The full campaign is outside ordinary pull-request CI.

## Rollback

This isolated branch can be discarded without changing `main` or either
source worktree. If protocol hardening regresses a deployed candidate,
revert its merge commit and rebuild the matching host/relay pair; do not
silently pair a host requiring `streamEofBytes` with an older relay.
