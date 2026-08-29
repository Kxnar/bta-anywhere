package io.github.kxnar.btaanywhere.mod.hosting;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public final class ServerConfigurationWriter {
	private ServerConfigurationWriter() {
	}

	public static void write(Path runtime, HostOptions options) throws IOException {
		Files.createDirectories(runtime);
		Properties properties = new Properties();
		properties.setProperty("online-mode", "true");
		properties.setProperty("white-list", Boolean.toString(options.whitelistEnabled()));
		properties.setProperty("max-players", Integer.toString(options.maximumPlayers()));
		properties.setProperty("server-port", Integer.toString(options.port()));
		properties.setProperty("server-ip", options.networkMode() == NetworkMode.RELAY ? "127.0.0.1" : "");
		properties.setProperty("motd", "BTA Anywhere - " + options.hostUsername() + "'s world");
		properties.setProperty("view-distance", "10");
		properties.setProperty("spawn-protection", "0");
		properties.setProperty("default-gamemode", "minecraft:gamemode/survival");
		try (OutputStream output = Files.newOutputStream(runtime.resolve("server.properties"))) {
			properties.store(output, "Managed by BTA Anywhere; manual changes may be replaced");
		}

		List<String> allowed = new ArrayList<>(options.invitedUsernames());
		Files.write(runtime.resolve("white-list.txt"), allowed, StandardCharsets.UTF_8);
		Files.write(runtime.resolve("ops.txt"), List.of(options.hostUsername()), StandardCharsets.UTF_8);
		for (String list : List.of("banned-players.txt", "banned-ips.txt")) {
			Path file = runtime.resolve(list);
			if (!Files.exists(file)) {
				Files.write(file, List.of(), StandardCharsets.UTF_8);
			}
		}
	}
}
