package io.github.kxnar.btaanywhere;

import java.net.InetSocketAddress;
import java.util.Objects;

public record PublicEndpoint(String host, int port) {
	public PublicEndpoint {
		Objects.requireNonNull(host, "host");
		if (host.isBlank()) {
			throw new IllegalArgumentException("public host cannot be blank");
		}
		if (host.length() > 253 || host.chars().anyMatch(character ->
			Character.isWhitespace(character) || Character.isISOControl(character))) {
			throw new IllegalArgumentException("public host is invalid");
		}
		if (port < 1 || port > 65_535) {
			throw new IllegalArgumentException("public port must be between 1 and 65535");
		}
	}

	public InetSocketAddress socketAddress() {
		return InetSocketAddress.createUnresolved(host, port);
	}

	@Override
	public String toString() {
		return host.contains(":") ? "[" + host + "]:" + port : host + ":" + port;
	}
}
