package io.github.kxnar.btaanywhere.encrypted;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.net.IDN;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** A strictly encoded, copyable BTAE1 encrypted-join invitation. */
public final class BtaeInvitation {
	private static final String PREFIX = "BTAE1:";
	private static final int MAX_ENCODED_LENGTH = 2 * 1024;
	private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");
	private static final Pattern BASE64URL_16 = Pattern.compile("[A-Za-z0-9_-]{22}");
	private static final Pattern BASE64URL_32 = Pattern.compile("[A-Za-z0-9_-]{43}");
	private static final Set<String> FIELDS = Set.of(
		"version", "invitationId", "hostSessionId", "hostSpkiSha256", "relayHost", "relayPort",
		"relaySpkiSha256", "expiresAtEpochMillis", "joinCapability", "statusCapability"
	);

	private final String invitationId;
	private final String hostSessionId;
	private final String hostSpkiSha256;
	private final String relayHost;
	private final int relayPort;
	private final String relaySpkiSha256;
	private final long expiresAtEpochMillis;
	private final String joinCapability;
	private final String statusCapability;

	public BtaeInvitation(
		String invitationId,
		String hostSessionId,
		String hostSpkiSha256,
		String relayHost,
		int relayPort,
		String relaySpkiSha256,
		long expiresAtEpochMillis,
		String joinCapability,
		String statusCapability
	) {
		this.invitationId = requireRandomId(invitationId);
		this.hostSessionId = requireSessionId(hostSessionId);
		this.hostSpkiSha256 = requirePin(hostSpkiSha256);
		this.relayHost = requireRelayHost(relayHost);
		if (relayPort < 1 || relayPort > 65_535) {
			throw invalid();
		}
		this.relayPort = relayPort;
		this.relaySpkiSha256 = requirePin(relaySpkiSha256);
		if (expiresAtEpochMillis <= 0) {
			throw invalid();
		}
		this.expiresAtEpochMillis = expiresAtEpochMillis;
		this.joinCapability = requireCapability(joinCapability);
		this.statusCapability = requireCapability(statusCapability);
		if (this.joinCapability.equals(this.statusCapability)) {
			throw invalid();
		}
	}

	public String invitationId() { return invitationId; }
	public String hostSessionId() { return hostSessionId; }
	public String hostSpkiSha256() { return hostSpkiSha256; }
	public String relayHost() { return relayHost; }
	public int relayPort() { return relayPort; }
	public String relaySpkiSha256() { return relaySpkiSha256; }
	public long expiresAtEpochMillis() { return expiresAtEpochMillis; }
	public String joinCapability() { return joinCapability; }
	public String statusCapability() { return statusCapability; }

	/** Encodes fields in the sole canonical order and omits Base64 padding. */
	public String encode() {
		String json = canonicalJson();
		String encoded = PREFIX + Base64.getUrlEncoder().withoutPadding()
			.encodeToString(json.getBytes(StandardCharsets.UTF_8));
		if (encoded.length() > MAX_ENCODED_LENGTH) {
			throw invalid();
		}
		return encoded;
	}

	/** Parses only the canonical BTAE1 representation. Errors never include invitation data. */
	public static BtaeInvitation parse(String encoded) {
		try {
			if (encoded == null || encoded.length() > MAX_ENCODED_LENGTH || !encoded.startsWith(PREFIX)) {
				throw invalid();
			}
			String payload = encoded.substring(PREFIX.length());
			if (payload.isEmpty() || !payload.matches("[A-Za-z0-9_-]+")) {
				throw invalid();
			}
			byte[] bytes = Base64.getUrlDecoder().decode(payload);
			if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(payload)) {
				throw invalid();
			}
			String json = StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(bytes)).toString();
			BtaeInvitation invitation = fromJson(json);
			if (!invitation.encode().equals(encoded)) {
				throw invalid();
			}
			return invitation;
		} catch (IllegalArgumentException exception) {
			if ("invitation is invalid".equals(exception.getMessage())) {
				throw exception;
			}
			throw invalid();
		} catch (IOException exception) {
			throw invalid();
		}
	}

	private static BtaeInvitation fromJson(String json) throws IOException {
		Map<String, String> values = new HashMap<>();
		try (JsonReader reader = new JsonReader(new StringReader(json))) {
			reader.beginObject();
			while (reader.hasNext()) {
				String name = reader.nextName();
				if (!FIELDS.contains(name) || values.containsKey(name)) {
					throw invalid();
				}
				JsonToken token = reader.peek();
				if (token != JsonToken.STRING && token != JsonToken.NUMBER) {
					throw invalid();
				}
				values.put(name, reader.nextString());
			}
			reader.endObject();
			if (reader.peek() != JsonToken.END_DOCUMENT || !values.keySet().equals(FIELDS)) {
				throw invalid();
			}
		}
		if (!"1".equals(values.get("version"))) {
			throw invalid();
		}
		int port = parseCanonicalInt(values.get("relayPort"));
		long expiry = parseCanonicalLong(values.get("expiresAtEpochMillis"));
		return new BtaeInvitation(
			values.get("invitationId"), values.get("hostSessionId"), values.get("hostSpkiSha256"),
			values.get("relayHost"), port, values.get("relaySpkiSha256"), expiry,
			values.get("joinCapability"), values.get("statusCapability")
		);
	}

	private String canonicalJson() {
		return "{\"version\":1,\"invitationId\":\"" + invitationId
			+ "\",\"hostSessionId\":\"" + hostSessionId
			+ "\",\"hostSpkiSha256\":\"" + hostSpkiSha256
			+ "\",\"relayHost\":\"" + relayHost
			+ "\",\"relayPort\":" + relayPort
			+ ",\"relaySpkiSha256\":\"" + relaySpkiSha256
			+ "\",\"expiresAtEpochMillis\":" + expiresAtEpochMillis
			+ ",\"joinCapability\":\"" + joinCapability
			+ "\",\"statusCapability\":\"" + statusCapability + "\"}";
	}

	private static String requireRandomId(String value) {
		if (value == null || !BASE64URL_16.matcher(value).matches() || decodeBase64Url(value).length != 16) {
			throw invalid();
		}
		return value;
	}

	private static String requireSessionId(String value) {
		if (value == null || !BASE64URL_32.matcher(value).matches() || decodeBase64Url(value).length != 32) {
			throw invalid();
		}
		return value;
	}

	private static String requireCapability(String value) {
		if (value == null || !BASE64URL_32.matcher(value).matches() || decodeBase64Url(value).length != 32) {
			throw invalid();
		}
		return value;
	}

	private static byte[] decodeBase64Url(String value) {
		try {
			byte[] decoded = Base64.getUrlDecoder().decode(value);
			if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value)) {
				throw invalid();
			}
			return decoded;
		} catch (IllegalArgumentException exception) {
			throw invalid();
		}
	}

	private static String requirePin(String value) {
		if (value == null || !SHA256_HEX.matcher(value).matches()) {
			throw invalid();
		}
		return value;
	}

	private static String requireRelayHost(String value) {
		if (value == null || value.isBlank() || value.endsWith(".")) {
			throw invalid();
		}
		try {
			String ascii = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).toLowerCase(java.util.Locale.ROOT);
			if (!ascii.equals(value) || ascii.length() > 253 || ascii.chars().anyMatch(Character::isISOControl)) {
				throw invalid();
			}
			return ascii;
		} catch (IllegalArgumentException exception) {
			throw invalid();
		}
	}

	private static int parseCanonicalInt(String value) {
		try {
			int parsed = Integer.parseInt(value);
			if (!Integer.toString(parsed).equals(value)) {
				throw invalid();
			}
			return parsed;
		} catch (NumberFormatException exception) {
			throw invalid();
		}
	}

	private static long parseCanonicalLong(String value) {
		try {
			long parsed = Long.parseLong(value);
			if (!Long.toString(parsed).equals(value)) {
				throw invalid();
			}
			return parsed;
		} catch (NumberFormatException exception) {
			throw invalid();
		}
	}

	private static IllegalArgumentException invalid() {
		return new IllegalArgumentException("invitation is invalid");
	}

	@Override
	public String toString() {
		return "BtaeInvitation[invitationId=[REDACTED], hostSessionId=[REDACTED], hostSpkiSha256="
			+ hostSpkiSha256 + ", relayHost=" + relayHost + ", relayPort=" + relayPort
			+ ", relaySpkiSha256=" + relaySpkiSha256 + ", expiresAtEpochMillis=" + expiresAtEpochMillis
			+ ", joinCapability=[REDACTED], statusCapability=[REDACTED]]";
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof BtaeInvitation invitation)) {
			return false;
		}
		return relayPort == invitation.relayPort
			&& expiresAtEpochMillis == invitation.expiresAtEpochMillis
			&& invitationId.equals(invitation.invitationId)
			&& hostSessionId.equals(invitation.hostSessionId)
			&& hostSpkiSha256.equals(invitation.hostSpkiSha256)
			&& relayHost.equals(invitation.relayHost)
			&& relaySpkiSha256.equals(invitation.relaySpkiSha256)
			&& joinCapability.equals(invitation.joinCapability)
			&& statusCapability.equals(invitation.statusCapability);
	}

	@Override
	public int hashCode() {
		return java.util.Objects.hash(invitationId, hostSessionId, hostSpkiSha256, relayHost, relayPort,
			relaySpkiSha256, expiresAtEpochMillis, joinCapability, statusCapability);
	}
}
