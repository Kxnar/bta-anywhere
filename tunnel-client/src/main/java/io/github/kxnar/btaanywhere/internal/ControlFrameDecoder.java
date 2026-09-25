package io.github.kxnar.btaanywhere.internal;

import com.google.gson.JsonParseException;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;
import java.util.List;

final class ControlFrameDecoder extends ByteToMessageDecoder {
	static final int MAX_FRAME_SIZE = 64 * 1024;
	@Override
	protected void decode(ChannelHandlerContext context, ByteBuf input, List<Object> output) {
		if (input.readableBytes() < Integer.BYTES) {
			return;
		}
		long length = input.getUnsignedInt(input.readerIndex());
		if (length > MAX_FRAME_SIZE) {
			throw new CorruptedFrameException("control frame exceeds 64 KiB");
		}
		if (input.readableBytes() < Integer.BYTES + length) {
			return;
		}
		input.skipBytes(Integer.BYTES);
		byte[] json = new byte[(int) length];
		input.readBytes(json);
		try {
			output.add(ProtocolFrames.parseObject(json));
		} catch (JsonParseException | IllegalArgumentException exception) {
			throw new CorruptedFrameException("control frame contains invalid JSON", exception);
		}
	}
}
