package io.github.kxnar.btaanywhere.internal;

import com.google.gson.JsonObject;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.DuplexChannel;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.util.ReferenceCountUtil;

/** Parses one connection header and then bridges the remaining stream to the local service. */
final class IncomingTunnelHandler extends ChannelInboundHandlerAdapter {
	private final NettyTunnelSession session;
	private final long connectionGeneration;
	private ByteBuf headerBuffer;
	private int expectedLength = -1;
	private volatile Channel localChannel;
	private ByteBuf pendingPayload;
	private volatile boolean quicInputShutdown;
	private volatile ChannelFuture lastLocalWrite;
	private boolean localOutputShutdownScheduled;
	private String traceId;
	private String connectionId;

	IncomingTunnelHandler(NettyTunnelSession session, long connectionGeneration) {
		this.session = session;
		this.connectionGeneration = connectionGeneration;
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
			requestRead(context);
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
		JsonObject header = decodeConnectionHeader(jsonBytes);
		validateConnectionHeader(header, session.sessionId());
		connectionId = ProtocolFrames.requiredString(header, "connectionId");
		traceId = EofTrace.id(connectionId);
		if (headerBuffer.isReadable()) {
			pendingPayload = headerBuffer.readRetainedSlice(headerBuffer.readableBytes());
		}
		headerBuffer.release();
		headerBuffer = null;
		connectLocal(context);
		return true;
	}

	static JsonObject decodeConnectionHeader(byte[] jsonBytes) {
		return ProtocolFrames.parseObject(jsonBytes);
	}

	static void validateConnectionHeader(JsonObject header, String expectedSession) {
		if (header == null) {
			throw new IllegalArgumentException("connection header must be a JSON object");
		}
		int version = ProtocolFrames.requiredInt(header, "version");
		String headerSession = ProtocolFrames.requiredString(header, "sessionId");
		String connectionId = ProtocolFrames.requiredString(header, "connectionId");
		String remoteAddress = ProtocolFrames.requiredString(header, "remoteAddress");
		if (version != ProtocolFrames.VERSION || !headerSession.equals(expectedSession)) {
			throw new IllegalArgumentException("connection stream belongs to an invalid protocol or session");
		}
		if (headerSession.isBlank() || headerSession.length() > 128
			|| connectionId.isBlank() || connectionId.length() > 128) {
			throw new IllegalArgumentException("connection stream identifiers are invalid");
		}
		validateRemoteAddress(remoteAddress);
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
			.handler(new LocalToQuicHandler(session, connectionGeneration, quicContext.channel(), connectionId, traceId))
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
			} else if (quicInputShutdown) {
				scheduleLocalOutputShutdown();
			} else {
				requestRead(quicContext);
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
		ChannelFuture write = local.writeAndFlush(payload);
		lastLocalWrite = write;
		write.addListener(result -> {
			if (result.isSuccess()) {
				if (quicInputShutdown) {
					scheduleLocalOutputShutdown();
				} else {
					requestRead(quicContext);
				}
			} else {
				closePair(quicContext.channel(), local);
			}
		});
	}

	@Override
	public void channelInactive(ChannelHandlerContext context) {
		EofTrace.emit(traceId, "quic_channel_inactive", 0, "observed");
		releasePending();
		if (headerBuffer != null) {
			headerBuffer.release();
			headerBuffer = null;
		}
		if (localChannel != null && localChannel.isActive()) {
			localChannel.close();
		}
	}

	@Override
	public void userEventTriggered(ChannelHandlerContext context, Object event) throws Exception {
		if (event == ChannelInputShutdownEvent.INSTANCE || event == ChannelInputShutdownReadComplete.INSTANCE) {
			if (!quicInputShutdown) {
				EofTrace.emit(traceId, "quic_input_shutdown", 0, "observed");
			}
			quicInputShutdown = true;
			scheduleLocalOutputShutdown();
			return;
		}
		super.userEventTriggered(context, event);
	}

	private void scheduleLocalOutputShutdown() {
		Channel local = localChannel;
		if (!quicInputShutdown || !(local instanceof DuplexChannel duplex) || !local.isActive()) {
			return;
		}
		ChannelFuture tail;
		synchronized (this) {
			if (localOutputShutdownScheduled) {
				return;
			}
			localOutputShutdownScheduled = true;
			// A manual read can emit several messages. Chain EOF to the actual final write,
			// rather than a boolean that an earlier write completion could clear.
			tail = lastLocalWrite;
		}
		if (tail == null) {
			local.eventLoop().execute(() -> shutdownLocalOutput(duplex, local));
		} else {
			tail.addListener(result -> {
				if (result.isSuccess()) {
					shutdownLocalOutput(duplex, local);
				} else {
					local.close();
				}
			});
		}
	}

	private static void shutdownLocalOutput(DuplexChannel duplex, Channel local) {
		if (!local.isActive() || duplex.isOutputShutdown()) {
			return;
		}
		duplex.shutdownOutput().addListener(result -> {
			if (!result.isSuccess()) {
				local.close();
			}
		});
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
		private final NettyTunnelSession session;
		private final long connectionGeneration;
		private final QuicStreamChannel quicChannel;
		private final String connectionId;
		private final String traceId;
		private volatile boolean localInputShutdown;
		private volatile ChannelFuture lastQuicWrite;
		private volatile long localReadBytes;
		private boolean quicOutputShutdownScheduled;

		LocalToQuicHandler(NettyTunnelSession session, long connectionGeneration, Channel quicChannel,
			String connectionId, String traceId) {
			if (!(quicChannel instanceof QuicStreamChannel stream)) {
				throw new IllegalArgumentException("tunnel bridge requires a QUIC stream channel");
			}
			this.session = session;
			this.connectionGeneration = connectionGeneration;
			this.quicChannel = stream;
			this.connectionId = connectionId;
			this.traceId = traceId;
		}

		@Override
		public void channelActive(ChannelHandlerContext context) {
			context.read();
		}

		@Override
		public void channelRead(ChannelHandlerContext context, Object message) {
			if (message instanceof ByteBuf input) {
				int readable = input.readableBytes();
				if (Long.MAX_VALUE - localReadBytes < readable) {
					ReferenceCountUtil.release(message);
					closePair(context.channel(), quicChannel);
					return;
				}
				localReadBytes += readable;
			}
			ChannelFuture write = quicChannel.writeAndFlush(message);
			lastQuicWrite = write;
			write.addListener(result -> {
				if (result.isSuccess()) {
					if (localInputShutdown) {
						scheduleQuicOutputShutdown();
					} else {
						requestRead(context);
					}
				} else {
					closePair(context.channel(), quicChannel);
				}
			});
		}

		@Override
		public void channelInactive(ChannelHandlerContext context) {
			EofTrace.emit(traceId, "local_channel_inactive", localReadBytes,
				localInputShutdown ? "after-input-event" : "without-input-event");
			if (!localInputShutdown) {
				// A full local-channel close without an input EOF can follow a reset or
				// failed bridge write. It must not certify a complete response.
				quicChannel.close();
				return;
			}
			localInputShutdown = true;
			scheduleQuicOutputShutdown();
		}

		@Override
		public void userEventTriggered(ChannelHandlerContext context, Object event) throws Exception {
			if (event == ChannelInputShutdownEvent.INSTANCE || event == ChannelInputShutdownReadComplete.INSTANCE) {
				if (!localInputShutdown) {
					EofTrace.emit(traceId, "local_input_shutdown", localReadBytes, "observed");
				}
				localInputShutdown = true;
				scheduleQuicOutputShutdown();
				return;
			}
			super.userEventTriggered(context, event);
		}

		private void scheduleQuicOutputShutdown() {
			if (!localInputShutdown || !quicChannel.isActive()) {
				return;
			}
			ChannelFuture tail;
			synchronized (this) {
				if (quicOutputShutdownScheduled) {
					return;
				}
				quicOutputShutdownScheduled = true;
				// Netty's QUIC API requires FIN to follow the final stream write future.
				tail = lastQuicWrite;
			}
			if (tail == null) {
				EofTrace.emit(traceId, "quic_last_write", localReadBytes, "none");
				quicChannel.eventLoop().execute(this::shutdownQuicOutput);
			} else {
				tail.addListener(result -> {
					EofTrace.emit(traceId, "quic_last_write", localReadBytes,
						result.isSuccess() ? "success" : "failed");
					if (result.isSuccess()) {
						shutdownQuicOutput();
					} else {
						quicChannel.close();
					}
				});
			}
		}

		private void shutdownQuicOutput() {
			if (traceId != null) {
				EofTrace.emit(traceId, "quic_shutdown_attempt", localReadBytes,
					!quicChannel.isActive() ? "inactive"
						: quicChannel.isOutputShutdown() ? "already-shutdown" : "active");
			}
			if (!quicChannel.isActive() || quicChannel.isOutputShutdown()) {
				return;
			}
			quicChannel.shutdownOutput().addListener(result -> {
				EofTrace.emit(traceId, "quic_shutdown_complete", localReadBytes,
					result.isSuccess() ? "success" : "failed");
				if (result.isSuccess()) {
					session.sendStreamEof(connectionGeneration, connectionId, localReadBytes, quicChannel, traceId);
				} else {
					quicChannel.close();
				}
			});
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
			closePair(context.channel(), quicChannel);
		}
	}

	private static void closePair(Channel first, Channel second) {
		first.close();
		second.close();
	}

	private static void requestRead(ChannelHandlerContext context) {
		// A write future may complete synchronously while Netty is still inside the current
		// manual-read loop. Deferring avoids losing the read needed to observe a following EOF.
		context.executor().execute(() -> {
			if (context.channel().isActive()) {
				context.read();
			}
		});
	}
}
