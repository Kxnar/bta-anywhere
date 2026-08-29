package io.github.kxnar.btaanywhere;

import com.offbynull.portmapper.PortMapperFactory;
import com.offbynull.portmapper.gateway.Bus;
import com.offbynull.portmapper.gateways.network.NetworkGateway;
import com.offbynull.portmapper.gateways.network.internalmessages.KillNetworkRequest;
import com.offbynull.portmapper.gateways.process.ProcessGateway;
import com.offbynull.portmapper.gateways.process.internalmessages.KillProcessRequest;
import com.offbynull.portmapper.mapper.MappedPort;
import com.offbynull.portmapper.mapper.PortMapper;
import com.offbynull.portmapper.mapper.PortType;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

final class OffbynullPortMappingBackend implements PortMappingBackend {
	@Override
	public Discovery discover() throws InterruptedException {
		NetworkGateway network = NetworkGateway.create();
		ProcessGateway process = ProcessGateway.create();
		Bus networkBus = network.getBus();
		Bus processBus = process.getBus();
		try {
			List<Candidate> candidates = PortMapperFactory.discover(networkBus, processBus).stream()
				.map(OffbynullCandidate::new)
				.map(Candidate.class::cast)
				.toList();
			return new OffbynullDiscovery(candidates, network, networkBus);
		} catch (InterruptedException | RuntimeException exception) {
			shutdownNetwork(networkBus, network);
			throw exception;
		} finally {
			try {
				processBus.send(new KillProcessRequest());
				process.join();
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			} catch (RuntimeException ignored) {
				// Discovery may already have stopped the process gateway.
			}
		}
	}

	private static Protocol protocolFor(PortMapper mapper) {
		String name = mapper.getClass().getSimpleName().toLowerCase();
		if (name.contains("pcp") && !name.contains("nat")) {
			return Protocol.PCP;
		}
		if (name.contains("natpmp")) {
			return Protocol.NAT_PMP;
		}
		return Protocol.UPNP;
	}

	private static void shutdownNetwork(Bus bus, NetworkGateway gateway) {
		try {
			bus.send(new KillNetworkRequest());
			gateway.join();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		} catch (RuntimeException ignored) {
			// Mapping expiry remains the fallback if gateway cleanup fails.
		}
	}

	private record OffbynullCandidate(PortMapper mapper) implements Candidate {
		@Override
		public Protocol protocol() {
			return protocolFor(mapper);
		}

		@Override
		public Lease map(PortMappingRequest request) throws InterruptedException {
			MappedPort mapped = mapper.mapPort(
				PortType.TCP,
				request.internalPort(),
				request.preferredExternalPort(),
				request.lease().toSeconds()
			);
			return new OffbynullLease(mapper, mapped);
		}
	}

	private record OffbynullLease(PortMapper mapper, MappedPort mapped) implements Lease {
		@Override
		public java.net.InetAddress externalAddress() {
			return mapped.getExternalAddress();
		}

		@Override
		public int externalPort() {
			return mapped.getExternalPort();
		}

		@Override
		public long lifetimeSeconds() {
			return mapped.getLifetime();
		}

		@Override
		public Lease refresh() throws InterruptedException {
			return new OffbynullLease(mapper, mapper.refreshPort(mapped, mapped.getLifetime()));
		}

		@Override
		public void unmap() throws InterruptedException {
			mapper.unmapPort(mapped);
		}
	}

	private static final class OffbynullDiscovery implements Discovery {
		private final List<Candidate> candidates;
		private final NetworkGateway network;
		private final Bus networkBus;
		private final AtomicBoolean closed = new AtomicBoolean();

		OffbynullDiscovery(List<Candidate> candidates, NetworkGateway network, Bus networkBus) {
			this.candidates = List.copyOf(candidates);
			this.network = network;
			this.networkBus = networkBus;
		}

		@Override
		public List<Candidate> candidates() {
			return candidates;
		}

		@Override
		public void close() {
			if (closed.compareAndSet(false, true)) {
				shutdownNetwork(networkBus, network);
			}
		}
	}
}
