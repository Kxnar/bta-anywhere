package io.github.kxnar.btaanywhere.mod.hosting;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/** A client-lifetime OS lock for one BTA game directory. The lock file is never deleted. */
public final class GameDirectoryLease implements AutoCloseable {
	public static final String IN_USE_MESSAGE = "Another BTA Anywhere client is using this game directory. "
		+ "Close that client or use a separate game profile, then restart BTA.";
	public static final String UNAVAILABLE_MESSAGE = "BTA Anywhere cannot safely reserve this game directory. "
		+ "Close other clients, inspect the profile, or use a separate game profile, then restart BTA.";

	public static final class InUseException extends IOException {
		private static final long serialVersionUID = 1L;

		private InUseException() {
			super(IN_USE_MESSAGE);
		}
	}

	private final Path gameDirectory;
	private final FileChannel channel;
	private final FileLock lock;

	private GameDirectoryLease(Path gameDirectory, FileChannel channel, FileLock lock) {
		this.gameDirectory = gameDirectory;
		this.channel = channel;
		this.lock = lock;
	}

	public static GameDirectoryLease acquire(Path gameDirectory) throws IOException {
		Path game = Objects.requireNonNull(gameDirectory, "gameDirectory").toAbsolutePath().normalize();
		if (!Files.isDirectory(game, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(game)) {
			throw new IOException("BTA Anywhere cannot lock an unsafe game directory. Use a local game profile.");
		}
		Path managed = game.resolve("bta-anywhere");
		try {
			Files.createDirectory(managed);
		} catch (FileAlreadyExistsException ignored) {
			// A retained managed directory is expected after a previous client run.
		}
		if (!Files.isDirectory(managed, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(managed)
			|| !managed.toRealPath().startsWith(game.toRealPath())) {
			throw new IOException("BTA Anywhere managed directory is unsafe; inspect it before starting a client.");
		}
		Path lockFile = managed.resolve("client.lock");
		if (Files.exists(lockFile, LinkOption.NOFOLLOW_LINKS)
			&& (!Files.isRegularFile(lockFile, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(lockFile))) {
			throw new IOException("BTA Anywhere client lock file is unsafe; inspect it before starting a client.");
		}
		FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
			LinkOption.NOFOLLOW_LINKS);
		try {
			if (Files.isSymbolicLink(lockFile) || !Files.isRegularFile(lockFile, LinkOption.NOFOLLOW_LINKS)) {
				throw new IOException("BTA Anywhere client lock file changed during startup; inspect it.");
			}
			FileLock lock;
			try {
				lock = channel.tryLock();
			} catch (OverlappingFileLockException exception) {
				throw new InUseException();
			}
			if (lock == null) {
				throw new InUseException();
			}
			return new GameDirectoryLease(game, channel, lock);
		} catch (IOException | RuntimeException exception) {
			channel.close();
			throw exception;
		}
	}

	boolean covers(Path gameDirectory) {
		return lock.isValid() && this.gameDirectory.equals(
			Objects.requireNonNull(gameDirectory, "gameDirectory").toAbsolutePath().normalize());
	}

	@Override
	public void close() throws IOException {
		try {
			if (lock.isValid()) {
				lock.release();
			}
		} finally {
			channel.close();
		}
	}
}
