package io.github.kxnar.btaanywhere.mod.hosting;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CrashFaultCampaignTest {
	private static final List<HostingFaults.Point> COMMON_POINTS = Arrays.stream(HostingFaults.Point.values())
		.filter(point -> point != HostingFaults.Point.BEFORE_SHOWCASE_CLEANUP).toList();
	@TempDir Path temporaryDirectory;

	@Test
	void deterministicDisposableWorldFaultMatrix() throws Exception {
		int seeds = boundedEnvironment("BTA_FAULT_SEEDS", 1, 1, 50);
		int runs = boundedEnvironment("BTA_FAULT_RUNS", 1, 1, 3);
		int count = 0;
		for (int run = 0; run < runs; run++) {
			for (WorldMode mode : WorldMode.values()) {
				for (HostingFaults.Point point : COMMON_POINTS) {
					for (int seed = 0; seed < seeds; seed++) {
						runCase(run, mode, point, seed);
						count++;
					}
				}
			}
			for (int seed = 0; seed < seeds; seed++) {
				runCase(run, WorldMode.SHOWCASE, HostingFaults.Point.BEFORE_SHOWCASE_CLEANUP, seed);
				count++;
			}
		}
		System.out.println("Disposable fault simulation: " + count + " scenarios; seeds=" + seeds
			+ ", runs=" + runs + ", distinct common points=" + COMMON_POINTS.size());
	}

	private void runCase(int run, WorldMode mode, HostingFaults.Point selected, int seed) throws Exception {
		Path game = temporaryDirectory.resolve("run-" + run + "-" + mode + "-" + selected + "-" + seed);
		Path world = game.resolve("saves/world");
		Files.createDirectories(world.resolve("region"));
		byte[] original = new byte[64 + seed];
		new Random(0xB7A000L + seed).nextBytes(original);
		Files.write(world.resolve("level.dat"), original);
		Files.write(world.resolve("region/r.0.0.mcr"), original);
		Path managed = game.resolve("bta-anywhere");
		OneShotFault faults = new OneShotFault(selected);
		WorldBackupService backups = new WorldBackupService(Clock.systemUTC(), faults);
		RecoveryJournal journal = new RecoveryJournal(managed, faults);
		boolean clientOwnsWorld = true;
		boolean managedServerOwnsWorld = false;
		try {
			faults.hit(HostingFaults.Point.BEFORE_WORLD_SAVE);
			clientOwnsWorld = false;
			faults.hit(HostingFaults.Point.AFTER_WORLD_CLOSE);
			Path active;
			Path backup = null;
			if (mode == WorldMode.LIVE) {
				backup = backups.createBackup(world, managed, 5).backupFile();
				active = world;
			} else {
				active = backups.createShowcaseCopy(world, managed);
			}
			RecoveryJournal.Entry entry = RecoveryJournal.Entry.prepared(mode, NetworkMode.LAN,
				world, active, "world", backup, 25565, managed.resolve("server"),
				managed.resolve("logs/test.log"));
			journal.write(entry);
			journal.write(entry.withLaunchIntent());
			faults.hit(HostingFaults.Point.BEFORE_SUPERVISOR_LAUNCH);
			managedServerOwnsWorld = true;
			faults.hit(HostingFaults.Point.AFTER_SUPERVISOR_LAUNCH);
			journal.write(recordedForCurrentTestProcess(entry));
			faults.hit(HostingFaults.Point.AFTER_SUPERVISOR_IDENTITY_RECORDED);
			faults.hit(HostingFaults.Point.BEFORE_READINESS);
			faults.hit(HostingFaults.Point.BEFORE_NETWORK_EXPOSURE);
			faults.hit(HostingFaults.Point.AFTER_NETWORK_EXPOSURE);
			faults.hit(HostingFaults.Point.DURING_GRACEFUL_STOP);
			managedServerOwnsWorld = false;
			if (mode == WorldMode.SHOWCASE) {
				faults.hit(HostingFaults.Point.BEFORE_SHOWCASE_CLEANUP);
				backups.deleteCleanShowcase(active, managed);
			}
			journal.clear();
		} catch (InjectedFault failure) {
			assertEquals(selected, failure.point);
		}
		assertTrue(faults.triggered, "selected point was not reached: " + selected);
		assertFalse(clientOwnsWorld && managedServerOwnsWorld);
		assertArrayEquals(original, Files.readAllBytes(world.resolve("level.dat")));
		assertArrayEquals(original, Files.readAllBytes(world.resolve("region/r.0.0.mcr")));
		assertPublishedBackupAndCopyIntegrity(managed, original);
		if (Files.exists(journal.file()) || Files.exists(managed.resolve("recovery.json.tmp"))) {
			try {
				RecoveryInspection inspection = new RecoveryService(game).inspect().orElseThrow();
				assertFalse(inspection.canOpenOriginal(), "uncertain test-process identity should block reopen");
				assertFalse(inspection.canRestore());
			} catch (IOException incomplete) {
				assertTrue(incomplete.getMessage().contains("journal"));
			}
		}
	}

	private static void assertPublishedBackupAndCopyIntegrity(Path managed, byte[] original) throws IOException {
		Path backupRoot = managed.resolve("backups");
		if (Files.isDirectory(backupRoot)) {
			try (var files = Files.list(backupRoot)) {
				for (Path file : files.toList()) {
					assertFalse(file.getFileName().toString().contains(".tmp-"));
					try (ZipFile zip = new ZipFile(file.toFile())) {
						assertArrayEquals(original, zip.getInputStream(zip.getEntry("level.dat")).readAllBytes());
					}
				}
			}
		}
		Path showcaseRoot = managed.resolve("showcases");
		if (Files.isDirectory(showcaseRoot)) {
			try (var files = Files.list(showcaseRoot)) {
				for (Path copy : files.toList()) {
					assertFalse(copy.getFileName().toString().contains(".partial-"));
					assertArrayEquals(original, Files.readAllBytes(copy.resolve("level.dat")));
				}
			}
		}
	}

	private static RecoveryJournal.Entry recordedForCurrentTestProcess(RecoveryJournal.Entry entry) {
		ProcessHandle current = ProcessHandle.current();
		String started = current.info().startInstant().orElseThrow().toString();
		String executable = current.info().command().orElseThrow();
		return new RecoveryJournal.Entry(entry.schemaVersion(), entry.worldMode(), entry.networkMode(),
			entry.originalSavePath(), entry.activeSavePath(), entry.worldDirectoryName(), entry.backupPath(),
			current.pid(), started, executable, current.pid(), started, executable,
			Path.of(entry.logFile()).resolveSibling("synthetic-control.properties").toString(),
			entry.localPort(), entry.serverRuntime(), entry.logFile(), entry.createdAt(), false);
	}

	private static int boundedEnvironment(String name, int defaultValue, int minimum, int maximum) {
		String raw = System.getenv(name);
		int value = raw == null ? defaultValue : Integer.parseInt(raw);
		if (value < minimum || value > maximum) {
			throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
		}
		return value;
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
