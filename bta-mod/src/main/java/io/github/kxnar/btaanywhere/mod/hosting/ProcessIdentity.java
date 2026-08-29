package io.github.kxnar.btaanywhere.mod.hosting;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

public final class ProcessIdentity {
	private ProcessIdentity() {
	}

	public static Optional<ProcessHandle> matching(RecoveryJournal.Entry entry) {
		return matching(entry.pid(), entry.processStartTime(), entry.executable());
	}

	static Optional<ProcessHandle> matching(long pid, String processStartTime, String executable) {
		if (pid <= 0 || processStartTime.isBlank() || executable.isBlank()) {
			return Optional.empty();
		}
		Optional<ProcessHandle> candidate = ProcessHandle.of(pid);
		if (candidate.isEmpty() || !candidate.get().isAlive()) {
			return Optional.empty();
		}
		ProcessHandle.Info info = candidate.get().info();
		Optional<Instant> actualStart = info.startInstant();
		Optional<String> actualCommand = info.command();
		if (actualStart.isEmpty() || actualCommand.isEmpty()) {
			return Optional.empty();
		}
		Instant recordedStart;
		try {
			recordedStart = Instant.parse(processStartTime);
		} catch (RuntimeException exception) {
			return Optional.empty();
		}
		if (!recordedStart.equals(actualStart.get())) {
			return Optional.empty();
		}
		Path recordedCommand = Path.of(executable).toAbsolutePath().normalize();
		Path command = Path.of(actualCommand.get()).toAbsolutePath().normalize();
		try {
			if (Files.exists(recordedCommand) && Files.exists(command)) {
				return Files.isSameFile(recordedCommand, command) ? candidate : Optional.empty();
			}
		} catch (java.io.IOException ignored) {
			return Optional.empty();
		}
		return recordedCommand.equals(command) ? candidate : Optional.empty();
	}
}
