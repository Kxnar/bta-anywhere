package io.github.kxnar.btaanywhere.encrypted;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

final class BtaeInvitationTest {
	private static final String JOIN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
	private static final String STATUS = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE";
	private static final String ID = "AgICAgICAgICAgICAgICAg";
	private static final String SESSION = "AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM";
	private static final String PIN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

	@Test
	void roundTripsCanonicalInvitation() {
		BtaeInvitation invitation = sample();
		String encoded = invitation.encode();

		assertTrue(encoded.startsWith("BTAE1:"));
		assertEquals(invitation, BtaeInvitation.parse(encoded));
		assertEquals(encoded, BtaeInvitation.parse(encoded).encode());
	}

	@Test
	void rejectsDuplicateUnknownMissingAndNonCanonicalFields() {
		String canonical = json(sample());
		assertInvalid(encode(canonical.replace("\"version\":1,", "\"version\":1,\"version\":1,")));
		assertInvalid(encode(canonical.replace("{", "{\"extra\":0,")));
		assertInvalid(encode(canonical.replace("\"relayPort\":30000,", "")));
		assertInvalid(encode(canonical.replace("{\"version\":1,", "{ \"version\":1,")));
		assertInvalid(encode(canonical.replace("\"relayPort\":30000", "\"relayPort\":030000")));
	}

	@Test
	void rejectsMalformedAndOversizedEncodingsWithoutEchoingInput() {
		String secretLookingInput = "BTAE1:" + "A".repeat(2_100);
		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
			() -> BtaeInvitation.parse(secretLookingInput));
		assertFalse(failure.getMessage().contains(secretLookingInput));
		assertInvalid("BTAE1:***");
		assertInvalid(encode(json(sample()).replace(PIN, "F" + PIN.substring(1))));
	}

	@Test
	void redactsBothCapabilitiesFromToString() {
		String rendered = sample().toString();
		assertFalse(rendered.contains(JOIN));
		assertFalse(rendered.contains(STATUS));
		assertTrue(rendered.contains("[REDACTED]"));
	}

	private static BtaeInvitation sample() {
		return new BtaeInvitation(ID, SESSION, PIN, "relay.example", 30_000, PIN,
			1_800_000_000_000L, JOIN, STATUS);
	}

	private static String json(BtaeInvitation invitation) {
		byte[] bytes = Base64.getUrlDecoder().decode(invitation.encode().substring("BTAE1:".length()));
		return new String(bytes, StandardCharsets.UTF_8);
	}

	private static String encode(String json) {
		return "BTAE1:" + Base64.getUrlEncoder().withoutPadding()
			.encodeToString(json.getBytes(StandardCharsets.UTF_8));
	}

	private static void assertInvalid(String input) {
		assertThrows(IllegalArgumentException.class, () -> BtaeInvitation.parse(input));
	}
}
