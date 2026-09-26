package io.github.kxnar.btaanywhere.mod.hosting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.kxnar.btaanywhere.mod.config.BtaAnywhereConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class HostControllerWorldOpenGuardTest {
	@TempDir Path temporaryDirectory;

	@Test
	void liveHandoffBlocksOriginalBeforeJournalExistsAndReleasesAfterCleanStop() throws Exception {
		Path game = game("live");
		HostControllerFaultTest.installFakeRuntime(game.resolve("bta-anywhere/server"));
		try (HostController controller = new HostController(game)) {
			startToSaving(controller, game, WorldMode.LIVE);
			assertFalse(controller.hasRecoveryArtifacts());
			Path original = controller.liveOriginalNeedingGuard().orElseThrow();
			assertEquals(game.resolve("saves/world"), original);
			assertTrue(WorldOpenGuard.blocksLiveWorld(game, "world", original));
			assertTrue(WorldOpenGuard.blocksLiveWorld(game, "WORLD", original));
			assertFalse(WorldOpenGuard.blocksLiveWorld(game, "other", original));
			assertFalse(WorldOpenGuard.blocks(game, "world"), "the journal does not exist yet");
			assertTrue(controller.stop().toCompletableFuture().get(20, TimeUnit.SECONDS));
			assertTrue(controller.liveOriginalNeedingGuard().isEmpty());
		}
	}

	@Test
	void showcaseHandoffLeavesOriginalAvailable() throws Exception {
		Path game = game("showcase");
		HostControllerFaultTest.installFakeRuntime(game.resolve("bta-anywhere/server"));
		try (HostController controller = new HostController(game)) {
			startToSaving(controller, game, WorldMode.SHOWCASE);
			assertTrue(controller.liveOriginalNeedingGuard().isEmpty());
			assertFalse(WorldOpenGuard.blocks(game, "world"));
			assertTrue(controller.stop().toCompletableFuture().get(20, TimeUnit.SECONDS));
		}
	}

	@Test
	void failedValidationWithoutHandoffDoesNotKeepWorldBlocked() throws Exception {
		Path game = game("failed-validation");
		try (HostController controller = new HostController(game)) {
			WorldContext world = new WorldContext(game, game.resolve("saves/world"), "world", "Synthetic");
			controller.requestStart(world, options(WorldMode.LIVE), new BtaAnywhereConfig(), false)
				.toCompletableFuture().get(20, TimeUnit.SECONDS);
			assertEquals(HostState.FAILED, controller.status().state());
			assertTrue(controller.liveOriginalNeedingGuard().isEmpty());
			assertFalse(WorldOpenGuard.blocks(game, "world"));
		}
	}

	@Test
	void failedPreJournalHandoffReleasesOriginalAfterNoProcessWasLaunched() throws Exception {
		Path game = game("failed-handoff");
		HostControllerFaultTest.installFakeRuntime(game.resolve("bta-anywhere/server"));
		HostingFaults faults = point -> {
			if (point == HostingFaults.Point.AFTER_WORLD_CLOSE) {
				throw new IllegalStateException("synthetic failure before backup and launch");
			}
		};
		try (HostController controller = new HostController(game, faults)) {
			startToSaving(controller, game, WorldMode.LIVE);
			assertTrue(controller.liveOriginalNeedingGuard().isPresent());
			controller.continueAfterWorldClosed().toCompletableFuture().get(20, TimeUnit.SECONDS);
			assertEquals(HostState.FAILED, controller.status().state());
			assertFalse(controller.hasRecoveryArtifacts());
			assertTrue(controller.liveOriginalNeedingGuard().isEmpty());
		}
	}

	@Test
	void failedBeforeSupervisorLaunchCanClearJournalBecauseNoProcessWasStarted() throws Exception {
		Path game = game("failed-before-launch");
		HostControllerFaultTest.installFakeRuntime(game.resolve("bta-anywhere/server"));
		HostingFaults faults = point -> {
			if (point == HostingFaults.Point.BEFORE_SUPERVISOR_LAUNCH) {
				throw new IllegalStateException("synthetic failure before process start");
			}
		};
		try (HostController controller = new HostController(game, faults)) {
			startToSaving(controller, game, WorldMode.LIVE);
			controller.continueAfterWorldClosed().toCompletableFuture().get(20, TimeUnit.SECONDS);
			assertEquals(HostState.FAILED, controller.status().state());
			assertTrue(controller.hasRecoveryArtifacts());
			assertTrue(WorldOpenGuard.blocks(game, "world"));
			assertTrue(controller.stop().toCompletableFuture().get(20, TimeUnit.SECONDS));
			assertFalse(controller.hasRecoveryArtifacts());
			assertFalse(WorldOpenGuard.blocks(game, "world"));
			assertTrue(controller.liveOriginalNeedingGuard().isEmpty());
		}
	}

	private void startToSaving(HostController controller, Path game, WorldMode mode) throws Exception {
		WorldContext world = new WorldContext(game, game.resolve("saves/world"), "world", "Synthetic");
		controller.requestStart(world, options(mode), new BtaAnywhereConfig(), false)
			.toCompletableFuture().get(20, TimeUnit.SECONDS);
		assertEquals(HostState.SAVING, controller.status().state());
	}

	private static HostOptions options(WorldMode mode) throws Exception {
		return new HostOptions(mode, NetworkMode.LAN, List.of(), "Host", 8, 768,
			HostControllerFaultTest.availablePort(), true);
	}

	private Path game(String name) throws Exception {
		Path game = temporaryDirectory.resolve(name);
		Files.createDirectories(game.resolve("saves/world"));
		Files.createDirectories(game.resolve("saves/other"));
		Files.writeString(game.resolve("saves/world/level.dat"), "synthetic original");
		Files.writeString(game.resolve("saves/other/level.dat"), "synthetic other");
		return game;
	}
}
