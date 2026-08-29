package io.github.kxnar.btaanywhere;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Router mapping implementation backed by PCP, NAT-PMP, and UPnP-IGD. */
public final class DefaultPortMappingService implements PortMappingService {
	private static final Duration DEFAULT_RENEWAL_FLOOR = Duration.ofSeconds(30);

	private final PortMappingBackend backend;
	private final ScheduledExecutorService executor;
	private final Duration renewalFloor;
	private final Set<ManagedPortMapping> mappings = ConcurrentHashMap.newKeySet();
	private final Set<CompletableFuture<PortMapping>> pending = ConcurrentHashMap.newKeySet();
	private final AtomicBoolean closed = new AtomicBoolean();

	public DefaultPortMappingService() {
		this(new OffbynullPortMappingBackend(), DEFAULT_RENEWAL_FLOOR);
	}

	DefaultPortMappingService(PortMappingBackend backend, Duration renewalFloor) {
		this.backend = Objects.requireNonNull(backend, "backend");
		this.renewalFloor = Objects.requireNonNull(renewalFloor, "renewalFloor");
		if (renewalFloor.isNegative() || renewalFloor.isZero()) {
			throw new IllegalArgumentException("renewal floor must be positive");
		}
		ThreadFactory factory = runnable -> {
			Thread thread = new Thread(runnable, "bta-anywhere-port-mapping");
			thread.setDaemon(true);
			return thread;
		};
		executor = Executors.newSingleThreadScheduledExecutor(factory);
	}

	@Override
	public CompletionStage<PortMapping> open(PortMappingRequest request) {
		Objects.requireNonNull(request, "request");
		if (closed.get()) {
			return CompletableFuture.failedFuture(new IllegalStateException("port mapping service is closed"));
		}
		CompletableFuture<PortMapping> result = new CompletableFuture<>();
		pending.add(result);
		try {
			executor.execute(() -> {
				try {
					if (closed.get()) {
						throw new IllegalStateException("port mapping service is closed");
					}
					PortMapping mapping = discoverAndMap(request);
					if (closed.get() || !result.complete(mapping)) {
						mapping.close();
						result.completeExceptionally(new IllegalStateException("port mapping service is closed"));
					}
				} catch (RuntimeException exception) {
					result.completeExceptionally(exception);
				} finally {
					pending.remove(result);
				}
			});
		} catch (RejectedExecutionException exception) {
			pending.remove(result);
			result.completeExceptionally(new IllegalStateException("port mapping service is closed", exception));
		}
		return result;
	}

	private PortMapping discoverAndMap(PortMappingRequest request) {
		PortMappingBackend.Discovery discovery = null;
		try {
			discovery = backend.discover();
			List<PortMappingBackend.Candidate> candidates = discovery.candidates().stream()
				.sorted(Comparator.comparingInt(candidate -> candidate.protocol().priority()))
				.toList();
			RuntimeException lastFailure = null;
			for (PortMappingBackend.Candidate candidate : candidates) {
				try {
					PortMappingBackend.Lease lease = candidate.map(request);
					if (!usableLease(lease)) {
						lease.unmap();
						lastFailure = new IllegalStateException(
							"router returned a private, CGNAT, or unavailable external address"
						);
						continue;
					}
					ManagedPortMapping managed = new ManagedPortMapping(candidate, lease, discovery);
					discovery = null;
					mappings.add(managed);
					try {
						if (closed.get()) {
							throw new IllegalStateException("port mapping service is closed");
						}
						managed.scheduleRenewal();
						return managed;
					} catch (RuntimeException exception) {
						managed.close();
						throw exception;
					}
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException("port mapping was interrupted", exception);
				} catch (RuntimeException exception) {
					lastFailure = exception;
				}
			}
			throw new IllegalStateException(
				"no PCP, NAT-PMP, or UPnP gateway produced a public TCP mapping",
				lastFailure
			);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("router discovery was interrupted", exception);
		} finally {
			if (discovery != null) {
				discovery.close();
			}
		}
	}

	@Override
	public void close() {
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		IllegalStateException failure = new IllegalStateException("port mapping service is closed");
		List.copyOf(pending).forEach(result -> result.completeExceptionally(failure));
		List.copyOf(mappings).forEach(ManagedPortMapping::close);
		mappings.clear();
		executor.shutdownNow();
	}

	private static boolean usableLease(PortMappingBackend.Lease lease) {
		return PublicAddress.isPublic(lease.externalAddress())
			&& lease.externalPort() >= 1 && lease.externalPort() <= 65_535;
	}

	private final class ManagedPortMapping implements PortMapping {
		private final PortMappingBackend.Candidate candidate;
		private final AtomicReference<PortMappingBackend.Lease> lease;
		private final PortMappingBackend.Discovery discovery;
		private final AtomicBoolean mappingClosed = new AtomicBoolean();
		private final CompletableFuture<Void> closedFuture = new CompletableFuture<>();
		private volatile Instant expiresAt;
		private volatile ScheduledFuture<?> renewal;

		ManagedPortMapping(
			PortMappingBackend.Candidate candidate,
			PortMappingBackend.Lease lease,
			PortMappingBackend.Discovery discovery
		) {
			this.candidate = candidate;
			this.lease = new AtomicReference<>(lease);
			this.discovery = discovery;
			expiresAt = Instant.now().plusSeconds(Math.max(0, lease.lifetimeSeconds()));
		}

		synchronized void scheduleRenewal() {
			if (mappingClosed.get()) {
				return;
			}
			long halfLeaseMillis = Duration.ofSeconds(Math.max(0, lease.get().lifetimeSeconds() / 2)).toMillis();
			long delayMillis = Math.max(renewalFloor.toMillis(), halfLeaseMillis);
			renewal = executor.schedule(this::renew, delayMillis, TimeUnit.MILLISECONDS);
		}

		private synchronized void renew() {
			if (mappingClosed.get()) {
				return;
			}
			try {
				PortMappingBackend.Lease refreshed = lease.get().refresh();
				if (!usableLease(refreshed)) {
					refreshed.unmap();
					throw new IllegalStateException(
						"router renewed the mapping with a private, CGNAT, or invalid endpoint"
					);
				}
				lease.set(refreshed);
				expiresAt = Instant.now().plusSeconds(Math.max(0, refreshed.lifetimeSeconds()));
				scheduleRenewal();
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				fail(exception);
			} catch (RuntimeException exception) {
				fail(exception);
			}
		}

		private void fail(Throwable failure) {
			closeInternal();
			closedFuture.completeExceptionally(failure);
		}

		@Override
		public PublicEndpoint endpoint() {
			PortMappingBackend.Lease current = lease.get();
			return new PublicEndpoint(current.externalAddress().getHostAddress(), current.externalPort());
		}

		@Override
		public String protocol() {
			return candidate.protocol().displayName();
		}

		@Override
		public Duration remainingLease() {
			Duration remaining = Duration.between(Instant.now(), expiresAt);
			return remaining.isNegative() ? Duration.ZERO : remaining;
		}

		@Override
		public CompletionStage<Void> closed() {
			return closedFuture;
		}

		@Override
		public void close() {
			if (closeInternal()) {
				closedFuture.complete(null);
			}
		}

		private synchronized boolean closeInternal() {
			if (!mappingClosed.compareAndSet(false, true)) {
				return false;
			}
			ScheduledFuture<?> task = renewal;
			if (task != null) {
				task.cancel(false);
			}
			try {
				lease.get().unmap();
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			} catch (RuntimeException ignored) {
				// Mapping expiry is an acceptable fallback when explicit unmapping fails.
			} finally {
				try {
					discovery.close();
				} catch (RuntimeException ignored) {
					// Mapping expiry remains the fallback if gateway cleanup fails.
				} finally {
					mappings.remove(this);
				}
			}
			return true;
		}
	}
}
