package io.github.kxnar.btaanywhere.mod.hosting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RecoveryFailClosedTest {
	@TempDir Path temporaryDirectory;

	@Test
	void unrecordedLaunchIntentBlocksOpenAndRestore() throws Exception {
		Path game = game();
		RecoveryJournal journal = new RecoveryJournal(game.resolve("bta-anywhere"));
		journal.write(prepared(game).withLaunchIntent());
		RecoveryInspection inspection = new RecoveryService(game).inspect().orElseThrow();
		assertFalse(inspection.canOpenOriginal());
		assertFalse(inspection.canRestore());
		assertTrue(inspection.message().contains("must remain closed"));
		try (HostController controller = new HostController(game)) {
			assertFalse(controller.canReopenOriginalWorld());
		}
		assertEquals("original", Files.readString(game.resolve("saves/world/level.dat")));
	}

	@Test
	void legacyPreparedJournalWithoutIdentityAlsoBlocksOpen() throws Exception {
		Path game = game();
		RecoveryJournal journal = new RecoveryJournal(game.resolve("bta-anywhere"));
		journal.write(prepared(game));
		RecoveryInspection inspection = new RecoveryService(game).inspect().orElseThrow();
		assertFalse(inspection.canOpenOriginal());
		assertThrows(IOException.class, () -> new RecoveryService(game).clearAndOpenOriginal(inspection));
		assertThrows(IOException.class, () -> new RecoveryService(game).restoreBackup(inspection, false));
		assertTrue(Files.exists(journal.file()));
	}

	@Test
	void malformedAndInterruptedJournalWritesRemainVisibleAndBlockHosting() throws Exception {
		Path game = game();
		RecoveryJournal journal = new RecoveryJournal(game.resolve("bta-anywhere"));
		Files.createDirectories(journal.file().getParent());
		Files.writeString(journal.file(), "{\"schemaVersion\":", StandardCharsets.UTF_8);
		IOException malformed = assertThrows(IOException.class, () -> new RecoveryService(game).inspect());
		assertTrue(malformed.getMessage().contains("malformed"));
		try (HostController controller = new HostController(game)) {
			assertTrue(controller.hasRecoveryArtifacts());
			assertFalse(controller.canReopenOriginalWorld());
		}
		Files.delete(journal.file());
		Files.writeString(journal.file().resolveSibling("recovery.json.tmp"), "partial");
		IOException interrupted = assertThrows(IOException.class, () -> new RecoveryService(game).inspect());
		assertTrue(interrupted.getMessage().contains("incomplete"));
		try (HostController controller = new HostController(game)) {
			assertTrue(controller.hasRecoveryArtifacts());
			assertFalse(controller.canReopenOriginalWorld());
		}
	}

	@Test
	void reusedPidIsNeverStoppedAndBlocksOpen() throws Exception {
		Path game = game();
		RecoveryJournal journal = new RecoveryJournal(game.resolve("bta-anywhere"));
		ProcessHandle current = ProcessHandle.current();
		String executable = current.info().command().orElseThrow();
		RecoveryJournal.Entry base = prepared(game);
		RecoveryJournal.Entry stale = new RecoveryJournal.Entry(
			base.schemaVersion(), base.worldMode(), base.networkMode(), base.originalSavePath(),
			base.activeSavePath(), base.worldDirectoryName(), base.backupPath(), current.pid(),
			Instant.EPOCH.toString(), executable, current.pid(), Instant.EPOCH.toString(), executable,
			game.resolve("bta-anywhere/logs/missing-control.properties").toString(), base.localPort(),
			base.serverRuntime(), base.logFile(), base.createdAt(), false);
		journal.write(stale);
		RecoveryInspection inspection = new RecoveryService(game).inspect().orElseThrow();
		assertTrue(inspection.identityAmbiguous());
		assertFalse(inspection.canOpenOriginal());
		assertTrue(inspection.message().contains("different identity"));
		assertThrows(IOException.class, () -> new RecoveryService(game).gracefulStop(inspection));
		assertTrue(current.isAlive());
	}

	@Test
	void malformedProcessExecutableFailsClosed() throws Exception {
		Path game = game();
		RecoveryJournal journal = new RecoveryJournal(game.resolve("bta-anywhere"));
		RecoveryJournal.Entry base = prepared(game);
		ProcessHandle current = ProcessHandle.current();
		String started = current.info().startInstant().orElseThrow().toString();
		RecoveryJournal.Entry malformed = new RecoveryJournal.Entry(
			base.schemaVersion(), base.worldMode(), base.networkMode(), base.originalSavePath(),
			base.activeSavePath(), base.worldDirectoryName(), base.backupPath(), current.pid(),
			started, "invalid\0executable", current.pid(), started,
			current.info().command().orElseThrow(),
			game.resolve("bta-anywhere/logs/missing-control.properties").toString(), base.localPort(),
			base.serverRuntime(), base.logFile(), base.createdAt(), false);
		journal.write(malformed);
		IOException denied = assertThrows(IOException.class, () -> new RecoveryService(game).inspect());
		assertTrue(denied.getMessage().contains("process identity"));
		try (HostController controller = new HostController(game)) {
			assertFalse(controller.canReopenOriginalWorld());
		}
		assertTrue(Files.exists(journal.file()));
	}

	private Path game() throws IOException {
		Path game = temporaryDirectory.resolve("game");
		Path world = game.resolve("saves/world");
		Files.createDirectories(world);
		Files.writeString(world.resolve("level.dat"), "original", StandardCharsets.UTF_8);
		return game;
	}

	private static RecoveryJournal.Entry prepared(Path game) {
		Path world = game.resolve("saves/world");
		Path managed = game.resolve("bta-anywhere");
		return RecoveryJournal.Entry.prepared(WorldMode.LIVE, NetworkMode.LAN, world, world,
			"world", null, 25565, managed.resolve("server"), managed.resolve("logs/test.log"));
	}
}
