package io.github.kxnar.btaanywhere.mod.hosting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.kxnar.btaanywhere.mod.config.BtaAnywhereConfig;
import io.github.kxnar.btaanywhere.mod.supervisor.SupervisorControlFile;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Opt-in production-controller fault campaign using only marked synthetic worlds and fake servers. */
final class HostControllerFaultCampaignTest {
	private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final long MAXIMUM_PRIOR_OUTPUT_BYTES = 480L * 1024L * 1024L;
	private static final long MAXIMUM_RESULTS_BYTES = 16L * 1024L * 1024L;
	private static final String MARKER = "BTA Anywhere disposable controller fault fixture\n";

	@Test
	void realControllerFaultCampaign() throws Exception {
		String profile = System.getenv("BTA_CONTROLLER_CAMPAIGN");
		Assumptions.assumeTrue("smoke".equals(profile) || "full".equals(profile),
			"set BTA_CONTROLLER_CAMPAIGN=smoke or full explicitly");
		boolean full = "full".equals(profile);
		int seeds = full ? 50 : 1;
		int runs = full ? 3 : 1;
		Path repository = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().getParent();
		if (repository == null || !Files.isRegularFile(repository.resolve(".gitignore"))) {
			throw new IOException("run the campaign from the bta-mod Gradle test task in its Git checkout");
		}
		Path outputBase = repository.resolve(".dev/controller-fault-campaign");
		if (Files.isSymbolicLink(repository.resolve(".dev")) || Files.isSymbolicLink(outputBase)) {
			throw new IOException("the ignored campaign output root must not be a symbolic link");
		}
		Files.createDirectories(outputBase);
		if (!outputBase.toRealPath().startsWith(repository.toRealPath())) {
			throw new IOException("campaign output root resolves outside the Git checkout");
		}
		if (treeBytes(outputBase) > MAXIMUM_PRIOR_OUTPUT_BYTES) {
			throw new IOException("campaign output already exceeds 480 MiB; archive it outside Git before another run");
		}
		String runId = requiredMetadata("BTA_CONTROLLER_RUN_ID");
		if (!runId.matches("[a-f0-9]{32}")) {
			throw new IOException("campaign run ID must be a lowercase 32-digit hex UUID");
		}
		Path output = Files.createDirectory(outputBase.resolve("run-" + runId));
		Path fixtures = Files.createDirectory(output.resolve("fixtures"));
		Path results = output.resolve("scenarios.jsonl");
		Files.writeString(output.resolve("manifest.json"), JSON.toJson(new CampaignManifest(
			1, runId, profile,
			"HostController fault callbacks, synthetic disposable world, fake server; no BTA client or power loss",
			Instant.now().toString(), requiredMetadata("BTA_CONTROLLER_GIT_COMMIT"),
			requiredMetadata("BTA_CONTROLLER_WORKTREE_STATUS"),
			System.getProperty("os.name") + " " + System.getProperty("os.version"),
			requiredMetadata("BTA_CONTROLLER_WINDOWS_BUILD"), requiredMetadata("BTA_CONTROLLER_CPU"),
			requiredMetadata("BTA_CONTROLLER_RAM_BYTES"), System.getProperty("java.version"),
			requiredMetadata("BTA_CONTROLLER_RUST_VERSION"), Runtime.getRuntime().availableProcessors(),
			seeds, runs, applicableCases() * seeds * runs
		)), StandardCharsets.UTF_8);
		System.out.println("Controller campaign raw results: " + output);
		int completed = 0;
		for (int run = 0; run < runs; run++) {
			for (WorldMode mode : WorldMode.values()) {
				for (HostingFaults.Point point : HostingFaults.Point.values()) {
					if (mode == WorldMode.LIVE && point == HostingFaults.Point.BEFORE_SHOWCASE_CLEANUP) {
						continue;
					}
					for (int seed = 0; seed < seeds; seed++) {
						ScenarioResult result = runCase(fixtures, run, mode, point, seed);
						appendBounded(results, JSON.toJson(result) + "\n");
						completed++;
						if (!result.passed()) {
							throw new AssertionError("controller fault campaign stopped after " + completed
								+ " scenarios; failed fixture retained at " + result.retainedFixture()
								+ "; results: " + results + "; " + result.message());
						}
					}
				}
			}
		}
		assertEquals(applicableCases() * seeds * runs, completed);
		Files.writeString(output.resolve("summary.json"), JSON.toJson(Map.of(
			"schemaVersion", 1, "profile", profile, "completed", completed,
			"failed", 0, "finishedAt", Instant.now().toString()
		)), StandardCharsets.UTF_8);
	}

	static ScenarioResult runCase(Path fixtures, int run, WorldMode mode,
		HostingFaults.Point point, int seed) throws Exception {
		Path game = Files.createTempDirectory(fixtures, "case-");
		Files.writeString(game.resolve(".bta-fault-disposable"), MARKER, StandardCharsets.UTF_8);
		long started = System.nanoTime();
		Throwable failure = null;
		RecordingFault fault = new RecordingFault(game, point);
		HostController controller = null;
		String manifestDigest = "";
		long worldBytes = 0L;
		Map<String, String> before = null;
		try {
			Path world = game.resolve("saves/world");
			createSyntheticWorld(world, mode, point, seed);
			before = manifest(world);
			worldBytes = treeBytes(world);
			manifestDigest = sha256(JSON.toJson(before).getBytes(StandardCharsets.UTF_8));
			HostControllerFaultTest.installFakeRuntime(game.resolve("bta-anywhere/server"));
			controller = new HostController(game, fault);
			int port = availablePort();
			HostOptions options = new HostOptions(mode, NetworkMode.LAN, List.of(), "Host", 8,
				768, port, true);
			WorldContext context = new WorldContext(game, world, "world", "Synthetic disposable world");
			controller.requestStart(context, options, new BtaAnywhereConfig(), false)
				.toCompletableFuture().get(25, TimeUnit.SECONDS);
			assertEquals(HostState.SAVING, controller.status().state());
			try {
				controller.beforeWorldSave();
			} catch (InjectedFault expected) {
				assertEquals(HostingFaults.Point.BEFORE_WORLD_SAVE, point);
				controller.reportWorldHandoffFailure(expected);
			}
			if (point != HostingFaults.Point.BEFORE_WORLD_SAVE) {
				controller.continueAfterWorldClosed().toCompletableFuture().get(45, TimeUnit.SECONDS);
			}
			if (isStopFault(point)) {
				assertEquals(HostState.CONNECTING, controller.status().state());
				controller.markHostConnectionStarted();
				assertFalse(controller.stop().toCompletableFuture().get(45, TimeUnit.SECONDS));
			} else {
				assertEquals(HostState.FAILED, controller.status().state());
			}
			assertTrue(fault.triggered, "injection point was not reached");
			if (point == HostingFaults.Point.BEFORE_SUPERVISOR_LAUNCH) {
				assertEquals(1, fault.journalPublications.get(),
					"launch intent should be in the first journal publication");
			}
			assertEquals(before, manifest(world), "the original synthetic world changed");
			assertNoAutomaticRestore(game);
			assertQuarantinedPartials(game);
			assertRecoveryPolicy(game, controller, mode, fault.control);
		} catch (Throwable exception) {
			failure = exception;
			try {
				preserveFailureJournal(game);
			} catch (IOException snapshotFailure) {
				failure.addSuppressed(snapshotFailure);
			}
		} finally {
			Throwable cleanupFailure = null;
			try {
				if (fault.control == null && point.ordinal() >= HostingFaults.Point.AFTER_SUPERVISOR_LAUNCH.ordinal()) {
					fault.control = readOnlyControlFile(game);
				}
				stopOnlyWithVerifiedControl(game, fault.control);
			} catch (Throwable exception) {
				cleanupFailure = exception;
			}
			try {
				if (controller != null) {
					controller.close();
				}
			} catch (Throwable exception) {
				if (cleanupFailure == null) {
					cleanupFailure = exception;
				} else {
					cleanupFailure.addSuppressed(exception);
				}
			}
			try {
				assertNoRecordedProcessesAlive(fault.control);
				if (before != null) {
					assertEquals(before, manifest(game.resolve("saves/world")),
						"cleanup changed the original synthetic world");
				}
			} catch (Throwable exception) {
				if (cleanupFailure == null) {
					cleanupFailure = exception;
				} else {
					cleanupFailure.addSuppressed(exception);
				}
			}
			if (cleanupFailure != null) {
				if (failure == null) {
					failure = cleanupFailure;
				} else {
					failure.addSuppressed(cleanupFailure);
				}
			}
		}
		if (failure != null && !Files.exists(game.resolve("fault-evidence"))) {
			try {
				preserveFailureJournal(game);
			} catch (IOException snapshotFailure) {
				failure.addSuppressed(snapshotFailure);
			}
		}
		if (failure == null) {
			try {
				deleteVerifiedFixture(game, fixtures);
			} catch (IOException exception) {
				failure = exception;
			}
		}
		return new ScenarioResult(1, run, mode.name(), point.name(), seed, failure == null,
			TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), worldBytes, manifestDigest,
			failure == null ? "" : boundedMessage(failure), failure == null ? "" : game.toString());
	}

	private static void assertRecoveryPolicy(Path game, HostController controller, WorldMode mode,
		SupervisorControlFile control) throws Exception {
		RecoveryJournal journal = new RecoveryJournal(game.resolve("bta-anywhere"));
		if (!controller.hasRecoveryArtifacts()) {
			assertTrue(control == null, "a launched server must have recovery evidence");
			return;
		}
		RecoveryInspection inspection;
		try {
			inspection = new RecoveryService(game).inspect().orElseThrow();
		} catch (IOException exception) {
			assertTrue(WorldOpenGuard.blocks(game, "world"));
			assertTrue(WorldOpenGuard.blocksNewWorld(game));
			assertFalse(controller.canReopenOriginalWorld());
			return;
		}
		assertThrows(IOException.class, () -> new RecoveryService(game).restoreBackup(inspection, false));
		if (!inspection.entry().identityRecorded()) {
			assertTrue(inspection.entry().launchIntent(),
				"a published journal without process identity must retain launch intent");
			assertFalse(inspection.canOpenOriginal());
			assertFalse(inspection.canRestore());
		}
		if (control != null && inspection.entry().identityRecorded()) {
			assertEquals(control.supervisorPid(), inspection.entry().pid());
			assertEquals(control.supervisorStartTime(), inspection.entry().processStartTime());
			assertEquals(control.supervisorExecutable(), inspection.entry().executable());
			assertEquals(control.serverPid(), inspection.entry().serverPid());
			assertEquals(control.serverStartTime(), inspection.entry().serverStartTime());
			assertEquals(control.serverExecutable(), inspection.entry().serverExecutable());
		}
		if (mode == WorldMode.LIVE) {
			assertTrue(WorldOpenGuard.blocks(game, "world"));
			if (inspection.identityAmbiguous() || inspection.entry().launchIntent()
				|| matchingAlive(control)) {
				assertFalse(inspection.canOpenOriginal());
				assertFalse(inspection.canRestore());
			}
		} else {
			assertFalse(WorldOpenGuard.blocks(game, "world"));
		}
		assertTrue(journal.read().isPresent());
	}

	private static void stopOnlyWithVerifiedControl(Path game, SupervisorControlFile control) throws Exception {
		if (control == null) {
			return;
		}
		ProcessHandle supervisor = ProcessHandle.of(control.supervisorPid()).orElse(null);
		ProcessHandle server = ProcessHandle.of(control.serverPid()).orElse(null);
		boolean supervisorAlive = supervisor != null && control.matchesSupervisor(supervisor);
		boolean serverAlive = server != null && control.matchesServer(server);
		if (!supervisorAlive && !serverAlive) {
			return;
		}
		if (!supervisorAlive || !serverAlive) {
			throw new IOException("only one captured managed process remains alive; manual inspection required");
		}
		SupervisorControlFile onDisk = readOnlyControlFile(game);
		if (!control.equals(onDisk)) {
			throw new IOException("control identity changed; refusing unauthenticated cleanup");
		}
		assertEquals("STOPPED clean=true", control.request("STOP", 45_000));
		supervisor.onExit().get(10, TimeUnit.SECONDS);
		server.onExit().get(10, TimeUnit.SECONDS);
	}

	private static void assertNoRecordedProcessesAlive(SupervisorControlFile control) throws IOException {
		if (control != null && matchingAlive(control)) {
			throw new IOException("captured supervisor or server identity is still alive; fixture retained");
		}
	}

	private static boolean matchingAlive(SupervisorControlFile control) {
		return control != null && (ProcessHandle.of(control.supervisorPid()).filter(control::matchesSupervisor).isPresent()
			|| ProcessHandle.of(control.serverPid()).filter(control::matchesServer).isPresent());
	}

	private static SupervisorControlFile readOnlyControlFile(Path game) throws IOException {
		Path logs = game.resolve("bta-anywhere/logs");
		if (!Files.isDirectory(logs)) {
			throw new IOException("managed control directory is missing; fixture retained");
		}
		List<Path> files;
		try (var entries = Files.list(logs)) {
			files = entries.filter(path -> path.getFileName().toString().startsWith("supervisor-"))
				.filter(path -> path.getFileName().toString().endsWith(".properties")).toList();
		}
		if (files.size() != 1 || Files.isSymbolicLink(files.get(0))) {
			throw new IOException("expected one regular supervisor control file; fixture retained");
		}
		return SupervisorControlFile.read(files.get(0));
	}

	private static void createSyntheticWorld(Path world, WorldMode mode, HostingFaults.Point point,
		int seed) throws IOException {
		Files.createDirectories(world.resolve("data"));
		long deterministic = 0x4254414CL ^ ((long) mode.ordinal() << 48)
			^ ((long) point.ordinal() << 32) ^ seed;
		SplittableRandom random = new SplittableRandom(deterministic);
		for (int index = 0; index < 3 + seed % 4; index++) {
			byte[] bytes = new byte[64 + random.nextInt(2_048)];
			for (int offset = 0; offset < bytes.length; offset++) {
				bytes[offset] = (byte) random.nextInt(256);
			}
			Path file = index == 0 ? world.resolve("level.dat")
				: world.resolve("data/synthetic-" + index + ".bin");
			Files.write(file, bytes);
		}
	}

	private static Map<String, String> manifest(Path root) throws IOException {
		Map<String, String> hashes = new TreeMap<>();
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
				throws IOException {
				if (Files.isSymbolicLink(directory)) {
					throw new IOException("synthetic world contains a directory link");
				}
				return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
				if (Files.isSymbolicLink(file)) {
					throw new IOException("synthetic world contains a file link");
				}
				hashes.put(root.relativize(file).toString().replace('\\', '/'), sha256(Files.readAllBytes(file)));
				return FileVisitResult.CONTINUE;
			}
		});
		return hashes;
	}

	private static void assertNoAutomaticRestore(Path game) throws IOException {
		try (var saves = Files.list(game.resolve("saves"))) {
			assertFalse(saves.anyMatch(path -> path.getFileName().toString().contains("pre-restore")
				|| path.getFileName().toString().contains("restore-partial")));
		}
	}

	private static void preserveFailureJournal(Path game) throws IOException {
		Path managed = game.resolve("bta-anywhere");
		Path evidence = Files.createDirectory(game.resolve("fault-evidence"));
		for (String name : List.of("recovery.json", "recovery.json.tmp")) {
			Path source = managed.resolve(name);
			if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
				&& Files.size(source) <= 64L * 1024L) {
				Files.copy(source, evidence.resolve(name));
			}
		}
	}

	private static void assertQuarantinedPartials(Path game) throws IOException {
		Path managed = game.resolve("bta-anywhere");
		List<Path> partials = new ArrayList<>();
		Files.walkFileTree(managed, new SimpleFileVisitor<>() {
			@Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
				throws IOException {
				if (Files.isSymbolicLink(directory)) {
					throw new IOException("managed fixture contains a directory link");
				}
				if (directory.getFileName().toString().contains("partial")) {
					partials.add(directory);
				}
				return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
				if (Files.isSymbolicLink(file)) {
					throw new IOException("managed fixture contains a file link");
				}
				if (file.getFileName().toString().contains(".tmp")) {
					partials.add(file);
				}
				return FileVisitResult.CONTINUE;
			}
		});
		assertTrue(partials.size() <= 3, "too many retained partial artifacts");
		for (Path partial : partials) {
			assertTrue(partial.startsWith(managed));
		}
	}

	private static String sha256(byte[] content) throws IOException {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		} catch (NoSuchAlgorithmException exception) {
			throw new IOException("JDK SHA-256 is unavailable", exception);
		}
	}

	private static int availablePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	private static int applicableCases() {
		return HostingFaults.Point.values().length * WorldMode.values().length - 1;
	}

	private static boolean isStopFault(HostingFaults.Point point) {
		return point == HostingFaults.Point.DURING_GRACEFUL_STOP
			|| point == HostingFaults.Point.BEFORE_SHOWCASE_CLEANUP
			|| point == HostingFaults.Point.BEFORE_JOURNAL_CLEAR;
	}

	private static void appendBounded(Path results, String line) throws IOException {
		long current = Files.exists(results) ? Files.size(results) : 0L;
		if (current + line.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_RESULTS_BYTES) {
			throw new IOException("controller campaign JSONL exceeded its 16 MiB limit");
		}
		Files.writeString(results, line, StandardCharsets.UTF_8,
			StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}

	private static String requiredMetadata(String name) throws IOException {
		String value = System.getenv(name);
		if (value == null || value.isBlank() || value.length() > 256) {
			throw new IOException("campaign metadata is missing or too long: " + name);
		}
		return value;
	}

	private static long treeBytes(Path root) throws IOException {
		final long[] total = {0L};
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
				throws IOException {
				if (Files.isSymbolicLink(directory)) {
					throw new IOException("campaign output contains a directory link");
				}
				return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
				if (Files.isSymbolicLink(file)) {
					throw new IOException("campaign output contains a file link");
				}
				total[0] = Math.addExact(total[0], attributes.size());
				return FileVisitResult.CONTINUE;
			}
		});
		return total[0];
	}

	private static void deleteVerifiedFixture(Path game, Path fixtures) throws IOException {
		Path resolvedRoot = fixtures.toRealPath();
		Path resolved = game.toRealPath();
		if (!resolved.getParent().equals(resolvedRoot) || !resolved.getFileName().toString().startsWith("case-")
			|| Files.isSymbolicLink(game) || !MARKER.equals(Files.readString(game.resolve(".bta-fault-disposable")))) {
			throw new IOException("refusing to remove an unmarked or out-of-root fault fixture");
		}
		Files.walkFileTree(resolved, new SimpleFileVisitor<>() {
			@Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
				throws IOException {
				if (Files.isSymbolicLink(directory)) {
					throw new IOException("refusing to remove a fixture containing a directory link");
				}
				return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
				if (Files.isSymbolicLink(file)) {
					throw new IOException("refusing to remove a fixture containing a file link");
				}
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult postVisitDirectory(Path directory, IOException exception)
				throws IOException {
				if (exception != null) {
					throw exception;
				}
				Files.delete(directory);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	private static String boundedMessage(Throwable failure) {
		String text = failure.getClass().getSimpleName() + ": " + failure.getMessage();
		return text.length() <= 300 ? text : text.substring(0, 300);
	}

	private record CampaignManifest(int schemaVersion, String runId, String profile, String simulation, String startedAt,
		String gitCommit, String worktreeStatus, String os, String windowsBuild, String cpu, String ramBytes, String javaVersion,
		String rustVersion, int logicalCores, int seedsPerPoint, int cleanRuns, int expectedScenarios) { }

	static record ScenarioResult(int schemaVersion, int run, String mode, String point, int seed,
		boolean passed, long elapsedMillis, long worldBytes, String worldManifestSha256,
		String message, String retainedFixture) { }

	private static final class RecordingFault implements HostingFaults {
		private final Path game;
		private final Point selected;
		private volatile SupervisorControlFile control;
		private volatile boolean triggered;
		private final AtomicInteger journalPublications = new AtomicInteger();

		private RecordingFault(Path game, Point selected) {
			this.game = game;
			this.selected = selected;
		}

		@Override public void hit(Point point) {
			if (point == Point.AFTER_JOURNAL_PUBLICATION) {
				journalPublications.incrementAndGet();
			}
			if (point == Point.AFTER_SUPERVISOR_LAUNCH) {
				try {
					control = readOnlyControlFile(game);
				} catch (IOException exception) {
					throw new IllegalStateException("could not retain launched process identity", exception);
				}
			}
			if (point == selected && !triggered) {
				triggered = true;
				throw new InjectedFault(point);
			}
		}
	}

	private static final class InjectedFault extends RuntimeException {
		private static final long serialVersionUID = 1L;
		private InjectedFault(HostingFaults.Point point) {
			super(point.name());
		}
	}
}
