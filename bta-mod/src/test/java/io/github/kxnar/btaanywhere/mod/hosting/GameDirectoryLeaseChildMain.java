package io.github.kxnar.btaanywhere.mod.hosting;

import java.nio.file.Path;

/** Disposable child process used to verify that Windows releases a lock after abrupt exit. */
public final class GameDirectoryLeaseChildMain {
	private GameDirectoryLeaseChildMain() {
	}

	public static void main(String[] arguments) throws Exception {
		Thread watchdog = new Thread(() -> {
			try {
				Thread.sleep(15_000);
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
			Runtime.getRuntime().halt(93);
		}, "disposable-lease-watchdog");
		watchdog.setDaemon(true);
		watchdog.start();
		Path game = Path.of(arguments[0]);
		try (GameDirectoryLease lease = GameDirectoryLease.acquire(game)) {
			if (!lease.covers(game)) {
				throw new IllegalStateException("disposable game directory lease is invalid");
			}
			System.out.println("READY");
			System.out.flush();
			Runtime.getRuntime().halt(System.in.read() == 1 ? 91 : 92);
		}
	}
}
