package io.github.kxnar.btaanywhere.mod.hosting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ServerDistributionManagerTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void extractsNormalArchiveAndRejectsZipSlip() throws Exception {
		Path normal = createZip("server.jar", "server");
		Path output = temporaryDirectory.resolve("normal-out");
		ServerDistributionManager.extractSafely(normal, output);
		assertEquals("server", Files.readString(output.resolve("server.jar"), StandardCharsets.UTF_8));

		Path traversal = createZip("../escaped.txt", "bad");
		assertThrows(IOException.class,
			() -> ServerDistributionManager.extractSafely(traversal, temporaryDirectory.resolve("bad-out")));
		assertTrue(Files.notExists(temporaryDirectory.resolve("escaped.txt")));
	}

	@Test
	void rejectsUnixSymbolicLinkEntry() throws Exception {
		Path archive = createZip("link", "target");
		byte[] bytes = Files.readAllBytes(archive);
		ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		int central = findSignature(bytes, new byte[] {0x50, 0x4b, 0x01, 0x02});
		buffer.putShort(central + 4, (short) ((3 << 8) | 20));
		buffer.putInt(central + 38, 0120777 << 16);
		Files.write(archive, bytes);

		assertThrows(IOException.class,
			() -> ServerDistributionManager.extractSafely(archive, temporaryDirectory.resolve("link-out")));
	}

	@Test
	void checksumComparisonRejectsMismatch() throws Exception {
		Path file = temporaryDirectory.resolve("archive.bin");
		Files.writeString(file, "known bytes", StandardCharsets.UTF_8);
		String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
			.digest("known bytes".getBytes(StandardCharsets.UTF_8)));
		ServerDistributionManager.verifySha256(file, expected);
		assertThrows(IOException.class, () -> ServerDistributionManager.verifySha256(file, "00".repeat(32)));
	}

	@Test
	void replacesRuntimeWithoutRetainingStaleFiles() throws Exception {
		Path runtime = temporaryDirectory.resolve("server");
		Path staging = temporaryDirectory.resolve(".server-install-test");
		Files.createDirectories(runtime);
		Files.createDirectories(staging);
		Files.writeString(runtime.resolve("stale.jar"), "old", StandardCharsets.UTF_8);
		Files.writeString(staging.resolve("server.jar"), "new", StandardCharsets.UTF_8);

		ServerDistributionManager.installStaging(staging, runtime);

		assertTrue(Files.notExists(runtime.resolve("stale.jar")));
		assertEquals("new", Files.readString(runtime.resolve("server.jar"), StandardCharsets.UTF_8));
		assertTrue(Files.notExists(staging));
	}

	private Path createZip(String name, String content) throws IOException {
		Path archive = temporaryDirectory.resolve(java.util.UUID.randomUUID() + ".zip");
		try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
			output.putNextEntry(new ZipEntry(name));
			output.write(content.getBytes(StandardCharsets.UTF_8));
			output.closeEntry();
		}
		return archive;
	}

	private static int findSignature(byte[] bytes, byte[] signature) {
		for (int index = 0; index <= bytes.length - signature.length; index++) {
			boolean matches = true;
			for (int offset = 0; offset < signature.length; offset++) {
				matches &= bytes[index + offset] == signature[offset];
			}
			if (matches) {
				return index;
			}
		}
		throw new IllegalStateException("ZIP central directory signature not found");
	}
}
