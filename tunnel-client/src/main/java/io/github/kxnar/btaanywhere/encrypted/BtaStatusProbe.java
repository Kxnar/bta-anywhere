package io.github.kxnar.btaanywhere.encrypted;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Strict BTA 8.0.1 server-list request classifier. The host constructs its own request. */
public final class BtaStatusProbe {
	private static final String MAGIC = "BTAPingHost";
	private BtaStatusProbe() { }

	/** Caller has already consumed the leading 0xFE packet ID. */
	public static boolean readVariant(InputStream source) throws IOException {
		DataInputStream input = new DataInputStream(source);
		int payload = input.readUnsignedByte();
		if (payload != 2 && payload != 3) { throw invalid(); }
		if (input.readUnsignedByte() != 250 || !MAGIC.equals(readUtf16(input, 32))) { throw invalid(); }
		int expectedSize = 3 + MAGIC.length() * 2 + 4;
		if (input.readUnsignedShort() != expectedSize || input.readUnsignedByte() != 1) { throw invalid(); }
		String requestedHost = readUtf16(input, 255);
		if (requestedHost.isBlank() || requestedHost.chars().anyMatch(Character::isISOControl)) { throw invalid(); }
		int port = input.readInt();
		if (port < 1 || port > 65_535) { throw invalid(); }
		return payload == 3;
	}

	public static byte[] serverRequest(boolean icon, int port) throws IOException {
		if (port < 1 || port > 65_535) { throw invalid(); }
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream output = new DataOutputStream(bytes);
		output.writeByte(0xFE);
		output.writeByte(icon ? 3 : 2);
		output.writeByte(250);
		writeUtf16(output, MAGIC);
		output.writeShort(3 + MAGIC.length() * 2 + 4);
		output.writeByte(1);
		writeUtf16(output, "localhost");
		output.writeInt(port);
		return bytes.toByteArray();
	}

	private static String readUtf16(DataInputStream input, int maxCharacters) throws IOException {
		int characters = input.readUnsignedShort();
		if (characters == 0 || characters > maxCharacters) { throw invalid(); }
		byte[] bytes = new byte[characters * 2];
		input.readFully(bytes);
		try {
			return StandardCharsets.UTF_16BE.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(bytes)).toString();
		} catch (java.nio.charset.CharacterCodingException failure) {
			throw invalid();
		}
	}

	private static void writeUtf16(DataOutputStream output, String value) throws IOException {
		output.writeShort(value.length());
		output.write(value.getBytes(StandardCharsets.UTF_16BE));
	}

	private static IOException invalid() { return new IOException("unsupported BTA status probe"); }
}
