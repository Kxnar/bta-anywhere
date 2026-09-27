package io.github.kxnar.btaanywhere.cli;

import io.github.kxnar.btaanywhere.RelayDescriptor;
import io.github.kxnar.btaanywhere.CoordinatorRelayResolver;
import io.github.kxnar.btaanywhere.RelayAllowlist;
import io.github.kxnar.btaanywhere.RelayMode;
import io.github.kxnar.btaanywhere.RelayResolver;
import io.github.kxnar.btaanywhere.Secret;
import io.github.kxnar.btaanywhere.TunnelClient;
import io.github.kxnar.btaanywhere.TunnelConfig;
import io.github.kxnar.btaanywhere.TunnelEvent;
import io.github.kxnar.btaanywhere.TunnelSession;
import io.github.kxnar.btaanywhere.TunnelState;
import io.github.kxnar.btaanywhere.encrypted.BtaeInvitation;
import io.github.kxnar.btaanywhere.encrypted.EncryptedGuestCompanion;
import io.github.kxnar.btaanywhere.encrypted.EncryptedHostContext;
import java.net.InetSocketAddress;
import java.net.URI;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import java.io.StringReader;

public final class TunnelCli {
	private TunnelCli() {
	}

	public static void main(String[] arguments) throws Exception {
		if (arguments.length == 1 && "doctor".equals(arguments[0])) {
			TunnelClient.ensureQuicAvailable();
			System.out.println("Native QUIC transport is available.");
			return;
		}
		if (arguments.length == 1 && "join-encrypted".equals(arguments[0])) {
			joinEncrypted();
			return;
		}
		boolean encrypted = arguments.length > 0 && "expose-encrypted".equals(arguments[0]);
		if (arguments.length == 0 || (!"expose".equals(arguments[0]) && !encrypted)) {
			usage();
			System.exit(2);
			return;
		}
		Map<String, String> options = parseOptions(arguments);
		InetSocketAddress localAddress = parseAddress(required(options, "--local"));
		String clientId = options.getOrDefault("--client-id", UUID.randomUUID().toString());

		try (
			RelaySetup relaySetup = relaySetup(options, encrypted, clientId);
			TunnelClient client = new TunnelClient()
		) {
			TunnelConfig config = TunnelConfig.defaults(relaySetup.resolver(), clientId);
			TunnelSession session;
			EncryptedHostContext encryptedHost = encrypted ? EncryptedHostContext.create() : null;
			try {
				session = (encrypted ? client.openEncrypted(config, localAddress, encryptedHost)
							: client.open(config, localAddress, TunnelCli::reportStartupEvent))
					.toCompletableFuture().join();
				System.out.println("Relay selection: " + relaySetup.selectionDescription());
				if (encryptedHost != null) {
					BtaeInvitation invitation = encryptedHost.createInvitation();
					System.out.println("Encrypted invitation (share through a trusted channel): " + invitation.encode());
					System.out.println("Invitation ID: " + invitation.invitationId());
				}
			} catch (CompletionException failure) {
				System.err.println("Failed to open tunnel: " + failure.getCause().getMessage());
				System.exit(1);
				return;
			}

			System.out.println("Public endpoint: " + session.endpoint());
			System.out.println("Press Ctrl+C or type 'stop' to stop.");
			Runtime.getRuntime().addShutdownHook(new Thread(session::close, "bta-anywhere-cli-shutdown"));
			startConsoleControl(session, encrypted ? encryptedHost : null);
			try {
				session.closed().toCompletableFuture().join();
			} catch (CompletionException failure) {
				System.err.println("Tunnel stopped: " + failure.getCause().getMessage());
				System.exit(1);
			}
		}
	}

	private static void joinEncrypted() throws Exception {
		System.out.println("Paste the BTAE1 invitation, then press Enter:");
		BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
		BtaeInvitation invitation = BtaeInvitation.parse(reader.readLine());
		EncryptedGuestCompanion companion = new EncryptedGuestCompanion(invitation);
		try {
			System.out.println("Host session SPKI SHA-256: " + invitation.hostSpkiSha256());
			System.out.println("Relay endpoint: " + invitation.relayHost() + ":" + invitation.relayPort());
			System.out.println("Connect the BTA 8.0.1 client to " + companion.address().getHostString()
				+ ":" + companion.address().getPort());
			System.out.println("Invitation expires at " + java.time.Instant.ofEpochMilli(invitation.expiresAtEpochMillis()));
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				try { companion.close(); } catch (IOException ignored) { }
			}, "bta-encrypted-guest-shutdown"));
			Thread console = new Thread(() -> {
				try {
					String line;
					while ((line = reader.readLine()) != null) {
						if ("stop".equalsIgnoreCase(line.trim())) { companion.close(); return; }
					}
				} catch (IOException ignored) { }
			}, "bta-encrypted-guest-console");
			console.setDaemon(true);
			console.start();
			companion.serve();
		} finally {
			companion.close();
		}
	}

	private static void reportStartupEvent(TunnelEvent event) {
		if (event.cause() == null || event.state() != TunnelState.RECONNECTING) {
			return;
		}
		String detail = event.cause().getMessage();
		if (detail == null || detail.isBlank()) {
			detail = event.cause().getClass().getSimpleName();
		}
		System.err.println(event.message() + ": " + detail);
	}

	private static void startConsoleControl(TunnelSession session, EncryptedHostContext encrypted) {
		Thread console = new Thread(() -> {
			try {
				BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
				String line;
				while ((line = reader.readLine()) != null) {
					if (encrypted != null && "invite".equalsIgnoreCase(line.trim())) {
						BtaeInvitation invite = encrypted.createInvitation();
						System.out.println("Encrypted invitation: " + invite.encode());
						System.out.println("Invitation ID: " + invite.invitationId());
						continue;
					}
					if (encrypted != null && line.trim().startsWith("revoke ")) {
						System.out.println(encrypted.revoke(line.trim().substring(7)) ? "Invitation revoked" : "Invitation not found");
						continue;
					}
					if ("stop".equalsIgnoreCase(line.trim()) || "quit".equalsIgnoreCase(line.trim())) {
						session.close();
						return;
					}
				}
			} catch (IOException exception) {
				System.err.println("Console control stopped: " + exception.getMessage());
			}
		}, "bta-anywhere-cli-console");
		console.setDaemon(true);
		console.start();
	}

	private static Map<String, String> parseOptions(String[] arguments) {
		Map<String, String> options = new HashMap<>();
		for (int index = 1; index < arguments.length; index += 2) {
			if (index + 1 >= arguments.length || !arguments[index].startsWith("--")) {
				throw new IllegalArgumentException("options must be supplied as --name value pairs");
			}
			if (options.putIfAbsent(arguments[index], arguments[index + 1]) != null) {
				throw new IllegalArgumentException("option was supplied more than once: " + arguments[index]);
			}
		}
		return options;
	}

	private static RelaySetup relaySetup(Map<String, String> options, boolean encrypted, String clientId)
		throws IOException {
		List<Secret> secrets = new ArrayList<>();
		try {
			if (options.containsKey("--coordinator")) {
				rejectUnknownOptions(options, Set.of(
					"--local", "--client-id", "--coordinator", "--coordinator-ca", "--coordinator-token-file",
					"--relay-allowlist", "--fallback-relay", "--fallback-ca", "--fallback-token-file"
				));
				if (options.containsKey("--relay") || options.containsKey("--ca")
					|| options.containsKey("--token-file")) {
					throw new IllegalArgumentException(
						"coordinated mode uses --relay-allowlist; static fallback must use --fallback-* options");
				}
				Secret coordinatorCredential = readSecret(Path.of(required(options, "--coordinator-token-file")));
				secrets.add(coordinatorCredential);
				RelayAllowlist allowlist = readRelayAllowlist(Path.of(required(options, "--relay-allowlist")), secrets);
				RelayDescriptor fallback = readFallback(options, secrets);
				CoordinatorRelayResolver resolver = new CoordinatorRelayResolver(
					URI.create(required(options, "--coordinator")),
					Path.of(required(options, "--coordinator-ca")),
					coordinatorCredential,
					allowlist,
					clientId,
					encrypted ? RelayMode.ENCRYPTED : RelayMode.LEGACY,
					fallback
				);
				return new RelaySetup(resolver, secrets, resolver);
			}

			rejectUnknownOptions(options, Set.of("--local", "--client-id", "--relay", "--ca", "--token-file"));
			RelayDescriptor relay = readDescriptor(
				required(options, "--relay"),
				Path.of(required(options, "--ca")),
				Path.of(required(options, "--token-file")),
				secrets
			);
			return new RelaySetup(new io.github.kxnar.btaanywhere.StaticRelayResolver(relay), secrets, null);
		} catch (RuntimeException | IOException failure) {
			secrets.forEach(Secret::close);
			throw failure;
		}
	}

	private static RelayAllowlist readRelayAllowlist(Path file, List<Secret> secrets) throws IOException {
		Path absolute = file.toAbsolutePath().normalize();
		long size = Files.size(absolute);
		if (size < 1 || size > 64 * 1024) {
			throw new IllegalArgumentException("relay allowlist must be between 1 byte and 64 KiB");
		}
		String content = Files.readString(absolute, StandardCharsets.UTF_8);
		JsonElement rootElement;
		try (JsonReader reader = new JsonReader(new StringReader(content))) {
			reader.setStrictness(Strictness.STRICT);
			rootElement = JsonParser.parseReader(reader);
			if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) {
				throw new IllegalArgumentException("relay allowlist contains trailing JSON");
			}
		}
		if (rootElement == null || !rootElement.isJsonObject()) {
			throw new IllegalArgumentException("relay allowlist must be a JSON object");
		}
		JsonObject root = rootElement.getAsJsonObject();
		if (!root.keySet().equals(Set.of("relays")) || !root.get("relays").isJsonArray()) {
			throw new IllegalArgumentException("relay allowlist fields are invalid");
		}
		JsonArray rows = root.getAsJsonArray("relays");
		if (rows.isEmpty() || rows.size() > 64) {
			throw new IllegalArgumentException("relay allowlist must contain 1-64 relays");
		}
		Map<String, RelayDescriptor> relays = new HashMap<>();
		Path base = absolute.getParent();
		for (JsonElement row : rows) {
			if (!row.isJsonObject()) {
				throw new IllegalArgumentException("relay allowlist entry must be an object");
			}
			JsonObject item = row.getAsJsonObject();
			if (!item.keySet().equals(Set.of("relayId", "endpoint", "trustedCertificate", "accessTokenFile"))) {
				throw new IllegalArgumentException("relay allowlist entry fields are invalid");
			}
			String relayId = jsonString(item, "relayId");
			String endpoint = jsonString(item, "endpoint");
			Path certificate = resolvePath(base, jsonString(item, "trustedCertificate"));
			Path tokenFile = resolvePath(base, jsonString(item, "accessTokenFile"));
			RelayDescriptor descriptor = readDescriptor(endpoint, certificate, tokenFile, secrets);
			if (relays.putIfAbsent(relayId, descriptor) != null) {
				throw new IllegalArgumentException("relay allowlist contains a duplicate relay ID");
			}
		}
		return new RelayAllowlist(relays);
	}

	private static RelayDescriptor readFallback(Map<String, String> options, List<Secret> secrets)
		throws IOException {
		int present = (options.containsKey("--fallback-relay") ? 1 : 0)
			+ (options.containsKey("--fallback-ca") ? 1 : 0)
			+ (options.containsKey("--fallback-token-file") ? 1 : 0);
		if (present == 0) {
			return null;
		}
		if (present != 3) {
			throw new IllegalArgumentException("static fallback requires all three --fallback-* options");
		}
		return readDescriptor(
			required(options, "--fallback-relay"),
			Path.of(required(options, "--fallback-ca")),
			Path.of(required(options, "--fallback-token-file")),
			secrets
		);
	}

	private static RelayDescriptor readDescriptor(
		String endpoint,
		Path certificate,
		Path tokenFile,
		List<Secret> secrets
	) throws IOException {
		InetSocketAddress address = parseAddress(endpoint);
		Secret token = readSecret(tokenFile);
		secrets.add(token);
		return new RelayDescriptor(address.getHostString(), address.getPort(), certificate, token);
	}

	private static Secret readSecret(Path tokenFile) throws IOException {
		return Secret.of(Files.readString(tokenFile, StandardCharsets.UTF_8).trim());
	}

	private static Path resolvePath(Path base, String value) {
		Path path = Path.of(value);
		return (path.isAbsolute() ? path : base.resolve(path)).normalize();
	}

	private static String jsonString(JsonObject object, String key) {
		JsonElement value = object.get(key);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
			|| value.getAsString().isBlank()) {
			throw new IllegalArgumentException("relay allowlist string field is invalid");
		}
		return value.getAsString();
	}

	private static void rejectUnknownOptions(Map<String, String> options, Set<String> allowed) {
		for (String key : options.keySet()) {
			if (!allowed.contains(key)) {
				throw new IllegalArgumentException("option is not valid for this tunnel configuration: " + key);
			}
		}
	}

	private record RelaySetup(
		RelayResolver resolver,
		List<Secret> secrets,
		CoordinatorRelayResolver coordinatorResolver
	) implements AutoCloseable {
		private String selectionDescription() {
			if (coordinatorResolver == null) {
				return "static";
			}
			return coordinatorResolver.selectionSource();
		}

		@Override
		public void close() {
			secrets.forEach(Secret::close);
		}
	}

	private static String required(Map<String, String> options, String key) {
		String value = options.get(key);
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("missing required option " + key);
		}
		return value;
	}

	private static InetSocketAddress parseAddress(String value) {
		int separator = value.lastIndexOf(':');
		if (separator <= 0 || separator == value.length() - 1) {
			throw new IllegalArgumentException("address must use host:port syntax: " + value);
		}
		String host = value.substring(0, separator);
		if (host.startsWith("[") && host.endsWith("]")) {
			host = host.substring(1, host.length() - 1);
		}
		int port = Integer.parseInt(value.substring(separator + 1));
		if (port < 1 || port > 65_535) {
			throw new IllegalArgumentException("port must be between 1 and 65535");
		}
		return InetSocketAddress.createUnresolved(host, port);
	}

	private static void usage() {
		System.err.println("Usage:");
		System.err.println("  java -jar bta-anywhere-tunnel-all.jar doctor");
		System.err.println("  java -jar bta-anywhere-tunnel-all.jar join-encrypted  (paste invitation on stdin)");
		System.err.println("  java -jar bta-anywhere-tunnel-all.jar expose-encrypted --relay localhost:25575 --ca .dev/relay/trust.pem --token-file .dev/relay/access.token --local 127.0.0.1:8000");
		System.err.println("  java -jar bta-anywhere-tunnel-all.jar expose \\");
		System.err.println("    --relay localhost:25575 --ca .dev/relay/trust.pem \\");
		System.err.println("    --token-file .dev/relay/access.token --local 127.0.0.1:8000");
		System.err.println("  java -jar bta-anywhere-tunnel-all.jar expose-encrypted --coordinator https://coordinator.example \\");
		System.err.println("    --coordinator-ca coordinator-ca.pem --coordinator-token-file coordinator.token --relay-allowlist relays.json --local 127.0.0.1:8000");
		System.err.println("    Optional static fallback: --fallback-relay host:port --fallback-ca trust.pem --fallback-token-file token.txt");
	}
}
