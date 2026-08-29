package io.github.kxnar.btaanywhere.mod.hosting;

import java.nio.file.Path;
import java.util.Objects;

public record BackupResult(Path backupFile, long worldSizeBytes) {
	public BackupResult {
		backupFile = Objects.requireNonNull(backupFile, "backupFile").toAbsolutePath().normalize();
		if (worldSizeBytes < 0) {
			throw new IllegalArgumentException("world size cannot be negative");
		}
	}
}
