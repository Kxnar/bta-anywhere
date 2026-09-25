package io.github.kxnar.btaanywhere.internal;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.math.BigInteger;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashSet;
import java.util.Set;

final class ProtocolFrames {
	static final int VERSION = 1;
	static final String ALPN = "bta-anywhere/1";
	private static final Gson GSON = new Gson();
	private static final int MAX_JSON_CONTAINERS = 127;
	private static final Set<String> KNOWN_TOP_LEVEL_PROPERTIES = Set.of(
		"type", "version", "accessToken", "clientInstanceId", "resumeToken",
		"sequence", "reason", "sessionId", "publicHost", "publicPort",
		"leaseSeconds", "code", "message", "retryable", "connectionId", "remoteAddress"
	);

	private ProtocolFrames() {
	}

	static String decodeUtf8(byte[] bytes) {
		try {
			return StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(bytes)).toString();
		} catch (CharacterCodingException exception) {
			throw new IllegalArgumentException("protocol frame contains invalid UTF-8", exception);
		}
	}

	static JsonObject parseObject(byte[] bytes) {
		if (bytes.length > ControlFrameDecoder.MAX_FRAME_SIZE) {
			throw new IllegalArgumentException("protocol frame exceeds 64 KiB");
		}
		String json = decodeUtf8(bytes);
		try (JsonReader reader = new JsonReader(new StringReader(json))) {
			reader.setStrictness(Strictness.STRICT);
			scanValue(reader, 0);
			if (reader.peek() != JsonToken.END_DOCUMENT) {
				throw new IllegalArgumentException("protocol frame contains trailing JSON");
			}
		} catch (IOException exception) {
			throw new IllegalArgumentException("protocol frame contains invalid JSON", exception);
		}
		JsonElement parsed = JsonParser.parseString(json);
		if (!parsed.isJsonObject()) {
			throw new IllegalArgumentException("protocol frame must contain a JSON object");
		}
		return parsed.getAsJsonObject();
	}

	private static void scanValue(JsonReader reader, int containers) throws IOException {
		switch (reader.peek()) {
			case BEGIN_OBJECT -> {
				checkDepth(containers);
				reader.beginObject();
				Set<String> seenKnown = containers == 0 ? new HashSet<>() : null;
				while (reader.hasNext()) {
					String name = reader.nextName();
					if (seenKnown != null && KNOWN_TOP_LEVEL_PROPERTIES.contains(name)
						&& !seenKnown.add(name)) {
						throw new IllegalArgumentException("repeated protocol property: " + name);
					}
					scanValue(reader, containers + 1);
				}
				reader.endObject();
			}
			case BEGIN_ARRAY -> {
				checkDepth(containers);
				reader.beginArray();
				while (reader.hasNext()) {
					scanValue(reader, containers + 1);
				}
				reader.endArray();
			}
			case STRING, NUMBER -> reader.nextString();
			case BOOLEAN -> reader.nextBoolean();
			case NULL -> reader.nextNull();
			default -> throw new IllegalArgumentException("protocol frame contains invalid JSON value");
		}
	}

	private static void checkDepth(int containers) {
		if (containers >= MAX_JSON_CONTAINERS) {
			throw new IllegalArgumentException("protocol JSON nesting exceeds 127 containers");
		}
	}

	static ChannelFuture write(Channel channel, JsonObject message) {
		byte[] bytes = GSON.toJson(message).getBytes(StandardCharsets.UTF_8);
		if (bytes.length > ControlFrameDecoder.MAX_FRAME_SIZE) {
			throw new IllegalArgumentException("control frame exceeds 64 KiB");
		}
		ByteBuf output = channel.alloc().buffer(Integer.BYTES + bytes.length);
		output.writeInt(bytes.length);
		output.writeBytes(bytes);
		return channel.writeAndFlush(output);
	}

	static String requiredString(JsonObject object, String property) {
		JsonElement value = object.get(property);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
			throw new IllegalArgumentException("missing or invalid string protocol property: " + property);
		}
		return value.getAsString();
	}

	static int requiredInt(JsonObject object, String property) {
		JsonElement value = object.get(property);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
			throw new IllegalArgumentException("missing or invalid integer protocol property: " + property);
		}
		String literal = value.getAsString();
		// Version and publicPort are unsigned 16-bit integers on the Rust wire.
		if (!literal.matches("0|[1-9][0-9]*") || literal.length() > 5) {
			throw new IllegalArgumentException("invalid unsigned protocol property: " + property);
		}
		int result = Integer.parseInt(literal);
		if (result > 65_535) {
			throw new IllegalArgumentException("unsigned protocol property exceeds 16 bits: " + property);
		}
		return result;
	}

	static BigInteger requiredUnsignedLong(JsonObject object, String property) {
		if (!object.has(property) || !object.get(property).isJsonPrimitive()
			|| !object.get(property).getAsJsonPrimitive().isNumber()) {
			throw new IllegalArgumentException("missing or invalid protocol property: " + property);
		}
		String literal = object.get(property).getAsString();
		if (!literal.matches("0|[1-9][0-9]*")) {
			throw new IllegalArgumentException("invalid unsigned protocol property: " + property);
		}
		BigInteger value = new BigInteger(literal);
		if (value.bitLength() > Long.SIZE) {
			throw new IllegalArgumentException("unsigned protocol property exceeds 64 bits: " + property);
		}
		return value;
	}
}
