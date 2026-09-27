package io.github.kxnar.btaanywhere;

/** Bounded, immediately measured host-to-relay QUIC handshake sample. */
public record RelayProbeSample(String relayId, long rttMillis, long measuredAtEpochMillis) {
	public RelayProbeSample {
		if (!RelayAllowlist.validRelayId(relayId)) {
			throw new IllegalArgumentException("relay probe ID is invalid");
		}
		if (rttMillis < 1 || rttMillis > 60_000 || measuredAtEpochMillis <= 0) {
			throw new IllegalArgumentException("relay probe sample is invalid");
		}
	}
}
