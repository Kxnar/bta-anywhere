package io.github.kxnar.btaanywhere.internal;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import java.nio.charset.StandardCharsets;

final class ProtocolFrames {
	static final int VERSION = 1;
	static final String ALPN = "bta-anywhere/1";
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
}
