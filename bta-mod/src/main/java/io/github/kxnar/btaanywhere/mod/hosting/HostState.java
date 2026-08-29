package io.github.kxnar.btaanywhere.mod.hosting;

public enum HostState {
	IDLE,
	VALIDATING,
	SAVING,
	BACKING_UP,
	COPYING,
	STARTING_SERVER,
	WAITING_READY,
	EXPOSING,
	CONNECTING,
	HOSTING,
	STOPPING,
	FAILED;

	public boolean isBusy() {
		return this != IDLE && this != FAILED;
	}

	public boolean canStop() {
		return switch (this) {
			case VALIDATING, SAVING, BACKING_UP, COPYING, STARTING_SERVER,
				WAITING_READY, EXPOSING, CONNECTING, HOSTING -> true;
			default -> false;
		};
	}
}
