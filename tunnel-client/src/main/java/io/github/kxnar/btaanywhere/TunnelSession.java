package io.github.kxnar.btaanywhere;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

public interface TunnelSession extends AutoCloseable {
	PublicEndpoint endpoint();

	TunnelState state();

	Flow.Publisher<TunnelEvent> events();

	CompletionStage<Void> closed();

	@Override
	void close();
}
