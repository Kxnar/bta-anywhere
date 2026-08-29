package io.github.kxnar.btaanywhere;

import java.time.Instant;
import java.util.Objects;

public record TunnelEvent(Instant timestamp, TunnelState state, String message, Throwable cause) {
	public TunnelEvent {
		Objects.requireNonNull(timestamp, "timestamp");
		Objects.requireNonNull(state, "state");
		Objects.requireNonNull(message, "message");
	}

	public static TunnelEvent of(TunnelState state, String message) {
		return new TunnelEvent(Instant.now(), state, message, null);
	}
}
