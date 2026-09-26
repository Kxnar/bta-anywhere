package io.github.kxnar.btaanywhere.encrypted;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

/** Loopback-only adapter for an ordinary BTA 8.0.1 guest client. */
public final class EncryptedGuestCompanion implements AutoCloseable {
	private static final int MAX_ACTIVE = 8;
	private final BtaeInvitation invitation;
	private final ServerSocket listener;
	private final ExecutorService workers = Executors.newFixedThreadPool(MAX_ACTIVE);
	private final Semaphore slots = new Semaphore(MAX_ACTIVE);
	private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
	private final Object socketsLock = new Object();
	private final AtomicBoolean closed = new AtomicBoolean();

	public EncryptedGuestCompanion(BtaeInvitation invitation) throws IOException {
		this.invitation = invitation;
		listener = new ServerSocket();
		listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), MAX_ACTIVE);
	}

	public InetSocketAddress address() { return (InetSocketAddress) listener.getLocalSocketAddress(); }

	public void serve() throws IOException {
		while (!closed.get()) {
			Socket local;
			try { local = listener.accept(); }
			catch (SocketException failure) {
				if (closed.get()) { return; }
				throw failure;
			}
		if (!slots.tryAcquire()) { local.close(); continue; }
		try { trackSocket(local); }
		catch (IOException closedDuringAccept) { slots.release(); return; }
			workers.execute(() -> {
				try { handle(local); }
				catch (Exception failure) {
					System.err.println("Encrypted guest connection closed: authentication, network, or protocol failure");
				} finally {
					sockets.remove(local);
					try { local.close(); } catch (IOException ignored) { }
					slots.release();
				}
			});
		}
	}

	private void handle(Socket local) throws Exception {
		if (System.currentTimeMillis() >= invitation.expiresAtEpochMillis()) {
			throw new IOException("invitation expired");
		}
		local.setSoTimeout(1500);
		InputStream gameInput = local.getInputStream();
		int first = gameInput.read();
		boolean status = first == 0xFE;
		if (!status && first != 0x02) { throw new IOException("unsupported BTA connection preface"); }
		boolean icon = false;
		if (status) {
			icon = BtaStatusProbe.readVariant(gameInput);
			if (gameInput.available() > 0) { throw new IOException("status probe has trailing data"); }
		}
		SSLSocket outer = openOuter();
		try (outer) {
		SSLSocket inner = openInner(outer);
		try (inner) {
			OutputStream encryptedOutput = inner.getOutputStream();
			InputStream encryptedInput = inner.getInputStream();
			EncryptedAuth auth = EncryptedAuth.forInvitation(invitation, status, icon);
			EncryptedFrames.write(encryptedOutput, EncryptedFrames.AUTH, auth.encode());
			EncryptedFrames.Frame acknowledged = EncryptedFrames.read(encryptedInput);
			if (acknowledged.type() != EncryptedFrames.AUTH_OK) {
				throw new IOException("encrypted invitation denied");
			}
			inner.setSoTimeout(0);
			local.setSoTimeout(0);
			if (status) {
				receive(encryptedInput, local, true);
			} else {
				EncryptedFrames.write(encryptedOutput, EncryptedFrames.DATA, new byte[] { (byte) first });
				Thread upload = new Thread(() -> upload(gameInput, encryptedOutput, inner), "bta-encrypted-upload");
				upload.setDaemon(true);
				upload.start();
				receive(encryptedInput, local, false);
				local.shutdownInput();
				upload.join(5000);
			}
		} finally {
			sockets.remove(inner);
		}
		} finally {
			sockets.remove(outer);
		}
	}

	private SSLSocket openOuter() throws Exception {
		SSLSocket socket = (SSLSocket) PinnedTls.context(invitation.relaySpkiSha256(), invitation.relayHost())
			.getSocketFactory().createSocket();
		try {
			trackSocket(socket);
			socket.connect(new InetSocketAddress(invitation.relayHost(), invitation.relayPort()), 10_000);
			socket.setSoTimeout(10_000);
			configure(socket, "bta-anywhere-relay/1");
			socket.startHandshake();
			if (!"bta-anywhere-relay/1".equals(socket.getApplicationProtocol())) {
				throw new IOException("relay ALPN mismatch");
			}
			return socket;
		} catch (Exception failure) {
			sockets.remove(socket);
			socket.close();
			throw failure;
		}
	}

	private SSLSocket openInner(SSLSocket outer) throws Exception {
		SSLSocket socket = (SSLSocket) PinnedTls.context(invitation.hostSpkiSha256(), null)
			.getSocketFactory().createSocket(outer, "bta-anywhere-host", invitation.relayPort(), true);
		try {
			trackSocket(socket);
			socket.setSoTimeout(10_000);
			configure(socket, EncryptedIdentity.INNER_ALPN);
			socket.startHandshake();
			if (!EncryptedIdentity.INNER_ALPN.equals(socket.getApplicationProtocol())) {
				throw new IOException("host ALPN mismatch");
			}
			return socket;
		} catch (Exception failure) {
			sockets.remove(socket);
			socket.close();
			throw failure;
		}
	}

	private static void configure(SSLSocket socket, String alpn) {
		SSLParameters parameters = socket.getSSLParameters();
		parameters.setProtocols(new String[] { "TLSv1.3" });
		parameters.setApplicationProtocols(new String[] { alpn });
		socket.setSSLParameters(parameters);
	}

	private void trackSocket(Socket socket) throws IOException {
		synchronized (socketsLock) {
			if (closed.get()) {
				socket.close();
				throw new IOException("encrypted guest companion is closed");
			}
			sockets.add(socket);
		}
	}

	private static void upload(InputStream gameInput, OutputStream encryptedOutput, SSLSocket inner) {
		byte[] buffer = new byte[EncryptedFrames.MAX_PAYLOAD];
		try {
			int length;
			while ((length = gameInput.read(buffer)) != -1) {
				byte[] payload = java.util.Arrays.copyOf(buffer, length);
				EncryptedFrames.write(encryptedOutput, EncryptedFrames.DATA, payload);
			}
			EncryptedFrames.write(encryptedOutput, EncryptedFrames.FIN, new byte[0]);
		} catch (IOException failure) {
			try { inner.close(); } catch (IOException ignored) { }
		}
	}

	private static void receive(InputStream encryptedInput, Socket local, boolean status) throws IOException {
		OutputStream gameOutput = local.getOutputStream();
		int total = 0;
		while (true) {
			EncryptedFrames.Frame frame = EncryptedFrames.read(encryptedInput);
			if (frame.type() == EncryptedFrames.FIN) {
				local.shutdownOutput();
				return;
			}
			if (frame.type() != EncryptedFrames.DATA || (status && (total += frame.payload().length) > 64 * 1024)) {
				throw new IOException("encrypted host frame is invalid");
			}
			gameOutput.write(frame.payload());
			gameOutput.flush();
		}
	}

	@Override
	public void close() throws IOException {
		if (closed.compareAndSet(false, true)) {
			listener.close();
			synchronized (socketsLock) {
				for (Socket socket : sockets) {
					try { socket.close(); } catch (IOException ignored) { }
				}
				sockets.clear();
			}
			workers.shutdownNow();
		}
	}
}
