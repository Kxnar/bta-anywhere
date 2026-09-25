package io.github.kxnar.btaanywhere.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.kxnar.btaanywhere.TunnelConfig;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.util.ReferenceCountUtil;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class NettyTunnelSessionNoticeTest {
	@Test
	void lateCompletionClosesOnlyItsOldConnection() throws Exception {
		try (Fixture fixture = new Fixture()) {
			Owner oldParent = new Owner();
			Owner newParent = new Owner();
			AtomicInteger controlWrites = new AtomicInteger();
			QuicStreamChannel oldStream = stream(oldParent, null, null);
			fixture.control = stream(newParent, controlWrites, null);
			fixture.installControl();
			fixture.generation().set(2);

			fixture.session.sendStreamEof(1, "old-connection", 5, oldStream, null);

			assertFalse(oldParent.channel.isActive());
			assertTrue(newParent.channel.isActive());
			assertEquals(0, controlWrites.get());
			Owner mismatchedParent = new Owner();
			fixture.session.sendStreamEof(2, "wrong-parent", 5,
				stream(mismatchedParent, null, null), null);
			assertFalse(mismatchedParent.channel.isActive());
			assertTrue(newParent.channel.isActive());
			assertEquals(0, controlWrites.get());
			newParent.channel.finishAndReleaseAll();
		}
	}

	@Test
	void failedNoticeWriteClosesItsOwnConnection() throws Exception {
		try (Fixture fixture = new Fixture()) {
			Owner parent = new Owner();
			AtomicInteger controlWrites = new AtomicInteger();
			fixture.control = stream(parent, controlWrites, new IllegalStateException("test write failure"));
			fixture.installControl();
			fixture.generation().set(1);
			QuicStreamChannel guestStream = stream(parent, null, null);

			fixture.session.sendStreamEof(1, "connection", 5, guestStream, null);
			parent.channel.runPendingTasks();

			assertEquals(1, controlWrites.get());
			assertFalse(parent.channel.isActive());
			assertEquals(0, fixture.pendingNotices().get());
		}
	}

	@Test
	void repeatedLocalEofEmitsOneNoticeWithTheSuccessfulByteCount() throws Exception {
		try (Fixture fixture = new Fixture()) {
			Owner parent = new Owner();
			List<JsonObject> notices = new ArrayList<>();
			fixture.control = stream(parent, null, null, notices);
			fixture.installControl();
			fixture.generation().set(1);
			QuicStreamChannel guestStream = stream(parent, null, null);
			Class<?> handlerType = Class.forName(IncomingTunnelHandler.class.getName() + "$LocalToQuicHandler");
			Constructor<?> constructor = handlerType.getDeclaredConstructor(
				NettyTunnelSession.class, long.class, Channel.class, String.class, String.class);
			constructor.setAccessible(true);
			ChannelHandler handler = (ChannelHandler) constructor.newInstance(
				fixture.session, 1L, guestStream, "connection", null);
			EmbeddedChannel local = new EmbeddedChannel(handler);

			local.writeInbound(Unpooled.wrappedBuffer(new byte[] {1, 2, 3, 4, 5}));
			local.pipeline().fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
			local.pipeline().fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
			for (int attempt = 0; attempt < 5; attempt++) {
				parent.channel.runPendingTasks();
				local.runPendingTasks();
			}

			assertEquals(1, notices.size());
			assertEquals("streamEof", notices.get(0).get("type").getAsString());
			assertEquals("connection", notices.get(0).get("connectionId").getAsString());
			assertEquals(5, notices.get(0).get("bytes").getAsLong());
			local.finishAndReleaseAll();
			parent.channel.finishAndReleaseAll();
		}
	}

	private static QuicStreamChannel stream(Owner parent, AtomicInteger writes, Throwable writeFailure) {
		return stream(parent, writes, writeFailure, null);
	}

	private static QuicStreamChannel stream(Owner parent, AtomicInteger writes, Throwable writeFailure,
		List<JsonObject> frames) {
		AtomicBoolean outputShutdown = new AtomicBoolean();
		return (QuicStreamChannel) Proxy.newProxyInstance(
			QuicStreamChannel.class.getClassLoader(),
			new Class<?>[] { QuicStreamChannel.class },
			(proxy, method, arguments) -> switch (method.getName()) {
				case "parent" -> parent.quic;
				case "isActive" -> parent.channel.isActive();
				case "isOutputShutdown" -> outputShutdown.get();
				case "eventLoop" -> parent.channel.eventLoop();
				case "alloc" -> UnpooledByteBufAllocator.DEFAULT;
				case "shutdownOutput" -> {
					outputShutdown.set(true);
					yield new DefaultChannelPromise(parent.channel).setSuccess();
				}
				case "writeAndFlush" -> {
					if (writes != null) {
						writes.incrementAndGet();
					}
					if (frames != null) {
						ByteBuf frame = (ByteBuf) arguments[0];
						int length = frame.getInt(frame.readerIndex());
						byte[] json = new byte[length];
						frame.getBytes(frame.readerIndex() + Integer.BYTES, json);
						frames.add(JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject());
					}
					ReferenceCountUtil.release(arguments[0]);
					DefaultChannelPromise result = new DefaultChannelPromise(parent.channel);
					if (writeFailure == null) {
						result.setSuccess();
					} else {
						result.setFailure(writeFailure);
					}
					yield result;
				}
				case "close" -> parent.channel.close();
				case "toString" -> "test-quic-stream";
				default -> throw new UnsupportedOperationException(method.getName());
			}
		);
	}

	private static final class Owner {
		final EmbeddedChannel channel = new EmbeddedChannel();
		final QuicChannel quic = (QuicChannel) Proxy.newProxyInstance(
			QuicChannel.class.getClassLoader(),
			new Class<?>[] { QuicChannel.class },
			(proxy, method, arguments) -> switch (method.getName()) {
				case "isActive" -> channel.isActive();
				case "close" -> channel.close();
				case "eventLoop" -> channel.eventLoop();
				case "toString" -> "test-quic-owner";
				default -> throw new UnsupportedOperationException(method.getName());
			}
		);
	}

	private static final class Fixture implements AutoCloseable {
		final NioEventLoopGroup group = new NioEventLoopGroup(1);
		final NettyTunnelSession session = new NettyTunnelSession(
			group,
			new TunnelConfig(() -> CompletableFuture.failedFuture(new IllegalStateException("unused")),
				"test-instance", Duration.ofSeconds(1), Duration.ofSeconds(1),
				Duration.ofSeconds(1), Duration.ofSeconds(2)),
			new InetSocketAddress("127.0.0.1", 1),
			event -> { }
		);
		QuicStreamChannel control;

		void installControl() throws Exception {
			Field field = NettyTunnelSession.class.getDeclaredField("controlChannel");
			field.setAccessible(true);
			field.set(session, control);
		}

		AtomicLong generation() throws Exception {
			Field field = NettyTunnelSession.class.getDeclaredField("generation");
			field.setAccessible(true);
			return (AtomicLong) field.get(session);
		}

		AtomicInteger pendingNotices() throws Exception {
			Field field = NettyTunnelSession.class.getDeclaredField("pendingEofNotices");
			field.setAccessible(true);
			return (AtomicInteger) field.get(session);
		}

		@Override
		public void close() {
			group.shutdownGracefully().syncUninterruptibly();
		}
	}
}
