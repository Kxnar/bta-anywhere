package io.github.kxnar.btaanywhere;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;

/** Mutable secret value whose string representation is always redacted. */
public final class Secret implements AutoCloseable {
	private char[] value;

	private Secret(char[] value) {
		this.value = value;
	}

	public static Secret of(String value) {
		Objects.requireNonNull(value, "value");
		if (value.isBlank()) {
			throw new IllegalArgumentException("secret cannot be blank");
		}
		return new Secret(value.toCharArray());
	}

	public synchronized <T> T use(Function<char[], T> action) {
		Objects.requireNonNull(action, "action");
		if (value == null) {
			throw new IllegalStateException("secret has been destroyed");
		}
		char[] copy = value.clone();
		try {
			return action.apply(copy);
		} finally {
			Arrays.fill(copy, '\0');
		}
	}

	@Override
	public synchronized void close() {
		if (value != null) {
			Arrays.fill(value, '\0');
			value = null;
		}
	}

	@Override
	public String toString() {
		return "[REDACTED]";
	}
}
