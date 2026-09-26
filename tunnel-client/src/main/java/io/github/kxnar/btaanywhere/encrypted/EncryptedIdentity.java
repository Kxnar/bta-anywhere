package io.github.kxnar.btaanywhere.encrypted;

import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/** Ephemeral in-memory TLS identity for one encrypted host process. */
public final class EncryptedIdentity {
	public static final String INNER_ALPN = "bta-anywhere-guest/1";
	private final X509Certificate certificate;
	private final SslContext serverContext;
	private final String spkiSha256;

	private EncryptedIdentity(X509Certificate certificate, SslContext serverContext, String spkiSha256) {
		this.certificate = certificate;
		this.serverContext = serverContext;
		this.spkiSha256 = spkiSha256;
	}

	public static EncryptedIdentity create() throws Exception {
		SecureRandom random = new SecureRandom();
		KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
		generator.initialize(new ECGenParameterSpec("secp256r1"), random);
		KeyPair pair = generator.generateKeyPair();
		Instant now = Instant.now();
		X500Name subject = new X500Name("CN=BTA Anywhere encrypted session");
		byte[] serialBytes = new byte[16];
		random.nextBytes(serialBytes);
		JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
			subject,
			new BigInteger(1, serialBytes),
			Date.from(now.minusSeconds(300)),
			Date.from(now.plusSeconds(86_400)),
			subject,
			pair.getPublic()
		);
		builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
		builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
		builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
		X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(
			builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(pair.getPrivate()))
		);
		certificate.verify(pair.getPublic());
		SslContext context = SslContextBuilder.forServer(pair.getPrivate(), certificate)
			.sslProvider(SslProvider.JDK)
			.protocols("TLSv1.3")
			.applicationProtocolConfig(new ApplicationProtocolConfig(
				ApplicationProtocolConfig.Protocol.ALPN,
				ApplicationProtocolConfig.SelectorFailureBehavior.FATAL_ALERT,
				ApplicationProtocolConfig.SelectedListenerFailureBehavior.FATAL_ALERT,
				INNER_ALPN
			))
			.build();
		String pin = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
			.digest(certificate.getPublicKey().getEncoded()));
		return new EncryptedIdentity(certificate, context, pin);
	}

	public X509Certificate certificate() { return certificate; }
	public SslContext serverContext() { return serverContext; }
	public String spkiSha256() { return spkiSha256; }

	@Override
	public String toString() {
		return "EncryptedIdentity[spkiSha256=" + spkiSha256 + "]";
	}
}
