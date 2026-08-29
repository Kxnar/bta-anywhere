package io.github.kxnar.btaanywhere.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import io.github.kxnar.btaanywhere.RelayDescriptor;
import io.github.kxnar.btaanywhere.Secret;
import io.github.kxnar.btaanywhere.TunnelConfig;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicClientCodecBuilder;
import io.netty.incubator.codec.quic.QuicServerCodecBuilder;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.incubator.codec.quic.QuicStreamType;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Exercises the production bridge without the Rust relay, BTA mod, or reconnect lifecycle. */
final class IncomingTunnelHandlerHalfCloseTest {
	private static final int ITERATIONS = 64;
	private static final long PHASE_TIMEOUT_SECONDS = 5;

	private NioEventLoopGroup eventLoopGroup;
	private Channel serverDatagram;
	private Channel clientDatagram;
	private QuicChannel clientConnection;
	private ServerSocket localService;
	private ExecutorService localWorkers;
	private SelfSignedCertificate certificate;
	private Secret testSecret;

	@AfterEach
	void closeFixture() throws Exception {
		close(clientConnection);
		close(clientDatagram);
		close(serverDatagram);
		if (localService != null) {
			localService.close();
		}
		if (localWorkers != null) {
			localWorkers.shutdownNow();
			assertTrue(localWorkers.awaitTermination(PHASE_TIMEOUT_SECONDS, TimeUnit.SECONDS), "local service did not stop");
		}
		if (eventLoopGroup != null) {
			eventLoopGroup.shutdownGracefully().syncUninterruptibly();
		}
		if (testSecret != null) {
			testSecret.close();
		}
		if (certificate != null) {
			certificate.delete();
		}
	}

	@Test
	@Timeout(60)
	void propagatesBothHalfClosesAcrossOneStreamAtATime() throws Exception {
		CompletableFuture<QuicChannel> serverConnection = new CompletableFuture<>();
		setUp(serverConnection);
		QuicChannel remote = await(serverConnection, "server QUIC connection");

		for (int iteration = 0; iteration < ITERATIONS; iteration++) {
			byte[] request = payload("request-" + iteration, boundarySize(iteration));
			byte[] response = payload("response-" + iteration, 32 * 1024 + boundarySize(iteration));
			CompletableFuture<byte[]> localRequest = new CompletableFuture<>();
			CompletableFuture<Void> localEof = new CompletableFuture<>();
			localWorkers.execute(() -> serveOnce(localRequest, localEof, response));

			ResponseCollector collector = new ResponseCollector();
			QuicStreamChannel stream = remote.createStream(QuicStreamType.BIDIRECTIONAL, collector)
				.syncUninterruptibly().getNow();
			JsonObject header = new JsonObject();
			header.addProperty("version", ProtocolFrames.VERSION);
			header.addProperty("sessionId", "half-close-test-session");
			header.addProperty("connectionId", "half-close-" + iteration);
			header.addProperty("remoteAddress", "127.0.0.1:" + (10_000 + iteration));
			ChannelFuture headerWrite = ProtocolFrames.write(stream, header);
			headerWrite.syncUninterruptibly();
			stream.writeAndFlush(Unpooled.wrappedBuffer(request))
				.addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);

			assertArrayEquals(request, await(localRequest, "local request " + iteration));
			await(localEof, "local TCP EOF " + iteration);
			assertArrayEquals(response, collector.awaitResponse(iteration));
			collector.awaitEof(iteration);
			stream.closeFuture().syncUninterruptibly();
		}
	}

	private void setUp(CompletableFuture<QuicChannel> serverConnection) throws Exception {
		certificate = new SelfSignedCertificate("localhost");
		eventLoopGroup = new NioEventLoopGroup(1);
		localService = new ServerSocket(0, 16, java.net.InetAddress.getLoopbackAddress());
		localWorkers = Executors.newCachedThreadPool();
		testSecret = Secret.of("half-close-test-token");
		RelayDescriptor relay = new RelayDescriptor("localhost", 1, certificate.certificate().toPath(), testSecret);
		NettyTunnelSession session = new NettyTunnelSession(
			eventLoopGroup,
			TunnelConfig.defaults(relay, "half-close-test-client"),
			new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), localService.getLocalPort()),
			event -> { }
		);
		JsonObject registered = new JsonObject();
		registered.addProperty("type", "registered");
		registered.addProperty("sessionId", "half-close-test-session");
		registered.addProperty("resumeToken", "half-close-test-resume");
		registered.addProperty("publicHost", "127.0.0.1");
		registered.addProperty("publicPort", 1);
		session.handleControl(registered, 0);

		QuicSslContext serverTls = QuicSslContextBuilder.forServer(certificate.key(), null, certificate.cert())
			.applicationProtocols(ProtocolFrames.ALPN)
			.build();
		serverDatagram = new Bootstrap()
			.group(eventLoopGroup)
			.channel(NioDatagramChannel.class)
			.handler(new ChannelInitializer<NioDatagramChannel>() {
				@Override
				protected void initChannel(NioDatagramChannel channel) {
					channel.pipeline().addLast(new QuicServerCodecBuilder()
						.sslContext(serverTls)
						.initialMaxStreamsBidirectional(ITERATIONS + 1L)
						.initialMaxData(16 * 1024 * 1024)
						.initialMaxStreamDataBidirectionalLocal(2 * 1024 * 1024)
						.initialMaxStreamDataBidirectionalRemote(2 * 1024 * 1024)
						.handler(new ChannelInboundHandlerAdapter() {
							@Override
							public void channelActive(ChannelHandlerContext context) {
								serverConnection.complete((QuicChannel) context.channel());
							}
						})
						.streamOption(ChannelOption.ALLOW_HALF_CLOSURE, true)
						.build());
				}
			})
			.bind(new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0))
			.syncUninterruptibly().channel();

		QuicSslContext clientTls = QuicSslContextBuilder.forClient()
			.trustManager(certificate.cert())
			.applicationProtocols(ProtocolFrames.ALPN)
			.build();
		clientDatagram = new Bootstrap()
			.group(eventLoopGroup)
			.channel(NioDatagramChannel.class)
			.handler(new QuicClientCodecBuilder()
				.sslContext(clientTls)
				.initialMaxStreamsBidirectional(ITERATIONS + 1L)
				.initialMaxData(16 * 1024 * 1024)
				.initialMaxStreamDataBidirectionalLocal(2 * 1024 * 1024)
				.initialMaxStreamDataBidirectionalRemote(2 * 1024 * 1024)
				.build())
			.bind(0).syncUninterruptibly().channel();
		clientConnection = QuicChannel.newBootstrap(clientDatagram)
			.streamHandler(new ChannelInitializer<QuicStreamChannel>() {
				@Override
				protected void initChannel(QuicStreamChannel channel) {
					channel.pipeline().addLast(new IncomingTunnelHandler(session));
				}
			})
			.streamOption(ChannelOption.AUTO_READ, false)
			.streamOption(ChannelOption.ALLOW_HALF_CLOSURE, true)
			.remoteAddress(serverDatagram.localAddress())
			.connect().syncUninterruptibly().getNow();
	}

	private void serveOnce(
		CompletableFuture<byte[]> request,
		CompletableFuture<Void> eof,
		byte[] response
	) {
		try (Socket socket = localService.accept()) {
			socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(PHASE_TIMEOUT_SECONDS));
			request.complete(socket.getInputStream().readAllBytes());
			eof.complete(null);
			int split = response.length / 2;
			socket.getOutputStream().write(response, 0, split);
			socket.getOutputStream().flush();
			socket.getOutputStream().write(response, split, response.length - split);
			socket.getOutputStream().flush();
			socket.shutdownOutput();
		} catch (Throwable failure) {
			request.completeExceptionally(failure);
			eof.completeExceptionally(failure);
		}
	}

	private static int boundarySize(int iteration) {
		return List.of(0, 1, 16 * 1024 - 1, 16 * 1024 + 1, 64 * 1024 - 1, 64 * 1024 + 1).get(iteration % 6);
	}

	private static byte[] payload(String label, int payloadLength) {
		byte[] prefix = (label + ':').getBytes(StandardCharsets.US_ASCII);
		byte[] result = new byte[prefix.length + payloadLength];
		System.arraycopy(prefix, 0, result, 0, prefix.length);
		Arrays.fill(result, prefix.length, result.length, (byte) ('a' + (payloadLength % 26)));
		return result;
	}

	private static <T> T await(CompletableFuture<T> future, String phase) throws Exception {
		try {
			return future.get(PHASE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (java.util.concurrent.TimeoutException failure) {
			throw new AssertionError("timed out waiting for " + phase, failure);
		}
	}

	private static void close(Channel channel) {
		if (channel != null) {
			channel.close().syncUninterruptibly();
		}
	}

	private static final class ResponseCollector extends ChannelInboundHandlerAdapter {
		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		private final CompletableFuture<byte[]> response = new CompletableFuture<>();
		private final CompletableFuture<Void> eof = new CompletableFuture<>();

		@Override
		public void channelRead(ChannelHandlerContext context, Object message) {
			ByteBuf buffer = (ByteBuf) message;
			try {
				byte[] chunk = new byte[buffer.readableBytes()];
				buffer.readBytes(chunk);
				bytes.writeBytes(chunk);
			} finally {
				buffer.release();
			}
		}

		@Override
		public void userEventTriggered(ChannelHandlerContext context, Object event) throws Exception {
			if (event == ChannelInputShutdownEvent.INSTANCE || event == ChannelInputShutdownReadComplete.INSTANCE) {
				response.complete(bytes.toByteArray());
				if (event == ChannelInputShutdownReadComplete.INSTANCE) {
					eof.complete(null);
				}
				return;
			}
			super.userEventTriggered(context, event);
		}

		byte[] awaitResponse(int iteration) throws Exception {
			return await(response, "remote QUIC response " + iteration);
		}

		void awaitEof(int iteration) throws Exception {
			await(eof, "remote QUIC EOF " + iteration);
		}
	}
}
