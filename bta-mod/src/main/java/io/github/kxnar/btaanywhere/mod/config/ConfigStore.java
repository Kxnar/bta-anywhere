package io.github.kxnar.btaanywhere.mod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Objects;

public final class ConfigStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private final Path file;

	public ConfigStore(Path gameDirectory) {
		Objects.requireNonNull(gameDirectory, "gameDirectory");
		file = gameDirectory.toAbsolutePath().normalize().resolve("config").resolve("bta-anywhere.json");
	}

	public BtaAnywhereConfig load() throws IOException {
		if (!Files.exists(file)) {
			BtaAnywhereConfig created = new BtaAnywhereConfig();
			save(created);
			return created;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			BtaAnywhereConfig config = GSON.fromJson(reader, BtaAnywhereConfig.class);
			return config == null ? new BtaAnywhereConfig() : config;
		}
	}

	public void save(BtaAnywhereConfig config) throws IOException {
		Objects.requireNonNull(config, "config");
		Files.createDirectories(file.getParent());
		Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
		try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
			GSON.toJson(config, writer);
		}
		try {
			Files.setPosixFilePermissions(temporary, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
		} catch (UnsupportedOperationException ignored) {
			// Windows ACLs inherit from the user's private game directory.
		}
		try {
			Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (java.nio.file.AtomicMoveNotSupportedException exception) {
			Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	public Path file() {
		return file;
	}
}
