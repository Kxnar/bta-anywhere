# BTA Anywhere

Work-in-progress mod for hosting a Better Than Adventure! 8.0.1 single-player world with a dedicated server.

It has LAN, direct, and self-hosted relay modes. Development currently targets Windows x86-64. There is no public relay.

Use a disposable world while testing.

## Build

JDK 21 and Rust 1.85+ are needed. See [setup](docs/setup.md) for build commands.

See the [benchmark procedure](docs/benchmarking.md) and the
[Windows loopback reliability report](docs/benchmarks/2026-10-08/README.md)
for measured results, retained failures and reproduction details.
The [scaling and recovery follow-up](docs/benchmarks/2026-10-08-scaling/README.md)
investigates event-loop scheduling, local delay/jitter/loss, and authenticated
restart detection, including failed experiments and remaining limits.

## License

[Apache-2.0](LICENSE). See [third-party notices](THIRD_PARTY_NOTICES.md) for dependencies and references.
