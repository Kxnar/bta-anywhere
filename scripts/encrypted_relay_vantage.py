#!/usr/bin/env python3
"""Bounded relay-operator-vantage check for encrypted guest forwarding.

The disposable proxy terminates outer guest TLS with the relay's own temporary
development key, then opens a second TLS connection to the actual relay. Bytes
observed between those TLS layers are retained only in memory and checked for
known game markers and invitation bearer credentials.
"""

from __future__ import annotations

import argparse
import base64
import contextlib
import json
import queue
import select
import socket
import socketserver
import ssl
import subprocess
import threading
import time
from pathlib import Path

from encrypted_join_smoke import LocalHandler, LocalServer, free_port, read_lines, send_local, stop, wait_event


MAX_CAPTURE_BYTES = 2 * 1024 * 1024


class VantageProxy:
    def __init__(self, listen_port: int, upstream_port: int, cert: Path, key: Path, trust: Path):
        self.captured = bytearray()
        self.capture_lock = threading.Lock()
        self.capture_overflow = False
        self.errors: list[str] = []
        self.error_lock = threading.Lock()
        server_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        server_context.minimum_version = ssl.TLSVersion.TLSv1_3
        server_context.set_alpn_protocols(["bta-anywhere-relay/1"])
        server_context.load_cert_chain(str(cert), str(key))
        upstream_context = ssl.create_default_context(ssl.Purpose.SERVER_AUTH, cafile=str(trust))
        upstream_context.minimum_version = ssl.TLSVersion.TLSv1_3
        upstream_context.set_alpn_protocols(["bta-anywhere-relay/1"])
        owner = self

        class Handler(socketserver.BaseRequestHandler):
            def handle(self) -> None:
                client = None
                upstream = None
                try:
                    client = server_context.wrap_socket(self.request, server_side=True)
                    if client.selected_alpn_protocol() != "bta-anywhere-relay/1":
                        raise AssertionError("guest TLS selected unexpected ALPN")
                    raw_upstream = socket.create_connection(("127.0.0.2", upstream_port), timeout=8)
                    upstream = upstream_context.wrap_socket(raw_upstream, server_hostname="localhost")
                    if upstream.selected_alpn_protocol() != "bta-anywhere-relay/1":
                        raise AssertionError("upstream TLS selected unexpected ALPN")
                    client.settimeout(12)
                    upstream.settimeout(12)
                    while True:
                        ready, _, _ = select.select([client, upstream], [], [], 12)
                        if not ready:
                            break
                        for source in ready:
                            block = source.recv(16384)
                            if not block:
                                destination = upstream if source is client else client
                                with contextlib.suppress(OSError, ssl.SSLError):
                                    destination.shutdown(socket.SHUT_WR)
                                return
                            if source is client:
                                with owner.capture_lock:
                                    remaining = MAX_CAPTURE_BYTES - len(owner.captured)
                                    if len(block) > remaining:
                                        owner.capture_overflow = True
                                        owner.captured.extend(block[:remaining])
                                    else:
                                        owner.captured.extend(block)
                                upstream.sendall(block)
                            else:
                                client.sendall(block)
                except (OSError, ssl.SSLError, AssertionError) as error:
                    with owner.error_lock:
                        owner.errors.append(type(error).__name__)
                finally:
                    for connection in (upstream, client):
                        if connection is not None:
                            with contextlib.suppress(OSError):
                                connection.close()

        class Server(socketserver.ThreadingTCPServer):
            allow_reuse_address = True
            daemon_threads = True

        self.server = Server(("127.0.0.1", listen_port), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def start(self) -> None:
        self.thread.start()

    def close(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=3)


def collect_output(process: subprocess.Popen[str], output: list[str]) -> None:
    assert process.stdout is not None and process.stderr is not None
    def read(stream: object) -> None:
        for line in iter(stream.readline, ""):
            output.append(line.rstrip("\r\n"))
    for stream in (process.stdout, process.stderr):
        threading.Thread(target=read, args=(stream,), daemon=True).start()


def invitation_secrets(encoded: str) -> tuple[bytes, bytes]:
    payload = encoded.split(":", 1)[1]
    document = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
    return document["joinCapability"].encode("ascii"), document["statusCapability"].encode("ascii")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--relay-binary", type=Path, required=True)
    parser.add_argument("--tunnel-jar", type=Path, required=True)
    parser.add_argument("--java", default="java")
    args = parser.parse_args()
    for path in (args.relay_binary, args.tunnel_jar):
        if not path.is_file():
            raise FileNotFoundError(path)

    events: queue.Queue[tuple[str, str]] = queue.Queue()
    relay = host = guest = None
    proxy = None
    relay_lines: list[str] = []
    local = LocalServer(("127.0.0.1", 0), LocalHandler)
    threading.Thread(target=local.serve_forever, daemon=True).start()
    try:
        with __import__("tempfile").TemporaryDirectory(prefix="bta-relay-vantage-") as temporary:
            directory = Path(temporary)
            development = directory / "relay"
            subprocess.run([str(args.relay_binary), "init-dev", "--output", str(development)],
                           check=True, capture_output=True, text=True, timeout=20)
            config_path = development / "relay.toml"
            config = config_path.read_text(encoding="utf-8")
            quic_port, guest_port, admin_port = free_port(socket.SOCK_DGRAM), free_port(), free_port()
            config = config.replace('quic_listen = "0.0.0.0:25575"',
                                    f'quic_listen = "127.0.0.1:{quic_port}"')
            config = config.replace('tcp_bind_ip = "0.0.0.0"', 'tcp_bind_ip = "127.0.0.2"')
            config = config.replace('tcp_port_start = 30000', f'tcp_port_start = {guest_port}')
            config = config.replace('tcp_port_end = 30100', f'tcp_port_end = {guest_port}')
            config = config.replace('admin_listen = "127.0.0.1:9090"',
                                    f'admin_listen = "127.0.0.1:{admin_port}"')
            config_path.write_text(config, encoding="utf-8")
            relay = subprocess.Popen([str(args.relay_binary), "run", "--config", str(config_path)],
                                     stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                     stdin=subprocess.DEVNULL, text=True, bufsize=1)
            collect_output(relay, relay_lines)
            proxy = VantageProxy(guest_port, guest_port, development / "server.pem",
                                 development / "server-key.pem", development / "trust.pem")
            proxy.start()
            deadline = time.monotonic() + 15
            while time.monotonic() < deadline:
                try:
                    with socket.create_connection(("127.0.0.1", admin_port), timeout=0.3):
                        break
                except OSError:
                    if relay.poll() is not None:
                        raise RuntimeError("disposable relay exited during startup")
                    time.sleep(0.1)
            else:
                raise TimeoutError("disposable relay did not bind its admin socket")

            host = subprocess.Popen([args.java, "-jar", str(args.tunnel_jar), "expose-encrypted",
                                     "--relay", f"127.0.0.1:{quic_port}", "--ca",
                                     str(development / "trust.pem"), "--token-file",
                                     str(development / "access.token"), "--local",
                                     f"127.0.0.1:{local.server_address[1]}"],
                                    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                    text=True, bufsize=1)
            read_lines(host, "vantage-host", events, [])
            encoded = "BTAE1:" + wait_event(events, "invitation", 25)
            wait_event(events, "invitation-id", 25)
            secrets = invitation_secrets(encoded) + (encoded.encode("ascii"),)
            guest = subprocess.Popen([args.java, "-jar", str(args.tunnel_jar), "join-encrypted"],
                                     stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                     text=True, bufsize=1)
            read_lines(guest, "vantage-guest", events, [])
            assert guest.stdin is not None
            guest.stdin.write(encoded + "\n")
            guest.stdin.flush()
            address = wait_event(events, "guest-address", 25)
            local_port = int(address.rsplit(":", 1)[1])

            marker = b"bta-relay-vantage-known-game-marker"
            game_bytes = b"\x02" + "Minecraft".encode("utf-16-be") + marker
            if send_local(local_port, game_bytes) != game_bytes:
                raise AssertionError("synthetic encrypted guest join did not echo exact bytes")
            stop(guest)
            guest = None
            time.sleep(0.3)

            observed = bytes(proxy.captured)
            if proxy.capture_overflow:
                raise AssertionError("bounded relay-vantage capture reached its limit")
            if len(observed) < 128:
                raise AssertionError("relay-vantage proxy did not observe forwarded TLS plaintext")
            markers = (b"Minecraft", "Minecraft".encode("utf-16-be"), marker, b"BTAPingHost")
            for needle in markers + secrets:
                if needle and needle in observed:
                    raise AssertionError("relay-vantage capture exposed a game marker or invite capability")
            log_bytes = "\n".join(relay_lines).encode("utf-8", errors="replace")
            for needle in (marker, b"Minecraft", b"BTAPingHost", b"BTAE1:") + secrets:
                if needle and needle in log_bytes:
                    raise AssertionError("relay logs exposed game data or an invitation secret")
            print("PASS: disposable TLS-terminating relay-vantage proxy forwarded encrypted guest bytes")
            print(f"PASS: {len(observed)} captured bytes; known game markers and both invite capabilities absent")
            print("PASS: disposable relay stdout/stderr contained no tested game markers or invite secrets")
            print("LIMIT: loopback dev-certificate synthetic game traffic only; metadata, timing, sizes, and denial remain visible")
        return 0
    finally:
        stop(guest)
        if proxy is not None:
            proxy.close()
        stop(host)
        stop(relay)
        local.shutdown()
        local.server_close()


if __name__ == "__main__":
    raise SystemExit(main())
