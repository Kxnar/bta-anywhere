"""Bounded loopback UDP impairment bridge. This is not a tc netem substitute.

Delay is per datagram, per direction; independent uniform jitter can reorder
packets. A seeded RNG reproduces decisions for an identical packet arrival order,
not the nondeterministic QUIC traffic of a whole run.
"""
import collections
import heapq
import random
import select
import socket
import threading
import time


class ImpairmentBridge:
    def __init__(self, relay_port, delay_ms=0, jitter_ms=0, loss=0, seed=1701,
                 max_packets=8192, max_bytes=8 * 1024 * 1024):
        if not 0 <= jitter_ms <= delay_ms <= 500 or not 0 <= loss <= 1:
            raise ValueError('invalid impairment')
        if max_packets < 1 or max_bytes < 65535:
            raise ValueError('invalid queue bounds')
        self.relay = ('127.0.0.1', relay_port)
        self.socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.socket.bind(('127.0.0.1', 0))
        self.socket.setblocking(False)
        self.port = self.socket.getsockname()[1]
        self.peer = None
        self.delay_ms, self.jitter_ms, self.loss = delay_ms, jitter_ms, loss
        self.rng = random.Random(seed)
        self.max_packets, self.max_bytes = max_packets, max_bytes
        self.queue = []
        self.queued_bytes = 0
        self.sequence = 0
        self.drop_until = 0
        self.outage_generation = 0
        self.counters = collections.Counter()
        self.delay_samples_ms = collections.deque(maxlen=4096)
        self.stop = threading.Event()
        self.error = None
        self.thread = threading.Thread(target=self._run, name='impairment-bridge', daemon=True)
        self.thread.start()

    def _enqueue(self, data, destination, now):
        self.counters['received'] += 1
        if now < self.drop_until:
            self.counters['outage_dropped'] += 1
        elif self.rng.random() < self.loss:
            self.counters['random_dropped'] += 1
        elif len(self.queue) >= self.max_packets or self.queued_bytes + len(data) > self.max_bytes:
            self.counters['overflow_dropped'] += 1
        else:
            delay = (self.delay_ms + self.rng.uniform(-self.jitter_ms, self.jitter_ms)) / 1000
            self.sequence += 1
            heapq.heappush(self.queue, (now + delay, self.sequence, now, data, destination))
            self.queued_bytes += len(data)
            self.counters['peak_queue_bytes'] = max(self.counters['peak_queue_bytes'], self.queued_bytes)
            self.counters['peak_queue_packets'] = max(self.counters['peak_queue_packets'], len(self.queue))

    def _run(self):
        try:
            processed_outage = 0
            while not self.stop.is_set():
                if processed_outage != self.outage_generation:
                    processed_outage = self.outage_generation
                    self.counters['outage_dropped'] += len(self.queue)
                    self.queue.clear()
                    self.queued_bytes = 0
                now = time.monotonic()
                while self.queue and self.queue[0][0] <= now:
                    _, _, entered, data, destination = heapq.heappop(self.queue)
                    self.queued_bytes -= len(data)
                    if now < self.drop_until:
                        self.counters['outage_dropped'] += 1
                        continue
                    try:
                        self.socket.sendto(data, destination)
                        self.counters['forwarded'] += 1
                        self.delay_samples_ms.append((time.monotonic() - entered) * 1000)
                    except OSError:
                        self.counters['send_errors'] += 1
                wait = max(0, min(.01, self.queue[0][0] - time.monotonic())) if self.queue else .01
                if not select.select([self.socket], [], [], wait)[0]:
                    continue
                # Bound ingress work so a busy receiver cannot starve due packets.
                for _ in range(64):
                    try:
                        data, source = self.socket.recvfrom(65535)
                    except BlockingIOError:
                        break
                    except ConnectionResetError:
                        # Windows reports ICMP port-unreachable on the next UDP recv
                        # after a relay restart. It is not a bridge-thread failure.
                        self.counters['icmp_resets_ignored'] += 1
                        break
                    if source == self.relay:
                        destination = self.peer
                    else:
                        if self.peer is not None and source != self.peer:
                            self.counters['peer_changes'] += 1
                            self.counters['stale_dropped'] += len(self.queue)
                            self.queue.clear()
                            self.queued_bytes = 0
                        self.peer = source
                        destination = self.relay
                    if destination is not None:
                        self._enqueue(data, destination, time.monotonic())
        except BaseException as error:
            self.error = repr(error)

    def interrupt(self, seconds):
        if not 0 < seconds <= 90:
            raise ValueError('outage must be in (0, 90] seconds')
        self.drop_until = time.monotonic() + seconds
        self.outage_generation += 1
        return self.drop_until

    def snapshot(self):
        return {'counters': dict(self.counters), 'queue_bytes': self.queued_bytes,
                'queue_packets': len(self.queue), 'delay_samples_ms': list(self.delay_samples_ms),
                'error': self.error, 'max_packets': self.max_packets, 'max_bytes': self.max_bytes}

    def close(self):
        self.stop.set()
        self.thread.join(timeout=2)
        self.socket.close()
        if self.thread.is_alive() or self.error:
            raise RuntimeError('impairment bridge failed: ' + str(self.error))
