#!/usr/bin/env python3
"""Short Windows loopback join/security smoke; no performance measurements or game saves."""

from __future__ import annotations

import argparse
import base64
import contextlib
import http.client
import json
import queue
import socket
import socketserver
import subprocess
import tempfile
import threading
import time
from pathlib import Path


class LocalHandler(socketserver.BaseRequestHandler):
    def handle(self) -> None:
        with self.server.connection_lock:
            self.server.connections += 1
        self.request.settimeout(12)
        first = self.request.recv(1)
        if first == b"\xfe":
            self.request.recv(512)
            self.request.sendall(b"synthetic-status")
            self.request.shutdown(socket.SHUT_WR)
            return
        data = bytearray(first)
        while True:
            chunk = self.request.recv(16384)
            if not chunk:
                break
            data.extend(chunk)
            if len(data) > 65536:
                raise AssertionError("synthetic join exceeded its byte limit")
        self.request.sendall(data)
        self.request.shutdown(socket.SHUT_WR)


class LocalServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, *args: object, **kwargs: object) -> None:
        super().__init__(*args, **kwargs)
        self.connection_lock = threading.Lock()
        self.connections = 0

    def count(self) -> int:
        with self.connection_lock:
            return self.connections


def free_port(kind: int = socket.SOCK_STREAM) -> int:
    with socket.socket(socket.AF_INET, kind) as probe:
        probe.bind(("127.0.0.1", 0))
        return int(probe.getsockname()[1])


def read_lines(process: subprocess.Popen[str], label: str,
               events: queue.Queue[tuple[str, str]], diagnostics: list[str]) -> None:
    assert process.stdout is not None and process.stderr is not None

    def consume(stream: object, suffix: str) -> None:
        for raw in iter(stream.readline, ""):
            line = raw.rstrip("\r\n")
            if "invitation" in line.lower() and "BTAE1:" in line:
                events.put(("invitation", line.split("BTAE1:", 1)[1]))
                diagnostics.append(f"{label}-{suffix}: [invitation redacted]")
            elif line.startswith("Invitation ID:"):
                events.put(("invitation-id", line.split(":", 1)[1].strip()))
            elif line == "Invitation revoked":
                events.put(("revoked", line))
            elif line.startswith("Connect the BTA 8.0.1 client to "):
                events.put(("guest-address", line.rsplit(" ", 1)[1]))
            else:
                diagnostics.append(f"{label}-{suffix}: {line}")

    threading.Thread(target=consume, args=(process.stdout, "out"), daemon=True).start()
    threading.Thread(target=consume, args=(process.stderr, "err"), daemon=True).start()


def wait_event(events: queue.Queue[tuple[str, str]], kind: str, timeout: float = 25) -> str:
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        remaining = end - time.monotonic()
        observed_kind, value = events.get(timeout=max(0.01, remaining))
        if observed_kind == kind:
            return value
    raise TimeoutError(f"timed out waiting for {kind}")


def stop(process: subprocess.Popen[str] | None) -> None:
    if process is None or process.poll() is not None:
        return
    if process.stdin is not None:
        with contextlib.suppress(OSError):
            process.stdin.write("stop\n")
            process.stdin.flush()
    try:
        process.wait(timeout=4)
    except subprocess.TimeoutExpired:
        process.terminate()
        try:
            process.wait(timeout=4)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=4)


def send_local(port: int, payload: bytes) -> bytes:
    with socket.create_connection(("127.0.0.1", port), timeout=15) as guest:
        guest.settimeout(15)
        guest.sendall(payload)
        guest.shutdown(socket.SHUT_WR)
        received = bytearray()
        while True:
            chunk = guest.recv(16384)
            if not chunk:
                return bytes(received)
            received.extend(chunk)


def status_probe(icon: bool, port: int) -> bytes:
    magic = "BTAPingHost".encode("utf-16-be")
    host = "127.0.0.1".encode("utf-16-be")
    return (bytes((0xFE, 3 if icon else 2, 250)) + (len(magic) // 2).to_bytes(2, "big")
            + magic + (3 + len(magic) + 4).to_bytes(2, "big") + bytes((1,))
            + (len(host) // 2).to_bytes(2, "big") + host + port.to_bytes(4, "big"))


def replace_invitation_field(encoded: str, field: str, value: object) -> str:
    payload = encoded.split(":", 1)[1]
    document = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
    document[field] = value
    canonical = json.dumps(document, separators=(",", ":")).encode("utf-8")
    return "BTAE1:" + base64.urlsafe_b64encode(canonical).decode("ascii").rstrip("=")


def denied(port: int) -> None:
    try:
        response = send_local(port, b"\x02denied-attempt")
    except OSError:
        return
    if response:
        raise AssertionError("rejected invitation reached the local service")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--relay-binary", type=Path, required=True)
    parser.add_argument("--tunnel-jar", type=Path, required=True)
    parser.add_argument("--java", default="java")
    args = parser.parse_args()
    diagnostics: list[str] = []
    events: queue.Queue[tuple[str, str]] = queue.Queue()
    relay = host = guest = None
    local = LocalServer(("127.0.0.1", 0), LocalHandler)
    threading.Thread(target=local.serve_forever, daemon=True).start()
    with tempfile.TemporaryDirectory(prefix="bta-encrypted-smoke-") as temporary:
        directory = Path(temporary)
        development = directory / "relay"
        subprocess.run([str(args.relay_binary), "init-dev", "--output", str(development)],
                       check=True, capture_output=True, text=True, timeout=20)
        config_path = development / "relay.toml"
        config = config_path.read_text(encoding="utf-8")
        quic = free_port(socket.SOCK_DGRAM)
        public = free_port()
        admin = free_port()
        config = config.replace("25575", str(quic)).replace("30000", str(public))
        config = config.replace("30100", str(public)).replace("9090", str(admin))
        config_path.write_text(config, encoding="utf-8")
        try:
            relay = subprocess.Popen([str(args.relay_binary), "run", "--config", str(config_path)],
                                     stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                     stderr=subprocess.PIPE, text=True, bufsize=1)
            read_lines(relay, "relay", events, diagnostics)
            deadline = time.monotonic() + 15
            while time.monotonic() < deadline:
                try:
                    connection = http.client.HTTPConnection("127.0.0.1", admin, timeout=1)
                    connection.request("GET", "/readyz")
                    response = connection.getresponse()
                    if response.status == 200:
                        break
                except OSError:
                    time.sleep(0.1)
            else:
                raise TimeoutError("relay did not become ready")
            host = subprocess.Popen([args.java, "-jar", str(args.tunnel_jar), "expose-encrypted",
                                     "--relay", f"127.0.0.1:{quic}", "--ca", str(development / "trust.pem"),
                                     "--token-file", str(development / "access.token"),
                                     "--local", f"127.0.0.1:{local.server_address[1]}"],
                                    stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                    stderr=subprocess.PIPE, text=True, bufsize=1)
            read_lines(host, "host", events, diagnostics)
            encoded = "BTAE1:" + wait_event(events, "invitation")
            wait_event(events, "invitation-id")
            guest = subprocess.Popen([args.java, "-jar", str(args.tunnel_jar), "join-encrypted"],
                                     stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.PIPE, text=True, bufsize=1)
            read_lines(guest, "guest", events, diagnostics)
            assert guest.stdin is not None
            guest.stdin.write(encoded + "\n")
            guest.stdin.flush()
            address = wait_event(events, "guest-address")
            guest_port = int(address.rsplit(":", 1)[1])
            for icon in (False, True):
                status = send_local(guest_port, status_probe(icon, guest_port))
                if status != b"synthetic-status":
                    raise AssertionError("encrypted status probe failed")
            for _ in range(8):
                if send_local(guest_port, status_probe(False, guest_port)) != b"synthetic-status":
                    raise AssertionError("repeat status probe failed")
            admitted_status = local.count()
            try:
                excess_status = send_local(guest_port, status_probe(False, guest_port))
            except OSError:
                excess_status = b""
            if excess_status or local.count() != admitted_status:
                raise AssertionError("status rate limit opened a local server socket")
            for malformed in (b"\x03unknown", b"\xfe\x02"):
                try:
                    malformed_response = send_local(guest_port, malformed)
                except OSError:
                    malformed_response = b""
                if malformed_response or local.count() != admitted_status:
                    raise AssertionError("malformed guest preface reached the local service")
            payload = b"\x02" + bytes((index * 17) % 256 for index in range(45_075))
            result = send_local(guest_port, payload)
            if result != payload:
                raise AssertionError(f"encrypted join byte mismatch: {len(result)} received")
            admitted = local.count()
            replay = send_local(guest_port, payload)
            if replay:
                raise AssertionError("replayed invitation unexpectedly reached the local service")
            if local.count() != admitted:
                raise AssertionError("replay opened a local server socket")
            with socket.create_connection(("127.0.0.1", public), timeout=3) as plaintext:
                plaintext.settimeout(3)
                plaintext.sendall(b"\x02plaintext-must-not-be-forwarded")
                with contextlib.suppress(OSError):
                    plaintext.recv(32)
            if local.count() != admitted:
                raise AssertionError("plaintext guest opened a local server socket")
            stop(guest)
            assert host.stdin is not None
            host.stdin.write("invite\n")
            host.stdin.flush()
            security_invite = "BTAE1:" + wait_event(events, "invitation")
            wait_event(events, "invitation-id")
            guest = subprocess.Popen([args.java, "-jar", str(args.tunnel_jar), "join-encrypted"],
                                     stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.PIPE, text=True, bufsize=1)
            read_lines(guest, "wrong-pin-guest", events, diagnostics)
            assert guest.stdin is not None
            guest.stdin.write(replace_invitation_field(security_invite, "hostSpkiSha256", "0" * 64) + "\n")
            guest.stdin.flush()
            wrong_pin_port = int(wait_event(events, "guest-address").rsplit(":", 1)[1])
            denied(wrong_pin_port)
            if local.count() != admitted:
                raise AssertionError("wrong-pin guest opened a local server socket")
            stop(guest)
            guest = subprocess.Popen([args.java, "-jar", str(args.tunnel_jar), "join-encrypted"],
                                     stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.PIPE, text=True, bufsize=1)
            read_lines(guest, "wrong-relay-pin-guest", events, diagnostics)
            assert guest.stdin is not None
            guest.stdin.write(replace_invitation_field(security_invite, "relaySpkiSha256", "0" * 64) + "\n")
            guest.stdin.flush()
            wrong_relay_port = int(wait_event(events, "guest-address").rsplit(":", 1)[1])
            denied(wrong_relay_port)
            if local.count() != admitted:
                raise AssertionError("wrong-relay-pin guest opened a local server socket")
            stop(guest)

            for label, field, value in (
                ("tampered-capability", "joinCapability", "A" * 43),
                ("wrong-session", "hostSessionId", "A" * 43),
                ("expired", "expiresAtEpochMillis", int(time.time() * 1000) - 1000),
            ):
                guest = subprocess.Popen([args.java, "-jar", str(args.tunnel_jar), "join-encrypted"],
                                         stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                         stderr=subprocess.PIPE, text=True, bufsize=1)
                read_lines(guest, label, events, diagnostics)
                assert guest.stdin is not None
                guest.stdin.write(replace_invitation_field(security_invite, field, value) + "\n")
                guest.stdin.flush()
                bad_port = int(wait_event(events, "guest-address").rsplit(":", 1)[1])
                denied(bad_port)
                if local.count() != admitted:
                    raise AssertionError(f"{label} opened a local server socket")
                stop(guest)

            # These failures must not spend the unmodified invitation.
            guest = subprocess.Popen([args.java, "-jar", str(args.tunnel_jar), "join-encrypted"],
                                     stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.PIPE, text=True, bufsize=1)
            read_lines(guest, "valid-after-rejections", events, diagnostics)
            assert guest.stdin is not None
            guest.stdin.write(security_invite + "\n")
            guest.stdin.flush()
            valid_port = int(wait_event(events, "guest-address").rsplit(":", 1)[1])
            if send_local(valid_port, b"\x02valid-after-rejections") != b"\x02valid-after-rejections":
                raise AssertionError("rejected invitations spent the valid capability")
            admitted = local.count()
            stop(guest)

            host.stdin.write("invite\n")
            host.stdin.flush()
            revoked_invite = "BTAE1:" + wait_event(events, "invitation")
            revoked_id = wait_event(events, "invitation-id")
            host.stdin.write("revoke " + revoked_id + "\n")
            host.stdin.flush()
            wait_event(events, "revoked")
            guest = subprocess.Popen([args.java, "-jar", str(args.tunnel_jar), "join-encrypted"],
                                     stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.PIPE, text=True, bufsize=1)
            read_lines(guest, "revoked-guest", events, diagnostics)
            assert guest.stdin is not None
            guest.stdin.write(revoked_invite + "\n")
            guest.stdin.flush()
            revoked_port = int(wait_event(events, "guest-address").rsplit(":", 1)[1])
            denied(revoked_port)
            if local.count() != admitted:
                raise AssertionError("revoked guest opened a local server socket")
            stop(guest)

            host.stdin.write("invite\n")
            host.stdin.flush()
            active_invite = "BTAE1:" + wait_event(events, "invitation")
            wait_event(events, "invitation-id")
            guest = subprocess.Popen([args.java, "-jar", str(args.tunnel_jar), "join-encrypted"],
                                     stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.PIPE, text=True, bufsize=1)
            read_lines(guest, "active-guest", events, diagnostics)
            assert guest.stdin is not None
            guest.stdin.write(active_invite + "\n")
            guest.stdin.flush()
            active_port = int(wait_event(events, "guest-address").rsplit(":", 1)[1])
            with socket.create_connection(("127.0.0.1", active_port), timeout=5) as active:
                active.sendall(b"\x02held-open")
                deadline = time.monotonic() + 5
                while local.count() == admitted and time.monotonic() < deadline:
                    time.sleep(0.05)
                if local.count() == admitted:
                    raise AssertionError("active join did not reach the local server")
                guest.stdin.write("stop\n")
                guest.stdin.flush()
                guest.wait(timeout=4)

            host.stdin.write("invite\n")
            host.stdin.flush()
            disconnect_invite = "BTAE1:" + wait_event(events, "invitation")
            wait_event(events, "invitation-id")
            guest = subprocess.Popen([args.java, "-jar", str(args.tunnel_jar), "join-encrypted"],
                                     stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.PIPE, text=True, bufsize=1)
            read_lines(guest, "host-disconnect-guest", events, diagnostics)
            assert guest.stdin is not None
            guest.stdin.write(disconnect_invite + "\n")
            guest.stdin.flush()
            disconnect_port = int(wait_event(events, "guest-address").rsplit(":", 1)[1])
            before_disconnect = local.count()
            with socket.create_connection(("127.0.0.1", disconnect_port), timeout=5) as active:
                active.settimeout(6)
                active.sendall(b"\x02held-open-host-stop")
                deadline = time.monotonic() + 5
                while local.count() == before_disconnect and time.monotonic() < deadline:
                    time.sleep(0.05)
                if local.count() == before_disconnect:
                    raise AssertionError("host disconnect case did not reach the local server")
                stop(host)
                try:
                    after_stop = active.recv(1)
                except (ConnectionResetError, ConnectionAbortedError):
                    after_stop = b""
                if after_stop:
                    raise AssertionError("guest socket remained usable after host stop")
            stop(guest)
            stop(host)
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                try:
                    with socket.create_connection(("127.0.0.1", public), timeout=0.25):
                        time.sleep(0.05)
                except OSError:
                    break
            else:
                raise AssertionError("host stop left the encrypted public listener open")
            print("Encrypted join smoke passed: status, half-close, replay, pin/capability/session/expiry/revocation/plaintext rejection, admission before local sockets, guest shutdown, active host disconnect and listener cleanup.")
            return 0
        except BaseException:
            print("\n".join(diagnostics[-80:]))
            raise
        finally:
            stop(guest)
            stop(host)
            stop(relay)
            local.shutdown()
            local.server_close()


if __name__ == "__main__":
    raise SystemExit(main())
