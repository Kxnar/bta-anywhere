package io.github.kxnar.btaanywhere.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.kxnar.btaanywhere.ProtocolException;
import io.github.kxnar.btaanywhere.PublicEndpoint;
import io.github.kxnar.btaanywhere.RelayDescriptor;
import io.github.kxnar.btaanywhere.RelayMode;
import io.github.kxnar.btaanywhere.RelaySelection;
import io.github.kxnar.btaanywhere.TunnelConfig;
import io.github.kxnar.btaanywhere.TunnelEvent;
import io.github.kxnar.btaanywhere.TunnelSession;
import io.github.kxnar.btaanywhere.TunnelState;
import io.github.kxnar.btaanywhere.encrypted.EncryptedHostContext;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class NettyTunnelSession implements TunnelSession {
	private static final int MAX_PENDING_EOF_NOTICES = 64;
	private final NioEventLoopGroup group;
	private final TunnelConfig config;
	private final InetSocketAddress localTarget;
	private final Consumer<TunnelEvent> eventObserver;
	private final EncryptedHostContext encrypted;
	private final SubmissionPublisher<TunnelEvent> publisher = new SubmissionPublisher<>();
	private final CompletableFuture<TunnelSession> opened = new CompletableFuture<>();
	private final CompletableFuture<Void> closed = new CompletableFuture<>();
	private final AtomicReference<TunnelState> state = new AtomicReference<>(TunnelState.CONNECTING);
	private final AtomicBoolean closeRequested = new AtomicBoolean();
	private final AtomicInteger reconnectAttempt = new AtomicInteger();
	private final AtomicLong generation = new AtomicLong();
	private final AtomicLong pingSequence = new AtomicLong();
	private final AtomicInteger pendingEofNotices = new AtomicInteger();

	private volatile RelayDescriptor relay;
	private volatile RelaySelection relaySelection;
	private volatile PublicEndpoint endpoint;
	private volatile String sessionId;
	private volatile String resumeToken;
	private volatile boolean allocationTicketPendingAck;
	private volatile long lastPongNanos = System.nanoTime();
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
		this(group, config, localTarget, eventObserver, null);
	}

	public NettyTunnelSession(
		NioEventLoopGroup group,
		TunnelConfig config,
		InetSocketAddress localTarget,
		Consumer<TunnelEvent> eventObserver,
		EncryptedHostContext encrypted
	) {
		this.group = Objects.requireNonNull(group, "group");
		this.config = Objects.requireNonNull(config, "config");
		this.localTarget = Objects.requireNonNull(localTarget, "localTarget");
		this.eventObserver = Objects.requireNonNull(eventObserver, "eventObserver");
		this.encrypted = encrypted;
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
		traceLifecycle("connect_start");
		long currentGeneration = generation.incrementAndGet();
		RelaySelection pinned = relaySelection;
		CompletionStage<RelaySelection> resolution = pinned == null
			? config.relayResolver().resolveSelection()
			: CompletableFuture.completedFuture(pinned);
		resolution.whenComplete((resolved, failure) -> {
			if (failure != null) {
				scheduleReconnect(failure, currentGeneration);
				return;
			}
			if (closeRequested.get() || currentGeneration != generation.get()) {
				if (pinned == null) {
					resolved.close();
				}
				return;
			}
			RelayMode requiredMode = encrypted == null ? RelayMode.LEGACY : RelayMode.ENCRYPTED;
			if (resolved.mode() != null && resolved.mode() != requiredMode) {
				resolved.close();
				fail(new IllegalStateException("resolved relay mode does not match the selected tunnel mode"));
				return;
			}
			if (pinned == null) {
				relaySelection = resolved;
			}
			relay = resolved.descriptor();
			group.next().execute(() -> connect(resolved, currentGeneration));
		});
	}

	private void connect(RelaySelection selection, long currentGeneration) {
		if (closeRequested.get() || currentGeneration != generation.get()) {
			return;
		}
		RelayDescriptor descriptor = selection.descriptor();
		try {
			QuicSslContext sslContext = QuicSslContextBuilder.forClient()
				.trustManager(descriptor.trustedCertificate().toFile())
				.applicationProtocols(encrypted == null ? ProtocolFrames.ALPN : "bta-anywhere/2")
				.build();
			ChannelHandler codec = new QuicClientCodecBuilder()
				.sslContext(sslContext)
				// Each UDP socket owns exactly one connection. With an empty local CID,
				// Netty also dispatches short-header stateless resets to quiche, which
				// authenticates their token. Nonempty CIDs made the codec discard those
				// random-looking packets before native reset verification could run.
				.localConnectionIdLength(0)
				.activeMigration(false)
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
					if (encrypted == null) {
						channel.pipeline().addLast(new IncomingTunnelHandler(NettyTunnelSession.this, currentGeneration));
					} else {
						channel.pipeline().addLast(new EncryptedIncomingHandler(encrypted, localTarget, NettyTunnelSession.this::sessionId));
					}
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
		JsonObject register;
		try {
			register = registrationMessage(
				encrypted != null,
				config.clientInstanceId(),
				relay,
				relaySelection,
				resumeToken,
				Instant.now().toEpochMilli()
			);
		} catch (RuntimeException failure) {
			fail(failure);
			return;
		}
		allocationTicketPendingAck = register.has("allocationTicket");
		ProtocolFrames.write(channel, register);
	}

	static JsonObject registrationMessage(
		boolean encryptedMode,
		String clientInstanceId,
		RelayDescriptor relay,
		RelaySelection selection,
		String resumeToken,
		long nowEpochMillis
	) {
		JsonObject register = new JsonObject();
		register.addProperty("type", "register");
		register.addProperty("version", encryptedMode ? 2 : ProtocolFrames.VERSION);
		register.addProperty("clientInstanceId", clientInstanceId);
		JsonArray features = new JsonArray();
		if (!encryptedMode) {
			features.add(ProtocolFrames.STREAM_EOF_BYTES);
		}
		boolean resuming = resumeToken != null;
		if (selection != null && selection.coordinated() && !resuming) {
			if (selection.expiresAtEpochMillis() <= nowEpochMillis) {
				throw new IllegalStateException("coordinator allocation expired before relay registration");
			}
			features.add(ProtocolFrames.ALLOCATION_TICKET);
			String allocationTicket = selection.useAllocationTicket(ticket -> new String(ticket));
			register.addProperty("allocationTicket", allocationTicket);
		}
		register.add("features", features);
		if (resuming) {
			register.addProperty("resumeToken", resumeToken);
		}
		relay.accessToken().use(value -> {
			register.addProperty("accessToken", new String(value));
			return null;
		});
		return register;
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
					lastPongNanos = System.nanoTime();
				}
				case "error" -> {
					String code = ProtocolFrames.requiredString(message, "code");
					String detail = ProtocolFrames.requiredString(message, "message");
					boolean retryable = ProtocolFrames.requiredBoolean(message, "retryable");
					ProtocolException failure = new ProtocolException(code + ": " + detail);
					if ("resume_rejected".equals(code)) {
						boolean coordinatedSession = relaySelection != null && relaySelection.coordinated()
							&& sessionId != null;
						resumeToken = null;
						sessionId = null;
						endpoint = null;
						if (coordinatedSession) {
							fail(new ProtocolException(
								"coordinated relay session could not resume; start a new tunnel session"));
							return;
						}
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
		Set<String> expectedFeatures = encrypted == null
			? (allocationTicketPendingAck
				? Set.of(ProtocolFrames.STREAM_EOF_BYTES, ProtocolFrames.ALLOCATION_TICKET)
				: Set.of(ProtocolFrames.STREAM_EOF_BYTES))
			: (allocationTicketPendingAck ? Set.of(ProtocolFrames.ALLOCATION_TICKET) : Set.of());
		ProtocolFrames.requireRegisteredFeatures(message, expectedFeatures);
		allocationTicketPendingAck = false;
		sessionId = ProtocolFrames.requiredString(message, "sessionId");
		resumeToken = ProtocolFrames.requiredString(message, "resumeToken");
		endpoint = new PublicEndpoint(
			ProtocolFrames.requiredString(message, "publicHost"),
			ProtocolFrames.requiredInt(message, "publicPort")
		);
		RelaySelection selection = relaySelection;
		if (selection != null && selection.coordinated() && endpoint.port() != selection.publicPort()) {
			throw new IllegalStateException("relay endpoint port does not match the reserved allocation");
		}
		if (selection != null && selection.coordinated()) {
			selection.close();
		}
		if (encrypted != null) {
			encrypted.onRegistered(sessionId, endpoint, relay, quicChannel);
		}
		reconnectAttempt.set(0);
		lastPongNanos = System.nanoTime();
		traceLifecycle("registered");
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
			if (heartbeatExpired(lastPongNanos, System.nanoTime(), config.heartbeatInterval())) {
				traceLifecycle("heartbeat_expired");
				control.close();
				return;
			}
			JsonObject ping = new JsonObject();
			ping.addProperty("type", "ping");
			ping.addProperty("sequence", pingSequence.incrementAndGet());
			ProtocolFrames.write(control, ping);
		}, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
	}

	static boolean heartbeatExpired(long lastPongNanos, long nowNanos, Duration interval) {
		// Wall-clock adjustments must not trigger or defer failure detection.
		return Duration.ofNanos(nowNanos - lastPongNanos).compareTo(interval.multipliedBy(3)) >= 0;
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
		traceLifecycle("failure_detected");
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

	private static void traceLifecycle(String phase) {
		if (Boolean.getBoolean("bta.lifecycleTrace")) {
			// Diagnostic only: monotonic timestamps and phase names, no addresses or credentials.
			System.err.println("BTA_LIFECYCLE " + phase + " " + System.nanoTime());
		}
	}

	private void fail(Throwable failure) {
		if (closeRequested.getAndSet(true)) {
			return;
		}
		generation.incrementAndGet();
		cancelReconnect();
		closeChannels();
		if (encrypted != null) {
			encrypted.revokeAll();
		}
		closeRelaySelection();
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

	void sendStreamEof(long connectionGeneration, String connectionId, long bytes,
		QuicStreamChannel stream, String traceId) {
		Channel owner = stream.parent();
		QuicStreamChannel control = controlChannel;
		if (connectionGeneration != generation.get() || control == null
			|| !control.isActive() || control.parent() != owner) {
			EofTrace.emit(traceId, "eof_notice_write", bytes, "stale-or-inactive-control");
			abortUnconfirmedCompletion(stream);
			return;
		}
		if (pendingEofNotices.incrementAndGet() > MAX_PENDING_EOF_NOTICES) {
			pendingEofNotices.decrementAndGet();
			EofTrace.emit(traceId, "eof_notice_write", bytes, "queue-full");
			abortUnconfirmedCompletion(stream);
			return;
		}
		ChannelFuture write;
		try {
			write = ProtocolFrames.write(control, ProtocolFrames.streamEofMessage(connectionId, bytes));
		} catch (RuntimeException failure) {
			pendingEofNotices.decrementAndGet();
			EofTrace.emit(traceId, "eof_notice_write", bytes, "failed");
			abortUnconfirmedCompletion(stream);
			return;
		}
		write.addListener(result -> {
			pendingEofNotices.decrementAndGet();
			EofTrace.emit(traceId, "eof_notice_write", bytes,
				result.isSuccess() ? "success" : "failed");
			if (!result.isSuccess()) {
				abortUnconfirmedCompletion(stream);
			}
		});
	}

	private void abortUnconfirmedCompletion(QuicStreamChannel stream) {
		// A locally successful output shutdown may not have sent FIN. Closing the
		// parent connection makes every pending guest fail instead of waiting for
		// a completion notice that the relay will never receive.
		Channel owner = stream.parent();
		if (owner != null) {
			owner.close();
		} else {
			stream.close();
		}
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
		if (encrypted != null) {
			encrypted.revokeAll();
		}
		closeRelaySelection();
		emit(TunnelState.CLOSED, "Tunnel closed");
		opened.completeExceptionally(new IllegalStateException("tunnel closed before registration"));
		closed.complete(null);
		publisher.close();
	}

	private void closeRelaySelection() {
		RelaySelection selection = relaySelection;
		relaySelection = null;
		if (selection != null) {
			selection.close();
		}
	}
}
