package io.github.kxnar.btaanywhere.encrypted;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class EncryptedProtocolTest {
	private static final String ID = "AQEBAQEBAQEBAQEBAQEBAQ";
	private static final String SESSION = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI";
	private static final String JOIN = "AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM";
	private static final String STATUS = "BAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQ";

	private static BtaeInvitation invitation() {
		return new BtaeInvitation(ID, SESSION, "a".repeat(64), "localhost", 30_000,
			"b".repeat(64), 2_000_000L, JOIN, STATUS);
	}

	@Test
	void authIsStrictAndDoesNotRevealCapabilities() {
		EncryptedAuth join = EncryptedAuth.forInvitation(invitation(), false, false);
		EncryptedAuth parsed = EncryptedAuth.parse(join.encode());
		assertEquals("join", parsed.intent());
		assertEquals(JOIN, parsed.capability());
		assertFalse(parsed.toString().contains(JOIN));
		EncryptedAuth status = EncryptedAuth.parse(EncryptedAuth.forInvitation(invitation(), true, true).encode());
		assertTrue(status.statusIcon());
		String json = new String(join.encode(), StandardCharsets.UTF_8);
		assertThrows(IllegalArgumentException.class, () -> EncryptedAuth.parse(
			json.replace("\"version\":1", "\"version\":1,\"version\":1")
				.getBytes(StandardCharsets.UTF_8)));
		assertThrows(IllegalArgumentException.class, () -> EncryptedAuth.parse(
			json.replace("\"intent\":\"join\"", "\"intent\":\"status\"")
				.getBytes(StandardCharsets.UTF_8)));
		assertThrows(IllegalArgumentException.class, () -> EncryptedAuth.parse(new byte[] { (byte) 0xFF }));
		assertThrows(IllegalArgumentException.class, () -> EncryptedAuth.parse(new byte[2049]));
	}

	@Test
	void encryptedFramesRejectUnknownOversizedAndTruncatedBodies() throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		EncryptedFrames.write(bytes, EncryptedFrames.DATA, new byte[] { 2, 7 });
		EncryptedFrames.Frame frame = EncryptedFrames.read(new ByteArrayInputStream(bytes.toByteArray()));
		assertEquals(EncryptedFrames.DATA, frame.type());
		assertArrayEquals(new byte[] { 2, 7 }, frame.payload());
		assertThrows(IllegalArgumentException.class, () -> EncryptedFrames.validate(6, 0));
		assertThrows(IllegalArgumentException.class, () -> EncryptedFrames.validate(EncryptedFrames.DATA, 16_385));
		assertThrows(IllegalArgumentException.class, () -> EncryptedFrames.validate(EncryptedFrames.FIN, 1));
		assertThrows(IOException.class, () -> EncryptedFrames.read(new ByteArrayInputStream(new byte[] { 3, 0, 2, 1 })));
	}

	@Test
	void statusClassifierCoversBothBtaVariantsAndRejectsMalformedInput() throws IOException {
		for (boolean icon : new boolean[] { false, true }) {
			byte[] request = BtaStatusProbe.serverRequest(icon, 30_000);
			assertEquals(0xFE, request[0] & 0xFF);
			assertEquals(icon, BtaStatusProbe.readVariant(
				new ByteArrayInputStream(request, 1, request.length - 1)));
		}
		byte[] invalid = BtaStatusProbe.serverRequest(false, 30_000);
		invalid[2] = 0;
		assertThrows(IOException.class, () -> BtaStatusProbe.readVariant(
			new ByteArrayInputStream(invalid, 1, invalid.length - 1)));
		assertThrows(IOException.class, () -> BtaStatusProbe.readVariant(new ByteArrayInputStream(new byte[] { 2 })));
	}
}
