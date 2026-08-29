package io.github.kxnar.btaanywhere.mod.supervisor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Properties;

public record SupervisorControlFile(
	int schemaVersion,
	String token,
	int controlPort,
	long supervisorPid,
	String supervisorStartTime,
	String supervisorExecutable,
	long serverPid,
	String serverStartTime,
	String serverExecutable
) {
	public static final int SCHEMA_VERSION = 1;
	private static final int MAXIMUM_CONTROL_LINE = 4_096;

	public SupervisorControlFile {
		Objects.requireNonNull(token, "token");
		Objects.requireNonNull(supervisorStartTime, "supervisorStartTime");
		Objects.requireNonNull(supervisorExecutable, "supervisorExecutable");
		Objects.requireNonNull(serverStartTime, "serverStartTime");
		Objects.requireNonNull(serverExecutable, "serverExecutable");
		if (schemaVersion != SCHEMA_VERSION || token.isBlank() || controlPort < 1 || controlPort > 65_535
			|| supervisorPid <= 0 || serverPid <= 0) {
			throw new IllegalArgumentException("invalid supervisor control data");
		}
	}

	public static SupervisorControlFile forProcesses(String token, int port, ProcessHandle supervisor,
		ProcessHandle server) throws IOException {
		return new SupervisorControlFile(
			SCHEMA_VERSION,
			token,
			port,
			supervisor.pid(),
			requireStart(supervisor, "supervisor"),
			requireCommand(supervisor, "supervisor"),
			server.pid(),
			requireStart(server, "server"),
			requireCommand(server, "server")
		);
	}

	public static SupervisorControlFile read(Path file) throws IOException {
		Properties properties = new Properties();
		try (InputStream input = Files.newInputStream(file)) {
			properties.load(input);
		}
		try {
			return new SupervisorControlFile(
				Integer.parseInt(properties.getProperty("schemaVersion", "0")),
				properties.getProperty("token", ""),
				Integer.parseInt(properties.getProperty("controlPort", "0")),
				Long.parseLong(properties.getProperty("supervisorPid", "-1")),
				properties.getProperty("supervisorStartTime", ""),
				properties.getProperty("supervisorExecutable", ""),
				Long.parseLong(properties.getProperty("serverPid", "-1")),
				properties.getProperty("serverStartTime", ""),
				properties.getProperty("serverExecutable", "")
			);
		} catch (IllegalArgumentException exception) {
			throw new IOException("malformed supervisor control file", exception);
		}
	}

	public void write(Path file) throws IOException {
		Properties properties = new Properties();
		properties.setProperty("schemaVersion", Integer.toString(schemaVersion));
		properties.setProperty("token", token);
		properties.setProperty("controlPort", Integer.toString(controlPort));
		properties.setProperty("supervisorPid", Long.toString(supervisorPid));
		properties.setProperty("supervisorStartTime", supervisorStartTime);
		properties.setProperty("supervisorExecutable", supervisorExecutable);
		properties.setProperty("serverPid", Long.toString(serverPid));
		properties.setProperty("serverStartTime", serverStartTime);
		properties.setProperty("serverExecutable", serverExecutable);
		Files.createDirectories(file.toAbsolutePath().normalize().getParent());
		Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
		try (OutputStream output = Files.newOutputStream(temporary)) {
			properties.store(output, "Private BTA Anywhere supervisor control data");
		}
		try {
			Files.setPosixFilePermissions(temporary,
				EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
		} catch (UnsupportedOperationException ignored) {
			// Windows inherits the game directory's user ACL.
		}
		try {
			Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (java.nio.file.AtomicMoveNotSupportedException exception) {
			Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	public String request(String command, int timeoutMillis) throws IOException {
		if (!("STATUS".equals(command) || "STOP".equals(command))) {
			throw new IllegalArgumentException("unsupported supervisor command");
		}
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress("127.0.0.1", controlPort), timeoutMillis);
			socket.setSoTimeout(timeoutMillis);
			OutputStream output = socket.getOutputStream();
			output.write((token + " " + command + "\n").getBytes(StandardCharsets.UTF_8));
			output.flush();
			return readBoundedLine(socket.getInputStream());
		}
	}

	public boolean matchesSupervisor(ProcessHandle handle) {
		return matches(handle, supervisorPid, supervisorStartTime, supervisorExecutable);
	}

	public boolean matchesServer(ProcessHandle handle) {
		return matches(handle, serverPid, serverStartTime, serverExecutable);
	}

	private static boolean matches(ProcessHandle handle, long pid, String start, String executable) {
		if (handle.pid() != pid || !handle.isAlive()) {
			return false;
		}
		try {
			Instant expectedStart = Instant.parse(start);
			if (handle.info().startInstant().filter(expectedStart::equals).isEmpty()) {
				return false;
			}
			Path expected = Path.of(executable).toAbsolutePath().normalize();
			Path actual = handle.info().command().map(Path::of).map(Path::toAbsolutePath)
				.map(Path::normalize).orElse(null);
			if (actual == null) {
				return false;
			}
			return Files.exists(expected) && Files.exists(actual)
				? Files.isSameFile(expected, actual) : expected.equals(actual);
		} catch (IOException | RuntimeException exception) {
			return false;
		}
	}

	private static String requireStart(ProcessHandle process, String label) throws IOException {
		return process.info().startInstant().map(Instant::toString)
			.orElseThrow(() -> new IOException(label + " process start time is unavailable"));
	}

	private static String requireCommand(ProcessHandle process, String label) throws IOException {
		return process.info().command()
			.orElseThrow(() -> new IOException(label + " process executable is unavailable"));
	}

	private static String readBoundedLine(InputStream input) throws IOException {
		byte[] bytes = new byte[MAXIMUM_CONTROL_LINE];
		int length = 0;
		while (length < bytes.length) {
			int value = input.read();
			if (value < 0 || value == '\n') {
				break;
			}
			if (value != '\r') {
				bytes[length++] = (byte) value;
			}
		}
		if (length == bytes.length) {
			throw new IOException("supervisor response exceeded the line limit");
		}
		return new String(bytes, 0, length, StandardCharsets.UTF_8);
	}

	@Override
	public String toString() {
		return "SupervisorControlFile[controlPort=" + controlPort + ", supervisorPid=" + supervisorPid
			+ ", serverPid=" + serverPid + ", token=[REDACTED]]";
	}
}
