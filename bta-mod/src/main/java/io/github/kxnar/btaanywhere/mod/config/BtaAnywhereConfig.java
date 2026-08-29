package io.github.kxnar.btaanywhere.mod.config;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

public final class BtaAnywhereConfig {
	public String clientInstanceId = UUID.randomUUID().toString();
	public String relayHost = "";
	public int relayPort = 25_575;
	public String relayTrustedCertificate = "";
	public String relayAccessToken = "";

	public boolean relayConfigured(Path gameDirectory) {
		Objects.requireNonNull(gameDirectory, "gameDirectory");
		if (relayHost == null || relayHost.isBlank() || relayAccessToken == null || relayAccessToken.isBlank()
			|| relayTrustedCertificate == null || relayTrustedCertificate.isBlank()
			|| relayPort < 1 || relayPort > 65_535) {
			return false;
		}
		Path root = gameDirectory.toAbsolutePath().normalize();
		Path certificate = root.resolve(relayTrustedCertificate).normalize();
		return certificate.startsWith(root) && java.nio.file.Files.isRegularFile(certificate);
	}

	public Path relayCertificate(Path gameDirectory) {
		return gameDirectory.toAbsolutePath().normalize().resolve(relayTrustedCertificate).normalize();
	}
}
