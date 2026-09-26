package io.github.kxnar.btaanywhere.mod.hosting;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

/** Child JVM for real process termination at disposable file/journal boundaries. */
public final class CrashFaultChildMain {
	private CrashFaultChildMain() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 3) {
			throw new IllegalArgumentException("expected disposable game path, mode, and point");
		}
		Path game = Path.of(args[0]).toAbsolutePath().normalize();
		if (!Files.isRegularFile(game.resolve(".bta-fault-disposable"))) {
			throw new IllegalArgumentException("fault child requires a disposable test marker");
		}
		WorldMode mode = WorldMode.valueOf(args[1]);
		HostingFaults.Point selected = HostingFaults.Point.valueOf(args[2]);
		HostingFaults faults = point -> {
			if (point == selected) {
				Runtime.getRuntime().halt(91);
			}
		};
		Path world = game.resolve("saves/world");
		Path managed = game.resolve("bta-anywhere");
		WorldBackupService backups = new WorldBackupService(Clock.systemUTC(), faults);
		Path active;
		Path backup = null;
		if (mode == WorldMode.LIVE) {
			backup = backups.createBackup(world, managed, 5).backupFile();
			active = world;
		} else {
			active = backups.createShowcaseCopy(world, managed);
		}
		RecoveryJournal journal = new RecoveryJournal(managed, faults);
		RecoveryJournal.Entry entry = RecoveryJournal.Entry.prepared(mode, NetworkMode.LAN,
			world, active, "world", backup, 25565, managed.resolve("server"),
			managed.resolve("logs/test.log"));
		journal.write(entry);
		journal.write(entry.withLaunchIntent());
		faults.hit(HostingFaults.Point.BEFORE_SUPERVISOR_LAUNCH);
		throw new IllegalStateException("selected fault point was not reached");
	}
}
