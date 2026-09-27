package io.github.kxnar.btaanywhere.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.kxnar.btaanywhere.RelayDescriptor;
import io.github.kxnar.btaanywhere.RelayMode;
import io.github.kxnar.btaanywhere.RelaySelection;
import io.github.kxnar.btaanywhere.Secret;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CoordinatorRegistrationTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void staticV1RegistrationRetainsExistingFieldsWithoutTicket() throws Exception {
		try (Secret access = Secret.of("local-relay-token")) {
			RelayDescriptor descriptor = descriptor(access);
			JsonObject request = NettyTunnelSession.registrationMessage(
				false, "client-1", descriptor, RelaySelection.staticRelay(descriptor), null, 10_000);
			assertEquals(1, request.get("version").getAsInt());
			assertEquals("client-1", request.get("clientInstanceId").getAsString());
			assertEquals("local-relay-token", request.get("accessToken").getAsString());
			assertEquals("streamEofBytes", request.getAsJsonArray("features").get(0).getAsString());
			assertFalse(request.has("allocationTicket"));
		}
	}

	@Test
	void coordinatedLegacyRegistrationCarriesTicketAndPreservesEofFeature() throws Exception {
		try (Secret access = Secret.of("local-relay-token");
			RelaySelection selection = RelaySelection.coordinated(
				descriptor(access), RelayMode.LEGACY, "relay-a", "BTACT1:opaque-ticket", 30001, 20_000)) {
			JsonObject request = NettyTunnelSession.registrationMessage(
				false, "client-1", descriptor(access), selection, null, 10_000);
			assertEquals(1, request.get("version").getAsInt());
			assertEquals(2, request.getAsJsonArray("features").size());
			assertEquals("streamEofBytes", request.getAsJsonArray("features").get(0).getAsString());
			assertEquals("allocationTicket", request.getAsJsonArray("features").get(1).getAsString());
			assertEquals("BTACT1:opaque-ticket", request.get("allocationTicket").getAsString());
		}
	}

	@Test
	void encryptedRegistrationIsV2AndNeverAddsLegacyFeature() throws Exception {
		try (Secret access = Secret.of("local-relay-token");
			RelaySelection selection = RelaySelection.coordinated(
				descriptor(access), RelayMode.ENCRYPTED, "relay-a", "BTACT1:opaque-ticket", 30001, 20_000)) {
			JsonObject request = NettyTunnelSession.registrationMessage(
				true, "client-1", descriptor(access), selection, null, 10_000);
			assertEquals(2, request.get("version").getAsInt());
			assertEquals(1, request.getAsJsonArray("features").size());
			assertEquals("allocationTicket", request.getAsJsonArray("features").get(0).getAsString());
			assertFalse(request.has("streamEof"));
		}
	}

	@Test
	void sameSessionResumeUsesPinnedRelayAndOmitsOneUseTicket() throws Exception {
		try (Secret access = Secret.of("local-relay-token");
			RelaySelection selection = RelaySelection.coordinated(
				descriptor(access), RelayMode.ENCRYPTED, "relay-a", "BTACT1:opaque-ticket", 30001, 20_000)) {
			JsonObject request = NettyTunnelSession.registrationMessage(
				true, "client-1", descriptor(access), selection, "resume-secret", 10_000);
			assertEquals(2, request.get("version").getAsInt());
			assertEquals("resume-secret", request.get("resumeToken").getAsString());
			assertTrue(request.getAsJsonArray("features").isEmpty());
			assertFalse(request.has("allocationTicket"));
		}
	}

	@Test
	void expiredFirstRegistrationTicketFailsBeforeItCanBeSent() throws Exception {
		try (Secret access = Secret.of("local-relay-token");
			RelaySelection selection = RelaySelection.coordinated(
				descriptor(access), RelayMode.LEGACY, "relay-a", "BTACT1:opaque-ticket", 30001, 9_999)) {
			assertThrows(IllegalStateException.class, () -> NettyTunnelSession.registrationMessage(
				false, "client-1", descriptor(access), selection, null, 10_000));
		}
	}

	@Test
	void registeredFeatureEchoMustMatchCoordinatedFreshVersusStaticMode() {
		JsonObject encryptedManaged = new JsonObject();
		encryptedManaged.add("features", JsonParser.parseString("[\"allocationTicket\"]"));
		ProtocolFrames.requireRegisteredFeatures(encryptedManaged, Set.of("allocationTicket"));
		assertThrows(IllegalArgumentException.class,
			() -> ProtocolFrames.requireRegisteredFeatures(encryptedManaged, Set.of()));

		JsonObject staticEncrypted = new JsonObject();
		staticEncrypted.add("features", JsonParser.parseString("[]"));
		ProtocolFrames.requireRegisteredFeatures(staticEncrypted, Set.of());

		JsonObject managedLegacy = new JsonObject();
		managedLegacy.add("features", JsonParser.parseString(
			"[\"streamEofBytes\",\"allocationTicket\"]"));
		ProtocolFrames.requireRegisteredFeatures(
			managedLegacy, Set.of("streamEofBytes", "allocationTicket"));
	}

	private RelayDescriptor descriptor(Secret access) throws Exception {
		Path certificate = temporaryDirectory.resolve("relay-ca.pem");
		if (!Files.exists(certificate)) {
			Files.writeString(certificate, "test-only placeholder");
		}
		return new RelayDescriptor("relay-a.example", 25575, certificate, access);
	}
}
