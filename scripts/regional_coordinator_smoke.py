#!/usr/bin/env python3
"""Disposable Windows coordinator, relay, and Java host functional integration.

This is a correctness test, not a latency, throughput, or soak measurement.
Run it separately from the serial and concurrent relay harnesses: they share ports.
"""

from __future__ import annotations

import argparse
import base64
import collections
import contextlib
import json
import queue
import re
import ssl
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

from cross_language_e2e import (
    EchoHandler,
    ThreadingEchoServer,
    echo_round_trip,
    start_reader,
    stop_process,
)
from encrypted_join_smoke import send_local


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--coordinator-binary", required=True, type=Path)
    parser.add_argument("--relay-binary", required=True, type=Path)
    parser.add_argument("--tunnel-jar", required=True, type=Path)
    parser.add_argument("--java", default="java")
    result = parser.parse_args()
    for path in (result.coordinator_binary, result.relay_binary, result.tunnel_jar):
        if not path.is_file():
            parser.error(f"missing built artifact: {path}")
    return result


def replace_once(source: str, old: str, new: str) -> str:
    if source.count(old) != 1:
        raise AssertionError(f"generated configuration changed: expected one {old!r}")
    return source.replace(old, new)


def wait_for(predicate, label: str, seconds: float = 20) -> None:
    deadline = time.monotonic() + seconds
    last: Exception | None = None
    while time.monotonic() < deadline:
        try:
            if predicate():
                return
        except Exception as error:  # startup and restart may briefly refuse connections
            last = error
        time.sleep(0.1)
    raise TimeoutError(f"timed out waiting for {label}: {last}")


def https_request(
    ca: Path, path: str, token: str | None = None, body: dict | None = None
) -> tuple[int, str]:
    headers = {}
    if token is not None:
        headers["Authorization"] = f"Bearer {token}"
    if body is not None:
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(
        f"https://localhost:30450{path}",
        data=json.dumps(body).encode("utf-8") if body is not None else None,
        headers=headers,
        method="POST" if body is not None else "GET",
    )
    context = ssl.create_default_context(cafile=str(ca))
    try:
        with urllib.request.urlopen(request, context=context, timeout=2) as response:
            return response.status, response.read(64 * 1024).decode("utf-8")
    except urllib.error.HTTPError as error:
        return error.code, error.read(64 * 1024).decode("utf-8")


def metric(body: str, name: str) -> int:
    match = re.search(rf"^{re.escape(name)} (\d+)$", body, re.MULTILINE)
    if match is None:
        raise AssertionError(f"missing metric {name}")
    return int(match.group(1))


def run() -> int:
    args = arguments()
    coordinator_binary = args.coordinator_binary.resolve()
    relay_binary = args.relay_binary.resolve()
    tunnel_jar = args.tunnel_jar.resolve()
    logs: collections.deque[str] = collections.deque(maxlen=300)
    events: queue.Queue[tuple[str, str]] = queue.Queue(maxsize=32)
    processes: list[subprocess.Popen[str]] = []

    with tempfile.TemporaryDirectory(prefix="bta-w4-functional-") as temporary:
        root = Path(temporary)
        coordinator_dir = root / "coordinator"
        created = subprocess.run(
            [str(coordinator_binary), "init-dev", "--dir", str(coordinator_dir)],
            check=True, text=True, capture_output=True, timeout=20,
        )
        key_match = re.search(r"Ticket verification key \(hex\): ([0-9a-f]{64})", created.stdout)
        if key_match is None:
            raise AssertionError("init-dev did not report a 32-byte public ticket key")
        public_key = base64.b64encode(bytes.fromhex(key_match.group(1))).decode("ascii")
        coordinator_config = coordinator_dir / "coordinator.toml"
        content = coordinator_config.read_text(encoding="utf-8")
        content = replace_once(content, "lastManagedPort = 30599", "lastManagedPort = 30500")
        coordinator_config.write_text(content, encoding="utf-8")
        ca = coordinator_dir / "tls-cert.pem"
        host_token = (coordinator_dir / "host.token").read_text(encoding="utf-8").strip()

        relay_dir = root / "relay"
        subprocess.run(
            [str(relay_binary), "init-dev", "--output", str(relay_dir)],
            check=True, text=True, capture_output=True, timeout=20,
        )
        relay_config = relay_dir / "relay.toml"
        content = relay_config.read_text(encoding="utf-8")
        content = replace_once(content, "heartbeat_seconds = 15", "heartbeat_seconds = 2")
        content = replace_once(content, "tcp_port_end = 30100", "tcp_port_end = 30000")
        content += (
            "\n[coordinator]\n"
            'relay_id = "dev-relay-1"\n'
            'url = "https://localhost:30450"\n'
            f'credential_path = {json.dumps((coordinator_dir / "relay.token").as_posix())}\n'
            f'ca_certificate_path = {json.dumps(ca.as_posix())}\n'
            'managed_port_start = 30500\nmanaged_port_end = 30500\n'
            f'signing_keys = {{ "dev-key-1" = {json.dumps(public_key)} }}\n'
        )
        relay_config.write_text(content, encoding="utf-8")

        allowlist = root / "relays.json"
        allowlist.write_text(json.dumps({"relays": [{
            "relayId": "dev-relay-1", "endpoint": "127.0.0.1:25575",
            "trustedCertificate": str(relay_dir / "trust.pem"),
            "accessTokenFile": str(relay_dir / "access.token"),
        }]}), encoding="utf-8")

        echo = ThreadingEchoServer(("127.0.0.1", 0), EchoHandler)
        echo_thread = threading.Thread(target=echo.serve_forever, daemon=True)
        echo_thread.start()
        local = f"127.0.0.1:{echo.server_address[1]}"

        def launch(label: str, command: list[str], console: bool = False) -> subprocess.Popen[str]:
            process = subprocess.Popen(
                command, stdin=subprocess.PIPE if console else subprocess.DEVNULL,
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, bufsize=1,
            )
            processes.append(process)
            assert process.stdout is not None and process.stderr is not None
            start_reader(process.stdout, f"{label}-out", logs, events)
            start_reader(process.stderr, f"{label}-err", logs, events)
            return process

        def start_coordinator() -> subprocess.Popen[str]:
            process = launch("coordinator", [str(coordinator_binary), "serve", "--config", str(coordinator_config)])
            wait_for(lambda: https_request(ca, "/metrics")[0] == 200,
                     "coordinator HTTPS startup")
            return process

        def wait_relay() -> None:
            def ready() -> bool:
                with urllib.request.urlopen("http://127.0.0.1:9090/readyz", timeout=1) as response:
                    return response.status == 200
            wait_for(ready, "relay readiness")

        def start_host(label: str, coordinated: bool, fallback: bool = False,
                       encrypted: bool = False) -> tuple[subprocess.Popen[str], int]:
            command = [args.java, "-jar", str(tunnel_jar),
                       "expose-encrypted" if encrypted else "expose", "--local", local,
                       "--client-id", f"w4-{label}"]
            if coordinated:
                command += ["--coordinator", "https://localhost:30450",
                            "--coordinator-ca", str(ca),
                            "--coordinator-token-file", str(coordinator_dir / "host.token"),
                            "--relay-allowlist", str(allowlist)]
                if fallback:
                    command += ["--fallback-relay", "127.0.0.1:25575",
                                "--fallback-ca", str(relay_dir / "trust.pem"),
                                "--fallback-token-file", str(relay_dir / "access.token")]
            else:
                command += ["--relay", "127.0.0.1:25575", "--ca", str(relay_dir / "trust.pem"),
                            "--token-file", str(relay_dir / "access.token")]
            process = launch(label, command, console=True)
            pattern = re.compile(
                rf"^{re.escape(label)}-out: Public endpoint: (?:\[[^]]+]|[^:]+):(\d+)$"
            )
            port: int | None = None
            def registered() -> bool:
                nonlocal port
                if process.poll() is not None:
                    raise RuntimeError(f"{label} exited before registration ({process.returncode})")
                for line in logs:
                    match = pattern.match(line)
                    if match:
                        port = int(match.group(1))
                        return True
                return False
            wait_for(registered, f"{label} registration", seconds=30)
            assert port is not None
            return process, port

        try:
            coordinator = start_coordinator()
            relay = launch("relay", [str(relay_binary), "run", "--config", str(relay_config)])
            wait_relay()
            managed, managed_port = start_host("managed", coordinated=True)
            if managed_port != 30500:
                raise AssertionError(f"managed registration bound {managed_port}, expected 30500")
            echo_round_trip(managed_port, b"managed-before-restart" * 1024)

            stop_process(coordinator)
            echo_round_trip(managed_port, b"existing-session-during-coordinator-outage" * 512)
            fallback_host, fallback_port = start_host("fallback", coordinated=True, fallback=True)
            if fallback_port != 30000:
                raise AssertionError(f"explicit static fallback bound {fallback_port}, expected 30000")
            echo_round_trip(fallback_port, b"static-fallback" * 1024)
            if not any("Relay selection: static fallback" in line for line in logs):
                raise AssertionError("CLI did not mark the explicit static fallback")

            coordinator = start_coordinator()
            wait_for(lambda: metric(https_request(ca, "/metrics")[1],
                                    "bta_coordinator_active_leases") == 1,
                     "active lease reconciliation")
            allocation = {
                "clientInstanceId": "w4-duplicate", "mode": "legacy",
                "probes": [{"relayId": "dev-relay-1", "rttMillis": 1,
                            "measuredAtEpochMillis": int(time.time() * 1000)}],
            }
            status, _ = https_request(ca, "/v1/allocate", host_token, allocation)
            if status != 503:
                raise AssertionError(f"occupied managed port was allocated again (HTTP {status})")

            stop_process(managed, graceful_stdin=True)
            wait_for(lambda: metric(https_request(ca, "/metrics")[1],
                                    "bta_coordinator_active_leases") == 0,
                     "managed listener release", seconds=25)
            new_host, new_port = start_host("managed-again", coordinated=True)
            if new_port != 30500:
                raise AssertionError(f"released managed port was not reused: {new_port}")
            echo_round_trip(new_port, b"managed-after-release" * 1024)
            echo_round_trip(fallback_port, b"static-still-working" * 1024)
            stop_process(new_host, graceful_stdin=True)
            wait_for(lambda: metric(https_request(ca, "/metrics")[1],
                                    "bta_coordinator_active_leases") == 0,
                     "second managed listener release")

            encrypted_host, encrypted_port = start_host(
                "encrypted", coordinated=True, encrypted=True
            )
            if encrypted_port != 30500:
                raise AssertionError(f"encrypted managed port was {encrypted_port}, expected 30500")
            invite_pattern = re.compile(
                r"^encrypted-out: Encrypted invitation .*: (BTAE1:[A-Za-z0-9_-]+)$"
            )
            invitation: str | None = None
            def have_invitation() -> bool:
                nonlocal invitation
                for line in logs:
                    match = invite_pattern.match(line)
                    if match:
                        invitation = match.group(1)
                        return True
                return False
            wait_for(have_invitation, "managed encrypted invitation")
            assert invitation is not None
            guest = launch("encrypted-guest", [args.java, "-jar", str(tunnel_jar),
                                               "join-encrypted"], console=True)
            assert guest.stdin is not None
            guest.stdin.write(invitation + "\n")
            guest.stdin.flush()
            guest_pattern = re.compile(
                r"^encrypted-guest-out: Connect the BTA 8\.0\.1 client to [^:]+:(\d+)$"
            )
            guest_port: int | None = None
            def guest_ready() -> bool:
                nonlocal guest_port
                if guest.poll() is not None:
                    raise RuntimeError("encrypted companion exited before opening its loopback listener")
                for line in logs:
                    match = guest_pattern.match(line)
                    if match:
                        guest_port = int(match.group(1))
                        return True
                return False
            wait_for(guest_ready, "managed encrypted guest companion")
            assert guest_port is not None
            payload = b"\x02managed-encrypted-byte-exact" * 256
            if send_local(guest_port, payload) != payload:
                raise AssertionError("managed encrypted guest traffic was not byte exact")
            stop_process(guest, graceful_stdin=True)
            stop_process(encrypted_host, graceful_stdin=True)
            stop_process(fallback_host, graceful_stdin=True)
            stop_process(relay)
            stop_process(coordinator)
            print("PASS: exact managed bind, legacy and encrypted byte-exact traffic, outage, explicit static fallback, restart reconciliation, no duplicate lease, release/reuse, legacy static coexistence")
            return 0
        except BaseException:
            print("Recent process output (secrets and tickets omitted):")
            for line in logs:
                if "BTACT1:" not in line and "token" not in line.lower() and "invitation" not in line.lower():
                    print(line)
            raise
        finally:
            for process in reversed(processes):
                stop_process(process, graceful_stdin=process.stdin is not None)
            echo.shutdown()
            echo.server_close()


if __name__ == "__main__":
    raise SystemExit(run())
