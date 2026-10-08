#!/usr/bin/env python3
"""Diagnostic ONLY: isolate every echo connection in its own Python interpreter.

Accepts benchmark_scaling arguments. Process startup stays in the measured wave;
parent Python CPU excludes echo and client child workers. Not the canonical CV
comparison workload. Guest verification/EOF behavior is unchanged.
"""
import hashlib
import json
import multiprocessing
from pathlib import Path
import socket
import sys

import benchmark_relay as b
import benchmark_scaling


def echo_worker(sock):
    try:
        sock.settimeout(30)
        mode = b.read_exact(sock, 1)
        if mode != b'T':
            raise ValueError('diagnostic accepts throughput mode only')
        while chunk := sock.recv(b.CHUNK):
            sock.sendall(chunk)
        sock.shutdown(socket.SHUT_WR)
    finally:
        sock.close()


class ProcessEchoServer(b.EchoServer):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.children = []

    def process_request(self, request, client_address):
        worker = multiprocessing.Process(target=echo_worker, args=(request,), daemon=True)
        worker.start()
        self.children.append(worker)
        request.close()

    def server_close(self):
        super().server_close()
        failures = []
        for worker in self.children:
            worker.join(timeout=2)
            if worker.is_alive():
                worker.terminate()
                worker.join(timeout=2)
                failures.append('unjoined echo child')
            elif worker.exitcode != 0:
                failures.append(f'echo child exit={worker.exitcode}')
        if failures:
            raise RuntimeError(str(failures))


if __name__ == '__main__':
    output = Path(sys.argv[sys.argv.index('--output') + 1])
    metadata = output.with_suffix('.ablation.json')
    if metadata.exists() or output.exists():
        raise SystemExit('use a fresh output path')
    metadata.parent.mkdir(parents=True, exist_ok=True)
    metadata.write_text(json.dumps({'mode': 'process-per-echo-connection',
                                   'cpu_scope': 'parent Python excludes child processes',
                                   'wrapper_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}, indent=2))
    b.EchoServer = ProcessEchoServer
    raise SystemExit(benchmark_scaling.main())
