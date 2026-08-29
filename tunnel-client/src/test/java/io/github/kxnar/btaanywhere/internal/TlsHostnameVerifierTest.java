package io.github.kxnar.btaanywhere.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class TlsHostnameVerifierTest {
	@Test
	void matchesExactAndSingleLabelWildcardDnsNames() {
		assertTrue(TlsHostnameVerifier.matches("relay.example.com", List.of(List.of(2, "relay.example.com"))));
		assertTrue(TlsHostnameVerifier.matches("eu.example.com", List.of(List.of(2, "*.example.com"))));
		assertFalse(TlsHostnameVerifier.matches("example.com", List.of(List.of(2, "*.example.com"))));
		assertFalse(TlsHostnameVerifier.matches("one.two.example.com", List.of(List.of(2, "*.example.com"))));
		assertFalse(TlsHostnameVerifier.matches("example.com", List.of(List.of(2, "*.com"))));
	}

	@Test
	void matchesIpSansWithoutDnsLookup() {
		assertTrue(TlsHostnameVerifier.matches("127.0.0.1", List.of(List.of(7, "127.0.0.1"))));
		assertTrue(TlsHostnameVerifier.matches("[::1]", List.of(List.of(7, "0:0:0:0:0:0:0:1"))));
		assertFalse(TlsHostnameVerifier.matches("127.0.0.1", List.of(List.of(2, "127.0.0.1"))));
	}
}
