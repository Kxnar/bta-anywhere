package io.github.kxnar.btaanywhere.internal;

import io.netty.util.NetUtil;
import java.net.IDN;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.cert.Certificate;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLPeerUnverifiedException;

final class TlsHostnameVerifier {
	private static final int DNS_NAME = 2;
	private static final int IP_ADDRESS = 7;

	private TlsHostnameVerifier() {
	}

	static void verify(String host, SSLEngine engine) throws SSLPeerUnverifiedException {
		if (engine == null) {
			throw new SSLPeerUnverifiedException("relay TLS session is unavailable");
		}
		Certificate[] certificates = engine.getSession().getPeerCertificates();
		if (certificates.length == 0 || !(certificates[0] instanceof X509Certificate leaf)) {
			throw new SSLPeerUnverifiedException("relay did not present an X.509 certificate");
		}
		Collection<List<?>> names;
		try {
			names = leaf.getSubjectAlternativeNames();
		} catch (CertificateParsingException exception) {
			SSLPeerUnverifiedException failure = new SSLPeerUnverifiedException(
				"relay certificate has malformed subject alternative names"
			);
			failure.initCause(exception);
			throw failure;
		}
		if (!matches(host, names)) {
			throw new SSLPeerUnverifiedException("relay certificate is not valid for the configured hostname");
		}
	}

	static boolean matches(String host, Collection<List<?>> subjectAlternativeNames) {
		if (host == null || host.isBlank() || subjectAlternativeNames == null) {
			return false;
		}
		String candidate = stripIpv6Brackets(host.trim());
		boolean ipLiteral = NetUtil.isValidIpV4Address(candidate) || NetUtil.isValidIpV6Address(candidate);
		for (List<?> name : subjectAlternativeNames) {
			if (name == null || name.size() < 2 || !(name.get(0) instanceof Integer type)
				|| !(name.get(1) instanceof String value)) {
				continue;
			}
			if (ipLiteral && type == IP_ADDRESS && sameIpLiteral(candidate, value)) {
				return true;
			}
			if (!ipLiteral && type == DNS_NAME && sameDnsName(candidate, value)) {
				return true;
			}
		}
		return false;
	}

	private static boolean sameIpLiteral(String host, String certificateName) {
		if (!(NetUtil.isValidIpV4Address(certificateName) || NetUtil.isValidIpV6Address(certificateName))) {
			return false;
		}
		try {
			return Arrays.equals(InetAddress.getByName(host).getAddress(),
				InetAddress.getByName(certificateName).getAddress());
		} catch (UnknownHostException exception) {
			return false;
		}
	}

	private static boolean sameDnsName(String host, String certificateName) {
		String normalizedHost = normalizeDnsName(host);
		String normalizedCertificate = certificateName.startsWith("*.")
			? wildcardName(certificateName.substring(2)) : normalizeDnsName(certificateName);
		if (normalizedHost == null || normalizedCertificate == null) {
			return false;
		}
		if (!normalizedCertificate.startsWith("*.")) {
			return normalizedHost.equals(normalizedCertificate);
		}
		String suffix = normalizedCertificate.substring(1);
		if (suffix.indexOf('*') >= 0 || suffix.substring(1).indexOf('.') < 0
			|| !normalizedHost.endsWith(suffix)) {
			return false;
		}
		String firstLabel = normalizedHost.substring(0, normalizedHost.length() - suffix.length());
		return !firstLabel.isEmpty() && firstLabel.indexOf('.') < 0;
	}

	private static String wildcardName(String suffix) {
		String normalized = normalizeDnsName(suffix);
		return normalized == null ? null : "*." + normalized;
	}

	private static String normalizeDnsName(String value) {
		String candidate = value.endsWith(".") ? value.substring(0, value.length() - 1) : value;
		try {
			return IDN.toASCII(candidate, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}

	private static String stripIpv6Brackets(String value) {
		return value.startsWith("[") && value.endsWith("]") ? value.substring(1, value.length() - 1) : value;
	}
}
