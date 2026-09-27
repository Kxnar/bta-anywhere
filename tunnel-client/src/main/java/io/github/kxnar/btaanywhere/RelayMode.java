package io.github.kxnar.btaanywhere;

/** Coordinator allocation protocol mode. */
public enum RelayMode {
	LEGACY("legacy"),
	ENCRYPTED("encrypted");

	private final String wireName;

	RelayMode(String wireName) {
		this.wireName = wireName;
	}

	public String wireName() {
		return wireName;
	}
}
