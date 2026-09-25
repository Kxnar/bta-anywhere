package io.github.kxnar.btaanywhere.mod.hosting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.kxnar.btaanywhere.mod.config.BtaAnywhereConfig;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Opt-in, instrumented pre-launch timing probe; never starts a managed process. */
final class PrelaunchTimingTest {
	private static final int WARMUPS = 5;
	private static final int SAMPLES = 30;

	@TempDir Path temporary;

	@Test
	void measureToBeforeSupervisorLaunch() throws Exception {
		String resultName = System.getenv("BTA_PRELAUNCH_RESULTS");
		Assumptions.assumeTrue(resultName != null && !resultName.isBlank(),
			"set BTA_PRELAUNCH_RESULTS to an ignored, unused JSONL path");
		Path results = Path.of(resultName).toAbsolutePath().normalize();
		assertTrue(Files.isDirectory(results.getParent()));
		assertTrue(!Files.exists(results), "use a fresh results path");
		for (int index = 0; index < WARMUPS + SAMPLES; index++) {
			for (WorldMode mode : WorldMode.values()) {
				Path game = temporary.resolve("case-" + index + "-" + mode);
				Path world = game.resolve("saves/world");
				Files.createDirectories(world.resolve("data"));
				byte[] payload = new byte[1024 * 1024];
				for (int byteIndex = 0; byteIndex < payload.length; byteIndex++) {
					payload[byteIndex] = (byte) (byteIndex * 31 + 17);
				}
				Files.write(world.resolve("level.dat"), payload);
				Files.write(world.resolve("data/region.bin"), payload);
				installFakeRuntime(game.resolve("bta-anywhere/server"));
				AtomicLong reached = new AtomicLong();
				HostingFaults stopBeforeLaunch = point -> {
					if (point == HostingFaults.Point.BEFORE_SUPERVISOR_LAUNCH) {
						reached.set(System.nanoTime());
						throw new TimedStop();
					}
				};
				int port;
				try (ServerSocket portReservation = new ServerSocket(0)) {
					port = portReservation.getLocalPort();
				}
				try (HostController controller = new HostController(game, stopBeforeLaunch)) {
					HostOptions options = new HostOptions(mode, NetworkMode.LAN, List.of(), "Host", 8,
						768, port, true);
					WorldContext context = new WorldContext(game, world, "world", "Synthetic timing world");
					controller.requestStart(context, options, new BtaAnywhereConfig(), false)
						.toCompletableFuture().get(25, TimeUnit.SECONDS);
					assertEquals(HostState.SAVING, controller.status().state());
					long started = System.nanoTime();
					controller.continueAfterWorldClosed().toCompletableFuture().get(45, TimeUnit.SECONDS);
					assertEquals(HostState.FAILED, controller.status().state());
					long ended = reached.get();
					assertTrue(ended > started, "pre-launch probe was not reached");
					String line = "{\"schemaVersion\":1,\"sample\":" + index
						+ ",\"warmup\":" + (index < WARMUPS)
						+ ",\"mode\":\"" + mode + "\",\"worldBytes\":2097152"
						+ ",\"elapsedNanos\":" + (ended - started) + "}\n";
					Files.writeString(results, line, StandardCharsets.UTF_8,
						StandardOpenOption.CREATE, StandardOpenOption.APPEND);
				}
			}
		}
	}

	private static void installFakeRuntime(Path runtime) throws Exception {
		Files.createDirectories(runtime);
		Manifest manifest = new Manifest();
		manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
		manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, FakeServerMain.class.getName());
		String resource = "/" + FakeServerMain.class.getName().replace('.', '/') + ".class";
		try (InputStream input = FakeServerMain.class.getResourceAsStream(resource);
			 JarOutputStream output = new JarOutputStream(Files.newOutputStream(
				runtime.resolve("fabric-server-launch.jar")), manifest)) {
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

	private static final class TimedStop extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}
}
