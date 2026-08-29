package io.github.kxnar.btaanywhere.mod.hosting;

public final class ConfirmationRequiredException extends Exception {
	private static final long serialVersionUID = 1L;

	public ConfirmationRequiredException(String message) {
		super(message);
	}
}
