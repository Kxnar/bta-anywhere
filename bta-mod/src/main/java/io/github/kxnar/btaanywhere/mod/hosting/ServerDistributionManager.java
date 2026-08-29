package io.github.kxnar.btaanywhere.mod.hosting;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ServerDistributionManager {
	public static final String VERSION = "8.0.1";
	public static final String ARCHIVE_NAME = "bta_fabric_server_8.0.1.zip";
	public static final String EXPECTED_SHA256 =
		"18a8dc132e9c08f9cc6928ac00cd05d2d9450fd98cb26eb8fb732455bf9011f4";
	public static final URI DOWNLOAD_URI = URI.create(
		"https://github.com/Turnip-Labs/bta-fabric-instance-repo/releases/download/v8.0.1/"
			+ ARCHIVE_NAME
	);
	private static final long MAXIMUM_DOWNLOAD_BYTES = 256L * 1024L * 1024L;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final Path managedDirectory;
	private final HttpClient httpClient;
	private final URI downloadUri;
	private final String expectedSha256;

	public ServerDistributionManager(Path managedDirectory) {
		this(
			managedDirectory,
			HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
				.followRedirects(HttpClient.Redirect.NORMAL).build(),
			DOWNLOAD_URI,
			EXPECTED_SHA256
		);
	}

	ServerDistributionManager(Path managedDirectory, HttpClient httpClient, URI downloadUri, String expectedSha256) {
		this.managedDirectory = Objects.requireNonNull(managedDirectory, "managedDirectory")
			.toAbsolutePath().normalize();
		this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
		this.downloadUri = Objects.requireNonNull(downloadUri, "downloadUri");
		this.expectedSha256 = Objects.requireNonNull(expectedSha256, "expectedSha256").toLowerCase();
	}

	public Path ensureInstalled(boolean downloadConfirmed) throws IOException, InterruptedException,
		ConfirmationRequiredException {
		Path runtime = runtimeDirectory();
		if (isInstalled(runtime)) {
			return runtime;
		}
		if (!downloadConfirmed) {
			throw new ConfirmationRequiredException(
				"The official BTA 8.0.1 Babric server (about 36 MiB) must be downloaded and verified."
			);
		}
		Files.createDirectories(managedDirectory);
		Path downloads = managedDirectory.resolve("downloads");
		Files.createDirectories(downloads);
		Path temporaryArchive = downloads.resolve("." + ARCHIVE_NAME + ".part-" + UUID.randomUUID());
		Path staging = managedDirectory.resolve(".server-install-" + UUID.randomUUID());
		try {
			download(temporaryArchive);
			verifySha256(temporaryArchive, expectedSha256);
			extractSafely(temporaryArchive, staging);
			if (!Files.isRegularFile(staging.resolve("fabric-server-launch.jar"))
				|| !Files.isRegularFile(staging.resolve("server.jar"))) {
				throw new IOException("verified server archive did not contain the expected launcher files");
			}
			DistributionMarker marker = new DistributionMarker(VERSION, expectedSha256, Instant.now().toString());
			Files.writeString(staging.resolve(".bta-anywhere-distribution.json"), GSON.toJson(marker),
				StandardCharsets.UTF_8);
			installStaging(staging, runtime);
			return runtime;
		} finally {
			Files.deleteIfExists(temporaryArchive);
			deleteTreeIfPresent(staging);
		}
	}

	public Path runtimeDirectory() {
		return managedDirectory.resolve("server");
	}

	public boolean isInstalled(Path runtime) {
		Path resolved = runtime.toAbsolutePath().normalize();
		if (!resolved.equals(runtimeDirectory())) {
			return false;
		}
		Path marker = resolved.resolve(".bta-anywhere-distribution.json");
		if (!Files.isRegularFile(marker) || !Files.isRegularFile(resolved.resolve("fabric-server-launch.jar"))
			|| !Files.isRegularFile(resolved.resolve("server.jar"))) {
			return false;
		}
		try {
			DistributionMarker parsed = GSON.fromJson(Files.readString(marker, StandardCharsets.UTF_8),
				DistributionMarker.class);
			return parsed != null && VERSION.equals(parsed.version()) && expectedSha256.equals(parsed.sha256());
		} catch (IOException | RuntimeException exception) {
			return false;
		}
	}

	private void download(Path destination) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(downloadUri).GET().timeout(Duration.ofMinutes(3))
			.header("User-Agent", "BTA-Anywhere/0.1.0").build();
		HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
		if (response.statusCode() != 200) {
			response.body().close();
			throw new IOException("BTA server download returned HTTP " + response.statusCode());
		}
		long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
		if (declaredLength > MAXIMUM_DOWNLOAD_BYTES) {
			response.body().close();
			throw new IOException("BTA server download is unexpectedly large");
		}
		try (InputStream input = new BufferedInputStream(response.body());
			 OutputStream output = new BufferedOutputStream(Files.newOutputStream(destination))) {
			byte[] buffer = new byte[64 * 1024];
			long total = 0L;
			for (int read; (read = input.read(buffer)) >= 0; ) {
				total += read;
				if (total > MAXIMUM_DOWNLOAD_BYTES) {
					throw new IOException("BTA server download exceeded the size limit");
				}
				output.write(buffer, 0, read);
			}
		}
	}

	static void extractSafely(Path archive, Path destination) throws IOException {
		verifyNoSymbolicLinks(archive);
		Path root = destination.toAbsolutePath().normalize();
		Files.createDirectories(root);
		try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
			var entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				String name = entry.getName();
				if (name.indexOf('\0') >= 0 || name.startsWith("/") || name.startsWith("\\")
					|| name.matches("^[A-Za-z]:.*")) {
					throw new IOException("unsafe absolute path in server archive: " + name);
				}
				Path target = root.resolve(name.replace('\\', '/')).normalize();
				if (target.equals(root) || !target.startsWith(root)) {
					throw new IOException("unsafe traversal path in server archive: " + name);
				}
				if (entry.isDirectory()) {
					Files.createDirectories(target);
					continue;
				}
				Files.createDirectories(target.getParent());
				try (InputStream input = zip.getInputStream(entry);
					 OutputStream output = new BufferedOutputStream(Files.newOutputStream(target))) {
					input.transferTo(output);
				}
			}
		}
	}

	private static void verifyNoSymbolicLinks(Path archive) throws IOException {
		byte[] data = Files.readAllBytes(archive);
		int end = findEndOfCentralDirectory(data);
		ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
		long centralSize = Integer.toUnsignedLong(buffer.getInt(end + 12));
		long centralOffset = Integer.toUnsignedLong(buffer.getInt(end + 16));
		if (centralOffset + centralSize > data.length || centralOffset > Integer.MAX_VALUE) {
			throw new IOException("unsupported or malformed ZIP central directory");
		}
		int cursor = (int) centralOffset;
		int limit = (int) (centralOffset + centralSize);
		while (cursor < limit) {
			if (cursor + 46 > data.length || buffer.getInt(cursor) != 0x02014b50) {
				throw new IOException("malformed ZIP central directory entry");
			}
			int madeBy = Short.toUnsignedInt(buffer.getShort(cursor + 4));
			int nameLength = Short.toUnsignedInt(buffer.getShort(cursor + 28));
			int extraLength = Short.toUnsignedInt(buffer.getShort(cursor + 30));
			int commentLength = Short.toUnsignedInt(buffer.getShort(cursor + 32));
			long externalAttributes = Integer.toUnsignedLong(buffer.getInt(cursor + 38));
			if ((madeBy >>> 8) == 3) {
				int unixMode = (int) (externalAttributes >>> 16);
				if ((unixMode & 0170000) == 0120000) {
					String name = new String(data, cursor + 46, nameLength, StandardCharsets.UTF_8);
					throw new IOException("symbolic links are not allowed in the server archive: " + name);
				}
			}
			long next = (long) cursor + 46L + nameLength + extraLength + commentLength;
			if (next > limit || next > Integer.MAX_VALUE) {
				throw new IOException("malformed ZIP central directory lengths");
			}
			cursor = (int) next;
		}
		if (cursor != limit) {
			throw new IOException("malformed ZIP central directory size");
		}
	}

	private static int findEndOfCentralDirectory(byte[] data) throws IOException {
		int minimum = Math.max(0, data.length - 65_557);
		for (int index = data.length - 22; index >= minimum; index--) {
			if (data[index] == 0x50 && data[index + 1] == 0x4b && data[index + 2] == 0x05
				&& data[index + 3] == 0x06) {
				return index;
			}
		}
		throw new IOException("ZIP end-of-central-directory record not found");
	}

	private static String sha256(Path file) throws IOException {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
		try (InputStream input = Files.newInputStream(file)) {
			byte[] buffer = new byte[64 * 1024];
			for (int read; (read = input.read(buffer)) >= 0; ) {
				digest.update(buffer, 0, read);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	static void verifySha256(Path file, String expected) throws IOException {
		String actual = sha256(file);
		String normalized = expected.toLowerCase();
		if (!MessageDigest.isEqual(
			actual.getBytes(StandardCharsets.US_ASCII),
			normalized.getBytes(StandardCharsets.US_ASCII))) {
			throw new IOException("BTA server archive SHA-256 mismatch: expected " + normalized
				+ " but received " + actual);
		}
	}

	static void installStaging(Path staging, Path runtime) throws IOException {
		Path parent = runtime.getParent();
		if (parent == null || !staging.getParent().equals(parent)) {
			throw new IOException("server staging and runtime directories must share a parent");
		}
		Files.createDirectories(parent);
		Path previous = parent.resolve(".server-previous-" + UUID.randomUUID());
		boolean previousExists = false;
		boolean installSucceeded = false;
		try {
			if (Files.exists(runtime)) {
				moveDirectory(runtime, previous);
				previousExists = true;
			}
			try {
				moveDirectory(staging, runtime);
				installSucceeded = true;
			} catch (IOException installFailure) {
				if (previousExists && !Files.exists(runtime)) {
					try {
						moveDirectory(previous, runtime);
						previousExists = false;
					} catch (IOException rollbackFailure) {
						installFailure.addSuppressed(rollbackFailure);
					}
				}
				throw installFailure;
			}
		} finally {
			if (installSucceeded && previousExists) {
				deleteTreeIfPresent(previous);
			}
		}
	}

	private static void moveDirectory(Path source, Path destination) throws IOException {
		try {
			Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
		} catch (java.nio.file.AtomicMoveNotSupportedException exception) {
			Files.move(source, destination);
		}
	}

	private static void moveReplacing(Path source, Path destination) throws IOException {
		try {
			Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (java.nio.file.AtomicMoveNotSupportedException exception) {
			Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void deleteTreeIfPresent(Path target) throws IOException {
		if (!Files.exists(target)) {
			return;
		}
		Files.walkFileTree(target, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
				if (exception != null) {
					throw exception;
				}
				Files.delete(directory);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	private record DistributionMarker(String version, String sha256, String installedAt) {
	}
}
