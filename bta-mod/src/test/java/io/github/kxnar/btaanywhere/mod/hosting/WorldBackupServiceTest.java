package io.github.kxnar.btaanywhere.mod.hosting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorldBackupServiceTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void createsAtomicBackupAndRetainsNewestFive() throws Exception {
		Path world = temporaryDirectory.resolve("saves/Test World");
		Files.createDirectories(world.resolve("region"));
		Files.writeString(world.resolve("level.dat"), "level", StandardCharsets.UTF_8);
		Files.writeString(world.resolve("region/r.0.0.mcr"), "chunks", StandardCharsets.UTF_8);
		Path managed = temporaryDirectory.resolve("bta-anywhere");
		WorldBackupService backups = new WorldBackupService(
			Clock.fixed(Instant.parse("2026-08-29T00:00:00Z"), ZoneOffset.UTC));

		BackupResult first = null;
		for (int index = 0; index < 6; index++) {
			first = backups.createBackup(world, managed, 5);
		}

		assertTrue(first.worldSizeBytes() >= 11);
		try (var files = Files.list(managed.resolve("backups"))) {
			assertEquals(5, files.filter(path -> path.toString().endsWith(".zip")).count());
		}
		try (ZipFile zip = new ZipFile(first.backupFile().toFile())) {
			assertTrue(zip.getEntry("level.dat") != null);
			assertTrue(zip.getEntry("region/r.0.0.mcr") != null);
		}
	}

	@Test
	void showcaseCopyIsIndependentAndOnlyDeletedThroughManagedRoot() throws Exception {
		Path world = temporaryDirectory.resolve("saves/world");
		Files.createDirectories(world);
		Files.writeString(world.resolve("level.dat"), "original", StandardCharsets.UTF_8);
		Path managed = temporaryDirectory.resolve("bta-anywhere");
		WorldBackupService backups = new WorldBackupService();

		Path showcase = backups.createShowcaseCopy(world, managed);
		Files.writeString(showcase.resolve("level.dat"), "changed", StandardCharsets.UTF_8);

		assertEquals("original", Files.readString(world.resolve("level.dat"), StandardCharsets.UTF_8));
		backups.deleteCleanShowcase(showcase, managed);
		assertFalse(Files.exists(showcase));
		assertTrue(Files.exists(world));
	}
}
