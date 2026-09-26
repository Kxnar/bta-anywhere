package io.github.kxnar.btaanywhere.mod.hosting;

import io.github.kxnar.btaanywhere.mod.config.BtaAnywhereConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Uses a disposable fake server to verify that failed cleanup keeps the controller-owned lock. */
public final class HostControllerLeaseFailureChildMain {
	private HostControllerLeaseFailureChildMain() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 2) {
			throw new IllegalArgumentException("expected disposable game path and port");
		}
		Path game = Path.of(args[0]).toAbsolutePath().normalize();
		if (!Files.isRegularFile(game.resolve(".bta-fault-disposable"))) {
			throw new IllegalArgumentException("missing disposable fixture marker");
		}
		int port = Integer.parseInt(args[1]);
		HostingFaults faults = point -> {
			if (point == HostingFaults.Point.DURING_GRACEFUL_STOP) {
				throw new IllegalStateException("synthetic failure before authenticated STOP");
			}
		};
		HostController controller = HostController.open(game, faults);
		Path world = game.resolve("saves/world");
		HostOptions options = new HostOptions(WorldMode.LIVE, NetworkMode.LAN, List.of(),
			"Host", 8, 768, port, true);
		controller.requestStart(new WorldContext(game, world, "world", "Synthetic world"), options,
			new BtaAnywhereConfig(), false).toCompletableFuture().get(20, TimeUnit.SECONDS);
		if (controller.status().state() != HostState.SAVING) {
			throw new IllegalStateException("synthetic validation failed");
		}
		controller.continueAfterWorldClosed();
		RecoveryJournal journal = new RecoveryJournal(game.resolve("bta-anywhere"));
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while (System.nanoTime() < deadline && !identityRecorded(journal)) {
			Thread.sleep(50);
		}
		if (!identityRecorded(journal)) {
			throw new IllegalStateException("synthetic managed process identity was not recorded");
		}
		controller.close();
		if (!Files.isRegularFile(journal.file())) {
			throw new IllegalStateException("failed cleanup cleared the recovery journal");
		}
		try {
			HostController.open(game);
			throw new IllegalStateException("failed cleanup released the game-directory lease");
		} catch (GameDirectoryLease.InUseException expected) {
			System.out.println("LEASE_RETAINED");
		}
		Runtime.getRuntime().halt(92);
	}

	private static boolean identityRecorded(RecoveryJournal journal) {
		try {
			return journal.read().filter(RecoveryJournal.Entry::identityRecorded).isPresent();
		} catch (IOException exception) {
			// An atomic journal publication may temporarily leave its .tmp file visible.
			return false;
		}
	}
}
