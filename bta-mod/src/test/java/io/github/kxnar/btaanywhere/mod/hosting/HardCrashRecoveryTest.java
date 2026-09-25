package io.github.kxnar.btaanywhere.mod.hosting;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class HardCrashRecoveryTest {
	@TempDir Path temporaryDirectory;

	@Test
	void terminatedChildLeavesOriginalIntactAndRecoveryConservative() throws Exception {
		List<HostingFaults.Point> points = List.of(
			HostingFaults.Point.DURING_BACKUP_OR_COPY,
			HostingFaults.Point.BEFORE_BACKUP_OR_COPY_PUBLICATION,
			HostingFaults.Point.BEFORE_JOURNAL_PUBLICATION,
			HostingFaults.Point.AFTER_JOURNAL_PUBLICATION,
			HostingFaults.Point.BEFORE_SUPERVISOR_LAUNCH);
		for (WorldMode mode : WorldMode.values()) {
			for (HostingFaults.Point point : points) {
				checkChild(mode, point);
			}
		}
	}

	private void checkChild(WorldMode mode, HostingFaults.Point point) throws Exception {
		Path game = temporaryDirectory.resolve(mode + "-" + point);
		Path world = game.resolve("saves/world");
		Files.createDirectories(world);
		Files.writeString(game.resolve(".bta-fault-disposable"), "synthetic world only");
		byte[] original = new byte[4096];
		new Random(81734L).nextBytes(original);
		Files.write(world.resolve("level.dat"), original);
		Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe");
		Process child = new ProcessBuilder(javaExecutable.toString(), "-cp", System.getProperty("java.class.path"),
			CrashFaultChildMain.class.getName(), game.toString(), mode.name(), point.name())
			.redirectErrorStream(true).start();
		if (!child.waitFor(30, TimeUnit.SECONDS)) {
			child.destroyForcibly();
			throw new AssertionError("fault child timed out at " + mode + "/" + point);
		}
		String output = new String(child.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		assertEquals(91, child.exitValue(), mode + "/" + point + ": " + output);
		assertArrayEquals(original, Files.readAllBytes(world.resolve("level.dat")));
		Path managed = game.resolve("bta-anywhere");
		Path artifactRoot = managed.resolve(mode == WorldMode.LIVE ? "backups" : "showcases");
		if (Files.isDirectory(artifactRoot)) {
			try (var artifacts = Files.list(artifactRoot)) {
				for (Path artifact : artifacts.toList()) {
					String name = artifact.getFileName().toString();
					if (name.contains(".tmp-") || name.contains(".partial-")) {
						assertTrue(name.startsWith("."), "incomplete artifact must be hidden/quarantined");
					}
				}
			}
		}
		RecoveryJournal journal = new RecoveryJournal(managed);
		if (Files.exists(journal.file()) || Files.exists(managed.resolve("recovery.json.tmp"))) {
			try {
				RecoveryInspection inspection = new RecoveryService(game).inspect().orElseThrow();
				assertFalse(inspection.canOpenOriginal());
				assertFalse(inspection.canRestore());
			} catch (IOException exception) {
				assertTrue(exception.getMessage().contains("journal"));
			}
		} else {
			assertTrue(new RecoveryService(game).inspect().isEmpty());
		}
	}
}
