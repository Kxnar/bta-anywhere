package io.github.kxnar.btaanywhere;

import java.net.InetAddress;
import java.util.List;

/** Package-private boundary that makes router discovery deterministic in tests. */
interface PortMappingBackend {
	Discovery discover() throws InterruptedException;

	interface Discovery extends AutoCloseable {
		List<Candidate> candidates();

		@Override
		void close();
	}

	interface Candidate {
		Protocol protocol();

		Lease map(PortMappingRequest request) throws InterruptedException;
	}

	interface Lease {
		InetAddress externalAddress();

		int externalPort();

		long lifetimeSeconds();

		Lease refresh() throws InterruptedException;

		void unmap() throws InterruptedException;
	}

	enum Protocol {
		PCP(0, "PCP"),
		NAT_PMP(1, "NAT-PMP"),
		UPNP(2, "UPnP");

		private final int priority;
		private final String displayName;

		Protocol(int priority, String displayName) {
			this.priority = priority;
			this.displayName = displayName;
		}

		int priority() {
			return priority;
		}

		String displayName() {
			return displayName;
		}
	}
}
