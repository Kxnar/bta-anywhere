# Third-party notices

BTA Anywhere is licensed under Apache License 2.0. Its release artifacts contain or link against third-party software under compatible licences. This file is informational and does not replace those licence terms.

## Redistributed in the Java tunnel and mod JARs

| Component | Version | Licence |
| --- | --- | --- |
| Gson and Error Prone annotations | 2.13.1 / 2.38.0 | Apache-2.0 |
| Netty common, buffer, resolver, transport, codec, and handler | 4.1.122.Final | Apache-2.0 |
| Netty Incubator Codec QUIC classes and natives | 0.0.73.Final | Apache-2.0 |
| offbynull portmapper | 2.0.6 | Apache-2.0 |
| Apache Commons Lang / IO / Collections | 3.4 / 2.5 / 4.1 | Apache-2.0 |
| SLF4J API and no-op binding | 1.7.21 | MIT |

The Netty QUIC native archives include notices for their bundled native components, including BoringSSL/OpenSSL-derived material and Cloudflare quiche. Shadowing preserves the upstream `META-INF/license/` material and relocates native resource names; it does not remove the native licence notices.

## Rust relay

The relay is statically built from the exact crate versions in `Cargo.lock`. The release workflow publishes `RUST_DEPENDENCIES.md`, a complete name/version/SPDX inventory, and `RUST_DEPENDENCY_NOTICES.txt`, the deduplicated upstream licence and notice texts resolved from those exact crate sources. `scripts/check_rust_licenses.py` fails closed when a package introduces a missing or unreviewed licence expression or lacks a redistributable notice path.

The Rust dependency set uses permissive licence selections including Apache-2.0, MIT, ISC, BSD-2-Clause, BSD-3-Clause, Zlib, Unlicense, CC0-1.0, MIT-0, Unicode-3.0, BSL-1.0, CDLA-Permissive-2.0, and the LLVM exception where applicable. AWS-LC/ring-derived cryptographic components carry their upstream ISC, Apache, MIT, BSD, and related notices in their source distributions.

## External runtime and build inputs

The repository and releases do not redistribute Minecraft, Better Than Adventure!, its client JAR, or the official BTA server archive. The mod downloads the BTA 8.0.1 Babric server from Turnip Labs only after user confirmation and verifies its pinned SHA-256. That archive, Babric Loader, HalpLibe, Mod Menu, Loom, Gradle, and development-only game artifacts remain governed by their respective upstream terms.

The Gradle mod foundation follows the public BTA 8.0 example template, which is CC0-1.0.

## Design references

The following projects informed protocol and integration decisions. No source code from them is incorporated in v0.1. Their licences are recorded so future adaptations cannot lose provenance:

- [e4mc Retro](https://github.com/xhyrom/e4mc-retro) — Apache-2.0.
- [e4mc Minecraft Architectury](https://github.com/vgskye/e4mc-minecraft-architectury) — MIT.
- [BetaLAN StationAPI](https://github.com/telvarost/BetaLAN-StationAPI) — MIT.
- [BTA example mod](https://github.com/Turnip-Labs/bta-example-mod/tree/8.0) — CC0-1.0.

If code is adapted from one of these projects in a later version, its required copyright and licence notice must be added here and retained beside the adapted source.
