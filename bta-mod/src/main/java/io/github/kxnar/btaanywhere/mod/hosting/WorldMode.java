package io.github.kxnar.btaanywhere.mod.hosting;

public enum WorldMode {
	LIVE("Live world"),
	SHOWCASE("Showcase copy");

	private final String label;

	WorldMode(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	public WorldMode next() {
		return this == LIVE ? SHOWCASE : LIVE;
	}
}
