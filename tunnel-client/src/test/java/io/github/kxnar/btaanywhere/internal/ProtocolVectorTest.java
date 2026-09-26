package io.github.kxnar.btaanywhere.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

final class ProtocolVectorTest {
	@Test
	void relayRetryabilityRequiresAJsonBoolean() {
		assertEquals(true, ProtocolFrames.requiredBoolean(
			JsonParser.parseString("{\"retryable\":true}").getAsJsonObject(), "retryable"));
		assertEquals(false, ProtocolFrames.requiredBoolean(
			JsonParser.parseString("{\"retryable\":false}").getAsJsonObject(), "retryable"));
		for (String payload : new String[] {
			"{}", "{\"retryable\":null}", "{\"retryable\":\"true\"}",
			"{\"retryable\":1}"
		}) {
			assertThrows(IllegalArgumentException.class,
				() -> ProtocolFrames.requiredBoolean(
					JsonParser.parseString(payload).getAsJsonObject(), "retryable"), payload);
		}
	}

	@Test
	void unsignedProtocolCountRejectsLongDigitsBeforeConversion() {
		JsonObject message = JsonParser.parseString(
			"{\"sequence\":" + "9".repeat(1000) + "}"
		).getAsJsonObject();
		assertThrows(IllegalArgumentException.class,
			() -> ProtocolFrames.requiredUnsignedLong(message, "sequence"));
	}

	@Test
	void sharedTypedBoundariesMatchV1FieldTypes() throws Exception {
		Path vectors = Path.of(System.getProperty("btaAnywhereProtocolVectors"));
		JsonObject document = JsonParser.parseString(
			Files.readString(vectors.resolve("typed-boundaries-v1.json"), StandardCharsets.UTF_8)
		).getAsJsonObject();
		assertEquals(1, document.get("schemaVersion").getAsInt());
		for (var item : document.getAsJsonArray("cases")) {
			JsonObject vector = item.getAsJsonObject();
			String name = vector.get("name").getAsString();
			byte[] payload = vector.get("payloadUtf8").getAsString().getBytes(StandardCharsets.UTF_8);
			JsonObject message = ProtocolFrames.parseObject(payload);
			JsonObject outcome = ProtocolCorpusMain.typedOutcome(message, vector);
			boolean accepted = !outcome.get("semantic").isJsonNull();
			assertEquals(vector.get("typedAccepted").getAsBoolean(), accepted, name);
		}
	}

	@Test
	void sharedStructuralVectorsMatchV1Boundaries() throws Exception {
		Path vectors = Path.of(System.getProperty("btaAnywhereProtocolVectors"));
		JsonObject document = JsonParser.parseString(
			Files.readString(vectors.resolve("structural-v1.json"), StandardCharsets.UTF_8)
		).getAsJsonObject();
		assertEquals(1, document.get("schemaVersion").getAsInt());
		for (var item : document.getAsJsonArray("cases")) {
			JsonObject vector = item.getAsJsonObject();
			String name = vector.get("name").getAsString();
			boolean accepted = vector.get("accepted").getAsBoolean();
			byte[] frame = HexFormat.of().parseHex(vector.get("frameHex").getAsString());
			if (vector.get("target").getAsString().equals("connection")) {
				byte[] payload = java.util.Arrays.copyOfRange(frame, Integer.BYTES, frame.length);
				if (accepted) {
					assertNotNull(IncomingTunnelHandler.decodeConnectionHeader(payload), name);
				} else {
					assertThrows(IllegalArgumentException.class,
						() -> IncomingTunnelHandler.decodeConnectionHeader(payload), name);
				}
				continue;
			}
			EmbeddedChannel decoder = new EmbeddedChannel(new ControlFrameDecoder());
			try {
				if (accepted) {
					decoder.writeInbound(decoder.alloc().buffer(frame.length).writeBytes(frame));
					assertNotNull(decoder.readInbound(), name);
				} else {
					assertThrows(DecoderException.class,
						() -> decoder.writeInbound(decoder.alloc().buffer(frame.length).writeBytes(frame)),
						name);
				}
			} finally {
				try {
					decoder.finishAndReleaseAll();
				} catch (DecoderException exception) {
					if (accepted) {
						throw exception;
					}
				}
			}
		}
	}
	@Test
	void rejectsSharedMalformedUtf8AndNonObjectVectors() throws Exception {
		Path vectors = Path.of(System.getProperty("btaAnywhereProtocolVectors"));
		JsonObject document = JsonParser.parseString(
			Files.readString(vectors.resolve("malformed-v1.json"), StandardCharsets.UTF_8)
		).getAsJsonObject();
		document.getAsJsonArray("cases").forEach(element -> {
			JsonObject vector = element.getAsJsonObject();
			String name = vector.get("name").getAsString();
			if (!name.equals("invalid-utf8-object") && !name.equals("invalid-utf8-unknown-control")
				&& !name.equals("non-object-array")) {
				return;
			}
			byte[] frame = HexFormat.of().parseHex(vector.get("frameHex").getAsString());
			EmbeddedChannel decoder = new EmbeddedChannel(new ControlFrameDecoder());
			assertThrows(DecoderException.class,
				() -> decoder.writeInbound(decoder.alloc().buffer(frame.length).writeBytes(frame)), name);
			decoder.finishAndReleaseAll();
		});
	}

	@Test
	void rejectsSharedStringSequenceAtTypedControlBoundary() throws Exception {
		Path vectors = Path.of(System.getProperty("btaAnywhereProtocolVectors"));
		JsonObject document = JsonParser.parseString(
			Files.readString(vectors.resolve("malformed-v1.json"), StandardCharsets.UTF_8)
		).getAsJsonObject();
		for (var item : document.getAsJsonArray("cases")) {
			JsonObject vector = item.getAsJsonObject();
			if (!vector.get("name").getAsString().equals("string-sequence")) {
				continue;
			}
			byte[] frame = HexFormat.of().parseHex(vector.get("frameHex").getAsString());
			EmbeddedChannel decoder = new EmbeddedChannel(new ControlFrameDecoder());
			decoder.writeInbound(decoder.alloc().buffer(frame.length).writeBytes(frame));
			JsonObject message = decoder.readInbound();
			assertThrows(IllegalArgumentException.class,
				() -> ProtocolFrames.requiredUnsignedLong(message, "sequence"));
			decoder.finishAndReleaseAll();
			return;
		}
		throw new AssertionError("string-sequence vector is missing");
	}

	@Test
	void rejectsSharedInvalidUtf8ConnectionHeader() throws Exception {
		Path vectors = Path.of(System.getProperty("btaAnywhereProtocolVectors"));
		JsonObject document = JsonParser.parseString(
			Files.readString(vectors.resolve("malformed-v1.json"), StandardCharsets.UTF_8)
		).getAsJsonObject();
		var checked = new java.util.HashSet<String>();
		for (var item : document.getAsJsonArray("cases")) {
			JsonObject vector = item.getAsJsonObject();
			String name = vector.get("name").getAsString();
			if (!name.equals("invalid-utf8-connection") && !name.equals("invalid-utf8-unknown-connection")) {
				continue;
			}
			checked.add(name);
			byte[] frame = HexFormat.of().parseHex(vector.get("frameHex").getAsString());
			byte[] payload = java.util.Arrays.copyOfRange(frame, Integer.BYTES, frame.length);
			assertThrows(IllegalArgumentException.class,
				() -> IncomingTunnelHandler.decodeConnectionHeader(payload));
		}
		assertEquals(2, checked.size(), "both invalid UTF-8 connection vectors must be present");
	}
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
