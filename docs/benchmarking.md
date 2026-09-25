# Windows loopback benchmark

`scripts/benchmark_relay.py` measures the release Rust relay and shaded Java
tunnel against the same synthetic TCP service used for the direct baseline.
It does not launch BTA, use a world, or measure internet/WAN behavior. It is a
standard-library Python script and adds no production dependency.

Run on Windows x86-64 with a JDK 21 runtime. Close unrelated heavy programs,
connect AC power if available, and reserve the local relay ports from other
integration tests. Build at a recorded commit:

```powershell
cargo build --locked --release --package bta-anywhere-relay
.\gradlew.bat --no-daemon :tunnel-client:shadowJar
python -m unittest discover -s scripts -p test_benchmark_relay.py
python scripts\benchmark_relay.py --profile smoke `
  --relay-binary target\release\bta-anywhere-relay.exe `
  --tunnel-jar tunnel-client\build\libs\bta-anywhere-tunnel-0.1.0-all.jar `
  --java .tools\jdk-21\bin\java.exe `
  --output benchmark-results\smoke.json
```

Use `--profile full --output benchmark-results\baseline-1.json` for a full
baseline. Run the unchanged commit three times, preferably interleaving a
candidate between baseline runs on the same machine. Compare median throughput
and p95 latency. Variation above 15% between unchanged runs blocks performance
comparison until the environmental cause is recorded. A candidate comparison
uses the same seed, machine, binaries built from each recorded commit, and
configuration. Do not move a threshold after seeing the result.

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
The reconnect test restarts the disposable relay, interrupting the tunnel QUIC
connection. It measures recovery from relay readiness and probes the previous
endpoint. The test has a fixed one-port pool, so that probe does not prove
general endpoint retention; a tunnel-process or link-only interruption is not
measured by this first harness version.

Before reviewing a data-path or lifecycle change, run the bounded two-hour soak:

```powershell
python scripts\benchmark_relay.py --profile soak `
  --relay-binary target\release\bta-anywhere-relay.exe `
  --tunnel-jar tunnel-client\build\libs\bta-anywhere-tunnel-0.1.0-all.jar `
  --java .tools\jdk-21\bin\java.exe `
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
schema version 2 includes environment and toolchains, commit and artifact
hashes, test configuration, raw samples, aggregate p50/p95/p99, CPU time,
working set/peak resident memory, reconnection, failures, and run duration.
The soak records memory samples for manual growth review; a successful data
transfer run alone does not close that review gate.
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
