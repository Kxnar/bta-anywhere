# Workstreams 3 and 4: functional merge review exception

On 27 September 2026, the maintainer explicitly deferred performance benchmarks
and long load or soak runs for the Workstream 3 and Workstream 4 merge decision.
The thresholds and commands in the technical roadmap and workstream designs
remain intact. Deferred gates are **not passed** and must not be represented as
performance or sustained-operation evidence.

The functional review still requires builds, unit and integration tests,
security and malformed-traffic rejection, failure and cleanup checks,
compatibility with LAN, Direct, and legacy Relay, concurrency correctness,
and five clean Windows functional integration runs. The serial and concurrent
relay harnesses must run sequentially because they share ports. Workstream 3
also requires an actual disposable BTA 8.0.1 client joining a disposable world;
Workstream 4 requires deterministic three-relay selection and durable lease
restart/reconciliation evidence. A missing real join or security/failure gate
means **not ready**.

The deferred W3 evidence is the alternating baseline/candidate throughput,
latency, invitation-handshake latency, CPU, and memory comparison and the
two-hour encrypted eight-stream soak. The deferred W4 evidence is the
100-allocation-requests-per-second, ten-minute latency/success run and the
two-hour churn/soak. The fixed 10,000-attempt, at-least-100-worker contended
lease uniqueness check remains a concurrency-correctness gate and is **not**
deferred.

The remaining risk is unknown performance regression, resource growth, or
sustained-load/churn failure. This exception authorizes a functional-only
readiness decision; it does not authorize a performance claim or deployment.
