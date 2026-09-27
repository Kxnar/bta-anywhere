package io.github.kxnar.btaanywhere;

import java.util.Objects;

/**
 * One resolved relay choice. Coordinated choices carry an opaque, one-use
 * registration ticket; endpoint trust and relay credentials remain local.
 */
public final class RelaySelection implements AutoCloseable {
	private final RelayDescriptor descriptor;
	private final RelayMode mode;
	private final String relayId;
	private final Secret allocationTicket;
	private final int publicPort;
	private final long expiresAtEpochMillis;

	private RelaySelection(
		RelayDescriptor descriptor,
		RelayMode mode,
		String relayId,
		Secret allocationTicket,
		int publicPort,
		long expiresAtEpochMillis
	) {
		this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
		this.mode = mode;
		this.relayId = relayId;
		this.allocationTicket = allocationTicket;
		this.publicPort = publicPort;
		this.expiresAtEpochMillis = expiresAtEpochMillis;
		if ((allocationTicket == null) != (relayId == null)) {
			throw new IllegalArgumentException("relay ID and allocation ticket must be present together");
		}
		if (allocationTicket == null) {
			if (publicPort != 0 || expiresAtEpochMillis != 0) {
				throw new IllegalArgumentException("static relay selection cannot carry lease fields");
			}
		} else if (mode == null || !RelayAllowlist.validRelayId(relayId)
			|| publicPort < 1 || publicPort > 65_535 || expiresAtEpochMillis <= 0) {
			throw new IllegalArgumentException("coordinator relay selection is invalid");
		}
	}

	public static RelaySelection staticRelay(RelayDescriptor descriptor) {
		return new RelaySelection(descriptor, null, null, null, 0, 0);
	}

	public static RelaySelection staticRelay(RelayDescriptor descriptor, RelayMode mode) {
		return new RelaySelection(descriptor, Objects.requireNonNull(mode, "mode"), null, null, 0, 0);
	}

	public static RelaySelection coordinated(
		RelayDescriptor descriptor,
		RelayMode mode,
		String relayId,
		String allocationTicket,
		int publicPort,
		long expiresAtEpochMillis
	) {
		Objects.requireNonNull(allocationTicket, "allocationTicket");
		if (!allocationTicket.startsWith("BTACT1:") || allocationTicket.length() > 2048) {
			throw new IllegalArgumentException("coordinator allocation ticket is invalid");
		}
		return new RelaySelection(
			descriptor,
			Objects.requireNonNull(mode, "mode"),
			relayId,
			Secret.of(allocationTicket),
			publicPort,
			expiresAtEpochMillis
		);
	}

	public RelayDescriptor descriptor() {
		return descriptor;
	}

	/** Null for a static selection without a coordinator mode constraint. */
	public RelayMode mode() {
		return mode;
	}

	public boolean coordinated() {
		return allocationTicket != null;
	}

	public String relayId() {
		return relayId;
	}

	public int publicPort() {
		return publicPort;
	}

	public long expiresAtEpochMillis() {
		return expiresAtEpochMillis;
	}

	public <T> T useAllocationTicket(java.util.function.Function<char[], T> action) {
		if (allocationTicket == null) {
			throw new IllegalStateException("static relay selection has no allocation ticket");
		}
		return allocationTicket.use(action);
	}

	@Override
	public void close() {
		if (allocationTicket != null) {
			allocationTicket.close();
		}
	}

	@Override
	public String toString() {
		return "RelaySelection[descriptor=" + descriptor + ", mode=" + mode
			+ ", relayId=" + relayId + ", allocationTicket="
			+ (allocationTicket == null ? "null" : "[REDACTED]")
			+ ", publicPort=" + publicPort + ", expiresAtEpochMillis=" + expiresAtEpochMillis + "]";
	}
}
