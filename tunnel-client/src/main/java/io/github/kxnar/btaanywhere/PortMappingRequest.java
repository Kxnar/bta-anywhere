package io.github.kxnar.btaanywhere;

import java.time.Duration;
import java.util.Objects;

public record PortMappingRequest(
	int internalPort,
	int preferredExternalPort,
	Duration lease,
	String description
) {
	public PortMappingRequest {
		Objects.requireNonNull(lease, "lease");
		Objects.requireNonNull(description, "description");
		if (internalPort < 1 || internalPort > 65_535
			|| preferredExternalPort < 1 || preferredExternalPort > 65_535) {
			throw new IllegalArgumentException("mapping ports must be between 1 and 65535");
		}
		if (lease.compareTo(Duration.ofSeconds(60)) < 0 || lease.compareTo(Duration.ofDays(1)) > 0) {
			throw new IllegalArgumentException("mapping lease must be between one minute and one day");
		}
		if (description.isBlank()) {
			throw new IllegalArgumentException("mapping description cannot be blank");
		}
	}

	public static PortMappingRequest oneHour(int port) {
		return new PortMappingRequest(port, port, Duration.ofHours(1), "BTA Anywhere");
	}
}
