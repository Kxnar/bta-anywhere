# Contributing

Contributions are welcome for BTA 8.0.1 safety, protocol correctness, relay operations, compatibility, tests, and documentation.

Before opening a pull request:

1. Keep the tunnel library free of Minecraft dependencies and the BTA mod client-only.
2. Preserve the world-ownership invariant and never add automatic backup restore.
3. Add shared protocol vectors for wire changes and bump the version/ALPN for incompatible semantics.
4. Add a deterministic test for lifecycle, extraction, path, process, mapping, quota, or reconnect changes.
5. Run the automated gates in [Building](docs/building.md).
6. Do not commit BTA/Minecraft artifacts, saves, logs, tokens, keys, certificates, generated runtimes, or personal configuration.
7. Update `THIRD_PARTY_NOTICES.md` before adapting upstream source or adding a redistributed dependency.
8. Use your own accurate Git author identity. Do not add automated-agent authorship or generation trailers.

Security-sensitive issues should follow [SECURITY.md](SECURITY.md), not a public issue.
