package io.github.kxnar.btaanywhere.mod.hosting;

import io.github.kxnar.btaanywhere.mod.config.BtaAnywhereConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs the real controller in a child JVM so an injected point can halt it. */
public final class HostControllerHardCrashChildMain {
	private HostControllerHardCrashChildMain() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 4) {
			throw new IllegalArgumentException("expected disposable game path, mode, point, port");
		}
		Path game = Path.of(args[0]).toAbsolutePath().normalize();
		if (!Files.isRegularFile(game.resolve(".bta-fault-disposable"))) {
			throw new IllegalArgumentException("missing disposable fixture marker");
		}
		WorldMode mode = WorldMode.valueOf(args[1]);
		HostingFaults.Point selected = HostingFaults.Point.valueOf(args[2]);
		int port = Integer.parseInt(args[3]);
		HostingFaults faults = point -> {
			if (point == selected) {
				Runtime.getRuntime().halt(91);
			}
		};
		Path world = game.resolve("saves/world");
		HostOptions options = new HostOptions(mode, NetworkMode.LAN, List.of(), "Host", 8,
			768, port, true);
		HostController controller = new HostController(game, faults);
		controller.requestStart(new WorldContext(game, world, "world", "Synthetic world"), options,
			new BtaAnywhereConfig(), false).toCompletableFuture().get(20, TimeUnit.SECONDS);
		if (controller.status().state() != HostState.SAVING) {
			throw new IllegalStateException("synthetic hosting validation failed: " + controller.status().logLines());
		}
		controller.beforeWorldSave();
		controller.continueAfterWorldClosed().toCompletableFuture().get(30, TimeUnit.SECONDS);
		throw new IllegalStateException("selected hard-crash point was not reached");
	}
}
