package io.github.kxnar.btaanywhere.mod.hosting;

import io.github.kxnar.btaanywhere.mod.supervisor.SupervisorControlFile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class RecoveryService {
	private static final DateTimeFormatter TIMESTAMP =
		DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss").withZone(ZoneOffset.UTC);
	private final Path gameDirectory;
	private final Path managedDirectory;
	private final RecoveryJournal journal;
	private final WorldBackupService backups = new WorldBackupService();

	public RecoveryService(Path gameDirectory) {
		this.gameDirectory = Objects.requireNonNull(gameDirectory, "gameDirectory").toAbsolutePath().normalize();
		managedDirectory = this.gameDirectory.resolve("bta-anywhere");
		journal = new RecoveryJournal(managedDirectory);
	}

	public Optional<RecoveryInspection> inspect() throws IOException {
		Optional<RecoveryJournal.Entry> optional = journal.read();
		if (optional.isEmpty()) {
			return Optional.empty();
		}
		RecoveryJournal.Entry entry = optional.get();
		try {
			validatePaths(entry);
		} catch (RuntimeException exception) {
			throw new IOException("malformed recovery paths; inspect managed processes and recovery files before opening a world", exception);
		}
		if (!entry.identityRecorded() || entry.launchIntent()) {
			return Optional.of(new RecoveryInspection(entry, false, false, false, true,
				"Managed process identity was not fully recorded. The original world must remain closed; inspect the supervisor, server, and recovery files manually."));
		}
		Optional<ProcessHandle> supervisor = ProcessIdentity.matching(entry);
		Optional<ProcessHandle> server = matchingServer(entry);
		Optional<SupervisorControlFile> control = trustedControl(entry, supervisor, server);
		boolean supervisorAlive = supervisor.isPresent();
		boolean serverAlive = server.isPresent();
		boolean identityAmbiguous = unmatchedLivePid(entry.pid(), supervisor)
			|| unmatchedLivePid(entry.serverPid(), server);
		boolean ready = false;
		String message;
		if (supervisorAlive && serverAlive && control.isPresent()) {
			try {
				String response = control.get().request("STATUS", 2_000);
				ready = response.contains("running=true") && response.contains("ready=true");
				message = ready ? "The managed BTA server is still running and ready."
					: "The managed BTA server is still starting or not ready.";
			} catch (IOException exception) {
				message = "The recorded processes match, but the supervisor control channel is unavailable.";
			}
		} else if (identityAmbiguous) {
			message = "A recorded PID is alive with a different identity. Do not stop it or open the original world; inspect the processes manually.";
		} else if (serverAlive) {
			message = "The BTA server is still running without its recoverable supervisor; do not open the save.";
		} else if (supervisorAlive) {
			message = "The supervisor remains, but the BTA server has exited.";
		} else {
			message = "No matching managed process is running. Recovery files were retained.";
		}
		return Optional.of(new RecoveryInspection(entry, supervisorAlive, serverAlive, ready,
			identityAmbiguous, message));
	}

	private static boolean unmatchedLivePid(long pid, Optional<ProcessHandle> matching) {
		return pid > 0 && matching.isEmpty()
			&& ProcessHandle.of(pid).filter(ProcessHandle::isAlive).isPresent();
	}

	public boolean gracefulStop(RecoveryInspection inspection) throws IOException, InterruptedException {
		Objects.requireNonNull(inspection, "inspection");
		RecoveryJournal.Entry entry = inspection.entry();
		ProcessHandle supervisor = ProcessIdentity.matching(entry)
			.orElseThrow(() -> new IOException("recorded supervisor identity no longer matches a live process"));
		ProcessHandle server = matchingServer(entry)
			.orElseThrow(() -> new IOException("recorded server identity no longer matches a live process"));
		SupervisorControlFile control = trustedControl(entry, Optional.of(supervisor), Optional.of(server))
			.orElseThrow(() -> new IOException("supervisor control data is missing or does not match the recorded processes"));
		String response = control.request("STOP", 45_000);
		boolean exited;
		try {
			exited = supervisor.onExit().get(10, TimeUnit.SECONDS) != null;
		} catch (java.util.concurrent.ExecutionException exception) {
			throw new IOException("could not observe the recovered supervisor exit", exception.getCause());
		} catch (java.util.concurrent.TimeoutException exception) {
			exited = false;
		}
		boolean clean = exited && response.equals("STOPPED clean=true");
		if (clean) {
			cleanupAfterCleanStop(inspection.entry());
		}
		return clean;
	}

	public String clearAndOpenOriginal(RecoveryInspection inspection) throws IOException {
		Objects.requireNonNull(inspection, "inspection");
		RecoveryInspection refreshed = inspect().orElseThrow(() -> new IOException("recovery journal is missing"));
		if (!refreshed.canOpenOriginal()) {
			throw new IOException("the original save must stay closed: " + refreshed.message());
		}
		journal.clear();
		return refreshed.entry().worldDirectoryName();
	}

	public String restoreBackup(RecoveryInspection inspection, boolean explicitlyConfirmed) throws IOException {
		Objects.requireNonNull(inspection, "inspection");
		if (!explicitlyConfirmed) {
			throw new IOException("backup restore requires explicit confirmation");
		}
		RecoveryInspection refreshed = inspect().orElseThrow(() -> new IOException("recovery journal is missing"));
		if (!refreshed.canRestore()) {
			throw new IOException("backup cannot be restored: " + refreshed.message());
		}
		RecoveryJournal.Entry entry = refreshed.entry();
		Path original = Path.of(entry.originalSavePath()).toAbsolutePath().normalize();
		Path backup = Path.of(entry.backupPath()).toAbsolutePath().normalize();
		Path backupsRoot = managedDirectory.resolve("backups").normalize();
		if (!backup.startsWith(backupsRoot) || !Files.isRegularFile(backup)) {
			throw new IOException("recorded backup is missing or outside the managed backup directory");
		}
		Path savesRoot = gameDirectory.resolve("saves").normalize();
		if (!original.startsWith(savesRoot) || original.equals(savesRoot)) {
			throw new IOException("recorded original save path is unsafe");
		}
		Path parent = original.getParent();
		Path previous = parent.resolve("." + original.getFileName() + ".pre-restore-" + TIMESTAMP.format(Instant.now()));
		Path temporary = parent.resolve("." + original.getFileName() + ".restore-partial-" + java.util.UUID.randomUUID());
		if (Files.exists(previous) || Files.exists(temporary)) {
			throw new IOException("restore staging path already exists");
		}
		boolean movedOriginal = false;
		try {
			if (Files.exists(original)) {
				move(original, previous);
				movedOriginal = true;
			}
			extractBackup(backup, temporary);
			move(temporary, original);
			journal.clear();
			return entry.worldDirectoryName();
		} catch (IOException exception) {
			try {
				deleteTreeIfPresent(temporary);
			} catch (IOException cleanupFailure) {
				exception.addSuppressed(cleanupFailure);
			}
			if (movedOriginal && !Files.exists(original) && Files.exists(previous)) {
				try {
					move(previous, original);
				} catch (IOException rollbackFailure) {
					exception.addSuppressed(rollbackFailure);
				}
			}
			throw exception;
		}
	}

	private void cleanupAfterCleanStop(RecoveryJournal.Entry entry) throws IOException {
		if (entry.worldMode() == WorldMode.SHOWCASE) {
			backups.deleteCleanShowcase(Path.of(entry.activeSavePath()), managedDirectory);
		}
		journal.clear();
		if (!entry.controlFile().isBlank()) {
			Files.deleteIfExists(Path.of(entry.controlFile()));
		}
	}

	private void validatePaths(RecoveryJournal.Entry entry) throws IOException {
		Path savesRoot = gameDirectory.resolve("saves").normalize();
		Path original = Path.of(entry.originalSavePath()).toAbsolutePath().normalize();
		Path runtime = Path.of(entry.serverRuntime()).toAbsolutePath().normalize();
		Path log = Path.of(entry.logFile()).toAbsolutePath().normalize();
		Path active = Path.of(entry.activeSavePath()).toAbsolutePath().normalize();
		Path control = entry.controlFile().isBlank() ? null
			: Path.of(entry.controlFile()).toAbsolutePath().normalize();
		Path backup = entry.backupPath().isBlank() ? null
			: Path.of(entry.backupPath()).toAbsolutePath().normalize();
		if (!original.startsWith(savesRoot) || original.equals(savesRoot)
			|| !runtime.startsWith(managedDirectory) || !log.startsWith(managedDirectory.resolve("logs"))
			|| (control != null && !control.startsWith(managedDirectory.resolve("logs")))
			|| (backup != null && !backup.startsWith(managedDirectory.resolve("backups")))
			|| (entry.worldMode() == WorldMode.LIVE && !active.equals(original))
			|| (entry.worldMode() == WorldMode.SHOWCASE
				&& !active.startsWith(managedDirectory.resolve("showcases")))) {
			throw new IOException("recovery journal contains paths outside managed locations");
		}
	}

	private static Optional<SupervisorControlFile> readControl(RecoveryJournal.Entry entry) {
		if (entry.controlFile().isBlank()) {
			return Optional.empty();
		}
		try {
			return Optional.of(SupervisorControlFile.read(Path.of(entry.controlFile())));
		} catch (IOException | RuntimeException exception) {
			return Optional.empty();
		}
	}

	private static Optional<ProcessHandle> matchingServer(RecoveryJournal.Entry entry) {
		return ProcessIdentity.matching(entry.serverPid(), entry.serverStartTime(), entry.serverExecutable());
	}

	private static Optional<SupervisorControlFile> trustedControl(
		RecoveryJournal.Entry entry,
		Optional<ProcessHandle> supervisor,
		Optional<ProcessHandle> server
	) {
		return readControl(entry).filter(control ->
			supervisor.filter(control::matchesSupervisor).isPresent()
				&& server.filter(control::matchesServer).isPresent()
				&& control.supervisorPid() == entry.pid()
				&& control.supervisorStartTime().equals(entry.processStartTime())
				&& control.supervisorExecutable().equals(entry.executable())
				&& control.serverPid() == entry.serverPid()
				&& control.serverStartTime().equals(entry.serverStartTime())
				&& control.serverExecutable().equals(entry.serverExecutable())
		);
	}

	private static void extractBackup(Path archive, Path destination) throws IOException {
		Path root = destination.toAbsolutePath().normalize();
		Files.createDirectories(root);
		try (ZipFile zip = new ZipFile(archive.toFile())) {
			var entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				String name = entry.getName();
				if (name.startsWith("/") || name.startsWith("\\") || name.matches("^[A-Za-z]:.*")) {
					throw new IOException("unsafe absolute backup path: " + name);
				}
				Path target = root.resolve(name.replace('\\', '/')).normalize();
				if (target.equals(root) || !target.startsWith(root)) {
					throw new IOException("unsafe traversal backup path: " + name);
				}
				if (entry.isDirectory()) {
					Files.createDirectories(target);
				} else {
					Files.createDirectories(target.getParent());
					try (InputStream input = zip.getInputStream(entry)) {
						Files.copy(input, target);
					}
				}
			}
		}
	}

	private static void move(Path source, Path destination) throws IOException {
		try {
			Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
		} catch (java.nio.file.AtomicMoveNotSupportedException exception) {
			Files.move(source, destination);
		}
	}

	private static void deleteTreeIfPresent(Path target) throws IOException {
		if (!Files.exists(target)) {
			return;
		}
		Files.walkFileTree(target, new SimpleFileVisitor<>() {
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
}
