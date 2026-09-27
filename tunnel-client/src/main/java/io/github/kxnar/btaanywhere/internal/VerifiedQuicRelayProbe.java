package io.github.kxnar.btaanywhere.internal;

import io.github.kxnar.btaanywhere.RelayDescriptor;
import io.github.kxnar.btaanywhere.RelayMode;
import io.github.kxnar.btaanywhere.RelayRttProbe;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicClientCodecBuilder;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** A bounded QUIC/TLS probe; it never opens a control stream or registers a session. */
public final class VerifiedQuicRelayProbe implements RelayRttProbe {
	private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);

	@Override
	public CompletionStage<Long> measureMillis(RelayDescriptor relay, RelayMode mode) {
		Objects.requireNonNull(relay, "relay");
		Objects.requireNonNull(mode, "mode");
		CompletableFuture<Long> result = new CompletableFuture<>();
		EventLoopGroup group = new NioEventLoopGroup(1);
		AtomicBoolean finished = new AtomicBoolean();
		Channel[] datagram = new Channel[1];
		QuicChannel[] quic = new QuicChannel[1];
		long[] started = new long[1];
		Runnable cleanup = () -> {
			if (quic[0] != null) {
				quic[0].close();
			}
			if (datagram[0] != null) {
				datagram[0].close();
			}
			group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
		};
		java.util.function.BiConsumer<Long, Throwable> finish = (elapsed, failure) -> {
			if (finished.compareAndSet(false, true)) {
				cleanup.run();
				if (failure == null) {
					result.complete(elapsed);
				} else {
					result.completeExceptionally(failure);
				}
			}
		};
		group.next().schedule(() -> finish.accept(null,
			new java.util.concurrent.TimeoutException("relay QUIC probe timed out")),
			PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		try {
			String alpn = mode == RelayMode.ENCRYPTED ? "bta-anywhere/2" : ProtocolFrames.ALPN;
			QuicSslContext sslContext = QuicSslContextBuilder.forClient()
				.trustManager(relay.trustedCertificate().toFile())
				.applicationProtocols(alpn)
				.build();
			ChannelHandler codec = new QuicClientCodecBuilder()
				.sslContext(sslContext)
				.sslEngineProvider(channel -> sslContext.newEngine(
					channel.alloc(),
					relay.host(),
					relay.port()
				))
				.build();
			ChannelFuture bound = new Bootstrap()
				.group(group)
				.channel(NioDatagramChannel.class)
				.handler(codec)
				.bind(0);
			bound.addListener(bindResult -> {
				if (finished.get()) {
					if (bound.channel() != null) {
						bound.channel().close();
					}
					return;
				}
				if (!bindResult.isSuccess()) {
					finish.accept(null, bindResult.cause());
					return;
				}
				datagram[0] = bound.channel();
				started[0] = System.nanoTime();
				QuicChannel.newBootstrap(bound.channel())
					.handler(new ChannelInboundHandlerAdapter() {
						@Override
						public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
							context.close();
							finish.accept(null, cause);
						}
					})
					.option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS,
						(int) PROBE_TIMEOUT.toMillis())
					.remoteAddress(new InetSocketAddress(relay.host(), relay.port()))
					.connect()
					.addListener(connectResult -> {
						if (finished.get()) {
							if (connectResult.isSuccess()) {
								((QuicChannel) connectResult.getNow()).close();
							}
							return;
						}
						if (!connectResult.isSuccess()) {
							finish.accept(null, connectResult.cause());
							return;
						}
						QuicChannel connected = (QuicChannel) connectResult.getNow();
						quic[0] = connected;
						try {
							TlsHostnameVerifier.verify(relay.host(), connected.sslEngine());
							String negotiated = connected.sslEngine().getApplicationProtocol();
							if (!alpn.equals(negotiated)) {
								throw new IllegalStateException("relay probe negotiated an unexpected protocol");
							}
							long elapsed = Math.max(1,
								TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started[0]));
							finish.accept(elapsed, null);
						} catch (Exception failure) {
							finish.accept(null, failure);
						}
					});
			});
		} catch (RuntimeException failure) {
			finish.accept(null, failure);
		}
		return result;
	}
}
