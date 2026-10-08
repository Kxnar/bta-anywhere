# BTA Anywhere

Work-in-progress mod for hosting a Better Than Adventure! 8.0.1 single-player world with a dedicated server.

It has LAN, direct, and self-hosted relay modes. Development currently targets Windows x86-64. There is no public relay.

Use a disposable world while testing.

See the [CV-readiness validation status](docs/validation-status.md) for the
remaining multiplayer demonstration and external-network validation work.

## Build

JDK 21 and Rust 1.85+ are needed. See [setup](docs/setup.md) for build commands.
The [architecture](docs/architecture.md) explains world ownership, relay data
flow, backpressure, EOF and the limits of the current evidence. The standalone
tunnel also has a [Linux netem campaign](docs/linux-netem.md).

See the [benchmark procedure](docs/benchmarking.md) and the
[Windows loopback reliability report](docs/benchmarks/2026-10-08/README.md)
for measured results, retained failures and reproduction details.
The [scaling and recovery follow-up](docs/benchmarks/2026-10-08-scaling/README.md)
investigates event-loop scheduling, local delay/jitter/loss, and authenticated
restart detection, including failed experiments and remaining limits.
The [jitter diagnosis and 25-trial recovery distribution](docs/benchmarks/2026-10-08-jitter/README.md)
add live transport measurements, pacing/ordering controls and longer drain observations.
The [packet-level follow-up](docs/benchmarks/2026-10-08-packets/README.md) traces
the divergent native RTT estimate and evaluates the QUIC dependency update with
matched controls. The [Linux netem report](docs/benchmarks/2026-10-08-linux/README.md)
records nine independent sessions, 540 verified transactions and nine successful
restart recoveries. [CV material](docs/cv-material.md) links each claim to its evidence.

## License

[Apache-2.0](LICENSE). See [third-party notices](THIRD_PARTY_NOTICES.md) for dependencies and references.
