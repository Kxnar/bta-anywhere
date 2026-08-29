package io.github.kxnar.btaanywhere.mod.hosting;

public enum NetworkMode {
	LAN("LAN"),
	DIRECT("Direct"),
	RELAY("Relay");

	private final String label;

	NetworkMode(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	public NetworkMode next() {
		NetworkMode[] values = values();
		return values[(ordinal() + 1) % values.length];
	}
}
