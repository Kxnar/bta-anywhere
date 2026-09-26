package io.github.kxnar.btaanywhere.encrypted;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Strict AUTH body inside inner TLS; parse failures never include capability text. */
public final class EncryptedAuth {
	private static final Set<String> BASE = Set.of("version", "invitationId", "hostSessionId", "intent", "capability");
	private static final Set<String> STATUS = Set.of("version", "invitationId", "hostSessionId", "intent", "capability", "statusVariant");
	private final String invitationId;
	private final String hostSessionId;
	private final String intent;
	private final String capability;
	private final String statusVariant;

	private EncryptedAuth(String invitationId, String hostSessionId, String intent,
		String capability, String statusVariant) {
		this.invitationId = invitationId;
		this.hostSessionId = hostSessionId;
		this.intent = intent;
		this.capability = capability;
		this.statusVariant = statusVariant;
	}

	public static EncryptedAuth forInvitation(BtaeInvitation invitation, boolean status, boolean icon) {
		return new EncryptedAuth(invitation.invitationId(), invitation.hostSessionId(),
			status ? "status" : "join", status ? invitation.statusCapability() : invitation.joinCapability(),
			status ? (icon ? "icon" : "basic") : null);
	}

	public String invitationId() { return invitationId; }
	public String hostSessionId() { return hostSessionId; }
	public String intent() { return intent; }
	public String capability() { return capability; }
	public boolean statusIcon() { return "icon".equals(statusVariant); }

	public byte[] encode() {
		String json = "{\"version\":1,\"invitationId\":\"" + invitationId
			+ "\",\"hostSessionId\":\"" + hostSessionId
			+ "\",\"intent\":\"" + intent
			+ "\",\"capability\":\"" + capability + "\""
			+ (statusVariant == null ? "" : ",\"statusVariant\":\"" + statusVariant + "\"") + "}";
		return json.getBytes(StandardCharsets.UTF_8);
	}

	public static EncryptedAuth parse(byte[] body) {
		if (body == null || body.length == 0 || body.length > 2048) { throw invalid(); }
		try {
			String json = StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(body)).toString();
			Map<String, String> values = new HashMap<>();
			try (JsonReader reader = new JsonReader(new StringReader(json))) {
				reader.beginObject();
				while (reader.hasNext()) {
					String name = reader.nextName();
					if (!STATUS.contains(name) || values.containsKey(name)) { throw invalid(); }
					JsonToken token = reader.peek();
					if ("version".equals(name) ? token != JsonToken.NUMBER : token != JsonToken.STRING) {
						throw invalid();
					}
					values.put(name, reader.nextString());
				}
				reader.endObject();
				if (reader.peek() != JsonToken.END_DOCUMENT || !"1".equals(values.get("version"))) {
					throw invalid();
				}
			}
			String intent = values.get("intent");
			if (("join".equals(intent) && !values.keySet().equals(BASE))
				|| ("status".equals(intent) && !values.keySet().equals(STATUS))
				|| (!"join".equals(intent) && !"status".equals(intent))) { throw invalid(); }
			String variant = values.get("statusVariant");
			if ("status".equals(intent) && !"basic".equals(variant) && !"icon".equals(variant)) {
				throw invalid();
			}
			EncryptedAuth auth = new EncryptedAuth(values.get("invitationId"),
				values.get("hostSessionId"), intent, values.get("capability"), variant);
			if (auth.invitationId == null || auth.hostSessionId == null || auth.capability == null) {
				throw invalid();
			}
			return auth;
		} catch (IOException failure) {
			throw invalid();
		}
	}

	private static IllegalArgumentException invalid() {
		return new IllegalArgumentException("encrypted AUTH is invalid");
	}

	@Override
	public String toString() { return "EncryptedAuth[REDACTED]"; }
}
