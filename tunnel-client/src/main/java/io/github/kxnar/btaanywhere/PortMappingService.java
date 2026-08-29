package io.github.kxnar.btaanywhere;

import java.util.concurrent.CompletionStage;

public interface PortMappingService extends AutoCloseable {
	CompletionStage<PortMapping> open(PortMappingRequest request);

	@Override
	void close();
}
