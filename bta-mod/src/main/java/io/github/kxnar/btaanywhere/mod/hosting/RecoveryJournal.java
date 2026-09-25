package io.github.kxnar.btaanywhere.mod.hosting;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class RecoveryJournal {
	private static final int SCHEMA_VERSION = 1;
	private static final long MAXIMUM_JOURNAL_BYTES = 64 * 1024;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private final Path file;
	private final HostingFaults faults;

	public RecoveryJournal(Path managedDirectory) {
		this(managedDirectory, HostingFaults.NONE);
	}

	RecoveryJournal(Path managedDirectory, HostingFaults faults) {
		file = Objects.requireNonNull(managedDirectory, "managedDirectory").toAbsolutePath().normalize()
			.resolve("recovery.json");
		this.faults = Objects.requireNonNull(faults, "faults");
	}

	public synchronized void write(Entry entry) throws IOException {
		Objects.requireNonNull(entry, "entry");
		Files.createDirectories(file.getParent());
		Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(temporary, GSON.toJson(entry), StandardCharsets.UTF_8);
		faults.hit(HostingFaults.Point.BEFORE_JOURNAL_PUBLICATION);
		try {
			Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (java.nio.file.AtomicMoveNotSupportedException exception) {
			Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
		}
		faults.hit(HostingFaults.Point.AFTER_JOURNAL_PUBLICATION);
	}

	public synchronized Optional<Entry> read() throws IOException {
		if (Files.exists(file.resolveSibling(file.getFileName() + ".tmp"), LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("incomplete BTA Anywhere recovery journal update; inspect managed processes and recovery files before opening a world");
		}
		if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
			return Optional.empty();
		}
		if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) {
			throw new IOException("unsafe BTA Anywhere recovery journal file; inspect managed processes before opening a world");
		}
		if (Files.size(file) > MAXIMUM_JOURNAL_BYTES) {
			throw new IOException("BTA Anywhere recovery journal exceeds its size limit; inspect it before opening a world");
		}
		Entry entry;
		try {
			entry = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Entry.class);
		} catch (RuntimeException exception) {
			throw new IOException("malformed BTA Anywhere recovery journal; inspect managed processes and recovery files before opening a world", exception);
		}
		if (entry == null || entry.schemaVersion() != SCHEMA_VERSION
			|| entry.worldMode() == null || entry.networkMode() == null
			|| entry.originalSavePath() == null || entry.activeSavePath() == null
			|| entry.worldDirectoryName() == null || entry.backupPath() == null
			|| entry.processStartTime() == null || entry.executable() == null
			|| entry.serverStartTime() == null || entry.serverExecutable() == null
			|| entry.controlFile() == null || entry.serverRuntime() == null
			|| entry.logFile() == null || entry.createdAt() == null
			|| entry.originalSavePath().isBlank() || entry.activeSavePath().isBlank()
			|| entry.serverRuntime().isBlank() || entry.logFile().isBlank()
			|| entry.worldDirectoryName().isBlank() || entry.localPort() < 1024
			|| entry.localPort() > 65535) {
			throw new IOException("unsupported or malformed BTA Anywhere recovery journal; inspect managed processes before opening a world");
		}
		return Optional.of(entry);
	}

	public synchronized void clear() throws IOException {
		faults.hit(HostingFaults.Point.BEFORE_JOURNAL_CLEAR);
		Files.deleteIfExists(file);
	}

	public Path file() {
		return file;
	}

	public record Entry(
		int schemaVersion,
		WorldMode worldMode,
		NetworkMode networkMode,
		String originalSavePath,
		String activeSavePath,
		String worldDirectoryName,
		String backupPath,
		long pid,
		String processStartTime,
		String executable,
		long serverPid,
		String serverStartTime,
		String serverExecutable,
		String controlFile,
		int localPort,
		String serverRuntime,
		String logFile,
		String createdAt,
		boolean launchIntent
	) {
		public Entry {
			Objects.requireNonNull(worldMode, "worldMode");
			Objects.requireNonNull(networkMode, "networkMode");
			Objects.requireNonNull(originalSavePath, "originalSavePath");
			Objects.requireNonNull(activeSavePath, "activeSavePath");
			Objects.requireNonNull(worldDirectoryName, "worldDirectoryName");
			backupPath = backupPath == null ? "" : backupPath;
			processStartTime = processStartTime == null ? "" : processStartTime;
			executable = executable == null ? "" : executable;
			serverStartTime = serverStartTime == null ? "" : serverStartTime;
			serverExecutable = serverExecutable == null ? "" : serverExecutable;
			controlFile = controlFile == null ? "" : controlFile;
			Objects.requireNonNull(serverRuntime, "serverRuntime");
			Objects.requireNonNull(logFile, "logFile");
			Objects.requireNonNull(createdAt, "createdAt");
		}

		public static Entry prepared(
			WorldMode worldMode,
			NetworkMode networkMode,
			Path originalSave,
			Path activeSave,
			String worldDirectoryName,
			Path backup,
			int localPort,
			Path serverRuntime,
			Path logFile
		) {
			return new Entry(
				SCHEMA_VERSION, worldMode, networkMode,
				originalSave.toAbsolutePath().normalize().toString(),
				activeSave.toAbsolutePath().normalize().toString(),
				worldDirectoryName,
				backup == null ? "" : backup.toAbsolutePath().normalize().toString(),
				-1L, "", "", -1L, "", "", "", localPort,
				serverRuntime.toAbsolutePath().normalize().toString(),
				logFile.toAbsolutePath().normalize().toString(),
				Instant.now().toString(), false
			);
		}

		public boolean identityRecorded() {
			return pid > 0 && !processStartTime.isBlank() && !executable.isBlank()
				&& serverPid > 0 && !serverStartTime.isBlank() && !serverExecutable.isBlank()
				&& !controlFile.isBlank();
		}

		public Entry withLaunchIntent() {
			return new Entry(schemaVersion, worldMode, networkMode, originalSavePath, activeSavePath,
				worldDirectoryName, backupPath, pid, processStartTime, executable, serverPid,
				serverStartTime, serverExecutable, controlFile, localPort, serverRuntime, logFile,
				createdAt, true);
		}

		public Entry withProcess(ManagedServerProcess managedProcess) {
			Process process = managedProcess.process();
			ProcessHandle.Info info = process.toHandle().info();
			String start = info.startInstant().map(Instant::toString).orElse("");
			String command = info.command().orElse("");
			io.github.kxnar.btaanywhere.mod.supervisor.SupervisorControlFile control = managedProcess.control();
			return new Entry(
				schemaVersion, worldMode, networkMode, originalSavePath, activeSavePath,
				worldDirectoryName, backupPath, process.pid(), start, command,
				control.serverPid(), control.serverStartTime(), control.serverExecutable(),
				managedProcess.controlFile().toAbsolutePath().normalize().toString(), localPort,
				serverRuntime, logFile, createdAt, false
			);
		}
	}
}
