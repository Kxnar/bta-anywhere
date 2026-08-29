package io.github.kxnar.btaanywhere;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

final class PublicAddress {
	private PublicAddress() {
	}

	static boolean isPublic(InetAddress address) {
		if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
			|| address.isLinkLocalAddress() || address.isSiteLocalAddress()
			|| address.isMulticastAddress()) {
			return false;
		}
		byte[] bytes = address.getAddress();
		if (address instanceof Inet4Address) {
			int first = Byte.toUnsignedInt(bytes[0]);
			int second = Byte.toUnsignedInt(bytes[1]);
			if (first == 100 && second >= 64 && second <= 127) {
				return false;
			}
			if (first == 0 || first >= 224) {
				return false;
			}
		}
		if (address instanceof Inet6Address) {
			int first = Byte.toUnsignedInt(bytes[0]);
			if ((first & 0xfe) == 0xfc) {
				return false;
			}
		}
		return true;
	}
}
