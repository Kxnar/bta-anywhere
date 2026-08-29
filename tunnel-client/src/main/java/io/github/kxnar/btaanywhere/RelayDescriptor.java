package io.github.kxnar.btaanywhere;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public record RelayDescriptor(String host, int port, Path trustedCertificate, Secret accessToken) {
	public RelayDescriptor {
		Objects.requireNonNull(host, "host");
		Objects.requireNonNull(trustedCertificate, "trustedCertificate");
		Objects.requireNonNull(accessToken, "accessToken");
		host = normalizeHost(host);
		if (host.isBlank()) {
			throw new IllegalArgumentException("relay host cannot be blank");
		}
		if (port < 1 || port > 65_535) {
			throw new IllegalArgumentException("relay port must be between 1 and 65535");
		}
		trustedCertificate = trustedCertificate.toAbsolutePath().normalize();
		if (!Files.isRegularFile(trustedCertificate)) {
			throw new IllegalArgumentException("trusted relay certificate does not exist: " + trustedCertificate);
		}
	}

	private static String normalizeHost(String value) {
		String normalized = value.trim();
		if (normalized.startsWith("[") && normalized.endsWith("]")) {
			normalized = normalized.substring(1, normalized.length() - 1);
		}
		if (normalized.length() > 253 || normalized.chars().anyMatch(character ->
			Character.isWhitespace(character) || Character.isISOControl(character))) {
			throw new IllegalArgumentException("relay host is invalid");
		}
		return normalized;
	}

	@Override
	public String toString() {
		return "RelayDescriptor[host=" + host + ", port=" + port
			+ ", trustedCertificate=" + trustedCertificate + ", accessToken=[REDACTED]]";
	}
}
