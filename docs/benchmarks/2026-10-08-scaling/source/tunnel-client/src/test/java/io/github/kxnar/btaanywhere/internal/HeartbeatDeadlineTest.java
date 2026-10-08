package io.github.kxnar.btaanywhere.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

final class HeartbeatDeadlineTest {
	@Test
	void toleratesShortOutagesAndExpiresAfterThreeMissedIntervals() {
		Duration interval = Duration.ofSeconds(3);
		long pong = 12345;
		assertFalse(NettyTunnelSession.heartbeatExpired(pong, pong + Duration.ofSeconds(3).toNanos(), interval));
		assertFalse(NettyTunnelSession.heartbeatExpired(pong, pong + Duration.ofSeconds(9).toNanos() - 1, interval));
		assertTrue(NettyTunnelSession.heartbeatExpired(pong, pong + Duration.ofSeconds(9).toNanos(), interval));
	}

	@Test
	void refreshedPongRestartsDeadlineEvenAcrossNanoTimeWrap() {
		long pong = Long.MAX_VALUE - Duration.ofSeconds(2).toNanos();
		assertFalse(NettyTunnelSession.heartbeatExpired(pong, pong + Duration.ofSeconds(3).toNanos(), Duration.ofSeconds(3)));
		assertTrue(NettyTunnelSession.heartbeatExpired(pong, pong + Duration.ofSeconds(10).toNanos(), Duration.ofSeconds(3)));
	}
}
