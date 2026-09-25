package io.github.kxnar.btaanywhere.mod.hosting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GameDirectoryLeaseTest {
	@TempDir Path temporaryDirectory;

	@Test
	void onlyOneClientCanOwnTheGameDirectoryAndLockFileRemains() throws Exception {
		Path game = Files.createDirectory(temporaryDirectory.resolve("game"));
		Path lockFile = game.resolve("bta-anywhere/client.lock");
		try (GameDirectoryLease first = GameDirectoryLease.acquire(game)) {
			assertTrue(first.covers(game));
			IOException denied = assertThrows(IOException.class, () -> GameDirectoryLease.acquire(game));
			assertTrue(denied.getMessage().contains("Another BTA Anywhere client"));
			assertTrue(Files.isRegularFile(lockFile));
		}
		try (GameDirectoryLease second = GameDirectoryLease.acquire(game)) {
			assertTrue(second.covers(game));
			assertTrue(Files.isRegularFile(lockFile));
		}
		assertTrue(Files.isRegularFile(lockFile), "the stable lock file must not be deleted on release");
	}

	@Test
	void controllerRejectsWrongOrClosedLease() throws Exception {
		Path firstGame = Files.createDirectory(temporaryDirectory.resolve("first-game"));
		Path otherGame = Files.createDirectory(temporaryDirectory.resolve("other-game"));
		GameDirectoryLease lease = GameDirectoryLease.acquire(firstGame);
		try {
			assertThrows(IllegalArgumentException.class, () -> new HostController(otherGame, lease));
		} finally {
			lease.close();
		}
		assertThrows(IllegalArgumentException.class, () -> new HostController(firstGame, lease));
	}

	@Test
	void unsafeManagedDirectoryAndLockFileFailClosed() throws Exception {
		Path badManaged = Files.createDirectory(temporaryDirectory.resolve("bad-managed"));
		Files.writeString(badManaged.resolve("bta-anywhere"), "not a directory");
		assertThrows(IOException.class, () -> GameDirectoryLease.acquire(badManaged));

		Path badLock = Files.createDirectory(temporaryDirectory.resolve("bad-lock"));
		Files.createDirectory(badLock.resolve("bta-anywhere"));
		Files.createDirectory(badLock.resolve("bta-anywhere/client.lock"));
		assertThrows(IOException.class, () -> GameDirectoryLease.acquire(badLock));
	}

	@Test
	void symbolicLockFileIsRejectedWhenWindowsAllowsCreatingIt() throws Exception {
		Path game = Files.createDirectory(temporaryDirectory.resolve("linked-lock"));
		Path managed = Files.createDirectory(game.resolve("bta-anywhere"));
		Path target = Files.writeString(temporaryDirectory.resolve("outside-lock"), "outside");
		try {
			Files.createSymbolicLink(managed.resolve("client.lock"), target);
		} catch (IOException | UnsupportedOperationException | SecurityException exception) {
			Assumptions.assumeTrue(false, "this Windows account cannot create a symbolic link");
		}
		assertThrows(IOException.class, () -> GameDirectoryLease.acquire(game));
	}

	@Test
	void abruptChildExitReleasesOperatingSystemLock() throws Exception {
		Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"),
			"BTA Anywhere targets Windows x86-64");
		Path game = Files.createDirectory(temporaryDirectory.resolve("crash-game"));
		Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe");
		Process child = new ProcessBuilder(javaExecutable.toString(), "-cp",
			System.getProperty("java.class.path"), GameDirectoryLeaseChildMain.class.getName(),
			game.toString()).redirectErrorStream(true).start();
		try {
			BufferedReader output = new BufferedReader(new InputStreamReader(
				child.getInputStream(), StandardCharsets.UTF_8));
			String ready = CompletableFuture.supplyAsync(() -> {
				try {
					return output.readLine();
				} catch (IOException exception) {
					throw new IllegalStateException(exception);
				}
			}).get(20, TimeUnit.SECONDS);
			assertEquals("READY", ready);
			assertThrows(IOException.class, () -> GameDirectoryLease.acquire(game));
			child.getOutputStream().write(1);
			child.getOutputStream().flush();
			assertTrue(child.waitFor(20, TimeUnit.SECONDS), "disposable lease child did not exit");
			assertEquals(91, child.exitValue());
			try (GameDirectoryLease recovered = GameDirectoryLease.acquire(game)) {
				assertTrue(recovered.covers(game));
				assertTrue(Files.isRegularFile(game.resolve("bta-anywhere/client.lock")));
			}
		} finally {
			if (child.isAlive()) {
				try {
					child.getOutputStream().write(1);
					child.getOutputStream().flush();
				} catch (IOException ignored) {
					// The child may have exited between isAlive() and the write.
				}
				child.waitFor(20, TimeUnit.SECONDS);
			}
		}
	}
}
