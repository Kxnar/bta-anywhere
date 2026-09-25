package io.github.kxnar.btaanywhere.mod.hosting;

import io.github.kxnar.btaanywhere.DefaultPortMappingService;
import io.github.kxnar.btaanywhere.PortMapping;
import io.github.kxnar.btaanywhere.PortMappingRequest;
import io.github.kxnar.btaanywhere.PortMappingService;
import io.github.kxnar.btaanywhere.RelayDescriptor;
import io.github.kxnar.btaanywhere.Secret;
import io.github.kxnar.btaanywhere.TunnelClient;
import io.github.kxnar.btaanywhere.TunnelConfig;
import io.github.kxnar.btaanywhere.TunnelEvent;
import io.github.kxnar.btaanywhere.TunnelSession;
import io.github.kxnar.btaanywhere.mod.config.BtaAnywhereConfig;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class HostController implements AutoCloseable {
	private static final int MAXIMUM_LOG_LINES = 250;
	private static final DateTimeFormatter LOG_TIMESTAMP =
		DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss").withZone(ZoneOffset.UTC);

	private final Path gameDirectory;
	private final Path managedDirectory;
	private final WorldBackupService backups;
	private final HostingFaults faults;
	private final ServerDistributionManager distributions;
	private final ModMirrorService modMirror = new ModMirrorService();
	private final RecoveryJournal journal;
	private final RecoveryService recoveryService;
	private final ExecutorService executor;
	private final GameDirectoryLease ownedLease;
	private final AtomicReference<HostStatus> status = new AtomicReference<>(HostStatus.idle());
	private final AtomicBoolean cancelRequested = new AtomicBoolean();
	private final AtomicBoolean closed = new AtomicBoolean();
	private final Object leaseActionLock = new Object();
	private final Object resourceLock = new Object();
	private final Deque<String> logs = new ArrayDeque<>();

	private volatile PendingStart pending;
	private volatile ManagedServerProcess server;
	private volatile PortMappingService mappingService;
	private volatile PortMapping portMapping;
	private volatile TunnelClient tunnelClient;
	private volatile TunnelSession tunnelSession;
	private volatile Secret relaySecret;
	private volatile Path activeWorld;
	private volatile Path backupFile;
	private volatile Path consoleLog;
	private volatile boolean showcase;
	private volatile boolean ownsJournal;
	/** Set before a launch attempt; a failed start may leave an untracked live server. */
	private volatile boolean supervisorLaunchAttempted;
	private volatile RecoveryInspection adoptedRecovery;
	private final AtomicBoolean hostWorldObserved = new AtomicBoolean();
	private volatile CompletableFuture<Void> pipeline = CompletableFuture.completedFuture(null);
	private volatile CompletableFuture<Boolean> stopFuture = CompletableFuture.completedFuture(true);

	HostController(Path gameDirectory) {
		this(gameDirectory, HostingFaults.NONE);
	}

	/** Opens a production controller that owns the client-lifetime game-directory lease. */
	public static HostController open(Path gameDirectory) throws IOException {
		return open(gameDirectory, HostingFaults.NONE);
	}

	static HostController open(Path gameDirectory, HostingFaults faults) throws IOException {
		GameDirectoryLease lease = GameDirectoryLease.acquire(gameDirectory);
		try {
			return new HostController(gameDirectory, faults, lease);
		} catch (RuntimeException | Error failure) {
			try {
				lease.close();
			} catch (IOException closeFailure) {
				failure.addSuppressed(closeFailure);
			}
			throw failure;
		}
	}

	HostController(Path gameDirectory, HostingFaults faults) {
		this(gameDirectory, faults, null);
	}

	private HostController(Path gameDirectory, HostingFaults faults, GameDirectoryLease lease) {
		this.gameDirectory = Objects.requireNonNull(gameDirectory, "gameDirectory").toAbsolutePath().normalize();
		this.faults = Objects.requireNonNull(faults, "faults");
		if (lease != null && !lease.covers(this.gameDirectory)) {
			throw new IllegalArgumentException("the BTA Anywhere game directory lease does not cover this profile");
		}
		ownedLease = lease;
		backups = new WorldBackupService(java.time.Clock.systemUTC(), faults);
		managedDirectory = this.gameDirectory.resolve("bta-anywhere");
		distributions = new ServerDistributionManager(managedDirectory);
		journal = new RecoveryJournal(managedDirectory, faults);
		recoveryService = new RecoveryService(this.gameDirectory);
		executor = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "bta-anywhere-hosting");
			thread.setDaemon(true);
			return thread;
		});
	}

	public synchronized CompletionStage<Void> requestStart(
		WorldContext world,
		HostOptions options,
		BtaAnywhereConfig config,
		boolean downloadConfirmed
	) {
		if (closed.get()) {
			return CompletableFuture.failedFuture(new IllegalStateException("the hosting controller is closed"));
		}
		Objects.requireNonNull(world, "world");
		Objects.requireNonNull(options, "options");
		Objects.requireNonNull(config, "config");
		HostState current = status.get().state();
		if (current != HostState.IDLE && current != HostState.FAILED) {
			return CompletableFuture.failedFuture(new IllegalStateException("hosting is already active"));
		}
		if (!world.gameDirectory().equals(gameDirectory)) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("world belongs to a different game directory"));
		}
		cancelRequested.set(false);
		ownsJournal = false;
		supervisorLaunchAttempted = false;
		hostWorldObserved.set(false);
		clearLog();
		PendingStart requested = new PendingStart(world, options, config, downloadConfirmed, null);
		pending = requested;
		setState(HostState.VALIDATING, "Validating world, disk space, port, and server files", "");
		pipeline = CompletableFuture.runAsync(() -> validate(requested), executor);
		return pipeline;
	}

	public synchronized CompletionStage<Void> continueAfterWorldClosed() {
		if (closed.get()) {
			return CompletableFuture.failedFuture(new IllegalStateException("the hosting controller is closed"));
		}
		PendingStart requested = pending;
		if (requested == null || status.get().state() != HostState.SAVING) {
			return CompletableFuture.failedFuture(new IllegalStateException("hosting is not waiting for a world save"));
		}
		if (requested.continued().compareAndSet(false, true)) {
			pipeline = CompletableFuture.runAsync(() -> startAfterSave(requested), executor);
		}
		return pipeline;
	}

	public void beforeWorldSave() {
		faults.hit(HostingFaults.Point.BEFORE_WORLD_SAVE);
	}

	public boolean hasRecoveryArtifacts() {
		return Files.exists(journal.file(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
			|| Files.exists(journal.file().resolveSibling("recovery.json.tmp"),
				java.nio.file.LinkOption.NOFOLLOW_LINKS);
	}

	public void markHostConnectionStarted() {
		if (status.get().state() == HostState.CONNECTING) {
			setState(HostState.HOSTING, "Hosting is active", status.get().connectionAddress());
		}
	}

	public synchronized CompletionStage<Boolean> stop() {
		HostState current = status.get().state();
		if (current == HostState.IDLE) {
			return CompletableFuture.completedFuture(true);
		}
		if (current == HostState.STOPPING) {
			return stopFuture;
		}
		cancelRequested.set(true);
		setState(HostState.STOPPING, "Stopping exposure and dedicated server", status.get().connectionAddress());
		stopFuture = CompletableFuture.supplyAsync(this::stopInternal, executor);
		return stopFuture;
	}

	public HostStatus status() {
		HostStatus current = status.get();
		return new HostStatus(current.state(), current.message(), current.connectionAddress(), snapshotLogs());
	}

	/** True only while a production controller still holds its client game-directory lock. */
	public boolean hasActiveGameDirectoryLease() {
		return !closed.get() && ownedLease != null && ownedLease.covers(gameDirectory);
	}

	/** Serializes recovery actions with close so they cannot outlive this controller's lease. */
	public <T> T withActiveGameDirectoryLease(java.util.concurrent.Callable<T> action) throws Exception {
		Objects.requireNonNull(action, "action");
		synchronized (leaseActionLock) {
			if (!hasActiveGameDirectoryLease()) {
				throw new IllegalStateException("the hosting controller no longer owns this game directory");
			}
			return action.call();
		}
	}

	public Optional<RecoveryJournal.Entry> recovery() {
		try {
			return journal.read();
		} catch (IOException exception) {
			appendLog("Could not read recovery journal: " + exception.getMessage());
			return Optional.empty();
		}
	}

	public Path managedDirectory() {
		return managedDirectory;
	}

	public boolean isServerDistributionInstalled() {
		return distributions.isInstalled(distributions.runtimeDirectory());
	}

	public Optional<String> originalWorldDirectoryName() {
		PendingStart requested = pending;
		if (requested != null) {
			return Optional.of(requested.world().worldDirectoryName());
		}
		RecoveryInspection recovered = adoptedRecovery;
		return recovered == null ? Optional.empty() : Optional.of(recovered.entry().worldDirectoryName());
	}

	/** The original Live save that must not be opened during an in-process handoff. */
	public Optional<Path> liveOriginalNeedingGuard() {
		RecoveryInspection recovered = adoptedRecovery;
		if (recovered != null && recovered.entry().worldMode() == WorldMode.LIVE) {
			return Optional.of(Path.of(recovered.entry().originalSavePath()));
		}
		PendingStart requested = pending;
		if (requested == null || requested.options().worldMode() != WorldMode.LIVE) {
			return Optional.empty();
		}
		if (status.get().state() == HostState.FAILED && !ownsJournal) {
			ManagedServerProcess running = server;
			if (running == null || !running.process().isAlive()) {
				return Optional.empty();
			}
		}
		return Optional.of(requested.world().worldDirectory());
	}

	public boolean canReopenOriginalWorld() {
		try {
			Optional<RecoveryInspection> inspection = recoveryService.inspect();
			if (inspection.isPresent()) {
				return inspection.get().canOpenOriginal();
			}
			if (ownsJournal || adoptedRecovery != null) {
				return false;
			}
		} catch (IOException | RuntimeException exception) {
			appendLog("Could not verify whether the managed server still owns the save: " + exception.getMessage());
			return false;
		}
		ManagedServerProcess running = server;
		return pending != null && (running == null || !running.process().isAlive());
	}

	public synchronized void adoptRecoveredSession(RecoveryInspection inspection) {
		if (closed.get()) {
			throw new IllegalStateException("the hosting controller is closed");
		}
		Objects.requireNonNull(inspection, "inspection");
		if (!inspection.canReconnect() || status.get().state() != HostState.IDLE) {
			throw new IllegalStateException("recovered server is not ready to reconnect");
		}
		adoptedRecovery = inspection;
		hostWorldObserved.set(false);
		status.set(new HostStatus(
			HostState.HOSTING,
			"Recovered managed server; relay and automatic mapping must be re-established if needed",
			"127.0.0.1:" + inspection.entry().localPort(),
			List.of()
		));
	}

	public Optional<Integer> localPort() {
		PendingStart requested = pending;
		return requested == null ? Optional.empty() : Optional.of(requested.options().port());
	}

	public void reportWorldHandoffFailure(Throwable failure) {
		Objects.requireNonNull(failure, "failure");
		if (status.get().state() == HostState.SAVING) {
			fail("BTA could not save and close the world: " + rootMessage(failure));
		}
	}

	public void observeConnectedWorld(boolean multiplayerWorldPresent, boolean connectionFailedScreen) {
		if (status.get().state() != HostState.HOSTING) {
			return;
		}
		if (multiplayerWorldPresent) {
			hostWorldObserved.set(true);
			return;
		}
		if (hostWorldObserved.get() || connectionFailedScreen) {
			appendLog(connectionFailedScreen
				? "Host connection failed; stopping managed server"
				: "Host left the managed multiplayer world; stopping managed server");
			stop();
		}
	}

	private void validate(PendingStart requested) {
		try {
			Path savesRoot = gameDirectory.resolve("saves").normalize();
			Path world = requested.world().worldDirectory();
			if (!world.startsWith(savesRoot) || world.equals(savesRoot) || !Files.isDirectory(world)) {
				throw new IOException("selected world is not a valid save beneath " + savesRoot);
			}
			if (journal.read().isPresent()) {
				throw new IOException("an earlier hosting recovery journal exists; resolve it before hosting again");
			}
			if (requested.options().networkMode() == NetworkMode.RELAY
				&& !requested.config().relayConfigured(gameDirectory)) {
				throw new IOException("relay mode requires a hostname, trusted certificate, and access token in "
					+ gameDirectory.resolve("config/bta-anywhere.json"));
			}
			backups.validateSpace(world, managedDirectory);
			validatePortAvailable(requested.options());
			Path runtime = distributions.ensureInstalled(requested.downloadConfirmed());
			pending = requested.withRuntime(runtime);
			checkNotCancelled();
			setState(HostState.SAVING, "Validation complete; saving and closing the world", "");
		} catch (ConfirmationRequiredException exception) {
			fail(exception.getMessage());
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			fail("validation was interrupted");
		} catch (Exception exception) {
			fail(safeMessage("validation failed", exception));
		}
	}

	private void startAfterSave(PendingStart originalRequest) {
		PendingStart requested = pending;
		if (requested == null || requested.runtime() == null) {
			fail("hosting preparation was lost before the world closed");
			return;
		}
		try {
			faults.hit(HostingFaults.Point.AFTER_WORLD_CLOSE);
			checkNotCancelled();
			if (requested.options().worldMode() == WorldMode.LIVE) {
				setState(HostState.BACKING_UP, "Creating an atomic safety backup", "");
				BackupResult backup = backups.createBackup(requested.world().worldDirectory(), managedDirectory, 5);
				backupFile = backup.backupFile();
				activeWorld = requested.world().worldDirectory();
				showcase = false;
				appendLog("Backup: " + backup.backupFile());
			} else {
				setState(HostState.COPYING, "Creating an isolated showcase copy", "");
				activeWorld = backups.createShowcaseCopy(requested.world().worldDirectory(), managedDirectory);
				backupFile = null;
				showcase = true;
				appendLog("Showcase copy: " + activeWorld);
			}
			checkNotCancelled();

			consoleLog = managedDirectory.resolve("logs")
				.resolve("server-" + LOG_TIMESTAMP.format(java.time.Instant.now()) + ".log");
			RecoveryJournal.Entry journalEntry = RecoveryJournal.Entry.prepared(
				requested.options().worldMode(), requested.options().networkMode(),
				requested.world().worldDirectory(), activeWorld,
				requested.world().worldDirectoryName(), backupFile,
				requested.options().port(), requested.runtime(), consoleLog
			).withLaunchIntent();
			journal.write(journalEntry);
			ownsJournal = true;

			setState(HostState.STARTING_SERVER, "Preparing mods and starting the BTA server", "");
			ModMirrorResult mirror = modMirror.mirror(gameDirectory, requested.runtime());
			appendLog("Mirrored server-compatible mods: " + String.join(", ", mirror.copiedModIds()));
			ServerConfigurationWriter.write(requested.runtime(), requested.options());
			checkNotCancelled();
			faults.hit(HostingFaults.Point.BEFORE_SUPERVISOR_LAUNCH);
			supervisorLaunchAttempted = true;
			ManagedServerProcess launched = ManagedServerProcess.start(
				requested.runtime(), activeWorld, requested.options(), consoleLog, this::appendLog, faults
			);
			synchronized (resourceLock) {
				server = launched;
			}
			faults.hit(HostingFaults.Point.AFTER_SUPERVISOR_LAUNCH);
			journal.write(journalEntry.withProcess(launched));
			faults.hit(HostingFaults.Point.AFTER_SUPERVISOR_IDENTITY_RECORDED);
			launched.process().onExit().thenRunAsync(() -> serverExited(launched), executor);
			checkNotCancelled();

			setState(HostState.WAITING_READY, "Waiting for BTA readiness and a localhost TCP probe", "");
			faults.hit(HostingFaults.Point.BEFORE_READINESS);
			launched.awaitReady(requested.options().port(), Duration.ofSeconds(180), cancelRequested::get);
			checkNotCancelled();
			setState(HostState.EXPOSING, "Exposing the local BTA server", "");
			faults.hit(HostingFaults.Point.BEFORE_NETWORK_EXPOSURE);
			String address = expose(requested);
			faults.hit(HostingFaults.Point.AFTER_NETWORK_EXPOSURE);
			checkNotCancelled();
			setState(HostState.CONNECTING, "Server ready; reconnecting the host through 127.0.0.1", address);
		} catch (CancelledStartException ignored) {
			// stop() owns cleanup and the terminal state.
		} catch (InterruptedException exception) {
			if (!cancelRequested.get()) {
				Thread.currentThread().interrupt();
				failAfterWorldClosed("hosting startup was interrupted");
			}
		} catch (Exception exception) {
			deleteUnjournaledShowcase();
			failAfterWorldClosed(safeMessage("hosting startup failed", exception));
		}
	}

	private void deleteUnjournaledShowcase() {
		if (ownsJournal || !showcase || activeWorld == null) {
			return;
		}
		try {
			backups.deleteCleanShowcase(activeWorld, managedDirectory);
			appendLog("Deleted unjournaled showcase copy after startup failure");
			activeWorld = null;
			showcase = false;
		} catch (IOException exception) {
			appendLog("Could not delete unjournaled showcase copy: " + exception.getMessage());
		}
	}

	private String expose(PendingStart requested) throws Exception {
		int localPort = requested.options().port();
		return switch (requested.options().networkMode()) {
			case LAN -> new io.github.kxnar.btaanywhere.PublicEndpoint(localAddress(), localPort).toString();
			case DIRECT -> exposeDirect(localPort);
			case RELAY -> exposeRelay(requested.config(), localPort);
		};
	}

	private String exposeDirect(int port) throws CancelledStartException {
		DefaultPortMappingService service = new DefaultPortMappingService();
		mappingService = service;
		try {
			PortMapping mapping = awaitCancellable(
				service.open(PortMappingRequest.oneHour(port)).toCompletableFuture(), Duration.ofSeconds(45)
			);
			portMapping = mapping;
			appendLog("Automatic router mapping active via " + mapping.protocol());
			return mapping.endpoint().toString();
		} catch (CancelledStartException exception) {
			service.close();
			mappingService = null;
			throw exception;
		} catch (Exception exception) {
			appendLog("Automatic PCP/NAT-PMP/UPnP mapping failed: " + rootMessage(exception));
			appendLog("Forward TCP port " + port + " to this computer, then share <your-public-ip>:" + port);
			service.close();
			mappingService = null;
			return "<your-public-ip>:" + port;
		}
	}

	private String exposeRelay(BtaAnywhereConfig config, int localPort) throws Exception {
		Secret secret = Secret.of(config.relayAccessToken);
		TunnelClient client = new TunnelClient();
		relaySecret = secret;
		tunnelClient = client;
		RelayDescriptor relay = new RelayDescriptor(
			config.relayHost,
			config.relayPort,
			config.relayCertificate(gameDirectory),
			secret
		);
		TunnelSession session = awaitCancellable(client.open(
			TunnelConfig.defaults(relay, config.clientInstanceId),
			new InetSocketAddress("127.0.0.1", localPort)
		).toCompletableFuture(), Duration.ofSeconds(45));
		tunnelSession = session;
		session.events().subscribe(new TunnelEventSubscriber());
		return session.endpoint().toString();
	}

	private boolean stopInternal() {
		RecoveryInspection recovered = adoptedRecovery;
		if (recovered != null) {
			boolean recoveredClean;
			try {
				recoveredClean = recoveryService.gracefulStop(recovered);
			} catch (IOException exception) {
				recoveredClean = false;
				appendLog("Recovered server stop failed: " + exception.getMessage());
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				recoveredClean = false;
			}
			if (recoveredClean) {
				adoptedRecovery = null;
				hostWorldObserved.set(false);
				setState(HostState.IDLE, "Recovered server stopped cleanly", "");
			} else {
				setState(HostState.FAILED, "Recovered server could not be stopped cleanly; recovery data was retained", "");
			}
			return recoveredClean;
		}
		boolean clean = true;
		closeExposure();
		ManagedServerProcess running;
		synchronized (resourceLock) {
			running = server;
		}
		if (running == null && ownsJournal && supervisorLaunchAttempted) {
			clean = false;
			appendLog("Supervisor launch may have started without a verified process identity; recovery files were retained for manual inspection");
		}
		if (running != null) {
			try {
				faults.hit(HostingFaults.Point.DURING_GRACEFUL_STOP);
				clean = running.stopGracefully(Duration.ofSeconds(30));
			} catch (IOException | RuntimeException exception) {
				clean = false;
				appendLog("Server stop failed: " + exception.getMessage());
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				clean = false;
			}
		}
		synchronized (resourceLock) {
			if (running == null || !running.process().isAlive()) {
				server = null;
			}
		}
		if (clean) {
			try {
				if (showcase && activeWorld != null) {
					faults.hit(HostingFaults.Point.BEFORE_SHOWCASE_CLEANUP);
					backups.deleteCleanShowcase(activeWorld, managedDirectory);
					appendLog("Deleted clean showcase copy");
				}
				if (ownsJournal) {
					journal.clear();
				}
			} catch (IOException | RuntimeException exception) {
				clean = false;
				appendLog("Clean shutdown bookkeeping failed: " + exception.getMessage());
			}
		}
		if (clean) {
			setState(HostState.IDLE, "Hosting stopped cleanly", "");
			pending = null;
			activeWorld = null;
			backupFile = null;
			consoleLog = null;
			showcase = false;
			ownsJournal = false;
			supervisorLaunchAttempted = false;
			hostWorldObserved.set(false);
		} else {
			setState(HostState.FAILED,
				"Hosting cleanup did not complete; recovery files and any showcase copy were retained", "");
		}
		return clean;
	}

	private void closeExposure() {
		TunnelSession session = tunnelSession;
		tunnelSession = null;
		if (session != null) {
			session.close();
		}
		TunnelClient client = tunnelClient;
		tunnelClient = null;
		if (client != null) {
			client.close();
		}
		Secret secret = relaySecret;
		relaySecret = null;
		if (secret != null) {
			secret.close();
		}
		PortMapping mapping = portMapping;
		portMapping = null;
		if (mapping != null) {
			mapping.close();
		}
		PortMappingService service = mappingService;
		mappingService = null;
		if (service != null) {
			service.close();
		}
	}

	private void serverExited(ManagedServerProcess exited) {
		if (server != exited) {
			return;
		}
		HostState state = status.get().state();
		if (state == HostState.STOPPING || state == HostState.IDLE || state == HostState.FAILED) {
			return;
		}
		closeExposure();
		failAfterWorldClosed("dedicated server exited unexpectedly; recovery data was retained");
	}

	private void fail(String message) {
		appendLog(message);
		if (status.get().state() != HostState.STOPPING) {
			setState(HostState.FAILED, message, "");
		}
	}

	private void failAfterWorldClosed(String message) {
		closeExposure();
		ManagedServerProcess running = server;
		if (running != null && running.process().isAlive()) {
			try {
				running.stopGracefully(Duration.ofSeconds(30));
			} catch (IOException exception) {
				appendLog("Cleanup after failure could not stop the server: " + exception.getMessage());
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
		}
		String guidance = hasRecoveryArtifacts()
			? "; use recovery controls, and keep the original closed if process identity is incomplete"
			: "; reopen the original only after the normal process-ownership checks";
		fail(message + guidance);
	}

	private <T> T awaitCancellable(Future<T> future, Duration timeout) throws Exception {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (System.nanoTime() < deadline) {
			checkNotCancelled();
			long remainingMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
			try {
				return future.get(Math.min(250, remainingMillis), TimeUnit.MILLISECONDS);
			} catch (TimeoutException ignored) {
				// Re-check cancellation before waiting again.
			} catch (ExecutionException exception) {
				Throwable cause = exception.getCause();
				if (cause instanceof Exception checked) {
					throw checked;
				}
				throw new IOException("asynchronous hosting operation failed", cause);
			}
		}
		throw new TimeoutException("hosting operation timed out after " + timeout.toSeconds() + " seconds");
	}

	private void setState(HostState next, String message, String address) {
		HostStatus previous = status.get();
		if (previous.state() == HostState.STOPPING && next != HostState.IDLE && next != HostState.FAILED) {
			appendLog("Ignored state " + next + " while shutdown was in progress");
			return;
		}
		if (!allowed(previous.state(), next)) {
			appendLog("State changed from " + previous.state() + " to " + next + " during recovery");
		}
		status.set(new HostStatus(next, message, address, List.of()));
	}

	private static boolean allowed(HostState from, HostState to) {
		if (to == HostState.FAILED || to == HostState.STOPPING) {
			return from != HostState.IDLE;
		}
		return switch (from) {
			case IDLE, FAILED -> to == HostState.VALIDATING;
			case VALIDATING -> to == HostState.SAVING;
			case SAVING -> to == HostState.BACKING_UP || to == HostState.COPYING;
			case BACKING_UP, COPYING -> to == HostState.STARTING_SERVER;
			case STARTING_SERVER -> to == HostState.WAITING_READY;
			case WAITING_READY -> to == HostState.EXPOSING;
			case EXPOSING -> to == HostState.CONNECTING;
			case CONNECTING -> to == HostState.HOSTING;
			case STOPPING -> to == HostState.IDLE;
			case HOSTING -> false;
		};
	}

	private void appendLog(String line) {
		String redacted = redact(line == null ? "" : line);
		synchronized (logs) {
			logs.addLast(redacted);
			while (logs.size() > MAXIMUM_LOG_LINES) {
				logs.removeFirst();
			}
		}
	}

	private List<String> snapshotLogs() {
		synchronized (logs) {
			return List.copyOf(logs);
		}
	}

	private void clearLog() {
		synchronized (logs) {
			logs.clear();
		}
	}

	private String redact(String line) {
		PendingStart requested = pending;
		if (requested == null || requested.config().relayAccessToken == null
			|| requested.config().relayAccessToken.isBlank()) {
			return line;
		}
		return line.replace(requested.config().relayAccessToken, "[REDACTED]");
	}

	private void checkNotCancelled() throws CancelledStartException {
		if (cancelRequested.get() || status.get().state() == HostState.STOPPING) {
			throw new CancelledStartException();
		}
	}

	private static void validatePortAvailable(HostOptions options) throws IOException {
		InetAddress bindAddress = options.networkMode() == NetworkMode.RELAY
			? InetAddress.getLoopbackAddress() : null;
		try (ServerSocket socket = new ServerSocket()) {
			socket.setReuseAddress(false);
			socket.bind(new InetSocketAddress(bindAddress, options.port()));
		}
	}

	private static String localAddress() throws SocketException {
		List<InetAddress> candidates = new ArrayList<>();
		Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
		while (interfaces.hasMoreElements()) {
			NetworkInterface network = interfaces.nextElement();
			if (!network.isUp() || network.isLoopback() || network.isVirtual()) {
				continue;
			}
			Enumeration<InetAddress> addresses = network.getInetAddresses();
			while (addresses.hasMoreElements()) {
				InetAddress address = addresses.nextElement();
				if (!address.isLoopbackAddress() && !address.isLinkLocalAddress()) {
					candidates.add(address);
				}
			}
		}
		return candidates.stream().filter(Inet4Address.class::isInstance).filter(InetAddress::isSiteLocalAddress)
			.findFirst().or(() -> candidates.stream().filter(Inet4Address.class::isInstance).findFirst())
			.or(() -> candidates.stream().filter(Inet6Address.class::isInstance).findFirst())
			.map(InetAddress::getHostAddress).orElse("127.0.0.1");
	}

	private static String safeMessage(String prefix, Exception exception) {
		String message = rootMessage(exception);
		return message.isBlank() ? prefix : prefix + ": " + message;
	}

	private static String rootMessage(Throwable throwable) {
		Throwable current = throwable;
		while (current.getCause() != null && current.getCause() != current) {
			current = current.getCause();
		}
		return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
	}

	@Override
	public void close() {
		synchronized (leaseActionLock) {
			if (!closed.compareAndSet(false, true)) {
				return;
			}
		}
		boolean clean = false;
		try {
			clean = stop().toCompletableFuture().get(5, TimeUnit.MINUTES);
		} catch (Exception exception) {
			appendLog("Shutdown cleanup did not complete: " + rootMessage(exception));
		} finally {
			executor.shutdownNow();
			if (clean && ownedLease != null) {
				try {
					ownedLease.close();
				} catch (IOException exception) {
					appendLog("Could not release BTA Anywhere game directory lease");
				}
			}
		}
	}

	private final class TunnelEventSubscriber implements Flow.Subscriber<TunnelEvent> {
		@Override
		public void onSubscribe(Flow.Subscription subscription) {
			subscription.request(Long.MAX_VALUE);
		}

		@Override
		public void onNext(TunnelEvent item) {
			appendLog("Relay: " + item.state() + " - " + item.message());
			if (item.state() == io.github.kxnar.btaanywhere.TunnelState.ACTIVE) {
				TunnelSession active = tunnelSession;
				if (active != null) {
					String address = active.endpoint().toString();
					status.updateAndGet(current -> new HostStatus(
						current.state(), current.message(), address, List.of()
					));
				}
			}
		}

		@Override
		public void onError(Throwable throwable) {
			appendLog("Relay event stream failed: " + rootMessage(throwable));
		}

		@Override
		public void onComplete() {
			appendLog("Relay event stream closed");
		}
	}

	private record PendingStart(
		WorldContext world,
		HostOptions options,
		BtaAnywhereConfig config,
		boolean downloadConfirmed,
		Path runtime,
		AtomicBoolean continued
	) {
		PendingStart(WorldContext world, HostOptions options, BtaAnywhereConfig config,
			boolean downloadConfirmed, Path runtime) {
			this(world, options, config, downloadConfirmed, runtime, new AtomicBoolean());
		}

		PendingStart withRuntime(Path value) {
			return new PendingStart(world, options, config, downloadConfirmed, value, continued);
		}
	}

	private static final class CancelledStartException extends Exception {
		private static final long serialVersionUID = 1L;
	}
}
