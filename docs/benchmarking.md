# Windows loopback benchmark

`scripts/benchmark_relay.py` measures the release Rust relay and shaded Java
tunnel against the same synthetic TCP service used for the direct baseline.
It does not launch BTA, use a world, or measure internet/WAN behavior. It is a
standard-library Python script and adds no production dependency.

Run on Windows x86-64 with JDK 21 on `PATH` (or pass an absolute path to
`--java`). Close unrelated heavy programs,
connect AC power if available, and reserve the local relay ports from other
integration tests. Build at a recorded commit:

```powershell
cargo build --locked --release --package bta-anywhere-relay
.\gradlew.bat --no-daemon :tunnel-client:shadowJar
python -m unittest discover -s scripts -p test_benchmark_relay.py
python scripts\benchmark_relay.py --profile smoke `
  --relay-binary target\release\bta-anywhere-relay.exe `
  --tunnel-jar tunnel-client\build\libs\bta-anywhere-tunnel-0.1.0-all.jar `
  --java java `
  --output benchmark-results\smoke.json
```

Use `--profile full --output benchmark-results\baseline-1.json` for a full
baseline. Run the unchanged commit three times, preferably interleaving a
candidate between baseline runs on the same machine. Compare median throughput
and p95 latency. Variation above 15% between unchanged runs blocks performance
comparison until the environmental cause is recorded. A candidate comparison
uses the same seed, machine, binaries built from each recorded source commit,
and configuration. Confirm relay and tunnel artifact source fields before
attributing a difference to a candidate. Do not move a threshold after seeing
the result.

The full profile covers both direct and relayed TCP, 1 and 8 streams, 1 KiB,
64 KiB, and 1 MiB payloads, request/response and half-close modes. Each latency
case has five warm-up waves and 30 measured waves, with one sample per stream.
Direct and relay are alternated within waves; relay-added samples are paired
differences for the same generated payload and stream index.
The throughput profile uses five 60-second runs per concurrency and path, in
alternating direct/relay order. The byte pattern and validation are identical
for both paths. Request/response waits for an exact reply without a guest
half-close; half-close waits for a guest EOF before the server replies. Every
test also checks server EOF. Throughput uses persistent bidirectional streams.
After throughput, three separate recovery cases run. First, the disposable
relay restarts, interrupting the tunnel QUIC connection; the old endpoint is
probed, although the CLI does not print a post-reconnect endpoint. Second, the
benchmark terminates its tunnel process and launches a fresh one with no
in-memory resume token. Its new CLI endpoint is parsed and compared with the
old endpoint; reallocation is reported rather than hidden. This test uses a
two-port disposable relay range while retaining normal token/session limits.
Third, a benchmark-owned loopback UDP bridge drops both directions for 55
seconds while the new tunnel process stays alive. The bridge holds no packet
queue, and it is closed after the test. The resumed relay session's port is
compared with the bridge-backed session established immediately before the
drop, separately from the original direct-path endpoint. A byte-exact probe
checks that the bridge-backed endpoint still works; the relay resume event
provides supporting port evidence. Bridge traffic does not enter latency or
throughput measurements.
The disposable relay uses `RUST_LOG=warn` during data-path measurement and
`RUST_LOG=info` only after relay restart for recovery-event evidence. These
settings are recorded in JSON for consistent future comparisons.

Before reviewing a data-path or lifecycle change, run the bounded two-hour soak:

```powershell
python scripts\benchmark_relay.py --profile soak `
  --relay-binary target\release\bta-anywhere-relay.exe `
  --tunnel-jar tunnel-client\build\libs\bta-anywhere-tunnel-0.1.0-all.jar `
  --java java `
  --output benchmark-results\soak.json
```

The soak uses eight streams and samples memory and active connections every
approximately 60 seconds. Each stream sends deterministic 64 KiB blocks with
0.5-second pacing. It verifies both directions and EOF, checks that the active
gauge returns to zero, and requires process termination. The report retains
the memory samples for review of growth. `--soak-seconds` accepts 7,200 to
14,400 seconds, rejecting non-finite values. A truncated or failed transfer makes
the command exit nonzero and writes bounded diagnostics. Do not treat a short
smoke run as the two-hour gate.

`benchmark-results/` is ignored because results are machine-specific. Keep
the JSON and generated Markdown with the review, not in the repository. JSON
schema version 8 includes environment and toolchains, separate harness, relay,
and tunnel source revisions, artifact hashes, test configuration, raw samples,
aggregate p50/p95/p99, CPU time,
working set/peak resident memory, reconnection, failures, and run duration.
The soak records memory samples for manual growth review; a successful data
transfer run alone does not close that review gate.

Schema 6 added bounded per-stream failure records, the successful peer streams
in a failed concurrent wave, completed latency cases, and the last 160
synthetic-service lifecycle events on failure. A reply that passes byte
validation but times out waiting for TCP EOF is labelled `missing_eofs` and
also counted as a timeout. Transfer counts describe failed streams; the report
still exits nonzero on the first failed case. The lifecycle events contain no
payload, peer address, token, or world path. Schema 2-5 files remain valid
historical evidence but must not be silently pooled with later samples.

For a focused reproduction of an eight-stream relay stall, use
`--profile diagnostic-eight-relay` with the same release artifacts and seed.
For example:

```powershell
python scripts\benchmark_relay.py --profile diagnostic-eight-relay `
  --relay-binary target\release\bta-anywhere-relay.exe `
  --tunnel-jar tunnel-client\build\libs\bta-anywhere-tunnel-0.1.0-all.jar `
  --java java `
  --output benchmark-results\diagnostic-eight-relay.json
```

It runs exactly one 60-second relayed throughput case with the full eight
streams and unchanged 30-second socket timeout. On failure, JSON retains the
case, run, path, stream indices, byte counts, bounded exception chain, relay
metrics, and bounded synthetic-service errors. A failed diagnostic is evidence
of an unresolved data-path problem; stop the full/soak sequence and investigate
it rather than weakening the test.

If the isolated eight-stream case passes but the full run stalls at its first
eight-stream relay case, use `--profile diagnostic-sequence` with the same
arguments and a new output path. This replays the **unchanged full-profile
prefix**: all latency waves, five 60-second direct/relay pairs at one stream,
one 60-second direct run at eight streams, then the first 60-second relayed run
at eight streams. It stops immediately after that case. This diagnostic
profile cannot satisfy the full benchmark or soak gate. During the last case,
schema 7 retains at most 90 one-second process/relay samples and the last 160
synthetic-server lifecycle events. Events contain case, connection number,
mode, first-payload size, EOF or error type; they contain no payload or peer
address. The relay byte counters are updated only when a transfer finishes,
so their flat value while eight streams are open cannot by itself show that
no bytes traversed them. Keep the failed full results and diagnostic result
separate when reviewing the cause.

Schema 7 also samples diagnostic throughput byte progress at both ends of each
stream once per second. Each JSON sample and the final snapshot names stream
indices 0-7 and records guest TCP send/receive and synthetic echo TCP
receive/send bytes, plus observed EOF transitions and error class names. The
generated Markdown includes the final snapshot. These are bytes accepted by or
read from local TCP sockets; they do not establish QUIC delivery or identify a
transport-level cause. The diagnostic-only synthetic mode sends a one-byte
stream index before the throughput payload so the echo service can correlate
the four counters despite concurrent connection ordering. Normal full, smoke,
and soak traffic is unchanged. The focused diagnostic is not a performance
comparison against normal mode. No payload bytes, addresses, tokens, or raw
exception messages enter the progress samples. The counter array is capped at
eight streams and the time series at 90 samples; failed runs still exit nonzero
and retain the final snapshot.

Schema 8 records `source_provenance.harness`, `.relay`, and `.tunnel`
independently. Each available entry has a Git commit and a `dirty` boolean for
tracked or untracked, non-ignored checkout changes at measurement time. The
harness entry comes from the checkout containing this Python script; relay and
tunnel entries come from the checkout containing each supplied artifact path.
An artifact outside a Git checkout, or one whose Git state cannot be read,
gets `status: unavailable` with a reason. No checkout paths or Git error text
are retained in this field. The existing SHA-256 values identify the exact
relay executable and tunnel JAR measured. Checkout inference alone does not
prove that a file was built from that commit, especially when it was copied
or the checkout was dirty; keep build commands and hashes with a review.
Earlier schema 2-7 `commit` values identify the working checkout used to run
the harness and cannot independently identify artifacts passed from another
checkout. Do not attribute a candidate result from those files using that
field alone. The artifact hashes remain usable for file identity.

The test config binds all services to loopback and raises only its disposable
relay's per-source accept rate from 30 to 10,000/minute so repeated latency
samples exercise the data path. The production default is unchanged; the
configured rate is not a measured capacity. The eight-active-guest limit is
unchanged. Long throughput and soak runs reuse persistent connections.

The harness uses synthetic traffic and one machine. It cannot establish real
player experience, public-network performance, or capacity under production
rate limits. Observe physical memory, CPU and thermal state when comparing
runs; current/peak process working-set figures and CPU time are point and
process-lifetime observations, respectively.

For the dated development history, raw artifact inventory, open gates, and
review scorecard, see [benchmark workstream review](benchmark-review.md).
