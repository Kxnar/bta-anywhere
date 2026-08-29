package io.github.kxnar.btaanywhere;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class SecretTest {
	@Test
	void neverPrintsValue() {
		try (Secret secret = Secret.of("private-value")) {
			assertEquals("[REDACTED]", secret.toString());
			assertEquals("private-value", secret.use(String::new));
		}
	}

	@Test
	void destroysValueOnClose() {
		Secret secret = Secret.of("private-value");
		secret.close();
		assertThrows(IllegalStateException.class, () -> secret.use(String::new));
	}
}
