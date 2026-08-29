package io.github.kxnar.btaanywhere;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface RelayResolver {
	CompletionStage<RelayDescriptor> resolve();
}
