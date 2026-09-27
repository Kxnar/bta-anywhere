package io.github.kxnar.btaanywhere;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.Certificate;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/** HTTPS-only host allocation client. All trust roots and credentials are local. */
public final class HttpsCoordinatorAllocationClient implements CoordinatorAllocationClient {
	private static final int MAX_RESPONSE_BYTES = 16 * 1024;
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
	private static final Gson GSON = new Gson();
	private final URI allocationUri;
	private final Secret credential;
	private final String clientInstanceId;
	private final HttpClient httpClient;

	public HttpsCoordinatorAllocationClient(URI baseUri, Secret credential, String clientInstanceId) {
		this(baseUri, credential, clientInstanceId, defaultHttpClient());
	}

	public HttpsCoordinatorAllocationClient(
		URI baseUri,
		Path trustedCertificate,
		Secret credential,
		String clientInstanceId
	) {
		this(baseUri, credential, clientInstanceId, trustedHttpClient(trustedCertificate));
	}

	public HttpsCoordinatorAllocationClient(
		URI baseUri,
		Secret credential,
		String clientInstanceId,
		HttpClient httpClient
	) {
		Objects.requireNonNull(baseUri, "baseUri");
		if (!"https".equalsIgnoreCase(baseUri.getScheme()) || baseUri.getHost() == null
			|| baseUri.getUserInfo() != null || baseUri.getQuery() != null || baseUri.getFragment() != null
			|| (baseUri.getPath() != null && !baseUri.getPath().isEmpty() && !"/".equals(baseUri.getPath()))) {
			throw new IllegalArgumentException("coordinator endpoint must be an HTTPS origin");
		}
		this.allocationUri = baseUri.resolve("/v1/allocate");
		this.credential = Objects.requireNonNull(credential, "credential");
		this.clientInstanceId = requireClientId(clientInstanceId);
		this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
	}

	private static HttpClient defaultHttpClient() {
		SSLParameters tls = new SSLParameters();
		tls.setEndpointIdentificationAlgorithm("HTTPS");
		return HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(4))
			.followRedirects(HttpClient.Redirect.NEVER)
			.sslParameters(tls)
			.build();
	}

	private static HttpClient trustedHttpClient(Path trustedCertificate) {
		Objects.requireNonNull(trustedCertificate, "trustedCertificate");
		try (InputStream input = Files.newInputStream(trustedCertificate)) {
			CertificateFactory factory = CertificateFactory.getInstance("X.509");
			var certificates = factory.generateCertificates(input);
			if (certificates.isEmpty()) {
				throw new IllegalArgumentException("coordinator trust file contains no certificates");
			}
			KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
			trustStore.load(null, null);
			int index = 0;
			for (Certificate certificate : certificates) {
				trustStore.setCertificateEntry("coordinator-ca-" + index++, certificate);
			}
			TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
				TrustManagerFactory.getDefaultAlgorithm());
			trustManagers.init(trustStore);
			SSLContext context = SSLContext.getInstance("TLS");
			context.init(null, trustManagers.getTrustManagers(), new SecureRandom());
			SSLParameters tls = new SSLParameters();
			tls.setEndpointIdentificationAlgorithm("HTTPS");
			return HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(4))
				.followRedirects(HttpClient.Redirect.NEVER)
				.sslContext(context)
				.sslParameters(tls)
				.build();
		} catch (IOException | java.security.GeneralSecurityException exception) {
			throw new IllegalArgumentException("coordinator trust file could not be loaded", exception);
		}
	}

	@Override
	public CompletionStage<CoordinatorAllocation> allocate(
		String requestedClientInstanceId,
		RelayMode mode,
		List<RelayProbeSample> probes
	) {
		if (!clientInstanceId.equals(requestedClientInstanceId)) {
			return java.util.concurrent.CompletableFuture.failedFuture(
				new IllegalArgumentException("coordinator client ID does not match local configuration"));
		}
		Objects.requireNonNull(mode, "mode");
		List<RelayProbeSample> boundedProbes = List.copyOf(Objects.requireNonNull(probes, "probes"));
		if (boundedProbes.isEmpty() || boundedProbes.size() > 64) {
			return java.util.concurrent.CompletableFuture.failedFuture(
				new IllegalArgumentException("coordinator requires 1-64 relay probes"));
		}
		String json = requestJson(clientInstanceId, mode, boundedProbes);
		HttpRequest request = credential.use(chars -> HttpRequest.newBuilder(allocationUri)
			.timeout(REQUEST_TIMEOUT)
			.header("Authorization", "Bearer " + new String(chars))
			.header("Content-Type", "application/json")
			.header("Accept", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(json))
			.build());
		return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
			.thenApply(response -> decodeResponse(response));
	}

	private static CoordinatorAllocation decodeResponse(HttpResponse<InputStream> response) {
		byte[] bytes;
		try (InputStream body = response.body()) {
			bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
		} catch (IOException exception) {
			throw new CompletionException(new CoordinatorHttpException(response.statusCode(), exception));
		}
		if (response.statusCode() != 200) {
			throw new CompletionException(new CoordinatorHttpException(response.statusCode()));
		}
		if (bytes.length > MAX_RESPONSE_BYTES) {
			throw new CompletionException(new IllegalArgumentException("coordinator response exceeds 16 KiB"));
		}
		try {
			JsonObject object = strictObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
			if (!object.keySet().equals(Set.of("ticket", "relayId", "publicPort", "expiresAtEpochMillis"))) {
				throw new IllegalArgumentException("coordinator response fields are invalid");
			}
			String ticket = requiredString(object, "ticket");
			String relayId = requiredString(object, "relayId");
			int publicPort = requiredInteger(object, "publicPort");
			long expiresAtEpochMillis = requiredLong(object, "expiresAtEpochMillis");
			return new CoordinatorAllocation(ticket, relayId, publicPort, expiresAtEpochMillis);
		} catch (RuntimeException failure) {
			throw new CompletionException(new IllegalArgumentException(
				"coordinator response is invalid", failure));
		}
	}

	private static JsonObject strictObject(String source) {
		try (JsonReader reader = new JsonReader(new StringReader(source))) {
			reader.setStrictness(Strictness.STRICT);
			if (reader.peek() != JsonToken.BEGIN_OBJECT) {
				throw new IllegalArgumentException("coordinator response must be an object");
			}
			JsonObject result = new JsonObject();
			Set<String> names = new HashSet<>();
			reader.beginObject();
			while (reader.hasNext()) {
				String name = reader.nextName();
				if (!names.add(name)) {
					throw new IllegalArgumentException("coordinator response repeats a field");
				}
				JsonElement value = GSON.fromJson(reader, JsonElement.class);
				result.add(name, value);
			}
			reader.endObject();
			if (reader.peek() != JsonToken.END_DOCUMENT) {
				throw new IllegalArgumentException("coordinator response has trailing data");
			}
			return result;
		} catch (IOException exception) {
			throw new IllegalArgumentException("coordinator response JSON is invalid", exception);
		}
	}

	private static String requiredString(JsonObject object, String name) {
		JsonElement value = object.get(name);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
			throw new IllegalArgumentException("coordinator response field is invalid");
		}
		return value.getAsString();
	}

	private static int requiredInteger(JsonObject object, String name) {
		long value = requiredLong(object, name);
		if (value < 1 || value > 65_535) {
			throw new IllegalArgumentException("coordinator response port is invalid");
		}
		return (int) value;
	}

	private static long requiredLong(JsonObject object, String name) {
		JsonElement value = object.get(name);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
			throw new IllegalArgumentException("coordinator response numeric field is invalid");
		}
		try {
			return value.getAsBigDecimal().longValueExact();
		} catch (ArithmeticException | NumberFormatException exception) {
			throw new IllegalArgumentException("coordinator response numeric field is invalid", exception);
		}
	}

	private static String requestJson(String clientInstanceId, RelayMode mode, List<RelayProbeSample> probes) {
		StringBuilder json = new StringBuilder(128 + probes.size() * 96);
		json.append("{\"clientInstanceId\":\"").append(escapeJson(clientInstanceId))
			.append("\",\"mode\":\"").append(mode.wireName()).append("\",\"probes\":[");
		for (int index = 0; index < probes.size(); index++) {
			if (index != 0) {
				json.append(',');
			}
			RelayProbeSample sample = probes.get(index);
			json.append("{\"relayId\":\"").append(escapeJson(sample.relayId()))
				.append("\",\"rttMillis\":").append(sample.rttMillis())
				.append(",\"measuredAtEpochMillis\":").append(sample.measuredAtEpochMillis()).append('}');
		}
		return json.append("]}").toString();
	}

	private static String escapeJson(String value) {
		StringBuilder escaped = new StringBuilder(value.length() + 8);
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			switch (character) {
				case '"' -> escaped.append("\\\"");
				case '\\' -> escaped.append("\\\\");
				default -> {
					if (character < 0x20) {
						escaped.append(String.format("\\u%04x", (int) character));
					} else {
						escaped.append(character);
					}
				}
			}
		}
		return escaped.toString();
	}

	private static String requireClientId(String value) {
		if (value == null || value.isEmpty() || value.length() > 128
			|| !value.chars().allMatch(character -> (character >= 'a' && character <= 'z')
				|| (character >= 'A' && character <= 'Z')
				|| (character >= '0' && character <= '9')
				|| character == '-' || character == '_')) {
			throw new IllegalArgumentException("coordinator client ID must be 1-128 ASCII identifier characters");
		}
		return value;
	}

	public static final class CoordinatorHttpException extends RuntimeException {
		private static final long serialVersionUID = 1L;
		private final int statusCode;

		public CoordinatorHttpException(int statusCode) {
			super("coordinator returned HTTP " + statusCode);
			this.statusCode = statusCode;
		}

		CoordinatorHttpException(int statusCode, Throwable cause) {
			super("coordinator returned HTTP " + statusCode, cause);
			this.statusCode = statusCode;
		}

		public int statusCode() {
			return statusCode;
		}
	}
}
