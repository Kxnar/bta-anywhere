package io.github.kxnar.btaanywhere.internal;

import com.google.gson.JsonObject;
import io.github.kxnar.btaanywhere.ProtocolException;
import io.github.kxnar.btaanywhere.PublicEndpoint;
import io.github.kxnar.btaanywhere.RelayDescriptor;
import io.github.kxnar.btaanywhere.TunnelConfig;
import io.github.kxnar.btaanywhere.TunnelEvent;
import io.github.kxnar.btaanywhere.TunnelSession;
import io.github.kxnar.btaanywhere.TunnelState;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicClientCodecBuilder;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.incubator.codec.quic.QuicStreamType;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ScheduledFuture;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class NettyTunnelSession implements TunnelSession {
	private final NioEventLoopGroup group;
	private final TunnelConfig config;
	private final InetSocketAddress localTarget;
	private final Consumer<TunnelEvent> eventObserver;
	private final SubmissionPublisher<TunnelEvent> publisher = new SubmissionPublisher<>();
	private final CompletableFuture<TunnelSession> opened = new CompletableFuture<>();
	private final CompletableFuture<Void> closed = new CompletableFuture<>();
	private final AtomicReference<TunnelState> state = new AtomicReference<>(TunnelState.CONNECTING);
	private final AtomicBoolean closeRequested = new AtomicBoolean();
	private final AtomicInteger reconnectAttempt = new AtomicInteger();
	private final AtomicLong generation = new AtomicLong();
	private final AtomicLong pingSequence = new AtomicLong();

	private volatile RelayDescriptor relay;
	private volatile PublicEndpoint endpoint;
	private volatile String sessionId;
	private volatile String resumeToken;
	private volatile Instant lastPong = Instant.now();
	private volatile Channel datagramChannel;
	private volatile QuicChannel quicChannel;
	private volatile QuicStreamChannel controlChannel;
	private volatile ScheduledFuture<?> heartbeatTask;
	private volatile ScheduledFuture<?> reconnectTask;

	public NettyTunnelSession(
		NioEventLoopGroup group,
		TunnelConfig config,
		InetSocketAddress localTarget,
		Consumer<TunnelEvent> eventObserver
	) {
		this.group = Objects.requireNonNull(group, "group");
		this.config = Objects.requireNonNull(config, "config");
		this.localTarget = Objects.requireNonNull(localTarget, "localTarget");
		this.eventObserver = Objects.requireNonNull(eventObserver, "eventObserver");
	}

	public CompletionStage<TunnelSession> start() {
		emit(TunnelState.CONNECTING, "Resolving relay");
		resolveAndConnect();
		return opened;
	}

	private void resolveAndConnect() {
		if (closeRequested.get()) {
			return;
		}
		long currentGeneration = generation.incrementAndGet();
		config.relayResolver().resolve().whenComplete((resolved, failure) -> {
			if (failure != null) {
				scheduleReconnect(failure, currentGeneration);
				return;
			}
			relay = resolved;
			group.next().execute(() -> connect(resolved, currentGeneration));
		});
	}

	private void connect(RelayDescriptor descriptor, long currentGeneration) {
		if (closeRequested.get() || currentGeneration != generation.get()) {
			return;
		}
		try {
			QuicSslContext sslContext = QuicSslContextBuilder.forClient()
				.trustManager(descriptor.trustedCertificate().toFile())
				.applicationProtocols(ProtocolFrames.ALPN)
				.build();
			ChannelHandler codec = new QuicClientCodecBuilder()
				.sslContext(sslContext)
				.sslEngineProvider(channel -> sslContext.newEngine(
					channel.alloc(),
					descriptor.host(),
					descriptor.port()
				))
				.initialMaxStreamsBidirectional(64)
				.maxIdleTimeout(config.heartbeatInterval().multipliedBy(4).toMillis(), TimeUnit.MILLISECONDS)
				.initialMaxData(16 * 1024 * 1024)
				.initialMaxStreamDataBidirectionalRemote(2 * 1024 * 1024)
				.initialMaxStreamDataBidirectionalLocal(2 * 1024 * 1024)
				.initialMaxStreamDataUnidirectional(64 * 1024)
				.build();

			ChannelFuture bound = new Bootstrap()
				.group(group)
				.channel(NioDatagramChannel.class)
				.handler(codec)
				.bind(0);
			bound.addListener(result -> {
				if (!result.isSuccess()) {
					bound.channel().close();
					scheduleReconnect(result.cause(), currentGeneration);
					return;
				}
				if (currentGeneration != generation.get() || closeRequested.get()) {
					bound.channel().close();
					return;
				}
				datagramChannel = bound.channel();
				connectQuic(descriptor, bound.channel(), currentGeneration);
			});
		} catch (RuntimeException failure) {
			scheduleReconnect(failure, currentGeneration);
		}
	}

	private void connectQuic(
		RelayDescriptor descriptor,
		Channel datagram,
		long currentGeneration
	) {
		Future<QuicChannel> future = QuicChannel.newBootstrap(datagram)
			.streamHandler(new ChannelInitializer<QuicStreamChannel>() {
				@Override
				protected void initChannel(QuicStreamChannel channel) {
					channel.pipeline().addLast(new IncomingTunnelHandler(NettyTunnelSession.this));
				}
			})
			.handler(new ChannelInboundHandlerAdapter() {
				@Override
				public void channelInactive(ChannelHandlerContext context) {
					onDisconnected(currentGeneration, new IllegalStateException("relay connection closed"));
				}

				@Override
				public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
					context.close();
					onDisconnected(currentGeneration, cause);
				}
			})
			.streamOption(ChannelOption.AUTO_READ, false)
			.streamOption(ChannelOption.ALLOW_HALF_CLOSURE, true)
			.option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
				(int) Math.max(1, Math.min(Integer.MAX_VALUE, config.connectTimeout().toMillis())))
			.remoteAddress(new InetSocketAddress(descriptor.host(), descriptor.port()))
			.connect();
		future.addListener(result -> {
			if (!result.isSuccess()) {
				scheduleReconnect(result.cause(), currentGeneration);
				return;
			}
			if (currentGeneration != generation.get() || closeRequested.get()) {
				future.getNow().close();
				return;
			}
			QuicChannel connected = future.getNow();
			try {
				TlsHostnameVerifier.verify(descriptor.host(), connected.sslEngine());
			} catch (RuntimeException | javax.net.ssl.SSLPeerUnverifiedException failure) {
				connected.close();
				fail(failure);
				return;
			}
			quicChannel = connected;
			openControlStream(connected, currentGeneration);
		});
	}

	private void openControlStream(QuicChannel channel, long currentGeneration) {
		Future<QuicStreamChannel> future = channel.createStream(
			QuicStreamType.BIDIRECTIONAL,
			new ChannelInitializer<QuicStreamChannel>() {
				@Override
				protected void initChannel(QuicStreamChannel stream) {
					stream.pipeline().addLast(
						new ControlFrameDecoder(),
						new ControlHandler(NettyTunnelSession.this, currentGeneration)
					);
				}
			}
		);
		future.addListener(result -> {
			if (!result.isSuccess()) {
				scheduleReconnect(result.cause(), currentGeneration);
				return;
			}
			QuicStreamChannel control = future.getNow();
			controlChannel = control;
			control.config().setAutoRead(true);
			writeRegistration(control);
		});
	}

	private void writeRegistration(Channel channel) {
		JsonObject register = new JsonObject();
		register.addProperty("type", "register");
		register.addProperty("version", ProtocolFrames.VERSION);
		register.addProperty("clientInstanceId", config.clientInstanceId());
		if (resumeToken != null) {
			register.addProperty("resumeToken", resumeToken);
		}
		relay.accessToken().use(value -> {
			register.addProperty("accessToken", new String(value));
			return null;
		});
		ProtocolFrames.write(channel, register);
	}

	void handleControl(JsonObject message, long currentGeneration) {
		if (currentGeneration != generation.get()) {
			return;
		}
		try {
			String type = ProtocolFrames.requiredString(message, "type");
			switch (type) {
				case "registered" -> handleRegistered(message);
				case "pong" -> {
					ProtocolFrames.requiredUnsignedLong(message, "sequence");
					lastPong = Instant.now();
				}
				case "error" -> {
					String code = ProtocolFrames.requiredString(message, "code");
					String detail = ProtocolFrames.requiredString(message, "message");
					boolean retryable = message.has("retryable") && message.get("retryable").getAsBoolean();
					ProtocolException failure = new ProtocolException(code + ": " + detail);
					if ("resume_rejected".equals(code)) {
						resumeToken = null;
						sessionId = null;
						endpoint = null;
					}
					if (retryable) {
						scheduleReconnect(failure, currentGeneration);
					} else {
						fail(failure);
					}
				}
				default -> throw new ProtocolException("unknown control message type: " + type);
			}
		} catch (RuntimeException | ProtocolException failure) {
			fail(failure);
		}
	}

	private void handleRegistered(JsonObject message) {
		sessionId = ProtocolFrames.requiredString(message, "sessionId");
		resumeToken = ProtocolFrames.requiredString(message, "resumeToken");
		endpoint = new PublicEndpoint(
			ProtocolFrames.requiredString(message, "publicHost"),
			ProtocolFrames.requiredInt(message, "publicPort")
		);
		reconnectAttempt.set(0);
		lastPong = Instant.now();
		emit(TunnelState.ACTIVE, "Tunnel active at " + endpoint);
		opened.complete(this);
		scheduleHeartbeat();
	}

	private void scheduleHeartbeat() {
		QuicStreamChannel control = controlChannel;
		if (control == null) {
			return;
		}
		long intervalMillis = config.heartbeatInterval().toMillis();
		ScheduledFuture<?> previous = heartbeatTask;
		if (previous != null) {
			previous.cancel(false);
		}
		heartbeatTask = control.eventLoop().scheduleAtFixedRate(() -> {
			if (closeRequested.get() || !control.isActive()) {
				return;
			}
			if (Duration.between(lastPong, Instant.now()).compareTo(config.heartbeatInterval().multipliedBy(3)) > 0) {
				control.close();
				return;
			}
			JsonObject ping = new JsonObject();
			ping.addProperty("type", "ping");
			ping.addProperty("sequence", pingSequence.incrementAndGet());
			ProtocolFrames.write(control, ping);
		}, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
	}

	void onDisconnected(long disconnectedGeneration, Throwable failure) {
		if (disconnectedGeneration != generation.get() || closeRequested.get()) {
			return;
		}
		scheduleReconnect(failure, disconnectedGeneration);
	}

	long currentGeneration() {
		return generation.get();
	}

	private synchronized void scheduleReconnect(Throwable failure, long failedGeneration) {
		if (closeRequested.get() || state.get() == TunnelState.FAILED
			|| failedGeneration != generation.get()) {
			return;
		}
		generation.incrementAndGet();
		closeChannels();
		ScheduledFuture<?> previousReconnect = reconnectTask;
		if (previousReconnect != null) {
			previousReconnect.cancel(false);
		}
		int attempt = reconnectAttempt.getAndIncrement();
		long initial = config.initialReconnectDelay().toMillis();
		long maximum = config.maximumReconnectDelay().toMillis();
		long multiplier = 1L << Math.min(attempt, 20);
		long exponential = initial > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : initial * multiplier;
		long capped = Math.min(maximum, exponential);
		long jittered = (long) (capped * ThreadLocalRandom.current().nextDouble(0.75, 1.25));
		long delay = Math.min(maximum, Math.max(initial, jittered));
		emit(TunnelState.RECONNECTING, "Relay unavailable; retrying in " + delay + " ms", failure);
		reconnectTask = group.next().schedule(this::resolveAndConnect, delay, TimeUnit.MILLISECONDS);
	}

	private void fail(Throwable failure) {
		if (closeRequested.getAndSet(true)) {
			return;
		}
		generation.incrementAndGet();
		cancelReconnect();
		closeChannels();
		emit(TunnelState.FAILED, "Tunnel failed", failure);
		opened.completeExceptionally(failure);
		closed.completeExceptionally(failure);
		publisher.closeExceptionally(failure);
	}

	private void closeChannels() {
		ScheduledFuture<?> heartbeat = heartbeatTask;
		heartbeatTask = null;
		if (heartbeat != null) {
			heartbeat.cancel(false);
		}
		QuicStreamChannel control = controlChannel;
		controlChannel = null;
		if (control != null) {
			control.close();
		}
		QuicChannel quic = quicChannel;
		quicChannel = null;
		if (quic != null) {
			quic.close();
		}
		Channel datagram = datagramChannel;
		datagramChannel = null;
		if (datagram != null) {
			datagram.close();
		}
	}

	private void cancelReconnect() {
		ScheduledFuture<?> reconnect = reconnectTask;
		reconnectTask = null;
		if (reconnect != null) {
			reconnect.cancel(false);
		}
	}

	private void emit(TunnelState newState, String message) {
		emit(newState, message, null);
	}

	private void emit(TunnelState newState, String message, Throwable failure) {
		state.set(newState);
		TunnelEvent event = new TunnelEvent(Instant.now(), newState, message, failure);
		try {
			eventObserver.accept(event);
		} catch (RuntimeException ignored) {
			// Observability must never interfere with tunnel state transitions.
		}
		publisher.submit(event);
	}

	String sessionId() {
		return sessionId;
	}

	EventLoopGroup eventLoopGroup() {
		return group;
	}

	InetSocketAddress localTarget() {
		return localTarget;
	}

	@Override
	public PublicEndpoint endpoint() {
		PublicEndpoint current = endpoint;
		if (current == null) {
			throw new IllegalStateException("public endpoint has not been assigned yet");
		}
		return current;
	}

	@Override
	public TunnelState state() {
		return state.get();
	}

	@Override
	public Flow.Publisher<TunnelEvent> events() {
		return publisher;
	}

	@Override
	public CompletionStage<Void> closed() {
		return closed;
	}

	@Override
	public void close() {
		if (!closeRequested.compareAndSet(false, true)) {
			return;
		}
		emit(TunnelState.STOPPING, "Closing tunnel");
		QuicStreamChannel control = controlChannel;
		if (control != null && control.isActive()) {
			JsonObject close = new JsonObject();
			close.addProperty("type", "close");
			close.addProperty("reason", "client shutdown");
			ProtocolFrames.write(control, close).addListener(ignored -> finishClose());
		} else {
			finishClose();
		}
	}

	private void finishClose() {
		generation.incrementAndGet();
		cancelReconnect();
		closeChannels();
		emit(TunnelState.CLOSED, "Tunnel closed");
		opened.completeExceptionally(new IllegalStateException("tunnel closed before registration"));
		closed.complete(null);
		publisher.close();
	}
}
