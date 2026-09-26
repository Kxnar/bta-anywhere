package io.github.kxnar.btaanywhere.encrypted;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Consumes the public cross-implementation Workstream 3 wire and admission vectors. */
final class EncryptedV2VectorTest {
	private static final String SESSION = "AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM";
	private static final String PIN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

	@Test
	void sharedV2InvitationAndAuthVectorsMatchParsers() throws Exception {
		JsonObject corpus = corpus();
		assertEquals(1, corpus.get("schemaVersion").getAsInt());
		assertEquals(2, corpus.get("protocolVersion").getAsInt());
		assertEquals("bta-anywhere/2", corpus.get("hostAlpn").getAsString());
		assertEquals("bta-anywhere-relay/1", corpus.get("relayAlpn").getAsString());
		assertEquals("bta-anywhere-guest/1", corpus.get("guestAlpn").getAsString());
		for (var vector : corpus.getAsJsonArray("invitations")) {
			JsonObject item = vector.getAsJsonObject();
			String wire = item.has("wire")
				? item.get("wire").getAsString()
				: mutate(corpus.getAsJsonArray("invitations").get(0).getAsJsonObject().get("wire").getAsString(),
					item.get("mutation").getAsString());
			if (item.get("accepted").getAsBoolean()) {
				BtaeInvitation parsed = BtaeInvitation.parse(wire);
				assertEquals(wire, parsed.encode(), item.get("name").getAsString());
			} else {
				assertThrows(IllegalArgumentException.class,
					() -> BtaeInvitation.parse(wire), item.get("name").getAsString());
			}
		}
		for (var vector : corpus.getAsJsonArray("auth")) {
			JsonObject item = vector.getAsJsonObject();
			byte[] body = item.get("json").getAsString().getBytes(StandardCharsets.UTF_8);
			if (item.get("accepted").getAsBoolean()) {
				String intent = EncryptedAuth.parse(body).intent();
				assertTrue(intent.equals("join") || intent.equals("status"), item.get("name").getAsString());
			} else {
				assertThrows(IllegalArgumentException.class, () -> EncryptedAuth.parse(body), item.get("name").getAsString());
			}
		}
	}

	@Test
	void sharedV2FrameVectorsMatchDecoderAndRejectMalformedTraffic() throws Exception {
		for (var vector : corpus().getAsJsonArray("frames")) {
			JsonObject item = vector.getAsJsonObject();
			byte[] wire = HexFormat.of().parseHex(item.get("hex").getAsString());
			boolean accepted = false;
			try {
				EncryptedFrames.read(new ByteArrayInputStream(wire));
				accepted = true;
			} catch (IllegalArgumentException | java.io.IOException expected) {
				// Negative vectors intentionally stop in the bounded decoder.
			}
			assertEquals(item.get("accepted").getAsBoolean(), accepted, item.get("name").getAsString());
		}
	}

	@Test
	void sharedAdmissionVectorsRejectExpiryRevocationReplayTamperingAndWrongIdentity() throws Exception {
		for (var vector : corpus().getAsJsonArray("admission")) {
			JsonObject item = vector.getAsJsonObject();
			AtomicLong monotonic = new AtomicLong(1_000_000L);
			AtomicLong wall = new AtomicLong(1_800_000_000_000L);
			InvitationRegistry registry = new InvitationRegistry(SESSION, PIN, "relay.example", 30_000, PIN,
				new SecureRandom(), monotonic::get, wall::get);
			long lifetimeMillis = item.has("lifetimeMillis") ? item.get("lifetimeMillis").getAsLong() : 60_000L;
			BtaeInvitation invitation = registry.issue(Duration.ofMillis(lifetimeMillis));
			String operation = item.get("operation").getAsString();
			boolean accepted;
			switch (operation) {
				case "join" -> {
					long advance = item.get("advanceMillis").getAsLong();
					monotonic.addAndGet(Duration.ofMillis(advance).toNanos());
					accepted = registry.redeemJoin(invitation.invitationId(), SESSION, invitation.joinCapability());
				}
				case "revoke-status" -> {
					registry.revoke(invitation.invitationId());
					accepted = registry.authorizeStatus(invitation.invitationId(), SESSION, invitation.statusCapability());
				}
				case "replay-join" -> {
					registry.redeemJoin(invitation.invitationId(), SESSION, invitation.joinCapability());
					accepted = registry.redeemJoin(invitation.invitationId(), SESSION, invitation.joinCapability());
				}
				case "wrong-session" -> accepted = registry.redeemJoin(invitation.invitationId(), "BAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQ", invitation.joinCapability());
				case "join-with-status-capability" -> accepted = registry.redeemJoin(invitation.invitationId(), SESSION, invitation.statusCapability());
				case "tamper-capability" -> {
					String capability = invitation.joinCapability();
					String changed = (capability.charAt(0) == 'A' ? "B" : "A") + capability.substring(1);
					accepted = registry.redeemJoin(invitation.invitationId(), SESSION, changed);
				}
				default -> throw new AssertionError("unknown admission vector: " + operation);
			}
			assertEquals(item.get("accepted").getAsBoolean(), accepted, item.get("name").getAsString());
		}
	}

	private static JsonObject corpus() throws Exception {
		String directory = System.getProperty("btaAnywhereProtocolVectors");
		Path path = Path.of(directory, "encrypted-v2.json");
		return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
	}

	private static String mutate(String wire, String mutation) {
		if ("change-payload-character".equals(mutation)) {
			int index = "BTAE1:".length() + 40;
			char changed = wire.charAt(index) == 'A' ? 'B' : 'A';
			return wire.substring(0, index) + changed + wire.substring(index + 1);
		}
		throw new AssertionError("unknown invitation mutation: " + mutation);
	}

}
