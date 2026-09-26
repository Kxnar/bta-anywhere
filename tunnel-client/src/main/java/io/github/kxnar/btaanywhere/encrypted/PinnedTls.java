package io.github.kxnar.btaanywhere.encrypted;

import io.github.kxnar.btaanywhere.internal.TlsHostnameVerifier;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.HexFormat;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Validates the invitation's exact SPKI pin and a presented certificate chain. */
public final class PinnedTls {
	private PinnedTls() { }

	public static SSLContext context(String pin, String dnsName) throws Exception {
		byte[] expectedPin = HexFormat.of().parseHex(pin);
		if (expectedPin.length != 32) {
			throw new IllegalArgumentException("TLS pin is invalid");
		}
		SSLContext context = SSLContext.getInstance("TLSv1.3");
		context.init(null, new TrustManager[] { new PinnedTrustManager(expectedPin, dnsName) }, null);
		return context;
	}

	private static final class PinnedTrustManager implements X509TrustManager {
		private final byte[] expectedPin;
		private final String dnsName;

		PinnedTrustManager(byte[] expectedPin, String dnsName) {
			this.expectedPin = expectedPin;
			this.dnsName = dnsName;
		}

		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
			throw new CertificateException("client certificates are not accepted");
		}

		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
			if (chain == null || chain.length == 0 || chain.length > 8) {
				throw new CertificateException("TLS certificate chain is invalid");
			}
			try {
				for (int index = 0; index < chain.length; index++) {
					chain[index].checkValidity();
					if (index == 0) {
						boolean[] keyUsage = chain[index].getKeyUsage();
						if (keyUsage != null && !keyUsage[0]) {
							throw new CertificateException("TLS certificate key usage is invalid");
						}
						List<String> extendedUsage = chain[index].getExtendedKeyUsage();
						if (extendedUsage != null && !extendedUsage.contains("1.3.6.1.5.5.7.3.1")) {
							throw new CertificateException("TLS certificate server usage is invalid");
						}
					}
					if (index + 1 < chain.length) {
						if (chain[index + 1].getBasicConstraints() < 0) {
							throw new CertificateException("TLS certificate issuer is invalid");
						}
						chain[index].verify(chain[index + 1].getPublicKey());
					} else if (chain[index].getSubjectX500Principal().equals(chain[index].getIssuerX500Principal())) {
						chain[index].verify(chain[index].getPublicKey());
					}
				}
				byte[] pin = MessageDigest.getInstance("SHA-256").digest(chain[0].getPublicKey().getEncoded());
				if (!MessageDigest.isEqual(expectedPin, pin)) {
					throw new CertificateException("TLS certificate pin mismatch");
				}
				if (dnsName != null && !TlsHostnameVerifier.matches(dnsName, chain[0].getSubjectAlternativeNames())) {
					throw new CertificateException("TLS certificate hostname mismatch");
				}
			} catch (CertificateException failure) {
				throw failure;
			} catch (Exception failure) {
				throw new CertificateException("TLS certificate validation failed", failure);
			}
		}

		@Override
		public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
	}
}
