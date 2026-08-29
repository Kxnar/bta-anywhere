package io.github.kxnar.btaanywhere.mod.hosting;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ModMirrorService {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Set<String> PROVIDED_MODS = Set.of(
		"minecraft", "java", "fabricloader", "mixinextras", "halplibe"
	);
	private static final Set<String> EXCLUDED_MODS = Set.of("btaanywhere", "halplibe");

	public ModMirrorResult mirror(Path gameDirectory, Path serverRuntime) throws IOException {
		Path game = gameDirectory.toAbsolutePath().normalize();
		Path runtime = serverRuntime.toAbsolutePath().normalize();
		Path clientMods = game.resolve("mods");
		Path serverMods = runtime.resolve("mods");
		Files.createDirectories(serverMods);

		List<ModJar> discovered = discover(clientMods);
		Map<String, ModJar> byId = new LinkedHashMap<>();
		for (ModJar mod : discovered) {
			ModJar previous = byId.putIfAbsent(mod.id(), mod);
			if (previous != null) {
				throw new IOException("duplicate Fabric mod ID " + mod.id() + " in "
					+ previous.source().getFileName() + " and " + mod.source().getFileName());
			}
		}

		List<ModJar> copy = discovered.stream()
			.filter(mod -> !EXCLUDED_MODS.contains(mod.id()))
			.filter(mod -> !"client".equals(mod.environment()))
			.sorted(Comparator.comparing(ModJar::id))
			.toList();
		validateDependencyClosure(copy, byId);
		removePreviouslyManaged(serverMods);

		List<String> managedFiles = new ArrayList<>();
		List<String> copiedIds = new ArrayList<>();
		List<String> guestMods = new ArrayList<>();
		for (ModJar mod : copy) {
			String destinationName = safeFileName(mod.id() + "-" + mod.version() + ".jar");
			Path destination = serverMods.resolve(destinationName);
			Files.copy(mod.source(), destination, StandardCopyOption.REPLACE_EXISTING);
			managedFiles.add(destinationName);
			copiedIds.add(mod.id());
			if (!"server".equals(mod.environment())) {
				guestMods.add(mod.id() + " " + mod.version() + " sha256=" + sha256(mod.source()));
			}
		}
		Files.writeString(serverMods.resolve(".bta-anywhere-managed.json"), GSON.toJson(managedFiles),
			StandardCharsets.UTF_8);
		mirrorConfigurations(game.resolve("config"), runtime.resolve("config"), copy);

		Path manifest = runtime.resolve("guest-mods.txt");
		List<String> manifestLines = new ArrayList<>();
		manifestLines.add("BTA Anywhere guest compatibility manifest");
		manifestLines.add("Minecraft: Better Than Adventure! 8.0.1 (Babric)");
		manifestLines.add("Guests do not need BTA Anywhere. They do need every gameplay mod below:");
		manifestLines.addAll(guestMods.isEmpty() ? List.of("(none detected)") : guestMods);
		Files.write(manifest, manifestLines, StandardCharsets.UTF_8);
		return new ModMirrorResult(copiedIds, guestMods, manifest);
	}

	private static List<ModJar> discover(Path clientMods) throws IOException {
		if (!Files.isDirectory(clientMods)) {
			return List.of();
		}
		List<ModJar> mods = new ArrayList<>();
		try (var entries = Files.list(clientMods)) {
			for (Path path : entries.filter(Files::isRegularFile)
				.filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
				.sorted().toList()) {
				if (Files.isSymbolicLink(path)) {
					throw new IOException("refusing to mirror a symbolic-link mod: " + path);
				}
				ModJar mod = readDescriptor(path);
				if (mod != null) {
					mods.add(mod);
				}
			}
		}
		return mods;
	}

	private static ModJar readDescriptor(Path jar) throws IOException {
		try (ZipFile zip = new ZipFile(jar.toFile(), StandardCharsets.UTF_8)) {
			ZipEntry metadata = zip.getEntry("fabric.mod.json");
			if (metadata == null || metadata.isDirectory()) {
				return null;
			}
			try (Reader reader = new java.io.InputStreamReader(zip.getInputStream(metadata), StandardCharsets.UTF_8)) {
				JsonObject root = GSON.fromJson(reader, JsonObject.class);
				if (root == null || !root.has("id") || !root.has("version")) {
					throw new IOException("invalid fabric.mod.json in " + jar.getFileName());
				}
				String id = root.get("id").getAsString().toLowerCase(Locale.ROOT);
				String version = root.get("version").getAsString();
				String environment = root.has("environment")
					? root.get("environment").getAsString().toLowerCase(Locale.ROOT) : "*";
				if (!(environment.equals("client") || environment.equals("server") || environment.equals("*"))) {
					throw new IOException("unknown Fabric environment for " + id + ": " + environment);
				}
				Set<String> dependencies = new HashSet<>();
				JsonElement depends = root.get("depends");
				if (depends != null && depends.isJsonObject()) {
					depends.getAsJsonObject().keySet().stream()
						.map(value -> value.toLowerCase(Locale.ROOT)).forEach(dependencies::add);
				}
				return new ModJar(id, version, environment, Set.copyOf(dependencies), jar);
			}
		} catch (java.util.zip.ZipException exception) {
			throw new IOException("cannot inspect mod JAR " + jar.getFileName(), exception);
		}
	}

	private static void validateDependencyClosure(List<ModJar> copied, Map<String, ModJar> all) throws IOException {
		Set<String> available = new HashSet<>(PROVIDED_MODS);
		copied.stream().map(ModJar::id).forEach(available::add);
		List<String> failures = new ArrayList<>();
		for (ModJar mod : copied) {
			for (String dependency : mod.dependencies()) {
				if (!available.contains(dependency)) {
					ModJar clientOnly = all.get(dependency);
					String detail = clientOnly != null && "client".equals(clientOnly.environment())
						? " (dependency is client-only)" : " (dependency is missing)";
					failures.add(mod.id() + " requires " + dependency + detail);
				}
			}
		}
		if (!failures.isEmpty()) {
			throw new IOException("server mod dependency closure is incomplete: " + String.join("; ", failures));
		}
	}

	private static void removePreviouslyManaged(Path serverMods) throws IOException {
		Path manifest = serverMods.resolve(".bta-anywhere-managed.json");
		if (!Files.isRegularFile(manifest)) {
			return;
		}
		String[] names;
		try (Reader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
			names = GSON.fromJson(reader, String[].class);
		}
		if (names != null) {
			for (String name : names) {
				Path candidate = serverMods.resolve(name).normalize();
				if (candidate.getParent().equals(serverMods) && Files.isRegularFile(candidate)) {
					Files.delete(candidate);
				}
			}
		}
	}

	private static void mirrorConfigurations(Path clientConfig, Path serverConfig, List<ModJar> mods)
		throws IOException {
		if (!Files.isDirectory(clientConfig)) {
			return;
		}
		Files.createDirectories(serverConfig);
		Set<String> ids = new HashSet<>();
		mods.stream().map(ModJar::id).forEach(ids::add);
		List<String> copied = new ArrayList<>();
		try (var entries = Files.list(clientConfig)) {
			for (Path source : entries.sorted().toList()) {
				String name = source.getFileName().toString();
				if (!matchesModConfig(name, ids)) {
					continue;
				}
				Path destination = serverConfig.resolve(name);
				if (Files.isSymbolicLink(source)) {
					throw new IOException("refusing to mirror symbolic-link configuration: " + source);
				}
				if (Files.isDirectory(source)) {
					copyTree(source, destination);
				} else if (Files.isRegularFile(source)) {
					Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
				}
				copied.add(name);
			}
		}
		Files.writeString(serverConfig.resolve(".bta-anywhere-managed.json"), GSON.toJson(copied),
			StandardCharsets.UTF_8);
	}

	private static boolean matchesModConfig(String name, Set<String> ids) {
		String folded = name.toLowerCase(Locale.ROOT);
		for (String id : ids) {
			if (folded.equals(id) || folded.startsWith(id + ".") || folded.startsWith(id + "-")
				|| folded.startsWith(id + "_")) {
				return true;
			}
		}
		return false;
	}

	private static void copyTree(Path source, Path destination) throws IOException {
		Files.walkFileTree(source, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
				if (Files.isSymbolicLink(directory)) {
					throw new IOException("configuration contains a symbolic link: " + directory);
				}
				Files.createDirectories(destination.resolve(source.relativize(directory)));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
				if (Files.isSymbolicLink(file)) {
					throw new IOException("configuration contains a symbolic link: " + file);
				}
				Files.copy(file, destination.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
				return FileVisitResult.CONTINUE;
			}
		});
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

	private static String safeFileName(String value) {
		String safe = value.replaceAll("[^A-Za-z0-9._+-]", "_");
		return safe.isBlank() ? "mod.jar" : safe;
	}

	private record ModJar(String id, String version, String environment, Set<String> dependencies, Path source) {
		ModJar {
			Objects.requireNonNull(id, "id");
			Objects.requireNonNull(version, "version");
			Objects.requireNonNull(environment, "environment");
			dependencies = Set.copyOf(dependencies);
			source = source.toAbsolutePath().normalize();
		}
	}
}
