package io.github.kxnar.btaanywhere;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface RelayResolver {
	CompletionStage<RelayDescriptor> resolve();

	/**
	 * Resolves the trusted endpoint and, for coordinator-managed sessions, the
	 * short-lived allocation ticket that must accompany first registration.
	 */
	default CompletionStage<RelaySelection> resolveSelection() {
		return resolve().thenApply(RelaySelection::staticRelay);
	}
}
