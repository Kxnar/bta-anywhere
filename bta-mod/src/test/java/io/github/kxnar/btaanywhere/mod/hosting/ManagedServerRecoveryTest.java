package io.github.kxnar.btaanywhere.mod.hosting;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.kxnar.btaanywhere.mod.supervisor.SupervisorControlFile;
import java.io.IOException;
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
				assertThrows(IOException.class, () -> process.stopGracefully(Duration.ofSeconds(2)));
				assertTrue(process.process().isAlive(), "missing control data must not force-stop the supervisor");
				try {
					assertFalse(process.control().request("STOP", 2_000).equals("STOPPED clean=true"));
				} catch (IOException expected) {
					// The supervisor may close the authenticated request without a success reply.
				}
				assertTrue(process.process().isAlive(), "supervisor must deny STOP without matching recorded identity");
				SupervisorControlFile originalControl = process.control();
				assertTrue(ProcessHandle.of(originalControl.serverPid()).filter(originalControl::matchesServer).isPresent(),
					"missing control data must leave the original server running");
				SupervisorControlFile wrongServer = new SupervisorControlFile(
					originalControl.schemaVersion(), originalControl.token(), originalControl.controlPort(),
					originalControl.supervisorPid(), originalControl.supervisorStartTime(),
					originalControl.supervisorExecutable(), originalControl.serverPid() + 1,
					originalControl.serverStartTime(), originalControl.serverExecutable());
				wrongServer.write(process.controlFile());
				assertThrows(IOException.class, () -> process.stopGracefully(Duration.ofSeconds(2)));
				try {
					assertFalse(originalControl.request("STOP", 2_000).equals("STOPPED clean=true"));
				} catch (IOException expected) {
					// A wrong recorded server identity may close the authenticated request.
				}
				assertTrue(process.process().isAlive(), "wrong server identity must not force-stop the supervisor");
				assertTrue(ProcessHandle.of(originalControl.serverPid()).filter(originalControl::matchesServer).isPresent(),
					"wrong control identity must leave the original server running");
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
