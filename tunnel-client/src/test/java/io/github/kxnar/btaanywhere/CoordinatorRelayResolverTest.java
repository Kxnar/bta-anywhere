package io.github.kxnar.btaanywhere;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CoordinatorRelayResolverTest {
	private static final long NOW = 1_800_000_000_000L;

	@TempDir
	Path temporaryDirectory;

	@Test
	void probesAllowlistedRelaysAndMapsAllocationBackToLocalTrustConfiguration() throws Exception {
		Map<String, RelayDescriptor> configured = relays();
		RelayAllowlist allowlist = new RelayAllowlist(configured);
		AtomicReference<List<RelayProbeSample>> sentProbes = new AtomicReference<>();
		AtomicReference<RelayMode> sentMode = new AtomicReference<>();
		CoordinatorAllocationClient coordinator = (clientId, mode, probes) -> {
			assertEquals("client-7", clientId);
			sentMode.set(mode);
			sentProbes.set(probes);
			return CompletableFuture.completedFuture(
				new CoordinatorAllocation("BTACT1:opaque-ticket", "west-1", 30123, NOW + 20_000));
		};
		RelayRttProbe probe = (descriptor, mode) -> CompletableFuture.completedFuture(
			descriptor.host().equals("relay-a.example") ? 27L : 12L);
		CoordinatorRelayResolver resolver = resolver(
			coordinator, probe, allowlist, RelayMode.ENCRYPTED, null);

		try (RelaySelection selection = resolver.resolveSelection().toCompletableFuture().join()) {
			assertTrue(selection.coordinated());
			assertEquals(RelayMode.ENCRYPTED, selection.mode());
			assertEquals("west-1", selection.relayId());
			assertEquals(30123, selection.publicPort());
			assertEquals(configured.get("west-1"), selection.descriptor());
			assertEquals("coordinated", resolver.selectionSource());
			assertFalse(selection.toString().contains("opaque-ticket"));
			assertEquals("BTACT1:opaque-ticket", selection.useAllocationTicket(value -> new String(value)));
		}
		assertEquals(RelayMode.ENCRYPTED, sentMode.get());
		assertEquals(List.of("east-1", "west-1"), sentProbes.get().stream()
			.map(RelayProbeSample::relayId).toList());
		assertEquals(List.of(27L, 12L), sentProbes.get().stream()
			.map(RelayProbeSample::rttMillis).toList());
		assertTrue(sentProbes.get().stream().allMatch(sample -> sample.measuredAtEpochMillis() == NOW));
	}

	@Test
	void onlyExplicitStaticFallbackHandlesCoordinatorUnavailabilityAndKeepsMode() throws Exception {
		RelayDescriptor fallback = descriptor("fallback.example", 25575);
		CoordinatorAllocationClient unavailable = (clientId, mode, probes) ->
			CompletableFuture.failedFuture(new HttpTimeoutException("offline"));
		CoordinatorRelayResolver resolver = resolver(
			unavailable, (relay, mode) -> CompletableFuture.completedFuture(10L),
			new RelayAllowlist(relays()), RelayMode.ENCRYPTED, fallback);

		try (RelaySelection selection = resolver.resolveSelection().toCompletableFuture().join()) {
			assertFalse(selection.coordinated());
			assertEquals(RelayMode.ENCRYPTED, selection.mode());
			assertEquals(fallback, selection.descriptor());
			assertEquals("static fallback", resolver.selectionSource());
		}
	}

	@Test
	void coordinatorAuthorizationFailureDoesNotSilentlyUseFallback() throws Exception {
		RelayDescriptor fallback = descriptor("fallback.example", 25575);
		CoordinatorAllocationClient rejected = (clientId, mode, probes) ->
			CompletableFuture.failedFuture(new HttpsCoordinatorAllocationClient.CoordinatorHttpException(401));
		CoordinatorRelayResolver resolver = resolver(
			rejected, (relay, mode) -> CompletableFuture.completedFuture(10L),
			new RelayAllowlist(relays()), RelayMode.LEGACY, fallback);

		CompletionException failure = assertThrows(CompletionException.class,
			() -> resolver.resolveSelection().toCompletableFuture().join());
		assertTrue(failure.getCause() instanceof HttpsCoordinatorAllocationClient.CoordinatorHttpException);
		assertEquals("unresolved", resolver.selectionSource());
	}

	@Test
	void nestedCoordinatorTlsFailureDoesNotUseStaticFallback() throws Exception {
		RelayDescriptor fallback = descriptor("fallback.example", 25575);
		java.net.ConnectException outer = new java.net.ConnectException("connection failed");
		outer.initCause(new javax.net.ssl.SSLHandshakeException("untrusted coordinator"));
		CoordinatorAllocationClient rejected = (clientId, mode, probes) ->
			CompletableFuture.failedFuture(outer);
		CoordinatorRelayResolver resolver = resolver(
			rejected, (relay, mode) -> CompletableFuture.completedFuture(10L),
			new RelayAllowlist(relays()), RelayMode.ENCRYPTED, fallback);

		assertThrows(CompletionException.class,
			() -> resolver.resolveSelection().toCompletableFuture().join());
		assertEquals("unresolved", resolver.selectionSource());
	}

	@Test
	void unknownRelayOrBadLeaseExpiryFailsClosedInsteadOfUsingFallback() throws Exception {
		RelayDescriptor fallback = descriptor("fallback.example", 25575);
		CoordinatorAllocationClient unknown = (clientId, mode, probes) -> CompletableFuture.completedFuture(
			new CoordinatorAllocation("BTACT1:opaque-ticket", "not-allowlisted", 30123, NOW + 20_000));
		CoordinatorRelayResolver resolver = resolver(
			unknown, (relay, mode) -> CompletableFuture.completedFuture(10L),
			new RelayAllowlist(relays()), RelayMode.LEGACY, fallback);
		assertThrows(CompletionException.class,
			() -> resolver.resolveSelection().toCompletableFuture().join());

		CoordinatorAllocationClient stale = (clientId, mode, probes) -> CompletableFuture.completedFuture(
			new CoordinatorAllocation("BTACT1:opaque-ticket", "east-1", 30123, NOW - 1));
		CoordinatorRelayResolver staleResolver = resolver(
			stale, (relay, mode) -> CompletableFuture.completedFuture(10L),
			new RelayAllowlist(relays()), RelayMode.LEGACY, fallback);
		assertThrows(CompletionException.class,
			() -> staleResolver.resolveSelection().toCompletableFuture().join());
	}

	@Test
	void failedVerifiedProbesRequireExplicitFallback() throws Exception {
		RelayRttProbe failedProbe = (relay, mode) ->
			CompletableFuture.failedFuture(new javax.net.ssl.SSLHandshakeException("untrusted relay"));
		CoordinatorAllocationClient shouldNotRun = (clientId, mode, probes) -> {
			throw new AssertionError("coordinator must not receive an empty probe set");
		};
		CoordinatorRelayResolver noFallback = resolver(
			shouldNotRun, failedProbe, new RelayAllowlist(relays()), RelayMode.LEGACY, null);
		assertThrows(CompletionException.class,
			() -> noFallback.resolveSelection().toCompletableFuture().join());

		RelayDescriptor fallback = descriptor("fallback.example", 25575);
		CoordinatorRelayResolver withFallback = resolver(
			shouldNotRun, failedProbe, new RelayAllowlist(relays()), RelayMode.ENCRYPTED, fallback);
		try (RelaySelection selection = withFallback.resolveSelection().toCompletableFuture().join()) {
			assertEquals(fallback, selection.descriptor());
			assertEquals(RelayMode.ENCRYPTED, selection.mode());
			assertEquals("static fallback", withFallback.selectionSource());
		}
	}

	@Test
	void ticketSecretsAreDestroyedWhenSelectionCloses() throws Exception {
		try (RelaySelection selection = RelaySelection.coordinated(
			descriptor("relay-a.example", 25575),
			RelayMode.LEGACY,
			"east-1",
			"BTACT1:opaque-ticket",
			30000,
			NOW + 1)) {
			assertTrue(selection.toString().contains("[REDACTED]"));
		}
	}

	private CoordinatorRelayResolver resolver(
		CoordinatorAllocationClient coordinator,
		RelayRttProbe probe,
		RelayAllowlist allowlist,
		RelayMode mode,
		RelayDescriptor fallback
	) {
		return new CoordinatorRelayResolver(
			coordinator, probe, allowlist, "client-7", mode, fallback,
			Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
	}

	private Map<String, RelayDescriptor> relays() throws Exception {
		Map<String, RelayDescriptor> result = new HashMap<>();
		result.put("east-1", descriptor("relay-a.example", 25575));
		result.put("west-1", descriptor("relay-b.example", 25576));
		return result;
	}

	private RelayDescriptor descriptor(String host, int port) throws Exception {
		Path certificate = temporaryDirectory.resolve(host + ".pem");
		if (!Files.exists(certificate)) {
			Files.writeString(certificate, "test-only placeholder");
		}
		return new RelayDescriptor(host, port, certificate, Secret.of("local-token-" + host));
	}
}
