package io.github.kxnar.btaanywhere.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

final class ProtocolVectorTest {
	@Test
	void decodesAndEncodesSharedProtocolVectorsByteExactly() throws Exception {
		Path vectors = Path.of(System.getProperty("btaAnywhereProtocolVectors"));
		JsonObject document = JsonParser.parseString(
			Files.readString(vectors.resolve("framing-v1.json"), StandardCharsets.UTF_8)
		).getAsJsonObject();
		assertEquals(ProtocolFrames.VERSION, document.get("protocolVersion").getAsInt());
		assertEquals(ProtocolFrames.ALPN, document.get("alpn").getAsString());

		document.getAsJsonArray("frames").forEach(element -> {
			JsonObject vector = element.getAsJsonObject();
			byte[] expectedFrame = HexFormat.of().parseHex(vector.get("frameHex").getAsString());
			JsonObject expectedJson = JsonParser.parseString(vector.get("json").getAsString()).getAsJsonObject();
			assertEquals(vector.get("payloadLength").getAsInt(), expectedFrame.length - Integer.BYTES);

			EmbeddedChannel decoder = new EmbeddedChannel(new ControlFrameDecoder());
			decoder.writeInbound(decoder.alloc().buffer(expectedFrame.length).writeBytes(expectedFrame));
			JsonObject decoded = decoder.readInbound();
			assertEquals(expectedJson, decoded, vector.get("name").getAsString());
			decoder.finishAndReleaseAll();

			EmbeddedChannel encoder = new EmbeddedChannel();
			ProtocolFrames.write(encoder, expectedJson).syncUninterruptibly();
			ByteBuf encoded = encoder.readOutbound();
			byte[] actualFrame = new byte[encoded.readableBytes()];
			encoded.readBytes(actualFrame);
			encoded.release();
			assertEquals(
				HexFormat.of().formatHex(expectedFrame),
				HexFormat.of().formatHex(actualFrame),
				vector.get("name").getAsString()
			);
			encoder.finishAndReleaseAll();
		});
	}
}
