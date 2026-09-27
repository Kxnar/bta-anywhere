package io.github.kxnar.btaanywhere;

import java.util.List;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface CoordinatorAllocationClient {
	CompletionStage<CoordinatorAllocation> allocate(
		String clientInstanceId,
		RelayMode mode,
		List<RelayProbeSample> probes
	);
}
