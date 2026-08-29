package io.github.kxnar.btaanywhere.mod.hosting;

import java.nio.file.Path;
import java.util.Objects;

public record WorldContext(Path gameDirectory, Path worldDirectory, String worldDirectoryName, String displayName) {
	public WorldContext {
		Objects.requireNonNull(gameDirectory, "gameDirectory");
		Objects.requireNonNull(worldDirectory, "worldDirectory");
		Objects.requireNonNull(worldDirectoryName, "worldDirectoryName");
		Objects.requireNonNull(displayName, "displayName");
		gameDirectory = gameDirectory.toAbsolutePath().normalize();
		worldDirectory = worldDirectory.toAbsolutePath().normalize();
		if (worldDirectoryName.isBlank() || displayName.isBlank()) {
			throw new IllegalArgumentException("world names cannot be blank");
		}
	}

	public Path managedDirectory() {
		return gameDirectory.resolve("bta-anywhere");
	}
}
