package io.github.kxnar.btaanywhere.internal;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.DuplexChannel;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.ReferenceCountUtil;
import java.nio.charset.StandardCharsets;

/** Parses one connection header and then bridges the remaining stream to the local service. */
final class IncomingTunnelHandler extends ChannelInboundHandlerAdapter {
	private static final Gson GSON = new Gson();
	private final NettyTunnelSession session;
	private ByteBuf headerBuffer;
	private int expectedLength = -1;
	private Channel localChannel;
	private ByteBuf pendingPayload;

	IncomingTunnelHandler(NettyTunnelSession session) {
		this.session = session;
	}

	@Override
	public void channelActive(ChannelHandlerContext context) {
		context.channel().config().setAutoRead(false);
		headerBuffer = context.alloc().buffer(256, ControlFrameDecoder.MAX_FRAME_SIZE + Integer.BYTES);
		context.read();
	}

	@Override
	public void channelRead(ChannelHandlerContext context, Object message) {
		if (!(message instanceof ByteBuf input)) {
			ReferenceCountUtil.release(message);
			context.close();
			return;
		}
		if (localChannel != null) {
			writeToLocal(context, input);
			return;
		}
		try {
			copyHeaderBytes(input);
			if (expectedLength >= 0 && headerBuffer.readableBytes() >= expectedLength && input.isReadable()) {
				pendingPayload = input.readRetainedSlice(input.readableBytes());
			}
		} finally {
			input.release();
		}
		if (!parseHeader(context)) {
			context.read();
		}
	}

	private void copyHeaderBytes(ByteBuf input) {
		if (expectedLength < 0) {
			int lengthBytesNeeded = Integer.BYTES - headerBuffer.readableBytes();
			int lengthBytes = Math.min(lengthBytesNeeded, input.readableBytes());
			headerBuffer.writeBytes(input, lengthBytes);
			if (headerBuffer.readableBytes() < Integer.BYTES) {
				return;
			}
			long length = headerBuffer.readUnsignedInt();
			if (length > ControlFrameDecoder.MAX_FRAME_SIZE) {
				throw new IllegalArgumentException("connection header exceeds 64 KiB");
			}
			expectedLength = (int) length;
		}
		int headerBytesNeeded = expectedLength - headerBuffer.readableBytes();
		int headerBytes = Math.min(headerBytesNeeded, input.readableBytes());
		headerBuffer.writeBytes(input, headerBytes);
	}

	private boolean parseHeader(ChannelHandlerContext context) {
		if (expectedLength < 0) {
			return false;
		}
		if (headerBuffer.readableBytes() < expectedLength) {
			return false;
		}
		byte[] jsonBytes = new byte[expectedLength];
		headerBuffer.readBytes(jsonBytes);
		JsonObject header = GSON.fromJson(new String(jsonBytes, StandardCharsets.UTF_8), JsonObject.class);
		if (header == null) {
			throw new IllegalArgumentException("connection header must be a JSON object");
		}
		int version = ProtocolFrames.requiredInt(header, "version");
		String headerSession = ProtocolFrames.requiredString(header, "sessionId");
		String connectionId = ProtocolFrames.requiredString(header, "connectionId");
		String remoteAddress = ProtocolFrames.requiredString(header, "remoteAddress");
		if (version != ProtocolFrames.VERSION || !headerSession.equals(session.sessionId())) {
			throw new IllegalArgumentException("connection stream belongs to an invalid protocol or session");
		}
		if (headerSession.isBlank() || headerSession.length() > 128
			|| connectionId.isBlank() || connectionId.length() > 128) {
			throw new IllegalArgumentException("connection stream identifiers are invalid");
		}
		validateRemoteAddress(remoteAddress);
		if (headerBuffer.isReadable()) {
			pendingPayload = headerBuffer.readRetainedSlice(headerBuffer.readableBytes());
		}
		headerBuffer.release();
		headerBuffer = null;
		connectLocal(context);
		return true;
	}

	static void validateRemoteAddress(String value) {
		if (value.isBlank() || value.length() > 128) {
			throw new IllegalArgumentException("remote address is invalid");
		}
		int separator;
		String host;
		if (value.startsWith("[")) {
			int closing = value.indexOf(']');
			if (closing <= 1 || closing + 1 >= value.length() || value.charAt(closing + 1) != ':') {
				throw new IllegalArgumentException("remote IPv6 address is invalid");
			}
			host = value.substring(1, closing);
			separator = closing + 1;
		} else {
			separator = value.lastIndexOf(':');
			if (separator <= 0 || value.substring(0, separator).indexOf(':') >= 0) {
				throw new IllegalArgumentException("remote address must use host:port syntax");
			}
			host = value.substring(0, separator);
		}
		try {
			int port = Integer.parseInt(value.substring(separator + 1));
			if (host.isBlank() || port < 1 || port > 65_535) {
				throw new IllegalArgumentException("remote address has an invalid host or port");
			}
		} catch (NumberFormatException exception) {
			throw new IllegalArgumentException("remote address has an invalid port", exception);
		}
	}

	private void connectLocal(ChannelHandlerContext quicContext) {
		ChannelFuture future = new Bootstrap()
			.group(session.eventLoopGroup())
			.channel(NioSocketChannel.class)
			.handler(new LocalToQuicHandler(quicContext.channel()))
			.option(ChannelOption.AUTO_READ, false)
			.option(ChannelOption.ALLOW_HALF_CLOSURE, true)
			.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
			.connect(session.localTarget());
		future.addListener(result -> {
			if (!result.isSuccess()) {
				releasePending();
				quicContext.close();
				return;
			}
			if (!quicContext.channel().isActive()) {
				future.channel().close();
				releasePending();
				return;
			}
			localChannel = future.channel();
			if (pendingPayload != null) {
				ByteBuf payload = pendingPayload;
				pendingPayload = null;
				writeToLocal(quicContext, payload);
			} else {
				quicContext.read();
			}
		});
	}

	private void writeToLocal(ChannelHandlerContext quicContext, ByteBuf payload) {
		Channel local = localChannel;
		if (local == null || !local.isActive()) {
			payload.release();
			quicContext.close();
			return;
		}
		local.writeAndFlush(payload).addListener(result -> {
			if (result.isSuccess()) {
				quicContext.read();
			} else {
				quicContext.close();
			}
		});
	}

	@Override
	public void channelInactive(ChannelHandlerContext context) {
		releasePending();
		if (headerBuffer != null) {
			headerBuffer.release();
			headerBuffer = null;
		}
		if (localChannel != null && localChannel.isActive()) {
			localChannel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
		}
	}

	@Override
	public void userEventTriggered(ChannelHandlerContext context, Object event) throws Exception {
		if (event == ChannelInputShutdownEvent.INSTANCE || event == ChannelInputShutdownReadComplete.INSTANCE) {
			if (localChannel instanceof DuplexChannel duplex && localChannel.isActive()) {
				duplex.shutdownOutput();
			}
			return;
		}
		super.userEventTriggered(context, event);
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
		context.close();
	}

	private void releasePending() {
		if (pendingPayload != null) {
			pendingPayload.release();
			pendingPayload = null;
		}
	}

	private static final class LocalToQuicHandler extends ChannelInboundHandlerAdapter {
		private final Channel quicChannel;

		LocalToQuicHandler(Channel quicChannel) {
			this.quicChannel = quicChannel;
		}

		@Override
		public void channelActive(ChannelHandlerContext context) {
			context.read();
		}

		@Override
		public void channelRead(ChannelHandlerContext context, Object message) {
			quicChannel.writeAndFlush(message).addListener(result -> {
				if (result.isSuccess()) {
					context.read();
				} else {
					context.close();
				}
			});
		}

		@Override
		public void channelInactive(ChannelHandlerContext context) {
			if (quicChannel.isActive()) {
				quicChannel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
			}
		}

		@Override
		public void userEventTriggered(ChannelHandlerContext context, Object event) throws Exception {
			if (event == ChannelInputShutdownEvent.INSTANCE || event == ChannelInputShutdownReadComplete.INSTANCE) {
				if (quicChannel instanceof DuplexChannel duplex && quicChannel.isActive()) {
					duplex.shutdownOutput();
				}
				return;
			}
			super.userEventTriggered(context, event);
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
			context.close();
			quicChannel.close();
		}
	}
}
