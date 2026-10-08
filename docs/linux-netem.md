# Linux kernel network impairment campaign

This campaign measures the Java tunnel and Rust relay on Linux x86-64. The
Windows game mod is excluded with `-PtunnelOnly=true`. It does not demonstrate
gameplay, a physical two-machine network, NAT traversal or a real WAN.

Use a disposable Linux runner with Java 21, Rust 1.85, Python 3.11 or newer,
OpenSSL and iproute2. Network namespace creation needs root/CAP_NET_ADMIN.
The script creates uniquely named namespaces and a veth pair entirely inside
them; it does not change the runner's existing routes, interfaces or qdiscs.

```sh
cargo build --locked --release --package bta-anywhere-relay
bash gradlew --no-daemon -PtunnelOnly=true check build
java -jar tunnel-client/build/libs/bta-anywhere-tunnel-0.1.0-all.jar doctor
sudo python3 scripts/benchmark_netem.py \
  --relay-binary target/release/bta-anywhere-relay \
  --tunnel-jar tunnel-client/build/libs/bta-anywhere-tunnel-0.1.0-all.jar \
  --java "$(command -v java)" \
  --condition jitter --sessions 3 --output-directory /tmp/bta-netem-jitter-new
```

Repeat with `clean` and `loss`, always using a fresh output directory. The
manual **Linux netem validation** GitHub workflow runs all three conditions on
separate Ubuntu 24.04 runners. Each runner creates three independent relay and
tunnel sessions sequentially. Build and test work finishes before measurement.
Every result, including failed sessions, is uploaded with a 90-day retention
window; copy selected evidence into a versioned archive for long-term claims.

## Topology and interpretation

The synthetic guest, Java tunnel and byte-verifying echo server share one
namespace. The Rust relay occupies the other. QUIC UDP packets traverse the
veth pair and its kernel `netem` queues in both directions. Guest TCP and admin
HTTP traverse the same pair but bypass impairment, selected by an IP protocol
filter. The echo server remains local to the Java tunnel.

`clean` has no qdisc impairment. `jitter` applies 10 ms delay with 2 ms normally
distributed jitter and a 20 Mbit/s rate to each UDP direction; `loss` adds 1%
random loss. Each netem queue holds at most 1,000 packets, so queue overflow is
also possible. These are nominal kernel parameters, not promises of exact
delivered delay or loss. Random kernel schedules are independent and unseeded.
The final `tc -s -j qdisc` output records actual drops and backlog. Shared runner
CPU scheduling and veth offload can affect results; do not generalise to a WAN.

Each session records 60 transactions: ten repetitions of request/reply and TCP
half-close at 1 KiB, 64 KiB and 1 MiB. Latency includes TCP connection setup,
byte verification and response EOF. Eight concurrent throughput streams send
for eight seconds and get the existing 120-second drain allowance; every reply
byte and EOF is checked. Report verified payload bytes per direction divided
by the whole concurrent phase, including drain, rather than by the send budget.

One relay restart per session has a fixed 100-second observation window, with
recovery defined as a verified 1 KiB transaction on the original endpoint.
Failed probes and their denominator remain in the result. Resource samples
every 500 ms use `/proc` CPU time and resident pages for each process PID,
including replacement relay PIDs. Peak sampled RSS is not allocator accounting
or a proof of a memory bound. CPU deltas must never span different PIDs.

Results are checkpointed before transactions and after phases. A failed
throughput phase retains partial successes and individual stream failures.
Graceful tunnel cleanup, relay termination and namespace deletion are checked;
cleanup failure makes the run fail. Test certificates include the peer IP and
normal CA/hostname verification remains enabled. Credentials, keys and test
worlds are not uploaded.
