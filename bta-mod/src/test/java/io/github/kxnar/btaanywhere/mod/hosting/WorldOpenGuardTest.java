package io.github.kxnar.btaanywhere.mod.hosting;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorldOpenGuardTest {
	@TempDir Path temporaryDirectory;

	@Test
	void normalWorldsOpenWithoutRecoveryArtifacts() throws Exception {
		Path game = game();
		assertFalse(WorldOpenGuard.blocks(game, "world"));
		assertFalse(WorldOpenGuard.blocks(game, "other"));
		assertFalse(WorldOpenGuard.blocksNewWorld(game));
	}

	@Test
	void liveJournalBlocksOnlyItsOriginalUntilRecoveryClearsIt() throws Exception {
		Path game = game();
		RecoveryJournal journal = new RecoveryJournal(game.resolve("bta-anywhere"));
		journal.write(prepared(game, WorldMode.LIVE));
		assertTrue(WorldOpenGuard.blocks(game, "world"));
		assertTrue(WorldOpenGuard.blocks(game, "WORLD"));
		assertTrue(WorldOpenGuard.blocks(game, "../outside"));
		assertFalse(WorldOpenGuard.blocks(game, "other"));
		assertFalse(WorldOpenGuard.blocksNewWorld(game));
		journal.clear();
		assertFalse(WorldOpenGuard.blocks(game, "world"));
	}

	@Test
	void showcaseJournalLeavesOriginalAvailable() throws Exception {
		Path game = game();
		RecoveryJournal journal = new RecoveryJournal(game.resolve("bta-anywhere"));
		journal.write(prepared(game, WorldMode.SHOWCASE).withLaunchIntent());
		assertFalse(WorldOpenGuard.blocks(game, "world"));
		assertFalse(WorldOpenGuard.blocks(game, "other"));
		assertFalse(WorldOpenGuard.blocksNewWorld(game));
	}

	@Test
	void malformedOrInterruptedJournalBlocksEveryWorld() throws Exception {
		Path game = game();
		RecoveryJournal journal = new RecoveryJournal(game.resolve("bta-anywhere"));
		Files.createDirectories(journal.file().getParent());
		Files.writeString(journal.file(), "{\"schemaVersion\":", StandardCharsets.UTF_8);
		assertTrue(WorldOpenGuard.blocks(game, "world"));
		assertTrue(WorldOpenGuard.blocks(game, "other"));
		assertTrue(WorldOpenGuard.blocksNewWorld(game));
		Files.delete(journal.file());
		Files.writeString(journal.file().resolveSibling("recovery.json.tmp"), "incomplete");
		assertTrue(WorldOpenGuard.blocks(game, "world"));
		assertTrue(WorldOpenGuard.blocks(game, "other"));
		assertTrue(WorldOpenGuard.blocksNewWorld(game));
	}

	@Test
	void unsafeRecordedPathBlocksEveryWorld() throws Exception {
		Path game = game();
		RecoveryJournal.Entry base = prepared(game, WorldMode.LIVE);
		RecoveryJournal.Entry unsafe = new RecoveryJournal.Entry(base.schemaVersion(), base.worldMode(),
			base.networkMode(), temporaryDirectory.resolve("outside/world").toString(),
			base.activeSavePath(), base.worldDirectoryName(), base.backupPath(), base.pid(),
			base.processStartTime(), base.executable(), base.serverPid(), base.serverStartTime(),
			base.serverExecutable(), base.controlFile(), base.localPort(), base.serverRuntime(),
			base.logFile(), base.createdAt(), base.launchIntent());
		new RecoveryJournal(game.resolve("bta-anywhere")).write(unsafe);
		assertTrue(WorldOpenGuard.blocks(game, "world"));
		assertTrue(WorldOpenGuard.blocks(game, "other"));
	}

	@Test
	void symlinkAliasToOriginalIsBlockedWhenWindowsAllowsCreatingOne() throws Exception {
		Path game = game();
		Path alias = game.resolve("saves/alias");
		try {
			Files.createSymbolicLink(alias, game.resolve("saves/world"));
		} catch (IOException | UnsupportedOperationException | SecurityException exception) {
			Assumptions.assumeTrue(false, "directory symlink creation is unavailable: " + exception.getMessage());
			return;
		}
		assertTrue(WorldOpenGuard.blocksLiveWorld(game, "alias", game.resolve("saves/world")));
	}

	private Path game() throws Exception {
		Path game = temporaryDirectory.resolve("game");
		Path world = game.resolve("saves/world");
		Files.createDirectories(world);
		Files.writeString(world.resolve("level.dat"), "synthetic");
		Path other = game.resolve("saves/other");
		Files.createDirectories(other);
		Files.writeString(other.resolve("level.dat"), "other synthetic world");
		return game;
	}

	private static RecoveryJournal.Entry prepared(Path game, WorldMode mode) {
		Path managed = game.resolve("bta-anywhere");
		Path world = game.resolve("saves/world");
		Path active = mode == WorldMode.LIVE ? world : managed.resolve("showcases/world-copy");
		return RecoveryJournal.Entry.prepared(mode, NetworkMode.LAN, world, active, "world", null,
			25565, managed.resolve("server"), managed.resolve("logs/test.log"));
	}
}
