package io.github.kxnar.btaanywhere.encrypted;

import io.github.kxnar.btaanywhere.PublicEndpoint;
import io.github.kxnar.btaanywhere.RelayDescriptor;
import io.netty.incubator.codec.quic.QuicChannel;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** Host process state for one encrypted relay session and its short-lived invitations. */
public final class EncryptedHostContext {
	private final EncryptedIdentity identity;
	private volatile InvitationRegistry invitations;
	private volatile String registrationKey;
	private final AtomicInteger activeStreams = new AtomicInteger();

	private EncryptedHostContext(EncryptedIdentity identity) {
		this.identity = identity;
	}

	public static EncryptedHostContext create() throws Exception {
		return new EncryptedHostContext(EncryptedIdentity.create());
	}

	public EncryptedIdentity identity() { return identity; }
	public boolean acquireStream() {
		while (true) {
			int current = activeStreams.get();
			if (current >= 8) {
				return false;
			}
			if (activeStreams.compareAndSet(current, current + 1)) {
				return true;
			}
		}
	}

	public void releaseStream() { activeStreams.decrementAndGet(); }

	public synchronized void onRegistered(String sessionId, PublicEndpoint endpoint,
		RelayDescriptor relay, QuicChannel channel) {
		Objects.requireNonNull(sessionId, "sessionId");
		Objects.requireNonNull(endpoint, "endpoint");
		try {
			X509Certificate certificate = (X509Certificate) channel.sslEngine()
				.getSession().getPeerCertificates()[0];
			String relayPin = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(certificate.getPublicKey().getEncoded()));
			String key = sessionId + ":" + relay.host() + ":" + endpoint.port() + ":" + relayPin;
			if (!key.equals(registrationKey)) {
				revokeAll();
				invitations = new InvitationRegistry(sessionId, identity.spkiSha256(),
					relay.host(), endpoint.port(), relayPin);
				registrationKey = key;
			}
		} catch (Exception exception) {
			revokeAll();
			throw new IllegalStateException("encrypted relay identity could not be read", exception);
		}
	}

	public BtaeInvitation createInvitation() {
		InvitationRegistry active = invitations;
		if (active == null) {
			throw new IllegalStateException("encrypted relay session is not registered");
		}
		if (identity.certificate().getNotAfter().getTime() <= System.currentTimeMillis() + 600_000) {
			throw new IllegalStateException("encrypted host certificate is near expiry; restart the session");
		}
		return active.issue(Duration.ofMinutes(10));
	}

	public boolean redeemJoin(String invitationId, String sessionId, String capability) {
		InvitationRegistry active = invitations;
		return active != null && active.redeemJoin(invitationId, sessionId, capability);
	}

	public boolean authorizeStatus(String invitationId, String sessionId, String capability) {
		InvitationRegistry active = invitations;
		return active != null && active.authorizeStatus(invitationId, sessionId, capability);
	}

	public boolean revoke(String invitationId) {
		InvitationRegistry active = invitations;
		return active != null && active.revoke(invitationId);
	}

	public synchronized void revokeAll() {
		InvitationRegistry active = invitations;
		if (active != null) {
			active.revokeAll();
		}
		invitations = null;
		registrationKey = null;
	}
}
