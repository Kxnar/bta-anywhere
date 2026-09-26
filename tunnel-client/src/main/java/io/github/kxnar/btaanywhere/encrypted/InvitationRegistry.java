package io.github.kxnar.btaanywhere.encrypted;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/** Process-local invitation admission with one-use join and rate-limited status capabilities. */
public final class InvitationRegistry {
	public static final int MAX_OUTSTANDING = 64;
	public static final int MAX_STATUS_CHECKS_PER_MINUTE = 10;
	private static final Duration MAX_LIFETIME = Duration.ofMinutes(10);
	private static final long STATUS_WINDOW_NANOS = Duration.ofMinutes(1).toNanos();
	private static final Pattern PIN = Pattern.compile("[0-9a-f]{64}");
	private static final Pattern HOST_SESSION_ID = Pattern.compile("[A-Za-z0-9_-]{43}");
	private static final Pattern CAPABILITY = Pattern.compile("[A-Za-z0-9_-]{43}");

	private final String hostSessionId;
	private final String hostSpkiSha256;
	private final String relayHost;
	private final int relayPort;
	private final String relaySpkiSha256;
	private final SecureRandom random;
	private final LongSupplier monotonicNanos;
	private final LongSupplier wallClockMillis;
	private final Map<String, Entry> entries = new HashMap<>();

	public InvitationRegistry(
		String hostSessionId,
		String hostSpkiSha256,
		String relayHost,
		int relayPort,
		String relaySpkiSha256
	) {
		this(hostSessionId, hostSpkiSha256, relayHost, relayPort, relaySpkiSha256,
			new SecureRandom(), System::nanoTime, System::currentTimeMillis);
	}

	InvitationRegistry(
		String hostSessionId,
		String hostSpkiSha256,
		String relayHost,
		int relayPort,
		String relaySpkiSha256,
		SecureRandom random,
		LongSupplier monotonicNanos,
		LongSupplier wallClockMillis
	) {
		this.hostSessionId = requireSessionId(hostSessionId);
		this.hostSpkiSha256 = requirePin(hostSpkiSha256);
		this.relayHost = Objects.requireNonNull(relayHost, "relayHost");
		if (relayPort < 1 || relayPort > 65_535) {
			throw new IllegalArgumentException("relay endpoint is invalid");
		}
		this.relayPort = relayPort;
		this.relaySpkiSha256 = requirePin(relaySpkiSha256);
		this.random = Objects.requireNonNull(random, "random");
		this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
		this.wallClockMillis = Objects.requireNonNull(wallClockMillis, "wallClockMillis");
	}

	/** Creates a fresh invitation with independent capabilities and a maximum ten-minute lifetime. */
	public synchronized BtaeInvitation issue(Duration lifetime) {
		Objects.requireNonNull(lifetime, "lifetime");
		if (lifetime.isZero() || lifetime.isNegative() || lifetime.compareTo(MAX_LIFETIME) > 0
			|| lifetime.toMillis() == 0) {
			throw new IllegalArgumentException("invitation lifetime must be between zero and ten minutes");
		}
		long lifetimeNanos;
		long expiresAtEpochMillis;
		try {
			lifetimeNanos = lifetime.toNanos();
			expiresAtEpochMillis = Math.addExact(wallClockMillis.getAsLong(), lifetime.toMillis());
		} catch (ArithmeticException failure) {
			throw new IllegalArgumentException("invitation lifetime is invalid");
		}
		long issuedAtNanos = monotonicNanos.getAsLong();
		pruneExpired(issuedAtNanos);
		if (entries.size() >= MAX_OUTSTANDING) {
			throw new IllegalStateException("too many outstanding invitations");
		}

		String invitationId;
		String joinCapability;
		String statusCapability;
		do {
			invitationId = randomToken(16);
		} while (entries.containsKey(invitationId));
		joinCapability = randomToken(32);
		do {
			statusCapability = randomToken(32);
		} while (joinCapability.equals(statusCapability));
		BtaeInvitation invitation = new BtaeInvitation(invitationId, hostSessionId, hostSpkiSha256,
			relayHost, relayPort, relaySpkiSha256, expiresAtEpochMillis, joinCapability, statusCapability);
		long deadline = issuedAtNanos + lifetimeNanos;
		entries.put(invitationId, new Entry(digestCapability(joinCapability), digestCapability(statusCapability), deadline));
		return invitation;
	}

	/** Atomically validates and consumes a join capability. A second redemption always fails. */
	public synchronized boolean redeemJoin(String invitationId, String suppliedHostSessionId, String capability) {
		long now = monotonicNanos.getAsLong();
		pruneExpired(now);
		Entry entry = entries.get(invitationId);
		boolean validCapability = isCanonicalCapability(capability);
		byte[] candidate = digestCapability(capability);
		byte[] expected = entry == null ? new byte[32] : entry.joinDigest;
		boolean matches = MessageDigest.isEqual(expected, candidate);
		if (entry == null || !validCapability || entry.joinRedeemed || !matches
			|| !hostSessionId.equals(suppliedHostSessionId)) {
			return false;
		}
		entry.joinRedeemed = true;
		java.util.Arrays.fill(entry.joinDigest, (byte) 0);
		return true;
	}

	/** Validates status-only scope and applies a per-invitation sliding-window limit. */
	public synchronized boolean authorizeStatus(
		String invitationId,
		String suppliedHostSessionId,
		String capability
	) {
		long now = monotonicNanos.getAsLong();
		pruneExpired(now);
		Entry entry = entries.get(invitationId);
		boolean validCapability = isCanonicalCapability(capability);
		byte[] candidate = digestCapability(capability);
		byte[] expected = entry == null ? new byte[32] : entry.statusDigest;
		boolean matches = MessageDigest.isEqual(expected, candidate);
		if (entry == null || !validCapability || !matches || !hostSessionId.equals(suppliedHostSessionId)) {
			return false;
		}
		while (!entry.statusChecks.isEmpty() && now - entry.statusChecks.peekFirst() >= STATUS_WINDOW_NANOS) {
			entry.statusChecks.removeFirst();
		}
		if (entry.statusChecks.size() >= MAX_STATUS_CHECKS_PER_MINUTE) {
			return false;
		}
		entry.statusChecks.addLast(now);
		return true;
	}

	/** Revokes one invitation, including both its join and status capability. */
	public synchronized boolean revoke(String invitationId) {
		return entries.remove(invitationId) != null;
	}

	/** Revokes every outstanding invitation. */
	public synchronized void revokeAll() {
		entries.clear();
	}

	/** Returns the number of currently live invitations, pruning expired entries first. */
	public synchronized int size() {
		pruneExpired(monotonicNanos.getAsLong());
		return entries.size();
	}

	private void pruneExpired(long now) {
		Iterator<Entry> iterator = entries.values().iterator();
		while (iterator.hasNext()) {
			if (now - iterator.next().deadlineNanos >= 0) {
				iterator.remove();
			}
		}
	}

	private String randomToken(int byteCount) {
		byte[] value = new byte[byteCount];
		random.nextBytes(value);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
	}

	private static byte[] digestCapability(String capability) {
		byte[] decoded = new byte[32];
		if (isCanonicalCapability(capability)) {
			try {
				decoded = Base64.getUrlDecoder().decode(capability);
			} catch (IllegalArgumentException ignored) {
				// Keep the fixed-size dummy candidate for malformed capabilities.
			}
		}
		return digest(decoded);
	}

	private static boolean isCanonicalCapability(String capability) {
		if (capability == null || !CAPABILITY.matcher(capability).matches()) {
			return false;
		}
		try {
			byte[] decoded = Base64.getUrlDecoder().decode(capability);
			return decoded.length == 32
				&& Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(capability);
		} catch (IllegalArgumentException ignored) {
			return false;
		}
	}

	private static byte[] digest(String value) {
		return digest(value.getBytes(StandardCharsets.US_ASCII));
	}

	private static byte[] digest(byte[] value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value);
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is unavailable");
		}
	}

	private static String requireSessionId(String value) {
		if (value == null || !HOST_SESSION_ID.matcher(value).matches()) {
			throw new IllegalArgumentException("host session ID is invalid");
		}
		try {
			byte[] decoded = Base64.getUrlDecoder().decode(value);
			if (decoded.length != 32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value)) {
				throw new IllegalArgumentException("host session ID is invalid");
			}
		} catch (IllegalArgumentException failure) {
			throw new IllegalArgumentException("host session ID is invalid");
		}
		return value;
	}

	private static String requirePin(String value) {
		if (value == null || !PIN.matcher(value).matches()) {
			throw new IllegalArgumentException("certificate pin is invalid");
		}
		return value;
	}

	private static final class Entry {
		private final byte[] joinDigest;
		private final byte[] statusDigest;
		private final long deadlineNanos;
		private final ArrayDeque<Long> statusChecks = new ArrayDeque<>();
		private boolean joinRedeemed;

		private Entry(byte[] joinDigest, byte[] statusDigest, long deadlineNanos) {
			this.joinDigest = joinDigest;
			this.statusDigest = statusDigest;
			this.deadlineNanos = deadlineNanos;
		}
	}
}
