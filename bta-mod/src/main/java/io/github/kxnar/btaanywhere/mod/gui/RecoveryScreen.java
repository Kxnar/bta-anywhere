package io.github.kxnar.btaanywhere.mod.gui;

import io.github.kxnar.btaanywhere.mod.BtaAnywhereMod;
import io.github.kxnar.btaanywhere.mod.hosting.HostController;
import io.github.kxnar.btaanywhere.mod.hosting.RecoveryInspection;
import io.github.kxnar.btaanywhere.mod.hosting.RecoveryService;
import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.ButtonElement;
import net.minecraft.client.gui.Screen;
import net.minecraft.client.gui.ScreenConnecting;

public final class RecoveryScreen extends Screen {
	private final HostController controller;
	private final RecoveryService recoveryService;
	private volatile RecoveryInspection inspection;
	private volatile String message = "Inspecting the recovery journal...";
	private volatile String worldToOpen;
	private volatile boolean working;
	private ButtonElement reconnectButton;
	private ButtonElement stopButton;
	private ButtonElement restoreButton;
	private ButtonElement openOriginalButton;

	public RecoveryScreen(Screen parent) {
		super(parent);
		controller = BtaAnywhereMod.activeController(mc);
		recoveryService = new RecoveryService(mc.getMinecraftDir().toPath());
	}

	private boolean ensureController() {
		if (controller != null && controller.hasActiveGameDirectoryLease()) {
			return true;
		}
		BtaAnywhereMod.showGameDirectoryFailure(mc, getParentScreen());
		return false;
	}

	@Override
	public void init() {
		if (!ensureController()) {
			return;
		}
		buttons.clear();
		int center = width / 2;
		reconnectButton = add(new ButtonElement(0, center - 102, height / 2 - 20, 100, 20, "Reconnect"));
		stopButton = add(new ButtonElement(1, center + 2, height / 2 - 20, 100, 20, "Graceful Stop"));
		add(new ButtonElement(2, center - 102, height / 2 + 4, 100, 20, "Open Logs"));
		restoreButton = add(new ButtonElement(3, center + 2, height / 2 + 4, 100, 20, "Restore Backup"));
		openOriginalButton = add(new ButtonElement(4, center - 102, height / 2 + 28, 204, 20,
			"Open Original (keep recovery files)"));
		add(new ButtonElement(5, center - 50, height / 2 + 56, 100, 20, "Back"));
		refreshAsync();
	}

	@Override
	public void tick() {
		if (!ensureController()) {
			return;
		}
		String world = worldToOpen;
		if (world != null) {
			worldToOpen = null;
			mc.startWorld(world);
			return;
		}
		updateButtons();
	}

	@Override
	protected void buttonClicked(ButtonElement button) {
		if (!ensureController() || !button.enabled || working) {
			return;
		}
		RecoveryInspection current = inspection;
		switch (button.id) {
			case 0 -> {
				if (current != null && current.canReconnect()) {
					try {
						controller.withActiveGameDirectoryLease(() -> {
							controller.adoptRecoveredSession(current);
							mc.displayScreen(new ScreenConnecting(mc, "127.0.0.1", current.entry().localPort()));
							return null;
						});
					} catch (Exception exception) {
						message = exception.getMessage();
					}
				}
			}
			case 1 -> {
				if (current != null) {
					runAsync(() -> {
						boolean clean = recoveryService.gracefulStop(current);
						if (!clean) {
							throw new IOException("the recovered server required forced termination; files were retained");
						}
						worldToOpen = current.entry().worldDirectoryName();
						message = "Recovered server stopped cleanly";
					});
				}
			}
			case 2 -> openLogs(current);
			case 3 -> {
				if (current != null) {
					mc.displayScreen(new ConfirmationScreen(
						this,
						"Restore the safety backup?",
						"The current save will be moved aside, not deleted. The selected backup will then be restored while no matching server process is running.",
						() -> runAsync(() -> worldToOpen = recoveryService.restoreBackup(current, true))
					));
				}
			}
			case 4 -> {
				if (current != null) {
					try {
						worldToOpen = controller.withActiveGameDirectoryLease(
							() -> recoveryService.clearAndOpenOriginal(current));
					} catch (Exception exception) {
						message = exception.getMessage();
					}
				}
			}
			case 5 -> mc.displayScreen(getParentScreen());
			default -> {
				// No action.
			}
		}
	}

	private void refreshAsync() {
		working = true;
		CompletableFuture.runAsync(() -> {
			try {
				Optional<RecoveryInspection> recovered = controller.withActiveGameDirectoryLease(
					recoveryService::inspect);
				inspection = recovered.orElse(null);
				message = recovered.map(RecoveryInspection::message).orElse("No recovery journal remains.");
			} catch (Exception exception) {
				message = "Recovery inspection failed: " + exception.getMessage();
			} finally {
				working = false;
			}
		});
	}

	private void runAsync(CheckedAction action) {
		working = true;
		message = "Working...";
		CompletableFuture.runAsync(() -> {
			try {
				controller.withActiveGameDirectoryLease(() -> {
					action.run();
					return null;
				});
			} catch (Exception exception) {
				message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
			} finally {
				working = false;
			}
		});
	}

	private void openLogs(RecoveryInspection current) {
		if (current == null) {
			return;
		}
		try {
			Path log = Path.of(current.entry().logFile()).toAbsolutePath().normalize();
			Path target = Files.isRegularFile(log) ? log : log.getParent();
			if (!Desktop.isDesktopSupported()) {
				throw new IOException("desktop file opening is unavailable");
			}
			Desktop.getDesktop().open(target.toFile());
		} catch (Exception exception) {
			message = "Could not open logs: " + exception.getMessage();
		}
	}

	private void updateButtons() {
		RecoveryInspection current = inspection;
		boolean active = controller.hasActiveGameDirectoryLease();
		reconnectButton.enabled = active && !working && current != null && current.canReconnect();
		stopButton.enabled = active && !working && current != null && current.canGracefullyStop();
		restoreButton.enabled = active && !working && current != null && current.canRestore();
		openOriginalButton.enabled = active && !working && current != null && current.canOpenOriginal();
	}

	@Override
	public void render(int mouseX, int mouseY, float partialTick) {
		if (!ensureController()) {
			return;
		}
		renderBackground();
		drawStringCenteredShadow(fontRenderer, "BTA Anywhere Recovery", width / 2, height / 2 - 72, 0xFFFFFF);
		java.util.List<String> lines = fontRenderer.splitCharsIntoLines(message, Math.min(290, width - 24),
			new java.util.ArrayList<>());
		int y = height / 2 - 54;
		for (String line : lines.stream().limit(4).toList()) {
			drawStringCenteredShadow(fontRenderer, line, width / 2, y, 0xD0D0D0);
			y += 10;
		}
		super.render(mouseX, mouseY, partialTick);
	}

	@FunctionalInterface
	private interface CheckedAction {
		void run() throws Exception;
	}
}
