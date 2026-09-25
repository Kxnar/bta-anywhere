package io.github.kxnar.btaanywhere.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class EofTraceTest {
	@Test
	void hashesConnectionIdUsingTheSameSha256PrefixAsTheRelay() {
		assertEquals("ba7816bf8f01cfea", EofTrace.hashId("abc"));
	}
}
