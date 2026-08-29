package io.github.kxnar.btaanywhere;

import java.time.Duration;
import java.util.Objects;

public record TunnelConfig(
	RelayResolver relayResolver,
	String clientInstanceId,
	Duration connectTimeout,
	Duration heartbeatInterval,
	Duration initialReconnectDelay,
	Duration maximumReconnectDelay
) {
	public TunnelConfig {
		Objects.requireNonNull(relayResolver, "relayResolver");
		Objects.requireNonNull(clientInstanceId, "clientInstanceId");
		Objects.requireNonNull(connectTimeout, "connectTimeout");
		Objects.requireNonNull(heartbeatInterval, "heartbeatInterval");
		Objects.requireNonNull(initialReconnectDelay, "initialReconnectDelay");
		Objects.requireNonNull(maximumReconnectDelay, "maximumReconnectDelay");
		if (clientInstanceId.isBlank() || clientInstanceId.length() > 128) {
			throw new IllegalArgumentException("client instance ID must contain 1-128 characters");
		}
		if (connectTimeout.isNegative() || connectTimeout.isZero()
			|| heartbeatInterval.isNegative() || heartbeatInterval.isZero()
			|| initialReconnectDelay.isNegative() || initialReconnectDelay.isZero()
			|| maximumReconnectDelay.compareTo(initialReconnectDelay) < 0) {
			throw new IllegalArgumentException("tunnel durations are invalid");
		}
	}

	public static TunnelConfig defaults(RelayDescriptor relay, String clientInstanceId) {
		return new TunnelConfig(
			new StaticRelayResolver(relay),
			clientInstanceId,
			Duration.ofSeconds(10),
			Duration.ofSeconds(15),
			Duration.ofSeconds(1),
			Duration.ofSeconds(30)
		);
	}
}
