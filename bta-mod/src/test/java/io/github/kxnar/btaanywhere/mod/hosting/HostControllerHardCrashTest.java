package io.github.kxnar.btaanywhere.mod.hosting;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.kxnar.btaanywhere.mod.config.BtaAnywhereConfig;
import io.github.kxnar.btaanywhere.mod.supervisor.SupervisorControlFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class HostControllerHardCrashTest {
	@Test
	void failedLaunchAfterVerifiedProcessStartCannotClearUnownedJournal() throws Exception {
		for (WorldMode mode : WorldMode.values()) {
			checkUnownedLaunchFailure(mode);
		}
	}

	private void checkUnownedLaunchFailure(WorldMode mode) throws Exception {
		Path tempRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
		Path game = Files.createTempDirectory(tempRoot, "bta-host-crash-");
		AtomicReference<SupervisorControlFile> launchedControl = new AtomicReference<>();
		HostingFaults faults = new HostingFaults() {
			@Override public void hit(Point point) {
			}

			@Override public void afterSupervisorProcessStarted(Process process, Path controlFile) throws IOException {
				SupervisorControlFile control = SupervisorControlFile.read(controlFile);
				if (!control.matchesSupervisor(process.toHandle())
					|| ProcessHandle.of(control.serverPid()).filter(control::matchesServer).isEmpty()) {
					throw new IOException("disposable supervisor control identity is not verified");
				}
				launchedControl.set(control);
				throw new IOException("injected failure before HostController receives the process handle");
			}
		};
		HostController controller = null;
		boolean cleaned = false;
		try {
			Path world = game.resolve("saves/world");
			Files.createDirectories(world);
			Files.writeString(game.resolve(".bta-fault-disposable"), "synthetic world only");
			byte[] original = "synthetic-disposable-world".getBytes(StandardCharsets.UTF_8);
			Files.write(world.resolve("level.dat"), original);
			HostControllerFaultTest.installFakeRuntime(game.resolve("bta-anywhere/server"));
			int port = HostControllerFaultTest.availablePort();
			HostOptions options = new HostOptions(mode, NetworkMode.LAN, List.of(),
				"Host", 8, 768, port, true);
			controller = new HostController(game, faults);
			controller.requestStart(new WorldContext(game, world, "world", "Synthetic"), options,
				new BtaAnywhereConfig(), false).toCompletableFuture().get(25, TimeUnit.SECONDS);
			assertEquals(HostState.SAVING, controller.status().state());
			controller.continueAfterWorldClosed().toCompletableFuture().get(45, TimeUnit.SECONDS);
			assertEquals(HostState.FAILED, controller.status().state());
			SupervisorControlFile control = launchedControl.get();
			assertNotNull(control, "the injected failure must follow a verified process start");
			assertTrue(ProcessHandle.of(control.supervisorPid()).filter(control::matchesSupervisor).isPresent());
			assertTrue(ProcessHandle.of(control.serverPid()).filter(control::matchesServer).isPresent());
			RecoveryJournal journal = new RecoveryJournal(game.resolve("bta-anywhere"));
			RecoveryJournal.Entry entry = journal.read().orElseThrow();
			assertTrue(entry.launchIntent());
			assertFalse(entry.identityRecorded());
			assertFalse(controller.stop().toCompletableFuture().get(45, TimeUnit.SECONDS));
			assertTrue(Files.exists(journal.file()), "an unowned managed process must retain its journal");
			assertEquals(mode == WorldMode.LIVE, WorldOpenGuard.blocks(game, "world"));
			if (mode == WorldMode.SHOWCASE) {
				assertTrue(Files.isDirectory(Path.of(entry.activeSavePath())),
					"an unowned showcase copy must remain for inspection");
			}
			assertFalse(controller.canReopenOriginalWorld());
			assertArrayEquals(original, Files.readAllBytes(world.resolve("level.dat")));
			stopThroughVerifiedControl(game);
			controller.close();
			deleteVerifiedFixture(game, tempRoot);
			cleaned = true;
		} finally {
			if (!cleaned) {
				if (controller != null) {
					controller.close();
				}
				try {
					stopThroughVerifiedControl(game);
				} catch (Exception exception) {
					System.err.println("Retained disposable launch failure fixture for manual review: " + game
						+ " (verified control cleanup unavailable: " + exception.getMessage() + ")");
				}
			}
		}
	}

	@Test
	void realControllerHaltsAcrossLaunchIdentityWindowAndRecoversConservatively() throws Exception {
		for (WorldMode mode : WorldMode.values()) {
			for (HostingFaults.Point point : List.of(
				HostingFaults.Point.AFTER_SUPERVISOR_LAUNCH,
				HostingFaults.Point.AFTER_SUPERVISOR_IDENTITY_RECORDED)) {
				checkCase(mode, point);
			}
		}
	}

	private void checkCase(WorldMode mode, HostingFaults.Point point) throws Exception {
		Path tempRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
		Path game = Files.createTempDirectory(tempRoot, "bta-host-crash-");
		boolean cleaned = false;
		try {
			Path world = game.resolve("saves/world");
			Files.createDirectories(world);
			Files.writeString(game.resolve(".bta-fault-disposable"), "synthetic world only");
			byte[] original = "synthetic-disposable-world".getBytes(StandardCharsets.UTF_8);
			Files.write(world.resolve("level.dat"), original);
			HostControllerFaultTest.installFakeRuntime(game.resolve("bta-anywhere/server"));
			int port = HostControllerFaultTest.availablePort();
			Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe");
			Process child = new ProcessBuilder(javaExecutable.toString(), "-cp",
				System.getProperty("java.class.path"), HostControllerHardCrashChildMain.class.getName(),
				game.toString(), mode.name(), point.name(), Integer.toString(port))
				.redirectErrorStream(true).start();
			if (!child.waitFor(30, TimeUnit.SECONDS)) {
				throw new AssertionError("controller crash child timed out; fixture retained at " + game);
			}
			String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			assertEquals(91, child.exitValue(), mode + "/" + point + " " + output);
			assertArrayEquals(original, Files.readAllBytes(world.resolve("level.dat")));
			RecoveryInspection inspection = new RecoveryService(game).inspect().orElseThrow();
			assertFalse(inspection.canOpenOriginal());
			assertFalse(inspection.canRestore());
			if (point == HostingFaults.Point.AFTER_SUPERVISOR_LAUNCH) {
				assertTrue(inspection.entry().launchIntent());
				assertFalse(inspection.entry().identityRecorded());
			} else {
				assertTrue(inspection.entry().identityRecorded());
			}
			stopThroughVerifiedControl(game);
			deleteVerifiedFixture(game, tempRoot);
			cleaned = true;
		} finally {
			if (!cleaned) {
				try {
					stopThroughVerifiedControl(game);
				} catch (Exception exception) {
					System.err.println("Retained disposable crash fixture for manual review: " + game
						+ " (verified control cleanup unavailable: " + exception.getMessage() + ")");
				}
			}
		}
	}

	private static void stopThroughVerifiedControl(Path game) throws Exception {
		Path logs = game.resolve("bta-anywhere/logs");
		if (!Files.isDirectory(logs)) {
			throw new IOException("supervisor control directory is missing");
		}
		List<Path> files;
		try (var entries = Files.list(logs)) {
			files = entries.filter(path -> path.getFileName().toString().startsWith("supervisor-"))
				.filter(path -> path.getFileName().toString().endsWith(".properties")).toList();
		}
		if (files.size() != 1) {
			throw new IOException("expected one retained supervisor control file, found " + files.size());
		}
		SupervisorControlFile control = SupervisorControlFile.read(files.get(0));
		ProcessHandle supervisor = ProcessHandle.of(control.supervisorPid())
			.orElseThrow(() -> new IOException("recorded supervisor PID no longer exists"));
		ProcessHandle server = ProcessHandle.of(control.serverPid())
			.orElseThrow(() -> new IOException("recorded server PID no longer exists"));
		if (!control.matchesSupervisor(supervisor) || !control.matchesServer(server)) {
			throw new IOException("PID, start time, or executable identity mismatch; refusing STOP");
		}
		assertEquals("STOPPED clean=true", control.request("STOP", 45_000));
		supervisor.onExit().get(10, TimeUnit.SECONDS);
		server.onExit().get(10, TimeUnit.SECONDS);
		assertFalse(supervisor.isAlive());
		assertFalse(server.isAlive());
	}

	private static void deleteVerifiedFixture(Path game, Path tempRoot) throws IOException {
		Path resolved = game.toAbsolutePath().normalize();
		if (!resolved.startsWith(tempRoot) || resolved.equals(tempRoot)
			|| !resolved.getFileName().toString().startsWith("bta-host-crash-")
			|| Files.isSymbolicLink(resolved)) {
			throw new IOException("refusing to delete a path outside the disposable fixture root");
		}
		Files.walkFileTree(resolved, new SimpleFileVisitor<>() {
			@Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}

			@Override public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
				if (error != null) {
					throw error;
				}
				Files.delete(directory);
				return FileVisitResult.CONTINUE;
			}
		});
	}
}
