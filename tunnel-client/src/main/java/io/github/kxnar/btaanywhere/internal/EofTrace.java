package io.github.kxnar.btaanywhere.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded, opt-in diagnostics for the TCP/QUIC half-close path. */
final class EofTrace {
	private static final boolean ENABLED = Boolean.getBoolean("bta.anywhere.traceEof");
	private static final int MAX_LINES = 4_096;
	private static final AtomicInteger LINES = new AtomicInteger();

	private EofTrace() {}

	static String id(String connectionId) {
		if (!ENABLED) {
			return null;
		}
		return hashId(connectionId);
	}

	static String hashId(String connectionId) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(connectionId.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest, 0, 8);
		} catch (NoSuchAlgorithmException failure) {
			throw new IllegalStateException("SHA-256 is unavailable", failure);
		}
	}

	static void emit(String traceId, String event, long bytes, String state) {
		if (!ENABLED || traceId == null) {
			return;
		}
		int line = LINES.getAndUpdate(current -> Math.min(current + 1, MAX_LINES));
		if (line < MAX_LINES) {
			System.err.println("BTA_EOF trace=" + traceId + " event=" + event
				+ " bytes=" + bytes + " state=" + state);
		}
	}
}
