package io.github.kxnar.btaanywhere;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class DefaultPortMappingServiceTest {
	@Test
	void triesProtocolsInOrderAndCleansRejectedMappings() throws Exception {
		List<String> attempts = new ArrayList<>();
		FakeLease rejected = new FakeLease("100.64.2.3", 25_565, 3_600);
		FakeLease accepted = new FakeLease("1.1.1.1", 30_001, 3_600);
		FakeDiscovery discovery = new FakeDiscovery(List.of(
			new FakeCandidate(PortMappingBackend.Protocol.UPNP, attempts, () -> accepted),
			new FakeCandidate(PortMappingBackend.Protocol.NAT_PMP, attempts, () -> rejected),
			new FakeCandidate(PortMappingBackend.Protocol.PCP, attempts, () -> {
				throw new IllegalStateException("emulated PCP failure");
			})
		));

		try (DefaultPortMappingService service = service(discovery)) {
			PortMapping mapping = service.open(PortMappingRequest.oneHour(25_565))
				.toCompletableFuture().join();
			assertEquals(List.of("PCP", "NAT-PMP", "UPnP"), attempts);
			assertEquals(new PublicEndpoint("1.1.1.1", 30_001), mapping.endpoint());
			assertEquals("UPnP", mapping.protocol());
			assertEquals(1, rejected.unmaps.get());
			mapping.close();
			assertEquals(1, accepted.unmaps.get());
			assertTrue(discovery.closed.get());
			assertTrue(mapping.closed().toCompletableFuture().isDone());
		}
	}

	@Test
	void renewsHalfLeaseAndRemovesMappingOnShutdown() throws Exception {
		CountDownLatch renewed = new CountDownLatch(1);
		FakeLease refreshed = new FakeLease("8.8.8.8", 25_565, 3_600);
		FakeLease initial = new FakeLease("8.8.8.8", 25_565, 0);
		initial.refreshed = refreshed;
		initial.renewed = renewed;
		FakeDiscovery discovery = new FakeDiscovery(List.of(
			new FakeCandidate(PortMappingBackend.Protocol.PCP, new ArrayList<>(), () -> initial)
		));

		DefaultPortMappingService service = service(discovery);
		PortMapping mapping = service.open(PortMappingRequest.oneHour(25_565)).toCompletableFuture().join();
		assertTrue(renewed.await(2, TimeUnit.SECONDS), "emulated lease was not renewed");
		service.close();
		assertEquals(1, refreshed.unmaps.get());
		assertTrue(discovery.closed.get());
		assertTrue(mapping.closed().toCompletableFuture().isDone());
	}

	@Test
	void rejectsCarrierGradeNatAndClosesDiscovery() throws Exception {
		FakeLease rejected = new FakeLease("100.127.10.9", 25_565, 3_600);
		FakeDiscovery discovery = new FakeDiscovery(List.of(
			new FakeCandidate(PortMappingBackend.Protocol.PCP, new ArrayList<>(), () -> rejected)
		));

		try (DefaultPortMappingService service = service(discovery)) {
			CompletionException failure = assertThrows(CompletionException.class, () ->
				service.open(PortMappingRequest.oneHour(25_565)).toCompletableFuture().join()
			);
			assertTrue(failure.getCause().getMessage().contains("no PCP, NAT-PMP, or UPnP"));
			assertEquals(1, rejected.unmaps.get());
			assertTrue(discovery.closed.get());
		}
	}

	@Test
	void rejectsCarrierGradeNatReturnedByRenewal() throws Exception {
		CountDownLatch renewed = new CountDownLatch(1);
		FakeLease rejectedRefresh = new FakeLease("100.64.1.2", 25_565, 3_600);
		FakeLease initial = new FakeLease("8.8.8.8", 25_565, 0);
		initial.refreshed = rejectedRefresh;
		initial.renewed = renewed;
		FakeDiscovery discovery = new FakeDiscovery(List.of(
			new FakeCandidate(PortMappingBackend.Protocol.PCP, new ArrayList<>(), () -> initial)
		));

		try (DefaultPortMappingService service = service(discovery)) {
			PortMapping mapping = service.open(PortMappingRequest.oneHour(25_565)).toCompletableFuture().join();
			assertTrue(renewed.await(2, TimeUnit.SECONDS), "emulated lease was not renewed");
			assertThrows(ExecutionException.class,
				() -> mapping.closed().toCompletableFuture().get(2, TimeUnit.SECONDS));
			assertEquals(1, rejectedRefresh.unmaps.get());
			assertEquals(1, initial.unmaps.get());
			assertTrue(discovery.closed.get());
		}
	}

	@Test
	void closingServiceCompletesAnInFlightOpen() throws Exception {
		CountDownLatch discoveryStarted = new CountDownLatch(1);
		CountDownLatch releaseDiscovery = new CountDownLatch(1);
		DefaultPortMappingService service = new DefaultPortMappingService(() -> {
			discoveryStarted.countDown();
			releaseDiscovery.await();
			return new FakeDiscovery(List.of());
		}, Duration.ofMillis(10));
		var opened = service.open(PortMappingRequest.oneHour(25_565)).toCompletableFuture();
		assertTrue(discoveryStarted.await(2, TimeUnit.SECONDS));

		service.close();
		releaseDiscovery.countDown();

		assertThrows(ExecutionException.class, () -> opened.get(2, TimeUnit.SECONDS));
	}

	private static DefaultPortMappingService service(FakeDiscovery discovery) {
		return new DefaultPortMappingService(() -> discovery, Duration.ofMillis(10));
	}

	@FunctionalInterface
	private interface MappingAction {
		PortMappingBackend.Lease map() throws InterruptedException;
	}

	private record FakeCandidate(
		PortMappingBackend.Protocol protocol,
		List<String> attempts,
		MappingAction action
	) implements PortMappingBackend.Candidate {
		@Override
		public PortMappingBackend.Lease map(PortMappingRequest request) throws InterruptedException {
			attempts.add(protocol.displayName());
			return action.map();
		}
	}

	private static final class FakeDiscovery implements PortMappingBackend.Discovery {
		private final List<PortMappingBackend.Candidate> candidates;
		private final AtomicBoolean closed = new AtomicBoolean();

		FakeDiscovery(List<PortMappingBackend.Candidate> candidates) {
			this.candidates = candidates;
		}

		@Override
		public List<PortMappingBackend.Candidate> candidates() {
			return candidates;
		}

		@Override
		public void close() {
			closed.set(true);
		}
	}

	private static final class FakeLease implements PortMappingBackend.Lease {
		private final InetAddress address;
		private final int port;
		private final long lifetime;
		private final AtomicInteger unmaps = new AtomicInteger();
		private volatile FakeLease refreshed;
		private volatile CountDownLatch renewed;

		FakeLease(String address, int port, long lifetime) throws Exception {
			this.address = InetAddress.getByName(address);
			this.port = port;
			this.lifetime = lifetime;
		}

		@Override
		public InetAddress externalAddress() {
			return address;
		}

		@Override
		public int externalPort() {
			return port;
		}

		@Override
		public long lifetimeSeconds() {
			return lifetime;
		}

		@Override
		public PortMappingBackend.Lease refresh() {
			CountDownLatch latch = renewed;
			if (latch != null) {
				latch.countDown();
			}
			return refreshed == null ? this : refreshed;
		}

		@Override
		public void unmap() {
			unmaps.incrementAndGet();
		}
	}
}
