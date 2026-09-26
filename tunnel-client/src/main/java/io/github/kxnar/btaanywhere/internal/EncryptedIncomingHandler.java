package io.github.kxnar.btaanywhere.internal;

import com.google.gson.JsonObject;
import io.github.kxnar.btaanywhere.encrypted.EncryptedHostContext;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.ssl.SslHandler;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Validates the v2 relay header before starting the inner TLS handshake. */
final class EncryptedIncomingHandler extends ByteToMessageDecoder {
	private final EncryptedHostContext host;
	private final InetSocketAddress localTarget;
	private final Supplier<String> sessionId;
	private boolean acquired;
	private boolean handedOff;
	private EncryptedHostHandler admitted;

	EncryptedIncomingHandler(EncryptedHostContext host, InetSocketAddress localTarget, Supplier<String> sessionId) {
		this.host = host;
		this.localTarget = localTarget;
		this.sessionId = sessionId;
	}

	@Override
	public void channelActive(ChannelHandlerContext context) throws Exception {
		if (!host.acquireStream()) {
			context.close();
			return;
		}
		acquired = true;
		context.channel().config().setAutoRead(true);
		context.executor().schedule(() -> {
			if (context.channel().isActive() && (admitted == null || !admitted.isAuthenticated())) {
				context.close();
			}
		}, 10, TimeUnit.SECONDS);
		super.channelActive(context);
	}

	@Override
	protected void decode(ChannelHandlerContext context, ByteBuf input, List<Object> output) {
		if (input.readableBytes() < Integer.BYTES) {
			return;
		}
		long length = input.getUnsignedInt(input.readerIndex());
		if (length == 0 || length > ControlFrameDecoder.MAX_FRAME_SIZE) {
			throw new IllegalArgumentException("encrypted connection header is invalid");
		}
		if (input.readableBytes() < Integer.BYTES + length) {
			return;
		}
		input.skipBytes(Integer.BYTES);
		byte[] json = new byte[(int) length];
		input.readBytes(json);
		JsonObject header = ProtocolFrames.parseObject(json);
		IncomingTunnelHandler.validateConnectionHeader(header, sessionId.get(), 2);
		SslHandler tls = host.identity().serverContext().newHandler(context.alloc());
		tls.setHandshakeTimeoutMillis(10_000);
		context.pipeline().addAfter(context.name(), "innerTls", tls);
		context.pipeline().addAfter("innerTls", "encryptedFrames",
			new LengthFieldBasedFrameDecoder(3 + 16 * 1024, 1, 2, 0, 0));
		admitted = new EncryptedHostHandler(host, localTarget, (QuicStreamChannel) context.channel());
		context.pipeline().addAfter("encryptedFrames", "encryptedHost", admitted);
		handedOff = true;
		context.pipeline().remove(this);
	}

	@Override
	public void channelInactive(ChannelHandlerContext context) throws Exception {
		if (acquired && !handedOff) { host.releaseStream(); }
		super.channelInactive(context);
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
		context.close();
	}
}
