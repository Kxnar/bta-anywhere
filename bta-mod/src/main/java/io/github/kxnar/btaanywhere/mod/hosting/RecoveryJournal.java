package io.github.kxnar.btaanywhere.mod.hosting;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class RecoveryJournal {
	private static final int SCHEMA_VERSION = 1;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private final Path file;

	public RecoveryJournal(Path managedDirectory) {
		file = Objects.requireNonNull(managedDirectory, "managedDirectory").toAbsolutePath().normalize()
			.resolve("recovery.json");
	}

	public synchronized void write(Entry entry) throws IOException {
		Objects.requireNonNull(entry, "entry");
		Files.createDirectories(file.getParent());
		Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(temporary, GSON.toJson(entry), StandardCharsets.UTF_8);
		try {
			Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (java.nio.file.AtomicMoveNotSupportedException exception) {
			Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	public synchronized Optional<Entry> read() throws IOException {
		if (!Files.isRegularFile(file)) {
			return Optional.empty();
		}
		Entry entry = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Entry.class);
		if (entry == null || entry.schemaVersion() != SCHEMA_VERSION) {
			throw new IOException("unsupported or malformed BTA Anywhere recovery journal");
		}
		return Optional.of(entry);
	}

	public synchronized void clear() throws IOException {
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
		String createdAt
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
				Instant.now().toString()
			);
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
				serverRuntime, logFile, createdAt
			);
		}
	}
}
