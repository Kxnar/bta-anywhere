package io.github.kxnar.btaanywhere.mod.hosting;

import java.util.List;
import java.util.Objects;

public record HostStatus(HostState state, String message, String connectionAddress, List<String> logLines) {
	public HostStatus {
		Objects.requireNonNull(state, "state");
		Objects.requireNonNull(message, "message");
		Objects.requireNonNull(connectionAddress, "connectionAddress");
		logLines = List.copyOf(Objects.requireNonNull(logLines, "logLines"));
	}

	public static HostStatus idle() {
		return new HostStatus(HostState.IDLE, "Ready to host", "", List.of());
	}
}
