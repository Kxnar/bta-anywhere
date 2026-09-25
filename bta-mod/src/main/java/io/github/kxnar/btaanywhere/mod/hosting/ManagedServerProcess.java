package io.github.kxnar.btaanywhere.mod.hosting;

import io.github.kxnar.btaanywhere.mod.supervisor.ServerSupervisorMain;
import io.github.kxnar.btaanywhere.mod.supervisor.SupervisorControlFile;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

public final class ManagedServerProcess implements AutoCloseable {
	private final Process process;
	private final SupervisorControlFile control;
	private final Path controlFile;
	private final CompletableFuture<Void> outputClosed = new CompletableFuture<>();
	private final AtomicBoolean readinessMessage = new AtomicBoolean();
	private final Consumer<String> logConsumer;
	private final Path logFile;

	private ManagedServerProcess(Process process, SupervisorControlFile control, Path controlFile, Path logFile,
		Consumer<String> logConsumer) {
		this.process = process;
		this.control = control;
		this.controlFile = controlFile;
		this.logFile = logFile;
		this.logConsumer = logConsumer;
		Thread outputThread = new Thread(this::captureOutput, "bta-anywhere-server-output");
		outputThread.setDaemon(true);
		outputThread.start();
	}

	public static ManagedServerProcess start(
		Path runtime,
		Path activeWorld,
		HostOptions options,
		Path logFile,
		Consumer<String> logConsumer
	) throws IOException {
		Objects.requireNonNull(options, "options");
		Path serverRoot = runtime.toAbsolutePath().normalize();
		Path world = activeWorld.toAbsolutePath().normalize();
		if (!Files.isDirectory(serverRoot) || !Files.isRegularFile(serverRoot.resolve("fabric-server-launch.jar"))) {
			throw new IOException("managed BTA server runtime is incomplete: " + serverRoot);
		}
		if (!Files.isDirectory(world)) {
			throw new IOException("active world directory is missing: " + world);
		}
		Path relativeWorld = serverRoot.relativize(world);
		if (relativeWorld.isAbsolute() || !serverRoot.resolve(relativeWorld).normalize().equals(world)) {
			throw new IOException("could not create a validated relative world path");
		}
		Path javaExecutable = currentJavaExecutable();
		Path resolvedLog = logFile.toAbsolutePath().normalize();
		Files.createDirectories(resolvedLog.getParent());
		Path controlFile = resolvedLog.getParent().resolve("supervisor-" + UUID.randomUUID() + ".properties");
		List<String> command = List.of(
			javaExecutable.toString(),
			"-cp",
			supervisorClassPath().toString(),
			ServerSupervisorMain.class.getName(),
			"--runtime", serverRoot.toString(),
			"--world", world.toString(),
			"--memory", Integer.toString(options.memoryMiB()),
			"--control-file", controlFile.toString(),
			"--log-file", resolvedLog.toString()
		);
		ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command));
		builder.directory(serverRoot.toFile());
		builder.redirectErrorStream(true);
		Process process = builder.start();
		try {
			SupervisorControlFile control = awaitControlFile(process, controlFile, Duration.ofSeconds(15));
			return new ManagedServerProcess(process, control, controlFile, resolvedLog, logConsumer);
		} catch (IOException | InterruptedException exception) {
			if (exception instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			throw new IOException("could not verify the new server supervisor; its process and control file were retained for manual inspection before reopening the world", exception);
		}
	}

	public void awaitReady(int port, Duration timeout, BooleanSupplier cancelled)
		throws IOException, InterruptedException {
		Objects.requireNonNull(cancelled, "cancelled");
		long deadline = System.nanoTime() + timeout.toNanos();
		while (System.nanoTime() < deadline) {
			if (cancelled.getAsBoolean()) {
				throw new InterruptedException("server readiness wait was cancelled");
			}
			if (!process.isAlive()) {
				throw new IOException("dedicated server supervisor exited before readiness (exit "
					+ process.exitValue() + ")");
			}
			if (readinessMessage.get() && tcpProbe(port)) {
				return;
			}
			Thread.sleep(250L);
		}
		throw new IOException("dedicated server did not become ready within " + timeout.toSeconds() + " seconds");
	}

	public void awaitReady(int port, Duration timeout) throws IOException, InterruptedException {
		awaitReady(port, timeout, () -> false);
	}

	public boolean stopGracefully(Duration timeout) throws IOException, InterruptedException {
		if (!process.isAlive()) {
			if (ProcessHandle.of(control.serverPid()).filter(control::matchesServer).isPresent()) {
				throw new IOException("supervisor exited but the recorded server is still alive; recovery files were retained");
			}
			return process.exitValue() == 0;
		}
		SupervisorControlFile recorded = SupervisorControlFile.read(controlFile);
		if (!control.equals(recorded) || !control.matchesSupervisor(process.toHandle())
			|| ProcessHandle.of(control.serverPid()).filter(control::matchesServer).isEmpty()) {
			throw new IOException("supervisor or server identity does not match its control file; refusing STOP and retaining recovery files");
		}
		String response;
		try {
			long requestTimeout = Math.min(Integer.MAX_VALUE, timeout.toMillis() + 15_000L);
			response = control.request("STOP", Math.toIntExact(requestTimeout));
		} catch (IOException exception) {
			throw new IOException("authenticated supervisor STOP failed; process and recovery files were retained", exception);
		}
		if (process.waitFor(5, TimeUnit.SECONDS)) {
			awaitOutputClosed();
			boolean childStopped = ProcessHandle.of(control.serverPid()).filter(control::matchesServer).isEmpty();
			boolean clean = childStopped && response.equals("STOPPED clean=true") && process.exitValue() == 0;
			if (clean) {
				Files.deleteIfExists(controlFile);
			}
			return clean;
		}
		throw new IOException("supervisor did not exit after authenticated STOP; process and recovery files were retained");
	}

	public Process process() {
		return process;
	}

	public Path logFile() {
		return logFile;
	}

	public Path controlFile() {
		return controlFile;
	}

	public SupervisorControlFile control() {
		return control;
	}

	private void captureOutput() {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
			process.getInputStream(), StandardCharsets.UTF_8))) {
			for (String line; (line = reader.readLine()) != null; ) {
				if (line.contains("Done (") && line.contains(")")) {
					readinessMessage.set(true);
				}
				logConsumer.accept(line);
			}
			outputClosed.complete(null);
		} catch (IOException exception) {
			outputClosed.completeExceptionally(exception);
			logConsumer.accept("Server log capture failed: " + exception.getMessage());
		}
	}

	private void awaitOutputClosed() throws IOException, InterruptedException {
		try {
			outputClosed.get(5, TimeUnit.SECONDS);
		} catch (java.util.concurrent.ExecutionException exception) {
			throw new IOException("server output capture failed during shutdown", exception.getCause());
		} catch (java.util.concurrent.TimeoutException exception) {
			throw new IOException("server output capture did not close after shutdown", exception);
		}
	}

	private static boolean tcpProbe(int port) {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
			return true;
		} catch (IOException exception) {
			return false;
		}
	}

	private static Path currentJavaExecutable() throws IOException {
		String command = ProcessHandle.current().info().command()
			.orElseThrow(() -> new IOException("current JVM executable path is unavailable"));
		Path executable = Path.of(command).toAbsolutePath().normalize();
		if (!Files.isRegularFile(executable)) {
			throw new IOException("current JVM executable does not exist: " + executable);
		}
		return executable;
	}

	private static Path supervisorClassPath() throws IOException {
		try {
			Path location = Path.of(ServerSupervisorMain.class.getProtectionDomain().getCodeSource()
				.getLocation().toURI()).toAbsolutePath().normalize();
			if (!Files.exists(location)) {
				throw new IOException("supervisor class path does not exist: " + location);
			}
			return location;
		} catch (java.net.URISyntaxException | NullPointerException exception) {
			throw new IOException("could not locate the BTA Anywhere supervisor classes", exception);
		}
	}

	private static SupervisorControlFile awaitControlFile(Process process, Path controlFile, Duration timeout)
		throws IOException, InterruptedException {
		long deadline = System.nanoTime() + timeout.toNanos();
		IOException lastFailure = null;
		while (System.nanoTime() < deadline) {
			if (!process.isAlive()) {
				throw new IOException("server supervisor exited during initialization (exit " + process.exitValue() + ")",
					lastFailure);
			}
			if (Files.isRegularFile(controlFile)) {
				try {
					SupervisorControlFile control = SupervisorControlFile.read(controlFile);
					if (!control.matchesSupervisor(process.toHandle())) {
						throw new IOException("supervisor control identity did not match its process");
					}
					if (ProcessHandle.of(control.serverPid()).filter(control::matchesServer).isEmpty()) {
						throw new IOException("server control identity did not match a live process");
					}
					return control;
				} catch (IOException exception) {
					lastFailure = exception;
				}
			}
			Thread.sleep(50L);
		}
		throw new IOException("server supervisor did not create its control file", lastFailure);
	}

	@Override
	public void close() {
		if (process.isAlive()) {
			try {
				stopGracefully(Duration.ofSeconds(30));
			} catch (IOException exception) {
				logConsumer.accept("Could not stop server cleanly: " + exception.getMessage());
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
		}
	}
}
