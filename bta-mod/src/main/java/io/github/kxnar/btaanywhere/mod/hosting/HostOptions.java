package io.github.kxnar.btaanywhere.mod.hosting;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public record HostOptions(
	WorldMode worldMode,
	NetworkMode networkMode,
	List<String> invitedUsernames,
	String hostUsername,
	int maximumPlayers,
	int memoryMiB,
	int port,
	boolean whitelistEnabled
) {
	private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

	public HostOptions {
		Objects.requireNonNull(worldMode, "worldMode");
		Objects.requireNonNull(networkMode, "networkMode");
		Objects.requireNonNull(invitedUsernames, "invitedUsernames");
		Objects.requireNonNull(hostUsername, "hostUsername");
		if (!USERNAME.matcher(hostUsername).matches()) {
			throw new IllegalArgumentException("the host username is not a valid BTA username");
		}
		if (maximumPlayers < 1 || maximumPlayers > 64) {
			throw new IllegalArgumentException("maximum players must be between 1 and 64");
		}
		if (memoryMiB < 768 || memoryMiB > 16_384) {
			throw new IllegalArgumentException("server memory must be between 768 MiB and 16384 MiB");
		}
		if (port < 1_024 || port > 65_535) {
			throw new IllegalArgumentException("server port must be between 1024 and 65535");
		}

		Set<String> unique = new LinkedHashSet<>();
		unique.add(hostUsername);
		for (String raw : invitedUsernames) {
			String username = Objects.requireNonNull(raw, "invited username").trim();
			if (username.isEmpty()) {
				continue;
			}
			if (!USERNAME.matcher(username).matches()) {
				throw new IllegalArgumentException("invalid invited username: " + username);
			}
			String folded = username.toLowerCase(Locale.ROOT);
			if (unique.stream().noneMatch(existing -> existing.toLowerCase(Locale.ROOT).equals(folded))) {
				unique.add(username);
			}
		}
		invitedUsernames = List.copyOf(unique);
	}

	public static HostOptions defaults(String hostUsername) {
		return new HostOptions(WorldMode.LIVE, NetworkMode.LAN, List.of(), hostUsername, 8, 2_048, 25_565, true);
	}

	public static List<String> parseInvitedUsernames(String value) {
		if (value == null || value.isBlank()) {
			return List.of();
		}
		return Pattern.compile("[,\\s]+").splitAsStream(value.trim()).filter(part -> !part.isBlank()).toList();
	}
}
