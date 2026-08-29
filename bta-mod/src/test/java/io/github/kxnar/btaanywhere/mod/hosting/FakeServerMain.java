package io.github.kxnar.btaanywhere.mod.hosting;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class FakeServerMain {
	public static void main(String[] arguments) throws Exception {
		Properties properties = new Properties();
		try (var input = Files.newInputStream(Path.of("server.properties"))) {
			properties.load(input);
		}
		int port = Integer.parseInt(properties.getProperty("server-port"));
		try (ServerSocket serverSocket = new ServerSocket(port)) {
			Thread acceptor = new Thread(() -> {
				while (!serverSocket.isClosed()) {
					try {
						serverSocket.accept().close();
					} catch (java.io.IOException exception) {
						return;
					}
				}
			}, "fake-bta-acceptor");
			acceptor.setDaemon(true);
			acceptor.start();
			System.out.println("Done (0.100s)! For help, type help");
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
				for (String line; (line = reader.readLine()) != null; ) {
					if (line.equals("stop")) {
						System.out.println("Saving chunks");
						return;
					}
				}
			}
		}
	}

	private FakeServerMain() {
	}
}
