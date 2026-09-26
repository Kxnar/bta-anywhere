package io.github.kxnar.btaanywhere.internal;

import io.github.kxnar.btaanywhere.encrypted.EncryptedFrames;
import io.github.kxnar.btaanywhere.encrypted.BtaStatusProbe;
import io.github.kxnar.btaanywhere.encrypted.EncryptedAuth;
import io.github.kxnar.btaanywhere.encrypted.EncryptedHostContext;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DuplexChannel;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/** Admits an inner-TLS guest before opening any local game-server socket. */
final class EncryptedHostHandler extends SimpleChannelInboundHandler<ByteBuf> {
	private static final int MAX_PENDING_BYTES = 64 * 1024;
	private final EncryptedHostContext host;
	private final InetSocketAddress localTarget;
	private final QuicStreamChannel stream;
	private Channel local;
	private volatile boolean authenticated;
	private boolean status;
	private boolean statusIcon;
	private boolean guestFin;
	private boolean localFin;
	private int pendingBytes;
	private int statusBytes;
	private ChannelFuture lastLocalWrite;
	private ChannelFuture lastEncryptedWrite;

	EncryptedHostHandler(EncryptedHostContext host, InetSocketAddress localTarget, QuicStreamChannel stream) {
		this.host = host;
		this.localTarget = localTarget;
		this.stream = stream;
	}

	boolean isAuthenticated() { return authenticated; }

	@Override
	public void userEventTriggered(ChannelHandlerContext context, Object event) throws Exception {
		if (event instanceof SslHandshakeCompletionEvent handshake) {
			SslHandler tls = context.pipeline().get(SslHandler.class);
			if (!handshake.isSuccess() || tls == null
				|| !"TLSv1.3".equals(tls.engine().getSession().getProtocol())
				|| !"bta-anywhere-guest/1".equals(tls.applicationProtocol())) {
				context.close();
			}
			return;
		}
		super.userEventTriggered(context, event);
	}

	@Override
	protected void channelRead0(ChannelHandlerContext context, ByteBuf frame) {
		if (frame.readableBytes() < 3) {
			context.close();
			return;
		}
		int type = frame.readUnsignedByte();
		int length = frame.readUnsignedShort();
		try {
			EncryptedFrames.validate(type, length);
		} catch (IllegalArgumentException failure) {
			context.close();
			return;
		}
		if (frame.readableBytes() != length) {
			context.close();
			return;
		}
		if (!authenticated) {
			if (type != EncryptedFrames.AUTH) {
				context.close();
				return;
			}
			byte[] auth = new byte[length];
			frame.readBytes(auth);
			admit(context, auth);
			return;
		}
		if (status || local == null || !local.isActive()) {
			context.close();
			return;
		}
		if (type == EncryptedFrames.FIN) {
			if (guestFin) {
				context.close();
				return;
			}
			guestFin = true;
			if (local instanceof DuplexChannel duplex) {
				ChannelFuture tail = lastLocalWrite;
				if (tail == null) { shutdownLocalOutput(context, duplex); }
				else { tail.addListener(result -> {
					if (result.isSuccess()) { shutdownLocalOutput(context, duplex); }
					else { context.close(); }
				}); }
			}
			if (localFin) { context.close(); }
			return;
		}
		if (type != EncryptedFrames.DATA || guestFin || pendingBytes + length > MAX_PENDING_BYTES) {
			context.close();
			return;
		}
		pendingBytes += length;
		ByteBuf data = frame.readRetainedSlice(length);
		lastLocalWrite = local.writeAndFlush(data);
		lastLocalWrite.addListener(result -> {
			pendingBytes -= length;
			if (!result.isSuccess()) { context.close(); }
			else { context.read(); }
		});
	}

	private static void shutdownLocalOutput(ChannelHandlerContext context, DuplexChannel duplex) {
		duplex.shutdownOutput().addListener(result -> {
			if (!result.isSuccess()) { context.close(); }
		});
	}

	private void admit(ChannelHandlerContext context, byte[] body) {
		try {
			EncryptedAuth auth = EncryptedAuth.parse(body);
			String intent = auth.intent();
			String id = auth.invitationId();
			String session = auth.hostSessionId();
			String capability = auth.capability();
			statusIcon = auth.statusIcon();
			boolean permitted = switch (intent) {
				case "join" -> host.redeemJoin(id, session, capability);
				case "status" -> host.authorizeStatus(id, session, capability);
				default -> false;
			};
			if (!permitted) {
				deny(context);
				return;
			}
			authenticated = true;
			status = "status".equals(intent);
			openLocal(context);
		} catch (RuntimeException failure) {
			deny(context);
		}
	}

	private void openLocal(ChannelHandlerContext context) {
		ChannelFuture connection = new Bootstrap()
			.group(context.channel().eventLoop())
			.channel(NioSocketChannel.class)
			.option(ChannelOption.AUTO_READ, false)
			.option(ChannelOption.ALLOW_HALF_CLOSURE, true)
			.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
			.handler(new LocalHandler(this, context))
			.connect(localTarget);
		connection.addListener(result -> {
			if (!result.isSuccess() || !context.channel().isActive()) {
				connection.channel().close();
				context.close();
				return;
			}
			local = connection.channel();
			send(context, EncryptedFrames.AUTH_OK, new byte[0]).addListener(sent -> {
				if (!sent.isSuccess()) { context.close(); return; }
				if (status) {
					byte[] request;
					try { request = BtaStatusProbe.serverRequest(statusIcon, localTarget.getPort()); }
					catch (java.io.IOException invalid) { context.close(); return; }
					local.writeAndFlush(context.alloc().buffer(request.length).writeBytes(request)).addListener(written -> {
						if (!written.isSuccess()) { context.close(); }
						else { local.read(); }
					});
				} else {
					context.channel().config().setAutoRead(false);
					context.read();
					local.read();
				}
			});
		});
	}

	private static ChannelFuture send(ChannelHandlerContext context, int type, byte[] payload) {
		return context.writeAndFlush(EncryptedFrames.encode(context.alloc(), type, payload));
	}

	private static void deny(ChannelHandlerContext context) {
		send(context, EncryptedFrames.ERROR, "invitation denied".getBytes(StandardCharsets.UTF_8))
			.addListener(ignored -> context.close());
	}

	private void localData(ChannelHandlerContext context, ChannelHandlerContext localContext, ByteBuf input) {
		if (status && statusBytes + input.readableBytes() > MAX_PENDING_BYTES) {
			input.release();
			context.close();
			return;
		}
		statusBytes += status ? input.readableBytes() : 0;
		ChannelFuture tail = null;
		while (input.isReadable()) {
			int count = Math.min(input.readableBytes(), EncryptedFrames.MAX_PAYLOAD);
			byte[] data = new byte[count];
			input.readBytes(data);
			tail = send(context, EncryptedFrames.DATA, data);
			lastEncryptedWrite = tail;
		}
		input.release();
		if (tail == null) {
			localContext.read();
		} else {
			tail.addListener(result -> {
				if (!result.isSuccess()) { context.close(); }
				else { localContext.read(); }
			});
		}
	}

	private void localEnd(ChannelHandlerContext context) {
		if (localFin) { return; }
		localFin = true;
		ChannelFuture tail = lastEncryptedWrite;
		if (tail == null) { sendLocalFin(context); }
		else { tail.addListener(result -> {
			if (result.isSuccess()) { sendLocalFin(context); }
			else { context.close(); }
		}); }
	}

	private void sendLocalFin(ChannelHandlerContext context) {
		send(context, EncryptedFrames.FIN, new byte[0]).addListener(result -> {
			if (!result.isSuccess() || status || guestFin) { context.close(); }
		});
	}

	@Override
	public void channelInactive(ChannelHandlerContext context) {
		Channel connected = local;
		if (connected != null) { connected.close(); }
		host.releaseStream();
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
		context.close();
	}

	private static final class LocalHandler extends ChannelInboundHandlerAdapter {
		private final EncryptedHostHandler host;
		private final ChannelHandlerContext encrypted;

		LocalHandler(EncryptedHostHandler host, ChannelHandlerContext encrypted) {
			this.host = host;
			this.encrypted = encrypted;
		}

		@Override
		public void channelRead(ChannelHandlerContext context, Object message) {
			if (message instanceof ByteBuf input) { host.localData(encrypted, context, input); }
			else { io.netty.util.ReferenceCountUtil.release(message); encrypted.close(); }
		}

		@Override
		public void userEventTriggered(ChannelHandlerContext context, Object event) {
			if (event == ChannelInputShutdownEvent.INSTANCE) { host.localEnd(encrypted); }
		}

		@Override
		public void channelInactive(ChannelHandlerContext context) { host.localEnd(encrypted); }

		@Override
		public void exceptionCaught(ChannelHandlerContext context, Throwable cause) { encrypted.close(); }
	}
}
