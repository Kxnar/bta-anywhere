package io.github.kxnar.btaanywhere.internal;

import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

/** Test-only Windows differential runner; no production entry point or dependency. */
public final class ProtocolCorpusMain {
	private ProtocolCorpusMain() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 2) {
			throw new IllegalArgumentException("usage: ProtocolCorpusMain <corpus.jsonl> <output.jsonl>");
		}
		try (BufferedReader input = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8);
			BufferedWriter output = Files.newBufferedWriter(Path.of(args[1]), StandardCharsets.UTF_8)) {
			String line;
			while ((line = input.readLine()) != null) {
				JsonObject testCase = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
				byte[] frame = HexFormat.of().parseHex(testCase.get("frameHex").getAsString());
				JsonObject semantic = decode(frame);
				JsonObject result = new JsonObject();
				result.add("id", testCase.get("id"));
				result.addProperty("accepted", semantic != null);
				if (semantic != null) {
					result.add("semantic", semantic);
				} else {
					result.add("semantic", com.google.gson.JsonNull.INSTANCE);
				}
				JsonObject typed = typedOutcome(semantic, testCase);
				result.addProperty("typedAccepted", typed.get("semantic") != null
					&& !typed.get("semantic").isJsonNull());
				result.add("typedSemantic", typed.get("semantic"));
				result.add("stateOutcome", typed.get("stateOutcome"));
				output.write(result.toString());
				output.newLine();
			}
		}
	}

	static JsonObject typedOutcome(JsonObject message, JsonObject testCase) {
		JsonObject outcome = new JsonObject();
		outcome.add("semantic", com.google.gson.JsonNull.INSTANCE);
		outcome.addProperty("stateOutcome", "syntax_rejected");
		if (message == null) {
			return outcome;
		}
		try {
			if ("connection".equals(testCase.get("target").getAsString())) {
				JsonObject fields = new JsonObject();
				fields.addProperty("version", ProtocolFrames.requiredInt(message, "version"));
				fields.addProperty("sessionId", ProtocolFrames.requiredString(message, "sessionId"));
				fields.addProperty("connectionId", ProtocolFrames.requiredString(message, "connectionId"));
				fields.addProperty("remoteAddress", ProtocolFrames.requiredString(message, "remoteAddress"));
				outcome.add("semantic", fields);
				try {
					IncomingTunnelHandler.validateConnectionHeader(message,
						testCase.get("expectedSession").getAsString());
					outcome.addProperty("stateOutcome", "open");
				} catch (RuntimeException exception) {
					outcome.addProperty("stateOutcome", "header_rejected");
				}
				return outcome;
			}
			String type = ProtocolFrames.requiredString(message, "type");
			String phase = testCase.get("phase").getAsString();
			JsonObject fields = new JsonObject();
			fields.addProperty("type", type);
			switch (type) {
				case "register" -> {
					int version = ProtocolFrames.requiredInt(message, "version");
					String token = ProtocolFrames.requiredString(message, "accessToken");
					String client = ProtocolFrames.requiredString(message, "clientInstanceId");
					fields.add("features", ProtocolFrames.registrationFeatures(message));
					fields.addProperty("version", version);
					fields.addProperty("accessToken", token);
					fields.addProperty("clientInstanceId", client);
					fields.add("resumeToken", message.has("resumeToken") && !message.get("resumeToken").isJsonNull()
						? new com.google.gson.JsonPrimitive(ProtocolFrames.requiredString(message, "resumeToken"))
						: com.google.gson.JsonNull.INSTANCE);
					String state = !"pre".equals(phase) ? "already_registered"
						: version != ProtocolFrames.VERSION ? "unsupported_version"
						: !"synthetic-test-token".equals(token) ? "authentication_failed"
						: client.isEmpty() || client.getBytes(StandardCharsets.UTF_8).length > 128
							? "registration_rejected" : "register";
					outcome.addProperty("stateOutcome", state);
				}
				case "ping" -> {
					fields.addProperty("sequence", ProtocolFrames.requiredUnsignedLong(message, "sequence"));
					outcome.addProperty("stateOutcome", "active".equals(phase) ? "pong" : "registration_required");
				}
				case "close" -> {
					fields.addProperty("reason", ProtocolFrames.requiredString(message, "reason"));
					outcome.addProperty("stateOutcome", "active".equals(phase) ? "close" : "registration_required");
				}
				case "streamEof" -> {
					String connectionId = ProtocolFrames.requiredString(message, "connectionId");
					java.math.BigInteger bytes = ProtocolFrames.requiredStreamEofBytes(message);
					fields.addProperty("connectionId", connectionId);
					fields.addProperty("bytes", bytes);
					boolean negotiated = testCase.has("featureNegotiated")
						&& testCase.get("featureNegotiated").getAsBoolean();
					String activeId = testCase.has("activeConnectionId")
						&& !testCase.get("activeConnectionId").isJsonNull()
							? testCase.get("activeConnectionId").getAsString() : null;
					boolean duplicate = testCase.has("noticeAlreadySent")
						&& testCase.get("noticeAlreadySent").getAsBoolean();
					long copied = testCase.has("copiedBytes") ? testCase.get("copiedBytes").getAsLong() : 0L;
					String state = !"active".equals(phase) ? "registration_required"
						: !negotiated ? "feature_not_negotiated"
						: !connectionId.equals(activeId) ? "late_notice"
						: duplicate || bytes.compareTo(java.math.BigInteger.valueOf(copied)) < 0
							? "notice_rejected" : "notice_accepted";
					outcome.addProperty("stateOutcome", state);
				}
				default -> {
					return outcome;
				}
			}
			outcome.add("semantic", fields);
		} catch (RuntimeException exception) {
			outcome.add("semantic", com.google.gson.JsonNull.INSTANCE);
			outcome.addProperty("stateOutcome", "syntax_rejected");
		}
		return outcome;
	}

	private static JsonObject decode(byte[] frame) {
		if (frame.length < Integer.BYTES ||
			Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(frame).getInt()) != frame.length - Integer.BYTES) {
			return null;
		}
		EmbeddedChannel channel = new EmbeddedChannel(new ControlFrameDecoder());
		try {
			ByteBuf bytes = channel.alloc().buffer(frame.length).writeBytes(frame);
			channel.writeInbound(bytes);
			JsonObject result = channel.readInbound();
			Object extra = channel.readInbound();
			return result != null && extra == null ? result : null;
		} catch (RuntimeException exception) {
			return null;
		} finally {
			try {
				channel.finishAndReleaseAll();
			} catch (DecoderException exception) {
				// Rejected partial/oversized input can also fail during decodeLast.
			}
		}
	}
}
