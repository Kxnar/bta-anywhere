package io.github.kxnar.btaanywhere.internal;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.math.BigInteger;

final class ProtocolFrames {
	static final int VERSION = 1;
	static final String ALPN = "bta-anywhere/1";
	private static final Gson GSON = new Gson();

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
		if (!object.has(property) || object.get(property).isJsonNull()) {
			throw new IllegalArgumentException("missing protocol property: " + property);
		}
		return object.get(property).getAsString();
	}

	static int requiredInt(JsonObject object, String property) {
		if (!object.has(property) || object.get(property).isJsonNull()) {
			throw new IllegalArgumentException("missing protocol property: " + property);
		}
		return object.get(property).getAsInt();
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
