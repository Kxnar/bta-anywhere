package io.github.kxnar.btaanywhere.internal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.EventExecutor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class IncomingTunnelHandlerTest {
	@Test
	void retainsAdditionalPayloadWhileLocalConnectionIsPending() throws Exception {
		IncomingTunnelHandler handler = new IncomingTunnelHandler(null, 1);
		EmbeddedChannel channel = new EmbeddedChannel(handler);
		try {
			// Reproduce the state after a valid header was consumed, before connect completes.
			beginPendingConnect(handler, Unpooled.wrappedBuffer(new byte[] {1, 2}));

			channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {3, 4, 5}));

			assertTrue(channel.isActive(), "a second read during connect must not close the stream");
			ByteBuf pending = (ByteBuf) field("pendingPayload").get(handler);
			assertEquals(5, pending.readableBytes());
			for (int value = 1; value <= 5; value++) {
				assertEquals(value, pending.readUnsignedByte());
			}
		} finally {
			channel.finishAndReleaseAll();
		}
	}

	@Test
	void pendingPayloadRemainsBoundedAndIsReleasedOnOverflow() throws Exception {
		IncomingTunnelHandler handler = new IncomingTunnelHandler(null, 1);
		EmbeddedChannel channel = new EmbeddedChannel(handler);
		ByteBuf initial = Unpooled.buffer(IncomingTunnelHandler.MAX_PENDING_PAYLOAD - 1)
			.writeZero(IncomingTunnelHandler.MAX_PENDING_PAYLOAD - 1);
		try {
			beginPendingConnect(handler, initial);
			channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {1}));
			assertTrue(channel.isActive());
			ByteBuf pending = (ByteBuf) field("pendingPayload").get(handler);
			assertEquals(IncomingTunnelHandler.MAX_PENDING_PAYLOAD, pending.readableBytes());
			ByteBuf excess = Unpooled.wrappedBuffer(new byte[] {2});
			channel.writeInbound(excess);
			assertFalse(channel.isActive());
			assertEquals(0, pending.refCnt());
			assertEquals(0, excess.refCnt());
			assertEquals(0, initial.refCnt());
		} finally {
			channel.finishAndReleaseAll();
		}
	}

	@Test
	void pendingMegabyteAndEofReachRealLocalSocketAfterConnectHandoff() throws Exception {
		IncomingTunnelHandler handler = new IncomingTunnelHandler(null, 1);
		EmbeddedChannel quic = new EmbeddedChannel(handler);
		NioEventLoopGroup group = new NioEventLoopGroup(1);
		ChannelFuture connected = null;
		try (ServerSocket server = new ServerSocket(0)) {
			server.setSoTimeout(5000);
			byte[] data = new byte[1024 * 1024];
			for (int index = 0; index < data.length; index++) {
				data[index] = (byte) (index * 31);
			}
			beginPendingConnect(handler, Unpooled.wrappedBuffer(Arrays.copyOfRange(data, 0, 32768)));
			quic.writeInbound(Unpooled.wrappedBuffer(Arrays.copyOfRange(data, 32768, data.length)));
			quic.pipeline().fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
			connected = new Bootstrap().group(group).channel(NioSocketChannel.class)
				.handler(new ChannelInboundHandlerAdapter())
				.option(ChannelOption.AUTO_READ, false)
				.option(ChannelOption.ALLOW_HALF_CLOSURE, true)
				.connect("127.0.0.1", server.getLocalPort()).sync();
			try (Socket accepted = server.accept()) {
				accepted.setSoTimeout(5000);
				dispatch(handler, quic.pipeline().context(handler), connected);
				quic.runPendingTasks();
				assertArrayEquals(data, accepted.getInputStream().readNBytes(data.length));
				assertEquals(-1, accepted.getInputStream().read(), "EOF must follow all pending bytes");
				assertTrue(quic.isActive(), "input half-close must retain the response direction");
				assertEquals(null, field("pendingPayload").get(handler));
			}
		} finally {
			quic.finishAndReleaseAll();
			if (connected != null) {
				connected.channel().close().syncUninterruptibly();
			}
			group.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
		}
	}

	@Test
	void rejectedConnectHandoffClosesLocalSocketAndReleasesPendingPayload() throws Exception {
		IncomingTunnelHandler handler = new IncomingTunnelHandler(null, 1);
		EmbeddedChannel quic = new EmbeddedChannel(handler);
		EmbeddedChannel local = new EmbeddedChannel();
		ByteBuf pending = Unpooled.wrappedBuffer(new byte[] {1, 2, 3});
		try {
			beginPendingConnect(handler, pending);
			EventExecutor rejecting = (EventExecutor) Proxy.newProxyInstance(
				EventExecutor.class.getClassLoader(), new Class<?>[] {EventExecutor.class},
				(proxy, method, arguments) -> {
					throw new RejectedExecutionException("QUIC loop stopped");
				});
			ChannelHandlerContext context = (ChannelHandlerContext) Proxy.newProxyInstance(
				ChannelHandlerContext.class.getClassLoader(), new Class<?>[] {ChannelHandlerContext.class},
				(proxy, method, arguments) -> switch (method.getName()) {
					case "executor" -> rejecting;
					case "close" -> quic.close();
					default -> throw new UnsupportedOperationException(method.getName());
				});
			dispatch(handler, context, local.newSucceededFuture());
			assertFalse(local.isActive());
			assertFalse(quic.isActive());
			assertEquals(0, pending.refCnt());
		} finally {
			quic.finishAndReleaseAll();
			local.finishAndReleaseAll();
		}
	}

	private static void dispatch(IncomingTunnelHandler handler, ChannelHandlerContext context, ChannelFuture future)
		throws ReflectiveOperationException {
		Method method = IncomingTunnelHandler.class.getDeclaredMethod(
			"dispatchLocalConnect", ChannelHandlerContext.class, ChannelFuture.class);
		method.setAccessible(true);
		method.invoke(handler, context, future);
	}

	@Test
	void inputEofDuringConnectRetainsPayloadUntilHandoffOrClose() throws Exception {
		IncomingTunnelHandler handler = new IncomingTunnelHandler(null, 1);
		EmbeddedChannel channel = new EmbeddedChannel(handler);
		ByteBuf initial = Unpooled.wrappedBuffer(new byte[] {1});
		try {
			beginPendingConnect(handler, initial);
			channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {2}));
			channel.pipeline().fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
			assertTrue(channel.isActive());
			assertTrue(field("quicInputShutdown").getBoolean(handler));
			ByteBuf pending = (ByteBuf) field("pendingPayload").get(handler);
			assertEquals(2, pending.readableBytes());
			channel.close();
			assertEquals(0, pending.refCnt());
		} finally {
			channel.finishAndReleaseAll();
		}
	}

	private static void beginPendingConnect(IncomingTunnelHandler handler, ByteBuf initial)
		throws ReflectiveOperationException {
		ByteBuf header = (ByteBuf) field("headerBuffer").get(handler);
		header.release();
		field("headerBuffer").set(handler, null);
		field("expectedLength").setInt(handler, 1);
		field("pendingPayload").set(handler, initial);
	}

	private static Field field(String name) throws ReflectiveOperationException {
		Field result = IncomingTunnelHandler.class.getDeclaredField(name);
		result.setAccessible(true);
		return result;
	}

	@Test
	void acceptsSocketAddressesProducedByTheRustRelay() {
		assertDoesNotThrow(() -> IncomingTunnelHandler.validateRemoteAddress("203.0.113.9:49152"));
		assertDoesNotThrow(() -> IncomingTunnelHandler.validateRemoteAddress("[2001:db8::9]:49152"));
	}

	@Test
	void rejectsMalformedOrUnboundedRemoteAddresses() {
		assertThrows(IllegalArgumentException.class,
			() -> IncomingTunnelHandler.validateRemoteAddress("2001:db8::9:49152"));
		assertThrows(IllegalArgumentException.class,
			() -> IncomingTunnelHandler.validateRemoteAddress("host:not-a-port"));
		assertThrows(IllegalArgumentException.class,
			() -> IncomingTunnelHandler.validateRemoteAddress("host:0"));
		assertThrows(IllegalArgumentException.class,
			() -> IncomingTunnelHandler.validateRemoteAddress("x".repeat(129) + ":1"));
	}
}
