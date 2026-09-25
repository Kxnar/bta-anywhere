package io.github.kxnar.btaanywhere.mod.hosting;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.kxnar.btaanywhere.mod.config.BtaAnywhereConfig;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class HostControllerFaultTest {
	@TempDir Path temporaryDirectory;

	@Test
	void disposableControllerExercisesRealHostingBoundaries() throws Exception {
		boolean full = "1".equals(System.getenv("BTA_CONTROLLER_MATRIX"));
		List<HostingFaults.Point> points = full
			? Arrays.asList(HostingFaults.Point.values())
			: List.of(HostingFaults.Point.AFTER_WORLD_CLOSE,
				HostingFaults.Point.BEFORE_SUPERVISOR_LAUNCH,
				HostingFaults.Point.AFTER_SUPERVISOR_LAUNCH,
				HostingFaults.Point.BEFORE_NETWORK_EXPOSURE,
				HostingFaults.Point.BEFORE_JOURNAL_CLEAR);
		int scenarios = 0;
		for (WorldMode mode : WorldMode.values()) {
			for (HostingFaults.Point point : points) {
				if (mode == WorldMode.LIVE && point == HostingFaults.Point.BEFORE_SHOWCASE_CLEANUP) {
					continue;
				}
				runCase(mode, point);
				scenarios++;
			}
		}
		System.out.println("Disposable HostController fixture: " + scenarios + " production-path scenarios");
	}

	private void runCase(WorldMode mode, HostingFaults.Point point) throws Exception {
		Path game = temporaryDirectory.resolve(mode + "-" + point);
		Path world = game.resolve("saves/world");
		Files.createDirectories(world);
		byte[] original = "synthetic-disposable-world".getBytes(StandardCharsets.UTF_8);
		Files.write(world.resolve("level.dat"), original);
		installFakeRuntime(game.resolve("bta-anywhere/server"));
		OneShotFault faults = new OneShotFault(point);
		try (HostController controller = new HostController(game, faults)) {
			int port = availablePort();
			HostOptions options = new HostOptions(mode, NetworkMode.LAN, List.of(), "Host", 8,
				768, port, true);
			WorldContext context = new WorldContext(game, world, "world", "Synthetic world");
			controller.requestStart(context, options, new BtaAnywhereConfig(), false)
				.toCompletableFuture().get(20, TimeUnit.SECONDS);
			assertEquals(HostState.SAVING, controller.status().state(), mode + "/" + point);
			try {
				controller.beforeWorldSave();
			} catch (InjectedFault failure) {
				assertEquals(point, failure.point);
				controller.reportWorldHandoffFailure(failure);
			}
			if (point != HostingFaults.Point.BEFORE_WORLD_SAVE) {
				controller.continueAfterWorldClosed().toCompletableFuture().get(30, TimeUnit.SECONDS);
			}
			if (point == HostingFaults.Point.DURING_GRACEFUL_STOP
				|| point == HostingFaults.Point.BEFORE_SHOWCASE_CLEANUP
				|| point == HostingFaults.Point.BEFORE_JOURNAL_CLEAR) {
				assertEquals(HostState.CONNECTING, controller.status().state(), mode + "/" + point);
				controller.markHostConnectionStarted();
				assertFalse(controller.stop().toCompletableFuture().get(30, TimeUnit.SECONDS));
			} else {
				assertEquals(HostState.FAILED, controller.status().state(), mode + "/" + point
					+ " logs=" + controller.status().logLines());
			}
			assertTrue(faults.triggered, "fault was not reached: " + mode + "/" + point);
			assertArrayEquals(original, Files.readAllBytes(world.resolve("level.dat")));
			if (controller.hasRecoveryArtifacts()) {
				try {
					RecoveryInspection inspection = new RecoveryService(game).inspect().orElseThrow();
					if (!inspection.entry().identityRecorded() || inspection.entry().launchIntent()) {
						assertFalse(inspection.canOpenOriginal());
					}
				} catch (java.io.IOException exception) {
					assertTrue(exception.getMessage().contains("journal"));
					assertFalse(controller.canReopenOriginalWorld());
				}
			}
			controller.stop().toCompletableFuture().get(30, TimeUnit.SECONDS);
		}
	}

	static int availablePort() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	static void installFakeRuntime(Path runtime) throws Exception {
		Files.createDirectories(runtime);
		Path launcher = runtime.resolve("fabric-server-launch.jar");
		Manifest manifest = new Manifest();
		manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
		manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, FakeServerMain.class.getName());
		String resource = "/" + FakeServerMain.class.getName().replace('.', '/') + ".class";
		try (InputStream input = FakeServerMain.class.getResourceAsStream(resource);
			 JarOutputStream output = new JarOutputStream(Files.newOutputStream(launcher), manifest)) {
			if (input == null) {
				throw new IllegalStateException("fake server class is missing");
			}
			output.putNextEntry(new JarEntry(resource.substring(1)));
			input.transferTo(output);
			output.closeEntry();
		}
		Files.writeString(runtime.resolve("server.jar"), "synthetic placeholder");
		Files.writeString(runtime.resolve(".bta-anywhere-distribution.json"),
			"{\"version\":\"8.0.1\",\"sha256\":\""
				+ ServerDistributionManager.EXPECTED_SHA256 + "\",\"installedAt\":\"synthetic\"}");
	}

	private static final class OneShotFault implements HostingFaults {
		private final Point selected;
		private boolean triggered;

		private OneShotFault(Point selected) {
			this.selected = selected;
		}

		@Override public void hit(Point point) {
			if (point == selected && !triggered) {
				triggered = true;
				throw new InjectedFault(point);
			}
		}
	}

	private static final class InjectedFault extends RuntimeException {
		private static final long serialVersionUID = 1L;
		private final HostingFaults.Point point;

		private InjectedFault(HostingFaults.Point point) {
			super(point.name());
			this.point = point;
		}
	}
}
