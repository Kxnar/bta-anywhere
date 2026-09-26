package io.github.kxnar.btaanywhere.encrypted;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Bounded application framing inside end-to-end TLS. */
public final class EncryptedFrames {
	public static final int AUTH = 1;
	public static final int AUTH_OK = 2;
	public static final int DATA = 3;
	public static final int FIN = 4;
	public static final int ERROR = 5;
	public static final int MAX_PAYLOAD = 16 * 1024;

	private EncryptedFrames() { }

	public record Frame(int type, byte[] payload) { }

	public static ByteBuf encode(ByteBufAllocator allocator, int type, byte[] payload) {
		validate(type, payload.length);
		ByteBuf buffer = allocator.buffer(3 + payload.length);
		buffer.writeByte(type).writeShort(payload.length).writeBytes(payload);
		return buffer;
	}

	public static void write(OutputStream output, int type, byte[] payload) throws IOException {
		validate(type, payload.length);
		DataOutputStream data = new DataOutputStream(output);
		data.writeByte(type);
		data.writeShort(payload.length);
		data.write(payload);
		data.flush();
	}

	public static Frame read(InputStream input) throws IOException {
		DataInputStream data = new DataInputStream(input);
		int type = data.readUnsignedByte();
		int length = data.readUnsignedShort();
		validate(type, length);
		byte[] payload = new byte[length];
		data.readFully(payload);
		return new Frame(type, payload);
	}

	public static void validate(int type, int length) {
		if (type < AUTH || type > ERROR || length < 0 || length > MAX_PAYLOAD
			|| ((type == AUTH_OK || type == FIN) && length != 0)
			|| (type == AUTH && length > 2048)) {
			throw new IllegalArgumentException("encrypted frame is invalid");
		}
	}
}
