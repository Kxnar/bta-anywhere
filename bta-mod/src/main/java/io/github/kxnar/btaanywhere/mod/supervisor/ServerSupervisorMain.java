package io.github.kxnar.btaanywhere.mod.supervisor;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/** A Minecraft-independent child process that keeps server stdin recoverable after a client crash. */
public final class ServerSupervisorMain {
	private static final int MAXIMUM_CONTROL_LINE = 4_096;

	private final Process server;
	private final BufferedWriter serverInput;
	private final ServerSocket controlSocket;
	private final String controlToken;
	private final SupervisorControlFile controlIdentity;
	private final Path controlFile;
	private final Path logFile;
	private final AtomicBoolean ready = new AtomicBoolean();
	private final AtomicBoolean stopRequested = new AtomicBoolean();
	private final AtomicBoolean cleanStop = new AtomicBoolean();
	private final CountDownLatch stopResponseSent = new CountDownLatch(1);

	private ServerSupervisorMain(Process server, ServerSocket controlSocket, String token,
		SupervisorControlFile controlIdentity, Path controlFile, Path logFile) {
		this.server = server;
		this.controlSocket = controlSocket;
		controlToken = token;
		this.controlIdentity = controlIdentity;
		this.controlFile = controlFile;
		this.logFile = logFile;
		serverInput = new BufferedWriter(new OutputStreamWriter(server.getOutputStream(), StandardCharsets.UTF_8));
	}

	public static void main(String[] arguments) {
		int exit = 1;
		try {
			Map<String, String> parsed = parseArguments(arguments);
			Path runtime = requiredPath(parsed, "runtime");
			Path world = requiredPath(parsed, "world");
			Path controlFile = requiredPath(parsed, "control-file");
			Path logFile = requiredPath(parsed, "log-file");
			int memory = Integer.parseInt(required(parsed, "memory"));
			if (memory < 768 || memory > 16_384 || !Files.isDirectory(runtime) || !Files.isDirectory(world)) {
				throw new IllegalArgumentException("invalid supervisor runtime, world, or memory");
			}
			Path relativeWorld = runtime.relativize(world);
			if (relativeWorld.isAbsolute() || !runtime.resolve(relativeWorld).normalize().equals(world)) {
				throw new IllegalArgumentException("world path cannot be represented safely relative to the server runtime");
			}
			Path javaExecutable = currentJavaExecutable();
			ProcessBuilder processBuilder = new ProcessBuilder(
				javaExecutable.toString(), "-Xms512M", "-Xmx" + memory + "M", "-jar",
				"fabric-server-launch.jar", "nogui", "--world", relativeWorld.toString()
			);
			processBuilder.directory(runtime.toFile());
			processBuilder.redirectErrorStream(true);
			Process server = processBuilder.start();
			ServerSocket control = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
			String token = randomToken();
			SupervisorControlFile identity = SupervisorControlFile.forProcesses(token, control.getLocalPort(),
				ProcessHandle.current(), server.toHandle());
			ServerSupervisorMain supervisor = new ServerSupervisorMain(server, control, token, identity,
				controlFile, logFile);
			identity.write(controlFile);
			Runtime.getRuntime().addShutdownHook(new Thread(supervisor::shutdownHook, "bta-anywhere-supervisor-shutdown"));
			exit = supervisor.run();
		} catch (Exception exception) {
			System.err.println("BTA Anywhere supervisor failed: " + safeMessage(exception));
		}
		System.exit(exit);
	}

	private int run() throws IOException, InterruptedException {
		Thread controlThread = new Thread(this::controlLoop, "bta-anywhere-supervisor-control");
		controlThread.setDaemon(true);
		controlThread.start();
		Files.createDirectories(logFile.getParent());
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
			server.getInputStream(), StandardCharsets.UTF_8));
			 BufferedWriter log = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
				 StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
			for (String line; (line = reader.readLine()) != null; ) {
				if (line.contains("Done (") && line.contains(")")) {
					ready.set(true);
				}
				log.write(line);
				log.newLine();
				log.flush();
				System.out.println(line);
			}
		}
		int exit = server.waitFor();
		if (stopRequested.get()) {
			stopResponseSent.await(5, TimeUnit.SECONDS);
		}
		controlSocket.close();
		Files.deleteIfExists(controlFile);
		return cleanStop.get() && exit == 0 ? 0 : exit == 0 ? 2 : exit;
	}

	private void controlLoop() {
		while (!controlSocket.isClosed() && server.isAlive()) {
			try (Socket socket = controlSocket.accept()) {
				socket.setSoTimeout(5_000);
				handleControl(socket);
			} catch (IOException exception) {
				if (!controlSocket.isClosed()) {
					System.err.println("Supervisor control error: " + safeMessage(exception));
				}
			}
		}
	}

	private void handleControl(Socket socket) throws IOException {
		String line = readBoundedLine(socket.getInputStream());
		int separator = line.indexOf(' ');
		if (separator <= 0 || !constantTimeEquals(controlToken, line.substring(0, separator))) {
			writeLine(socket.getOutputStream(), "ERROR unauthorized");
			return;
		}
		String command = line.substring(separator + 1);
		if ("STATUS".equals(command)) {
			writeLine(socket.getOutputStream(), "STATUS running=" + server.isAlive() + " ready=" + ready.get());
		} else if ("STOP".equals(command)) {
			try {
				boolean stopped = stopServer(Duration.ofSeconds(30));
				writeLine(socket.getOutputStream(), stopped ? "STOPPED clean=true" : "STOPPED clean=false");
			} finally {
				if (stopRequested.get()) {
					stopResponseSent.countDown();
				}
			}
		} else {
			writeLine(socket.getOutputStream(), "ERROR unknown-command");
		}
	}

	private boolean stopServer(Duration timeout) throws IOException {
		if (!server.isAlive()) {
			cleanStop.set(server.exitValue() == 0);
			return cleanStop.get();
		}
		if (!matchesCapturedControl()) {
			throw new IOException("recorded supervisor or server identity does not match; refusing STOP");
		}
		if (!stopRequested.compareAndSet(false, true)) {
			return cleanStop.get();
		}
		try {
			synchronized (serverInput) {
				serverInput.write("stop");
				serverInput.newLine();
				serverInput.flush();
			}
			if (server.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
				cleanStop.set(server.exitValue() == 0);
				return cleanStop.get();
			}
			if (!matchesCapturedControl()) {
				return false;
			}
			server.destroy();
			if (!server.waitFor(5, TimeUnit.SECONDS)) {
				if (!matchesCapturedControl()) {
					return false;
				}
				server.destroyForcibly();
				server.waitFor(5, TimeUnit.SECONDS);
			}
		} catch (IOException exception) {
			System.err.println("Could not complete authenticated server stop: " + safeMessage(exception));
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
		return false;
	}

	private boolean matchesCapturedControl() throws IOException {
		return controlIdentity.matchesSupervisor(ProcessHandle.current())
			&& controlIdentity.matchesServer(server.toHandle())
			&& controlIdentity.equals(SupervisorControlFile.read(controlFile));
	}

	private void shutdownHook() {
		if (server.isAlive()) {
			System.err.println("Supervisor exit left the managed server running; retain recovery files for manual inspection");
			return;
		}
		try {
			Files.deleteIfExists(controlFile);
		} catch (IOException ignored) {
			// Stale control files are validated against PIDs and process start times.
		}
	}

	private static Map<String, String> parseArguments(String[] arguments) {
		if (arguments.length % 2 != 0) {
			throw new IllegalArgumentException("supervisor arguments must be --key value pairs");
		}
		Map<String, String> result = new HashMap<>();
		for (int index = 0; index < arguments.length; index += 2) {
			String key = arguments[index];
			if (!key.startsWith("--") || key.length() == 2 || result.put(key.substring(2), arguments[index + 1]) != null) {
				throw new IllegalArgumentException("invalid or duplicate supervisor argument: " + key);
			}
		}
		return result;
	}

	private static Path requiredPath(Map<String, String> values, String key) {
		return Path.of(required(values, key)).toAbsolutePath().normalize();
	}

	private static String required(Map<String, String> values, String key) {
		String value = values.get(key);
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("missing --" + key);
		}
		return value;
	}

	private static Path currentJavaExecutable() throws IOException {
		return ProcessHandle.current().info().command().map(Path::of).map(Path::toAbsolutePath).map(Path::normalize)
			.filter(Files::isRegularFile)
			.orElseThrow(() -> new IOException("current JVM executable is unavailable"));
	}

	private static String randomToken() {
		byte[] bytes = new byte[32];
		new SecureRandom().nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static boolean constantTimeEquals(String expected, String actual) {
		return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
			actual.getBytes(StandardCharsets.UTF_8));
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
			throw new IOException("control request exceeded the line limit");
		}
		return new String(bytes, 0, length, StandardCharsets.UTF_8);
	}

	private static void writeLine(OutputStream output, String line) throws IOException {
		output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
		output.flush();
	}

	private static String safeMessage(Throwable throwable) {
		String message = throwable.getMessage();
		return message == null ? throwable.getClass().getSimpleName() : message;
	}

}
