package io.github.kxnar.btaanywhere;

/** Validated response fields from the coordinator's direct 200 JSON body. */
public record CoordinatorAllocation(
	String ticket,
	String relayId,
	int publicPort,
	long expiresAtEpochMillis
) {
	public CoordinatorAllocation {
		if (ticket == null || !ticket.startsWith("BTACT1:") || ticket.length() > 2048
			|| !RelayAllowlist.validRelayId(relayId)
			|| publicPort < 1 || publicPort > 65_535 || expiresAtEpochMillis <= 0) {
			throw new IllegalArgumentException("coordinator allocation response is invalid");
		}
	}

	@Override
	public String toString() {
		return "CoordinatorAllocation[ticket=[REDACTED], relayId=" + relayId
			+ ", publicPort=" + publicPort
			+ ", expiresAtEpochMillis=" + expiresAtEpochMillis + "]";
	}
}
