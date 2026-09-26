package io.github.kxnar.btaanywhere.mod.hosting;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Path and journal checks for BTA's ordinary single-player world-open path. */
public final class WorldOpenGuard {
	private WorldOpenGuard() {
	}

	public static boolean blocks(Path gameDirectory, String worldDirectoryName) {
		Path game = Objects.requireNonNull(gameDirectory, "gameDirectory").toAbsolutePath().normalize();
		try {
			Optional<RecoveryJournal.Entry> journal = new RecoveryService(game).readValidatedJournal();
			if (journal.isEmpty() || journal.get().worldMode() == WorldMode.SHOWCASE) {
				return false;
			}
			return blocksLiveWorld(game, worldDirectoryName, Path.of(journal.get().originalSavePath()));
		} catch (IOException | RuntimeException exception) {
			// The active world is unknown: fail closed for every single-player open.
			return true;
		}
	}

	public static boolean blocksLiveWorld(Path gameDirectory, String worldDirectoryName, Path originalWorld) {
		try {
			Path game = Objects.requireNonNull(gameDirectory, "gameDirectory").toAbsolutePath().normalize();
			Path savesRoot = game.resolve("saves");
			Path original = Objects.requireNonNull(originalWorld, "originalWorld").toAbsolutePath().normalize();
			if (!original.startsWith(savesRoot) || original.equals(savesRoot)
				|| Files.isSymbolicLink(original)) {
				return true;
			}
			if (Files.isSymbolicLink(savesRoot)) {
				return true;
			}
			Path relative = Path.of(worldDirectoryName);
			if (relative.isAbsolute() || relative.getNameCount() != 1) {
				return true;
			}
			Path requested = savesRoot.resolve(relative).normalize();
			if (!requested.startsWith(savesRoot) || requested.equals(savesRoot)) {
				return true;
			}
			if (requested.equals(original)
				|| worldDirectoryName.equalsIgnoreCase(original.getFileName().toString())) {
				return true;
			}
			if (!Files.exists(requested, LinkOption.NOFOLLOW_LINKS)) {
				return false;
			}
			if (Files.isSymbolicLink(requested)) {
				return true;
			}
			Path savesReal = savesRoot.toRealPath();
			Path requestedReal = requested.toRealPath();
			if (!requestedReal.startsWith(savesReal)) {
				return true;
			}
			return Files.exists(original, LinkOption.NOFOLLOW_LINKS)
				&& Files.isSameFile(requestedReal, original);
		} catch (IOException | RuntimeException exception) {
			// The active world is unknown: fail closed for every single-player open.
			return true;
		}
	}

	public static boolean blocksNewWorld(Path gameDirectory) {
		Path game = Objects.requireNonNull(gameDirectory, "gameDirectory").toAbsolutePath().normalize();
		try {
			new RecoveryService(game).readValidatedJournal();
			return false;
		} catch (IOException | RuntimeException exception) {
			return true;
		}
	}
}
