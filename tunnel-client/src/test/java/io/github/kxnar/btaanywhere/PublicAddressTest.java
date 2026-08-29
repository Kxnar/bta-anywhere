package io.github.kxnar.btaanywhere;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

final class PublicAddressTest {
	@Test
	void rejectsPrivateAndCarrierGradeNatRanges() throws Exception {
		assertFalse(PublicAddress.isPublic(InetAddress.getByName("127.0.0.1")));
		assertFalse(PublicAddress.isPublic(InetAddress.getByName("192.168.1.1")));
		assertFalse(PublicAddress.isPublic(InetAddress.getByName("100.64.0.1")));
		assertFalse(PublicAddress.isPublic(InetAddress.getByName("100.127.255.254")));
		assertFalse(PublicAddress.isPublic(InetAddress.getByName("fc00::1")));
	}

	@Test
	void acceptsPublicAddresses() throws Exception {
		assertTrue(PublicAddress.isPublic(InetAddress.getByName("1.1.1.1")));
		assertTrue(PublicAddress.isPublic(InetAddress.getByName("2606:4700:4700::1111")));
	}
}
