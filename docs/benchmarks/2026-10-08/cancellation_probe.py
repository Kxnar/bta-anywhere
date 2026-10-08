"""Run from the repository root. An intentional FAILED result verifies cleanup."""
from pathlib import Path
import sys

sys.path.insert(0, str(Path.cwd() / 'scripts'))
import benchmark_relay as benchmark


def cancel_after_transfer(rig, profile, seed, result):
    result['injected_fault'] = 'KeyboardInterrupt after one byte-exact half-close transaction'
    result['current_case'] = {'phase': 'intentional_cancellation'}
    result['cancellation_probe_transaction_ms'] = benchmark.transfer(
        rig.public_port, 'half_close', benchmark.payload(seed, 65536, 700), 10)
    raise KeyboardInterrupt('intentional cancellation cleanup probe')


benchmark.run_latency = cancel_after_transfer
raise SystemExit(benchmark.main())
