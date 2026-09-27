package io.github.kxnar.btaanywhere;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface RelayRttProbe {
	/** Performs a certificate- and ALPN-verified QUIC handshake without registration. */
	CompletionStage<Long> measureMillis(RelayDescriptor relay, RelayMode mode);
}
