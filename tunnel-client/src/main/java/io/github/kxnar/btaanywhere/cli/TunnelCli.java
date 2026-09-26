package io.github.kxnar.btaanywhere.cli;

import io.github.kxnar.btaanywhere.RelayDescriptor;
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
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;

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
		InetSocketAddress relayAddress = parseAddress(required(options, "--relay"));
		InetSocketAddress localAddress = parseAddress(required(options, "--local"));
		Path certificate = Path.of(required(options, "--ca"));
		Path tokenFile = Path.of(required(options, "--token-file"));
		String tokenValue = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
		String clientId = options.getOrDefault("--client-id", UUID.randomUUID().toString());

		try (
			Secret token = Secret.of(tokenValue);
			TunnelClient client = new TunnelClient()
		) {
			RelayDescriptor relay = new RelayDescriptor(
				relayAddress.getHostString(),
				relayAddress.getPort(),
				certificate,
				token
			);
			TunnelConfig config = TunnelConfig.defaults(relay, clientId);
			TunnelSession session;
			EncryptedHostContext encryptedHost = encrypted ? EncryptedHostContext.create() : null;
			try {
				session = (encrypted ? client.openEncrypted(config, localAddress, encryptedHost)
					: client.open(config, localAddress, TunnelCli::reportStartupEvent))
					.toCompletableFuture().join();
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
			options.put(arguments[index], arguments[index + 1]);
		}
		return options;
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
	}
}
