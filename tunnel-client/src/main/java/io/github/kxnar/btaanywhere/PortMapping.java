package io.github.kxnar.btaanywhere;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

public interface PortMapping extends AutoCloseable {
	PublicEndpoint endpoint();

	String protocol();

	Duration remainingLease();

	CompletionStage<Void> closed();

	@Override
	void close();
}
