package io.github.kxnar.btaanywhere.internal;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

final class ProtocolFrames {
	static final int VERSION = 1;
	static final String ALPN = "bta-anywhere/1";
	static final String STREAM_EOF_BYTES = "streamEofBytes";
	private static final Gson GSON = new Gson();

	private ProtocolFrames() {
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

	static void requireStreamEofFeature(JsonObject registered) {
		JsonElement element = registered.get("features");
		if (element == null || !element.isJsonArray()) {
			throw new IllegalArgumentException(
				"self-hosted relay lacks reliable stream completion; update the relay to this BTA Anywhere version"
			);
		}
		if (element.getAsJsonArray().size() > 16) {
			throw new IllegalArgumentException("relay returned too many features");
		}
		Set<String> features = new HashSet<>();
		for (JsonElement item : element.getAsJsonArray()) {
			if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()
				|| !features.add(item.getAsString())) {
				throw new IllegalArgumentException("relay returned a malformed feature list");
			}
		}
		if (!features.contains(STREAM_EOF_BYTES)) {
			throw new IllegalArgumentException(
				"self-hosted relay lacks reliable stream completion; update the relay to this BTA Anywhere version"
			);
		}
	}

	static JsonObject streamEofMessage(String connectionId, long bytes) {
		if (connectionId == null || connectionId.isBlank() || connectionId.length() > 128 || bytes < 0) {
			throw new IllegalArgumentException("invalid stream completion notice");
		}
		JsonObject message = new JsonObject();
		message.addProperty("type", "streamEof");
		message.addProperty("connectionId", connectionId);
		message.addProperty("bytes", bytes);
		return message;
	}
}
