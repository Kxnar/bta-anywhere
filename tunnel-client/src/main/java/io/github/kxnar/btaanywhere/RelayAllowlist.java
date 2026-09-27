package io.github.kxnar.btaanywhere;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Operator-provided mapping from coordinator relay IDs to local trust config. */
public final class RelayAllowlist {
	private final Map<String, RelayDescriptor> relays;

	public RelayAllowlist(Map<String, RelayDescriptor> relays) {
		Objects.requireNonNull(relays, "relays");
		if (relays.isEmpty() || relays.size() > 64) {
			throw new IllegalArgumentException("relay allowlist must contain 1-64 entries");
		}
		TreeMap<String, RelayDescriptor> copy = new TreeMap<>();
		for (Map.Entry<String, RelayDescriptor> entry : relays.entrySet()) {
			String relayId = entry.getKey();
			if (!validRelayId(relayId)) {
				throw new IllegalArgumentException("relay allowlist ID is invalid");
			}
			copy.put(relayId, Objects.requireNonNull(entry.getValue(), "relay descriptor"));
		}
		this.relays = Map.copyOf(copy);
	}

	public Map<String, RelayDescriptor> entries() {
		return relays;
	}

	public RelayDescriptor require(String relayId) {
		RelayDescriptor descriptor = relays.get(relayId);
		if (descriptor == null) {
			throw new IllegalArgumentException("coordinator selected a relay outside the local allowlist");
		}
		return descriptor;
	}

	public static boolean validRelayId(String value) {
		return value != null && !value.isEmpty() && value.length() <= 64
			&& value.chars().allMatch(character ->
				(character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z')
					|| (character >= '0' && character <= '9') || character == '-' || character == '_');
	}
}
