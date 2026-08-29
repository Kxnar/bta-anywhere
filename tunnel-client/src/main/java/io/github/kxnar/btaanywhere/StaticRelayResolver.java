package io.github.kxnar.btaanywhere;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class StaticRelayResolver implements RelayResolver {
	private final RelayDescriptor relay;

	public StaticRelayResolver(RelayDescriptor relay) {
		this.relay = Objects.requireNonNull(relay, "relay");
	}

	@Override
	public CompletionStage<RelayDescriptor> resolve() {
		return CompletableFuture.completedFuture(relay);
	}
}
