package io.github.kxnar.btaanywhere.mod.hosting;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ManagedServerRecoveryTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void supervisorSupportsReadinessIdentityRecoveryAndGracefulStop() throws Exception {
		Path game = temporaryDirectory.resolve("game");
		Path world = game.resolve("saves/world");
		Path runtime = game.resolve("bta-anywhere/server");
		Path log = game.resolve("bta-anywhere/logs/test.log");
		Files.createDirectories(world);
		Files.createDirectories(runtime);
		createFakeServerJar(runtime.resolve("fabric-server-launch.jar"));
		int port = availablePort();
		HostOptions options = new HostOptions(
			WorldMode.LIVE, NetworkMode.LAN, List.of("Friend"), "Host", 8, 768, port, true);
		ServerConfigurationWriter.write(runtime, options);

		ManagedServerProcess process = ManagedServerProcess.start(runtime, world, options, log, ignored -> { });
		try {
			process.awaitReady(port, Duration.ofSeconds(20));
			RecoveryJournal journal = new RecoveryJournal(game.resolve("bta-anywhere"));
			RecoveryJournal.Entry entry = RecoveryJournal.Entry.prepared(
				WorldMode.LIVE, NetworkMode.LAN, world, world, "world", null, port, runtime, log)
				.withProcess(process);
			journal.write(entry);

			RecoveryInspection inspection = new RecoveryService(game).inspect().orElseThrow();
			assertTrue(inspection.canReconnect());
			assertTrue(ProcessIdentity.matching(entry).isPresent());
			assertTrue(ProcessIdentity.matching(entry.pid(), Instant.EPOCH.toString(), entry.executable()).isEmpty());

			Path hiddenControl = process.controlFile().resolveSibling("hidden-control.properties");
			Files.move(process.controlFile(), hiddenControl, StandardCopyOption.REPLACE_EXISTING);
			try {
				RecoveryInspection withoutControl = new RecoveryService(game).inspect().orElseThrow();
				assertTrue(withoutControl.supervisorAlive());
				assertTrue(withoutControl.serverAlive());
				assertFalse(withoutControl.canOpenOriginal());
			} finally {
				Files.move(hiddenControl, process.controlFile(), StandardCopyOption.REPLACE_EXISTING);
			}

			assertTrue(new RecoveryService(game).gracefulStop(inspection));
			assertFalse(process.process().isAlive());
			assertTrue(journal.read().isEmpty());
		} finally {
			process.close();
		}
	}

	private static int availablePort() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	private static void createFakeServerJar(Path destination) throws Exception {
		Manifest manifest = new Manifest();
		manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
		manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, FakeServerMain.class.getName());
		String resource = "/" + FakeServerMain.class.getName().replace('.', '/') + ".class";
		try (InputStream input = FakeServerMain.class.getResourceAsStream(resource);
			 JarOutputStream output = new JarOutputStream(Files.newOutputStream(destination), manifest)) {
			if (input == null) {
				throw new IllegalStateException("compiled fake server class is unavailable");
			}
			output.putNextEntry(new JarEntry(resource.substring(1)));
			input.transferTo(output);
			output.closeEntry();
		}
	}
}
