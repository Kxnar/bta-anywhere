package io.github.kxnar.btaanywhere;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class PublicEndpointTest {
	@Test
	void rendersIpv4AndIpv6Addresses() {
		assertEquals("relay.example:30000", new PublicEndpoint("relay.example", 30000).toString());
		assertEquals("[2001:db8::1]:30000", new PublicEndpoint("2001:db8::1", 30000).toString());
	}

	@Test
	void rejectsHostsThatCouldInjectLogLines() {
		assertThrows(IllegalArgumentException.class, () -> new PublicEndpoint("relay.example\ninvalid", 30_000));
	}
}
