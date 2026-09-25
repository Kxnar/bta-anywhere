package io.github.kxnar.btaanywhere.internal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

final class StreamEofProtocolTest {
	@Test
	void requiresAnExplicitStringFeatureAcknowledgement() {
		assertDoesNotThrow(() -> ProtocolFrames.requireStreamEofFeature(
			JsonParser.parseString("{\"features\":[\"streamEofBytes\"]}").getAsJsonObject()));
		for (String malformed : new String[] {
			"{}", "{\"features\":null}", "{\"features\":\"streamEofBytes\"}",
			"{\"features\":[1]}", "{\"features\":[\"other\"]}",
			"{\"features\":[\"streamEofBytes\",1]}",
			"{\"features\":[\"streamEofBytes\",\"streamEofBytes\"]}"
		}) {
			assertThrows(IllegalArgumentException.class,
				() -> ProtocolFrames.requireStreamEofFeature(JsonParser.parseString(malformed).getAsJsonObject()),
				malformed);
		}
		assertThrows(IllegalArgumentException.class, () -> ProtocolFrames.requireStreamEofFeature(
			JsonParser.parseString("{\"features\":[" + "\"extra\",".repeat(16)
				+ "\"streamEofBytes\"]}").getAsJsonObject()));
	}

	@Test
	void emitsAnExactNonnegativeByteCount() {
		JsonObject message = ProtocolFrames.streamEofMessage("connection-1", 65_536L);
		assertEquals("streamEof", message.get("type").getAsString());
		assertEquals("connection-1", message.get("connectionId").getAsString());
		assertEquals(65_536L, message.get("bytes").getAsLong());
		assertEquals("0", ProtocolFrames.streamEofMessage("connection-1", 0).get("bytes").getAsString());
		assertEquals(Long.toString(Long.MAX_VALUE),
			ProtocolFrames.streamEofMessage("connection-1", Long.MAX_VALUE).get("bytes").getAsString());
	}

	@Test
	void rejectsInvalidCompletionIdentityAndCounts() {
		assertThrows(IllegalArgumentException.class, () -> ProtocolFrames.streamEofMessage(null, 0));
		assertThrows(IllegalArgumentException.class, () -> ProtocolFrames.streamEofMessage(" ", 0));
		assertThrows(IllegalArgumentException.class, () -> ProtocolFrames.streamEofMessage("x".repeat(129), 0));
		assertThrows(IllegalArgumentException.class, () -> ProtocolFrames.streamEofMessage("connection-1", -1));
	}
}
