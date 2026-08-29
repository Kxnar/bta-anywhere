package io.github.kxnar.btaanywhere.mod.hosting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ModMirrorServiceTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void excludesClientOnlyAndBtaAnywhereAndWritesGuestManifest() throws Exception {
		Path game = temporaryDirectory.resolve("game");
		Path mods = game.resolve("mods");
		Files.createDirectories(mods);
		createMod(mods.resolve("shared.jar"), "shared", "1.2.3", "*", "halplibe");
		createMod(mods.resolve("server.jar"), "serverhelper", "2.0", "server", "minecraft");
		createMod(mods.resolve("client.jar"), "clienthelper", "3.0", "client", "minecraft");
		createMod(mods.resolve("bta-anywhere.jar"), "btaanywhere", "0.1", "client", "minecraft");
		Path runtime = game.resolve("bta-anywhere/server");

		ModMirrorResult result = new ModMirrorService().mirror(game, runtime);

		assertEquals(java.util.List.of("serverhelper", "shared"), result.copiedModIds());
		assertEquals(1, result.guestRequiredMods().size());
		assertTrue(result.guestRequiredMods().get(0).startsWith("shared 1.2.3 sha256="));
		assertTrue(Files.isRegularFile(runtime.resolve("mods/shared-1.2.3.jar")));
		assertTrue(Files.isRegularFile(runtime.resolve("mods/serverhelper-2.0.jar")));
		assertFalse(Files.exists(runtime.resolve("mods/clienthelper-3.0.jar")));
	}

	@Test
	void rejectsServerModWhoseRequiredDependencyIsClientOnly() throws Exception {
		Path game = temporaryDirectory.resolve("broken-game");
		Path mods = game.resolve("mods");
		Files.createDirectories(mods);
		createMod(mods.resolve("client.jar"), "clientdep", "1", "client", "minecraft");
		createMod(mods.resolve("shared.jar"), "shared", "1", "*", "clientdep");

		IOException failure = assertThrows(IOException.class,
			() -> new ModMirrorService().mirror(game, game.resolve("bta-anywhere/server")));
		assertTrue(failure.getMessage().contains("client-only"));
	}

	private static void createMod(Path jar, String id, String version, String environment, String dependency)
		throws IOException {
		String metadata = """
			{
			  "schemaVersion": 1,
			  "id": "%s",
			  "version": "%s",
			  "environment": "%s",
			  "depends": {"%s": "*"}
			}
			""".formatted(id, version, environment, dependency);
		try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(jar))) {
			output.putNextEntry(new ZipEntry("fabric.mod.json"));
			output.write(metadata.getBytes(StandardCharsets.UTF_8));
			output.closeEntry();
		}
	}
}
