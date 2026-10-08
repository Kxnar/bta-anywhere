# Fault supplement observation-window correction

The original baseline and candidate records remain unchanged at
`benchmark-results/2026-10-08/baseline/faults.json` and
`benchmark-results/2026-10-08/candidate/faults.json`. Both used fault harness
SHA-256 `53885d8b7fd060e319dcb17f7e41d933362ef945bd2f3f25973b5b26e479a035`
and failed the process-termination idle check while fresh byte-exact/EOF recovery
succeeded. No result has been reclassified or overwritten.

In the baseline, fault onset was monotonic 116294.0361649. The snapshot after
forced observation termination was 116304.6560579, only 10.619893 seconds later.
The next UDP fault started at 116314.7287683, 20.692603 seconds after process
termination, showing that the ten-second idle verdict preceded 30 seconds.
Fresh recovery succeeded in 0.600691 seconds. Candidate onset was 116405.2035597
and its post-observation snapshot was 116415.8135507, 10.609991 seconds later;
fresh recovery succeeded in 0.581330 seconds. Each existing stream had verified
only its initial 4096-byte exchange before the fault. The artificial local-close
timeout cannot tell us when that stream would naturally have ended.

The original harness waited five seconds for post-fault traffic, joined its worker
for five seconds, forcibly shut the TCP socket, and then waited ten seconds for
the active-stream gauge. Relay forwarding retains a QUIC response read until
the QUIC connection reports failure; the active gauge decrements only after its
forwarding task returns (`relay/src/state.rs`, accept_guest and forward_guest).
The relay uses `quinn::TransportConfig::default()` without overriding idle timeout
(`relay/src/main.rs`, server transport configuration). Installed
`quinn-proto-0.11.17/src/config/transport.rs` sets its default `max_idle_timeout`
to 30000 ms. The baseline relay logs report control loss for the terminated
process's port at 00:32:29.063359Z and the subsequent UDP fault's port at
00:32:49.748614Z. Their 20.685255-second separation closely matches the
20.692603-second fault-onset spacing, consistent with the same roughly
30-second idle expiry after each fault. The baseline gauge subsequently reached
zero during the UDP case. This supports a premature observation verdict;
it does not independently prove the original stream's exact terminal latency.

Fault schema 2 now observes natural EOF/reset or successful post-fault exchange
through a bounded, configurable 90-second default budget. Process faults count
from onset; UDP faults count from the actual scheduled gate restoration. Initial
and rebased worker deadlines are recorded. Natural terminal timestamps provide
closure latency independently of fresh-probe observation time. Deadline expiry,
forced close and unexpected local errors remain incomplete failures; byte
corruption cannot be hidden by recovery retries. A worker surviving forced
shutdown is recorded as `worker_terminated=false`, fails the case, and cannot
mutate the lock-protected detached evidence snapshot. All owned process exit
records and observed CLI retry counts come from Rig.lifecycle_snapshot.

Twenty unit/local regressions passed, including a simulated natural terminal
at 30 seconds beyond the former five-second join, expired observation, a
nonterminating worker, unexpected local implementation error, incomplete
observation despite fresh recovery, absolute deadlines, corruption and
cancellation retention. The revised real fault runs subsequently passed for both frozen tunnel builds; see the report and results/*/faults-observed.json. Their recorded harness hashes distinguish them from the original failed records.
Raw regression output: [20 passing fault tests](results/regressions/fault-observation-tests.txt). The combined 60-test output is retained at [python-tests-observation.txt](results/python-tests-observation.txt).
