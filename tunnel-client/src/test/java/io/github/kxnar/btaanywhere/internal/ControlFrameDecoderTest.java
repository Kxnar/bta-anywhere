package io.github.kxnar.btaanywhere.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class ControlFrameDecoderTest {
	@Test
	void decodesFragmentedFrame() {
		EmbeddedChannel channel = new EmbeddedChannel(new ControlFrameDecoder());
		byte[] json = "{\"type\":\"pong\",\"sequence\":7}".getBytes(StandardCharsets.UTF_8);
		ByteBuf frame = Unpooled.buffer().writeInt(json.length).writeBytes(json);
		assertFalse(channel.writeInbound(frame.readRetainedSlice(3)));
		channel.writeInbound(frame.readRetainedSlice(frame.readableBytes()));
		JsonObject decoded = channel.readInbound();
		assertEquals("pong", decoded.get("type").getAsString());
		assertEquals(7, decoded.get("sequence").getAsInt());
		frame.release();
		channel.finishAndReleaseAll();
	}

	@Test
	void rejectsOversizedFrame() {
		EmbeddedChannel channel = new EmbeddedChannel(new ControlFrameDecoder());
		ByteBuf frame = Unpooled.buffer().writeInt(ControlFrameDecoder.MAX_FRAME_SIZE + 1);
		assertThrows(DecoderException.class, () -> channel.writeInbound(frame));
	}
}
