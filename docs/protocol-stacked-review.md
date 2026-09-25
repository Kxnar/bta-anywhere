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
ambiguous by repeating its byte count or connection ID. The Java host also
requires the relay error's `retryable` field to be an actual JSON boolean.
Rust serializes this server response; its type check is Java response-decoding
hardening with a negative unit test, not a cross-language differential claim.
Unsigned 64-bit accessors reject literals longer than 20 digits before doing
large-integer conversion, preserving the existing wire range.

A targeted five-frame private probe after the initial 100-case and 10,000-case
passes found that Gson accepted numbers outside `serde_json`'s finite range
and retained different semantics for out-of-range but finite numbers. Its
generator and Rust/Java outputs are retained under the ignored
`.dev/protocol-campaign/unknown-number-*` paths in this worktree. The decoder
now checks finite numeric range before building Gson objects, and shared
structural vectors plus the deterministic smoke corpus cover the observed
accepted and rejected boundaries. Earlier zero-mismatch runs do not validate
this correction; rerun them from its final commit.

A later private escaped-surrogate probe found that Rust rejected lone high and
low surrogate escapes while Java accepted them, then the Java evaluator failed
while writing malformed UTF-16 as UTF-8. Its exact corpus and Rust/partial
Java outputs remain in ignored `.dev/protocol-campaign/surrogate-*` files.
The Java streaming scan now rejects unpaired surrogates in all decoded names
and string values. Shared structural vectors cover unknown names, unknown
values, known fields, and valid high/low pairs. The pre-fix probe failure is
retained as evidence. The exact-commit validation follows.

At code commit `1b4c166a0bd00c44c658e0ede80fa719bd8cbb4e`, the fresh
fixed-seed 100-case and 10,000-case Rust/Java differential runs each finished
with zero framing, typed, or modelled-state mismatches. The 100-case corpus
included all nine surrogate vectors. A separate replay of the pre-fix
nine-case corpus rejected six unpaired escapes and accepted all three paired
controls with identical Rust/Java outcomes. Raw summaries, both evaluator
outputs, and the replay script are retained privately under
`.dev/protocol-campaign/stacked-surrogate-100-20260925/`,
`.dev/protocol-campaign/stacked-surrogate-10000-20260925/`, and
`.dev/protocol-campaign/surrogate-*` in this worktree. The code also passed
`cargo fmt --all -- --check`, `cargo test --locked --all` (20 relay tests and
11 corpus tests), `:tunnel-client:test`, and 17 Python generator tests.
These results do not include the long CPU campaign, repeated shared-port
integration, baseline/candidate performance, or soak.

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
