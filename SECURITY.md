# Security policy

Security fixes are accepted for the current `main` branch and the latest GitHub release. v0.1 is an early self-hosted release and does not include a project-operated relay.

Report vulnerabilities through GitHub private vulnerability reporting for `Kxnar/bta-anywhere` when available. Otherwise email `k.narayaka@gmail.com` with the subject `BTA Anywhere security report`.

Include the affected version, component, prerequisites, impact, and a minimal reproduction. Do not include live access tokens, private keys, private CA keys, saves, player data, or unredacted logs. Allow time for acknowledgement and coordinated remediation before public disclosure.

The following are documented limitations rather than vulnerabilities by themselves:

- guest-to-relay BTA traffic is not generally encrypted and the relay is trusted with game traffic;
- a self-hosted relay exposes public TCP ports and can be denial-of-service targeted;
- online mode and the whitelist, not the relay token, authenticate guests;
- v0.1 has no public relay, broker, hole punching, DDoS service, or guest companion encryption layer.
