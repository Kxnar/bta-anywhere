package io.github.kxnar.btaanywhere;

import io.github.kxnar.btaanywhere.internal.NettyTunnelSession;
import io.netty.incubator.codec.quic.Quic;
import io.netty.channel.nio.NioEventLoopGroup;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Entry point for opening relay tunnels to arbitrary local TCP services. */
public final class TunnelClient implements AutoCloseable {
	private final NioEventLoopGroup eventLoopGroup;
	private final Set<NettyTunnelSession> sessions = ConcurrentHashMap.newKeySet();
	private final AtomicBoolean closed = new AtomicBoolean();

	public TunnelClient() {
		ensureQuicAvailable();
		ThreadFactory factory = runnable -> {
			Thread thread = new Thread(runnable, "bta-anywhere-tunnel");
			thread.setDaemon(true);
			return thread;
		};
		eventLoopGroup = new NioEventLoopGroup(0, factory);
	}

	/** Fails fast when the current operating system/architecture native QUIC library cannot load. */
	public static void ensureQuicAvailable() {
		Quic.ensureAvailability();
	}

	public CompletionStage<TunnelSession> open(TunnelConfig config, InetSocketAddress localTarget) {
		return open(config, localTarget, ignored -> { });
	}

	/**
	 * Opens a tunnel and observes lifecycle events, including connection failures that happen before
	 * the public endpoint is assigned. The observer runs on a tunnel event-loop thread, must return
	 * quickly, and cannot interrupt the tunnel if it throws.
	 */
	public CompletionStage<TunnelSession> open(
		TunnelConfig config,
		InetSocketAddress localTarget,
		Consumer<TunnelEvent> eventObserver
	) {
		Objects.requireNonNull(config, "config");
		Objects.requireNonNull(localTarget, "localTarget");
		Objects.requireNonNull(eventObserver, "eventObserver");
		if (closed.get()) {
			return CompletableFuture.failedFuture(new IllegalStateException("tunnel client is closed"));
		}
		if (localTarget.getPort() < 1 || localTarget.getPort() > 65_535) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("invalid local target port"));
		}
		NettyTunnelSession session = new NettyTunnelSession(eventLoopGroup, config, localTarget, eventObserver);
		sessions.add(session);
		session.closed().whenComplete((ignored, failure) -> sessions.remove(session));
		return session.start();
	}

	@Override
	public void close() {
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		Set.copyOf(sessions).forEach(NettyTunnelSession::close);
		sessions.clear();
		eventLoopGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
	}
}
