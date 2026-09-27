package io.github.kxnar.btaanywhere;

import io.github.kxnar.btaanywhere.HttpsCoordinatorAllocationClient.CoordinatorHttpException;
import io.github.kxnar.btaanywhere.internal.VerifiedQuicRelayProbe;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

/** Probes a local allowlist, then asks the coordinator to reserve one relay lease. */
public final class CoordinatorRelayResolver implements RelayResolver {
	private static final long MAX_TICKET_FUTURE_MILLIS = 60_000;
	private final CoordinatorAllocationClient coordinator;
	private final RelayRttProbe probe;
	private final RelayAllowlist allowlist;
	private final String clientInstanceId;
	private final RelayMode mode;
	private final RelayDescriptor staticFallback;
	private final Clock clock;
	private volatile String lastSelectionSource = "unresolved";

	public CoordinatorRelayResolver(
		CoordinatorAllocationClient coordinator,
		RelayRttProbe probe,
		RelayAllowlist allowlist,
		String clientInstanceId,
		RelayMode mode,
		RelayDescriptor staticFallback
	) {
		this(coordinator, probe, allowlist, clientInstanceId, mode, staticFallback, Clock.systemUTC());
	}

	public CoordinatorRelayResolver(
		CoordinatorAllocationClient coordinator,
		RelayRttProbe probe,
		RelayAllowlist allowlist,
		String clientInstanceId,
		RelayMode mode,
		RelayDescriptor staticFallback,
		Clock clock
	) {
		this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
		this.probe = Objects.requireNonNull(probe, "probe");
		this.allowlist = Objects.requireNonNull(allowlist, "allowlist");
		this.clientInstanceId = requireClientId(clientInstanceId);
		this.mode = Objects.requireNonNull(mode, "mode");
		this.staticFallback = staticFallback;
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	public CoordinatorRelayResolver(
		java.net.URI coordinatorUri,
		Secret coordinatorCredential,
		RelayAllowlist allowlist,
		String clientInstanceId,
		RelayMode mode,
		RelayDescriptor staticFallback
	) {
		this(
			new HttpsCoordinatorAllocationClient(coordinatorUri, coordinatorCredential, clientInstanceId),
			new VerifiedQuicRelayProbe(),
			allowlist,
			clientInstanceId,
			mode,
			staticFallback
		);
	}

	public CoordinatorRelayResolver(
		java.net.URI coordinatorUri,
		java.nio.file.Path coordinatorTrustedCertificate,
		Secret coordinatorCredential,
		RelayAllowlist allowlist,
		String clientInstanceId,
		RelayMode mode,
		RelayDescriptor staticFallback
	) {
		this(
			new HttpsCoordinatorAllocationClient(
				coordinatorUri, coordinatorTrustedCertificate, coordinatorCredential, clientInstanceId),
			new VerifiedQuicRelayProbe(),
			allowlist,
			clientInstanceId,
			mode,
			staticFallback
		);
	}

	@Override
	public CompletionStage<RelayDescriptor> resolve() {
		return resolveSelection().thenApply(selection -> {
			try {
				return selection.descriptor();
			} finally {
				selection.close();
			}
		});
	}

	@Override
	public CompletionStage<RelaySelection> resolveSelection() {
		List<Map.Entry<String, RelayDescriptor>> entries = allowlist.entries().entrySet().stream()
			.sorted(Map.Entry.comparingByKey())
			.toList();
		CompletableFuture<List<RelayProbeSample>> probes = CompletableFuture.completedFuture(new ArrayList<>());
		for (Map.Entry<String, RelayDescriptor> entry : entries) {
			probes = probes.thenCompose(samples -> measure(entry)
				.handle((rttMillis, failure) -> {
					if (failure == null && rttMillis != null && rttMillis >= 1 && rttMillis <= 60_000) {
						samples.add(new RelayProbeSample(entry.getKey(), rttMillis, clock.millis()));
					}
					return samples;
				}));
		}
		return probes.thenCompose(samples -> {
			if (samples.isEmpty()) {
				return useFallbackOrFail(new IllegalStateException("no allowlisted relay passed its verified QUIC probe"));
			}
			samples.sort(Comparator.comparing(RelayProbeSample::relayId));
			return coordinator.allocate(clientInstanceId, mode, List.copyOf(samples))
				.handle((allocation, failure) -> {
					if (failure != null) {
						Throwable cause = unwrap(failure);
						if (fallbackAllowed(cause)) {
							return useFallbackOrFail(cause);
						}
						return CompletableFuture.<RelaySelection>failedFuture(cause);
					}
					try {
						RelayDescriptor descriptor = allowlist.require(allocation.relayId());
						long remaining = allocation.expiresAtEpochMillis() - clock.millis();
						if (remaining <= 0 || remaining > MAX_TICKET_FUTURE_MILLIS) {
							throw new IllegalArgumentException("coordinator allocation expiry is invalid");
						}
						lastSelectionSource = "coordinated";
						return CompletableFuture.completedFuture(RelaySelection.coordinated(
							descriptor,
							mode,
							allocation.relayId(),
							allocation.ticket(),
							allocation.publicPort(),
							allocation.expiresAtEpochMillis()
						));
					} catch (RuntimeException invalid) {
						return CompletableFuture.<RelaySelection>failedFuture(invalid);
					}
				})
				.thenCompose(stage -> stage);
		});
	}

	private CompletionStage<Long> measure(Map.Entry<String, RelayDescriptor> relay) {
		try {
			return probe.measureMillis(relay.getValue(), mode);
		} catch (RuntimeException failure) {
			return CompletableFuture.failedFuture(failure);
		}
	}

	private CompletionStage<RelaySelection> useFallbackOrFail(Throwable cause) {
		if (staticFallback == null) {
			return CompletableFuture.failedFuture(cause);
		}
		lastSelectionSource = "static fallback";
		return CompletableFuture.completedFuture(RelaySelection.staticRelay(staticFallback, mode));
	}

	public String selectionSource() {
		return lastSelectionSource;
	}

	private static boolean fallbackAllowed(Throwable failure) {
		for (Throwable current = failure; current != null; current = current.getCause()) {
			if (current instanceof CoordinatorHttpException http) {
				return http.statusCode() >= 500 && http.statusCode() <= 599;
			}
			if (current instanceof javax.net.ssl.SSLException) {
				return false;
			}
			if (current instanceof HttpTimeoutException || current instanceof HttpConnectTimeoutException
				|| current instanceof ConnectException || current instanceof UnknownHostException) {
				return true;
			}
		}
		return false;
	}

	private static Throwable unwrap(Throwable failure) {
		Throwable current = failure;
		while ((current instanceof CompletionException || current instanceof ExecutionException)
			&& current.getCause() != null) {
			current = current.getCause();
		}
		return current;
	}

	private static String requireClientId(String value) {
		if (value == null || value.isEmpty() || value.length() > 128
			|| !value.chars().allMatch(character -> Character.isLetterOrDigit(character)
				|| character == '-' || character == '_')) {
			throw new IllegalArgumentException("coordinator client ID must be 1-128 ASCII identifier characters");
		}
		return value;
	}
}
