package io.github.kxnar.btaanywhere.mod.hosting;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;

final class HostControllerFaultTest {
	@Test
	void disposableControllerExercisesRealHostingBoundaries() throws Exception {
		Path fixtures = Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")),
			"bta-controller-ci-");
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
				var result = HostControllerFaultCampaignTest.runCase(fixtures, 0, mode, point, 0);
				assertTrue(result.passed(), mode + "/" + point + ": " + result.message()
					+ "; retained fixture: " + result.retainedFixture());
				scenarios++;
			}
		}
		System.out.println("Disposable HostController fixture: " + scenarios + " production-path scenarios");
		Files.delete(fixtures);
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

}
