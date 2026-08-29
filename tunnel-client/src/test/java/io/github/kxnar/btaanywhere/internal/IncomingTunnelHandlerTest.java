package io.github.kxnar.btaanywhere.internal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class IncomingTunnelHandlerTest {
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
