package io.github.kxnar.btaanywhere.mod.hosting;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileStore;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class WorldBackupService {
	public static final long MINIMUM_HEADROOM_BYTES = 512L * 1024L * 1024L;
	private static final DateTimeFormatter TIMESTAMP =
		DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss").withZone(ZoneOffset.UTC);
	private final Clock clock;
	private final HostingFaults faults;

	public WorldBackupService() {
		this(Clock.systemUTC());
	}

	WorldBackupService(Clock clock) {
		this(clock, HostingFaults.NONE);
	}

	WorldBackupService(Clock clock, HostingFaults faults) {
		this.clock = Objects.requireNonNull(clock, "clock");
		this.faults = Objects.requireNonNull(faults, "faults");
	}

	public long validateSpace(Path worldDirectory, Path managedDirectory) throws IOException {
		Path world = requireWorldDirectory(worldDirectory);
		Files.createDirectories(managedDirectory);
		long size = directorySize(world);
		FileStore targetStore = Files.getFileStore(managedDirectory);
		long required;
		try {
			required = Math.addExact(size, MINIMUM_HEADROOM_BYTES);
		} catch (ArithmeticException exception) {
			throw new IOException("world is too large to calculate backup headroom", exception);
		}
		if (targetStore.getUsableSpace() < required) {
			throw new IOException("not enough free space: need the world size plus 512 MiB ("
				+ required + " bytes total)");
		}
		return size;
	}

	public BackupResult createBackup(Path worldDirectory, Path managedDirectory, int retention) throws IOException {
		if (retention < 1) {
			throw new IllegalArgumentException("backup retention must be positive");
		}
		Path world = requireWorldDirectory(worldDirectory);
		long size = validateSpace(world, managedDirectory);
		Path backupDirectory = managedDirectory.toAbsolutePath().normalize().resolve("backups");
		Files.createDirectories(backupDirectory);
		String stem = safeName(world.getFileName().toString());
		String timestamp = TIMESTAMP.format(clock.instant());
		Path destination = uniqueDestination(backupDirectory, stem + "-" + timestamp, ".zip");
		Path temporary = backupDirectory.resolve("." + destination.getFileName() + ".tmp-" + UUID.randomUUID());
		try {
			writeZip(world, temporary);
			faults.hit(HostingFaults.Point.BEFORE_BACKUP_OR_COPY_PUBLICATION);
			moveAtomically(temporary, destination);
		} finally {
			Files.deleteIfExists(temporary);
		}
		pruneBackups(backupDirectory, stem, retention);
		return new BackupResult(destination, size);
	}

	public Path createShowcaseCopy(Path worldDirectory, Path managedDirectory) throws IOException {
		Path world = requireWorldDirectory(worldDirectory);
		validateSpace(world, managedDirectory);
		Path showcaseDirectory = managedDirectory.toAbsolutePath().normalize().resolve("showcases");
		Files.createDirectories(showcaseDirectory);
		String name = safeName(world.getFileName().toString()) + "-" + TIMESTAMP.format(clock.instant());
		Path destination = uniqueDestination(showcaseDirectory, name, "");
		Path temporary = showcaseDirectory.resolve("." + destination.getFileName() + ".partial-" + UUID.randomUUID());
		try {
			copyTree(world, temporary);
			faults.hit(HostingFaults.Point.BEFORE_BACKUP_OR_COPY_PUBLICATION);
			moveAtomically(temporary, destination);
		} catch (IOException | RuntimeException exception) {
			deleteManagedTree(temporary, showcaseDirectory);
			throw exception;
		}
		return destination;
	}

	public void deleteCleanShowcase(Path showcase, Path managedDirectory) throws IOException {
		Path root = managedDirectory.toAbsolutePath().normalize().resolve("showcases");
		deleteManagedTree(showcase, root);
	}

	public static long directorySize(Path directory) throws IOException {
		final long[] size = {0L};
		Files.walkFileTree(requireWorldDirectory(directory), new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
				if (Files.isSymbolicLink(file)) {
					throw new IOException("world contains a symbolic link: " + file);
				}
				try {
					size[0] = Math.addExact(size[0], attributes.size());
				} catch (ArithmeticException exception) {
					throw new IOException("world size exceeds supported range", exception);
				}
				return FileVisitResult.CONTINUE;
			}
		});
		return size[0];
	}

	private void writeZip(Path world, Path destination) throws IOException {
		try (ZipOutputStream output = new ZipOutputStream(
			new BufferedOutputStream(Files.newOutputStream(destination)))) {
			Files.walkFileTree(world, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
					if (Files.isSymbolicLink(directory)) {
						throw new IOException("world contains a symbolic link: " + directory);
					}
					Path relative = world.relativize(directory);
					if (!relative.toString().isEmpty()) {
						ZipEntry entry = new ZipEntry(toZipPath(relative) + "/");
						entry.setTime(attributes.lastModifiedTime().toMillis());
						output.putNextEntry(entry);
						output.closeEntry();
					}
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
					if (Files.isSymbolicLink(file)) {
						throw new IOException("world contains a symbolic link: " + file);
					}
					ZipEntry entry = new ZipEntry(toZipPath(world.relativize(file)));
					entry.setTime(attributes.lastModifiedTime().toMillis());
					output.putNextEntry(entry);
					try (InputStream input = Files.newInputStream(file)) {
						input.transferTo(output);
					}
					output.closeEntry();
					faults.hit(HostingFaults.Point.DURING_BACKUP_OR_COPY);
					return FileVisitResult.CONTINUE;
				}
			});
		}
	}

	private void copyTree(Path source, Path destination) throws IOException {
		Files.walkFileTree(source, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
				if (Files.isSymbolicLink(directory)) {
					throw new IOException("world contains a symbolic link: " + directory);
				}
				Files.createDirectories(destination.resolve(source.relativize(directory)));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
				if (Files.isSymbolicLink(file)) {
					throw new IOException("world contains a symbolic link: " + file);
				}
				Files.copy(file, destination.resolve(source.relativize(file)),
					StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
				faults.hit(HostingFaults.Point.DURING_BACKUP_OR_COPY);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	private static void pruneBackups(Path backupDirectory, String stem, int retention) throws IOException {
		List<Path> backups = new ArrayList<>();
		try (var entries = Files.list(backupDirectory)) {
			entries.filter(path -> path.getFileName().toString().startsWith(stem + "-"))
				.filter(path -> path.getFileName().toString().endsWith(".zip"))
				.filter(Files::isRegularFile)
				.forEach(backups::add);
		}
		backups.sort(Comparator.comparingLong(WorldBackupService::lastModified).reversed());
		for (int index = retention; index < backups.size(); index++) {
			Files.deleteIfExists(backups.get(index));
		}
	}

	private static long lastModified(Path path) {
		try {
			return Files.getLastModifiedTime(path).toMillis();
		} catch (IOException ignored) {
			return Long.MIN_VALUE;
		}
	}

	private static void deleteManagedTree(Path target, Path allowedRoot) throws IOException {
		Path root = allowedRoot.toAbsolutePath().normalize();
		Path resolved = target.toAbsolutePath().normalize();
		if (resolved.equals(root) || !resolved.startsWith(root)) {
			throw new IOException("refusing to delete a path outside the managed showcase directory: " + resolved);
		}
		if (!Files.exists(resolved)) {
			return;
		}
		Files.walkFileTree(resolved, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
				if (exception != null) {
					throw exception;
				}
				Files.delete(directory);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	private static Path requireWorldDirectory(Path directory) throws IOException {
		Path resolved = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
		if (!Files.isDirectory(resolved) || Files.isSymbolicLink(resolved)) {
			throw new IOException("world directory does not exist or is a symbolic link: " + resolved);
		}
		return resolved;
	}

	private static Path uniqueDestination(Path directory, String base, String extension) {
		Path candidate = directory.resolve(base + extension);
		for (int suffix = 2; Files.exists(candidate); suffix++) {
			candidate = directory.resolve(base + "-" + suffix + extension);
		}
		return candidate;
	}

	private static void moveAtomically(Path source, Path destination) throws IOException {
		try {
			Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
		} catch (java.nio.file.AtomicMoveNotSupportedException exception) {
			Files.move(source, destination);
		}
	}

	private static String safeName(String name) {
		String sanitized = name.replaceAll("[^A-Za-z0-9._-]", "_");
		return sanitized.isBlank() ? "world" : sanitized;
	}

	private static String toZipPath(Path path) {
		return path.toString().replace('\\', '/');
	}
}
