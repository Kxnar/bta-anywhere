# Loopback fault supplement

Status: **FAILED**

Existing-stream interruption is an observed outcome; fresh recovery requires exact bytes and EOF.

| Case | Status | Existing stream survived | New stream bytes and EOF |
|---|---|---|---|
| eight_slow_receivers | PASS | n/a | n/a |
| tunnel_process_termination | FAILED | False | True |
| udp_drop_55_seconds | PASS | False | True |
