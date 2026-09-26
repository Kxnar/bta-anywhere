#!/usr/bin/env python3
"""Compare live BTA 8.0.1 status replies directly and through the encrypted join path."""

from __future__ import annotations

import argparse
import http.client
import queue
import socket
import subprocess
import tempfile
import time
from pathlib import Path

from encrypted_join_smoke import free_port, read_lines, send_local, status_probe, stop, wait_event


def direct_status(port: int, icon: bool) -> bytes:
    with socket.create_connection(("127.0.0.1", port), timeout=5) as connection:
        connection.settimeout(5)
        connection.sendall(status_probe(icon, port))
        response = bytearray()
        while True:
            chunk = connection.recv(4096)
            if not chunk:
                return bytes(response)
            response.extend(chunk)
            if len(response) > 64 * 1024:
                raise AssertionError("BTA status response exceeds 64 KiB")


def handshake_prefix(port: int) -> bytes:
    username = b"W3Probe"
    request = b"\x02" + len(username).to_bytes(2, "big") + username
    with socket.create_connection(("127.0.0.1", port), timeout=5) as connection:
        connection.settimeout(5)
        connection.sendall(request)
        prefix = bytearray()
        while len(prefix) < 4:
            chunk = connection.recv(4 - len(prefix))
            if not chunk:
                break
            prefix.extend(chunk)
        return bytes(prefix)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--relay-binary", type=Path, required=True)
    parser.add_argument("--tunnel-jar", type=Path, required=True)
    parser.add_argument("--local-port", type=int, required=True,
                        help="loopback port of a disposable running BTA 8.0.1 server")
    parser.add_argument("--java", default="java")
    args = parser.parse_args()
    if not 1 <= args.local_port <= 65535:
        parser.error("--local-port must be 1-65535")

    diagnostics: list[str] = []
    events: queue.Queue[tuple[str, str]] = queue.Queue()
    relay = host = guest = None
    with tempfile.TemporaryDirectory(prefix="bta-live-status-") as temporary:
        development = Path(temporary) / "relay"
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
                    if connection.getresponse().status == 200:
                        break
                except OSError:
                    time.sleep(0.1)
            else:
                raise TimeoutError("relay did not become ready")

            host = subprocess.Popen([args.java, "-jar", str(args.tunnel_jar), "expose-encrypted",
                                     "--relay", f"127.0.0.1:{quic}", "--ca", str(development / "trust.pem"),
                                     "--token-file", str(development / "access.token"),
                                     "--local", f"127.0.0.1:{args.local_port}"],
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
            companion_port = int(wait_event(events, "guest-address").rsplit(":", 1)[1])
            for icon in (False, True):
                direct = direct_status(args.local_port, icon)
                encrypted = send_local(companion_port, status_probe(icon, companion_port))
                if not direct or direct[0] != 0xFF or encrypted != direct:
                    raise AssertionError(f"BTA status mismatch for icon={icon}: "
                                         f"direct={len(direct)} bytes, encrypted={len(encrypted)} bytes, "
                                         f"direct_prefix={direct[:4].hex()}, encrypted_prefix={encrypted[:4].hex()}")
            direct_handshake = handshake_prefix(args.local_port)
            encrypted_handshake = handshake_prefix(companion_port)
            if len(direct_handshake) != 4 or encrypted_handshake != direct_handshake:
                raise AssertionError("BTA handshake did not reach the real server through encrypted join")
            print("Live BTA 8.0.1 server checks passed through the encrypted companion: basic/icon status match direct bytes and the first handshake response matches.")
            return 0
        except BaseException:
            print("\n".join(diagnostics[-80:]))
            raise
        finally:
            stop(guest)
            stop(host)
            stop(relay)


if __name__ == "__main__":
    raise SystemExit(main())
