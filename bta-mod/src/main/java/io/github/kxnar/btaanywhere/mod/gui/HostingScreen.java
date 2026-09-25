package io.github.kxnar.btaanywhere.mod.gui;

import io.github.kxnar.btaanywhere.mod.BtaAnywhereMod;
import io.github.kxnar.btaanywhere.mod.config.BtaAnywhereConfig;
import io.github.kxnar.btaanywhere.mod.hosting.HostController;
import io.github.kxnar.btaanywhere.mod.hosting.HostOptions;
import io.github.kxnar.btaanywhere.mod.hosting.HostState;
import io.github.kxnar.btaanywhere.mod.hosting.HostStatus;
import io.github.kxnar.btaanywhere.mod.hosting.NetworkMode;
import io.github.kxnar.btaanywhere.mod.hosting.WorldContext;
import io.github.kxnar.btaanywhere.mod.hosting.WorldMode;
import io.github.kxnar.btaanywhere.mod.mixin.LevelStorageBaseAccessor;
import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.ButtonElement;
import net.minecraft.client.gui.Screen;
import net.minecraft.client.gui.ScreenConnecting;
import net.minecraft.client.gui.TextFieldElement;
import net.minecraft.core.world.save.LevelStorage;
import org.lwjgl.input.Keyboard;

public final class HostingScreen extends Screen {
	private static final int BUTTON_WORLD_MODE = 1;
	private static final int BUTTON_NETWORK_MODE = 2;
	private static final int BUTTON_WHITELIST = 3;
	private static final int BUTTON_PRIMARY = 4;
	private static final int BUTTON_COPY = 5;
	private static final int BUTTON_LOGS = 6;
	private static final int BUTTON_BACK = 7;

	private final HostController controller;
	private final BtaAnywhereConfig config;
	private WorldMode worldMode = WorldMode.LIVE;
	private NetworkMode networkMode = NetworkMode.LAN;
	private boolean whitelistEnabled = true;
	private String invitedValue = "";
	private String maximumPlayersValue = "8";
	private String memoryValue = "2048";
	private String portValue = "25565";
	private String localMessage = "";
	private boolean handoffPerformed;
	private boolean connectionOpened;
	private String reopenWorldAfterStop;
	private boolean downloadConfirmed;
	private WorldContext selectedWorld;

	private TextFieldElement invitedField;
	private TextFieldElement maximumPlayersField;
	private TextFieldElement memoryField;
	private TextFieldElement portField;
	private ButtonElement worldModeButton;
	private ButtonElement networkModeButton;
	private ButtonElement whitelistButton;
	private ButtonElement primaryButton;
	private ButtonElement copyButton;
	private ButtonElement logsButton;

	public HostingScreen(Screen parent) {
		super(parent);
		controller = BtaAnywhereMod.activeController(mc);
		config = BtaAnywhereMod.config(mc);
		// A reopened hosting screen must not hand off a different world for an active start.
		handoffPerformed = controller != null && controller.status().state().isBusy();
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
		Keyboard.enableRepeatEvents(true);
		buttons.clear();
		int center = width / 2;
		worldModeButton = add(new ButtonElement(BUTTON_WORLD_MODE, center - 102, 18, 100, 20,
			"World: " + worldMode.label()));
		networkModeButton = add(new ButtonElement(BUTTON_NETWORK_MODE, center + 2, 18, 100, 20,
			networkModeLabel()));

		invitedField = field(center - 102, 50, 204, invitedValue, "Player1, Player2");
		invitedField.setMaxStringLength(256);
		maximumPlayersField = field(center - 102, 82, 98, maximumPlayersValue, "8");
		maximumPlayersField.setMaxStringLength(2);
		memoryField = field(center + 4, 82, 98, memoryValue, "2048");
		memoryField.setMaxStringLength(5);
		portField = field(center - 102, 114, 98, portValue, "25565");
		portField.setMaxStringLength(5);
		whitelistButton = add(new ButtonElement(BUTTON_WHITELIST, center + 4, 114, 98, 20,
			whitelistLabel()));

		primaryButton = add(new ButtonElement(BUTTON_PRIMARY, center - 102, 139, 204, 20, primaryLabel()));
		copyButton = add(new ButtonElement(BUTTON_COPY, center - 102, 163, 100, 20, "Copy Address"));
		logsButton = add(new ButtonElement(BUTTON_LOGS, center + 2, 163, 100, 20, "Open Logs"));
		add(new ButtonElement(BUTTON_BACK, center - 50, height - 24, 100, 20, "Back"));
		updateControls();
	}

	private TextFieldElement field(int x, int y, int fieldWidth, String value, String placeholder) {
		TextFieldElement field = new TextFieldElement(this, fontRenderer, x, y, fieldWidth, 20, value, placeholder);
		field.setText(value);
		return field;
	}

	@Override
	public void tick() {
		if (!ensureController()) {
			return;
		}
		for (TextFieldElement field : fields()) {
			field.updateCursorCounter();
		}
		captureFieldValues();
		HostStatus hostStatus = controller.status();
		if (hostStatus.state() == HostState.SAVING && !handoffPerformed) {
			handoffPerformed = true;
			try {
				if (mc.currentWorld == null || mc.isMultiplayerWorld()) {
					throw new IllegalStateException("the original single-player world is no longer open");
				}
				// BTA's native changeWorld(null) path forces a save, waits for chunk I/O,
				// unloads all chunks, invokes onUnload, and closes LevelStorage on the game thread.
				controller.beforeWorldSave();
				mc.changeWorld(null);
				controller.continueAfterWorldClosed();
			} catch (RuntimeException exception) {
				controller.reportWorldHandoffFailure(exception);
			}
		}
		if (hostStatus.state() == HostState.CONNECTING && !connectionOpened) {
			connectionOpened = true;
			int port = controller.localPort().orElse(25_565);
			controller.markHostConnectionStarted();
			mc.displayScreen(new ScreenConnecting(mc, "127.0.0.1", port));
			return;
		}
		if (reopenWorldAfterStop != null && hostStatus.state() == HostState.IDLE) {
			String world = reopenWorldAfterStop;
			reopenWorldAfterStop = null;
			if (mc.currentWorld == null) {
				mc.startWorld(world);
			}
			return;
		}
		updateControls();
	}

	@Override
	protected void buttonClicked(ButtonElement button) {
		if (!ensureController() || !button.enabled) {
			return;
		}
		switch (button.id) {
			case BUTTON_WORLD_MODE -> {
				worldMode = worldMode.next();
				worldModeButton.displayString = "World: " + worldMode.label();
			}
			case BUTTON_NETWORK_MODE -> {
				networkMode = nextAvailableNetworkMode();
				networkModeButton.displayString = networkModeLabel();
			}
			case BUTTON_WHITELIST -> changeWhitelist();
			case BUTTON_PRIMARY -> primaryAction();
			case BUTTON_COPY -> {
				String address = controller.status().connectionAddress();
				if (!address.isBlank()) {
					mc.copyToClipboard(address);
					localMessage = "Connection address copied";
				}
			}
			case BUTTON_LOGS -> openLogs();
			case BUTTON_BACK -> mc.displayScreen(getParentScreen());
			default -> {
				// No action.
			}
		}
	}

	private void primaryAction() {
		HostState state = controller.status().state();
		if (state.canStop() || state == HostState.STOPPING) {
			if (state != HostState.STOPPING) {
				reopenWorldAfterStop = controller.originalWorldDirectoryName().orElse(null);
				disconnectManagedClient();
				controller.stop();
			}
			return;
		}
		if (mc.currentWorld == null) {
			if (controller.canReopenOriginalWorld()) {
				controller.originalWorldDirectoryName().ifPresent(mc::startWorld);
			} else {
				localMessage = "No single-player world is open";
			}
			return;
		}
		if (mc.isMultiplayerWorld()) {
			localMessage = "Only a single-player world can be hosted";
			return;
		}
		if (!controller.isServerDistributionInstalled() && !downloadConfirmed) {
			mc.displayScreen(new ConfirmationScreen(
				this,
				"Download official BTA server?",
				"BTA Anywhere will download the official 8.0.1 Babric server archive, verify its pinned SHA-256, and extract it under the game directory.",
				() -> {
					downloadConfirmed = true;
					startHosting();
				}
			));
			return;
		}
		startHosting();
	}

	private void startHosting() {
		if (!ensureController()) {
			return;
		}
		captureFieldValues();
		try {
			selectedWorld = captureWorld();
			HostOptions options = new HostOptions(
				worldMode,
				networkMode,
				HostOptions.parseInvitedUsernames(invitedValue),
				mc.session.username,
				parseInteger(maximumPlayersValue, "maximum players"),
				parseInteger(memoryValue, "server memory"),
				parseInteger(portValue, "port"),
				whitelistEnabled
			);
			handoffPerformed = false;
			connectionOpened = false;
			localMessage = "";
			controller.requestStart(selectedWorld, options, config, downloadConfirmed);
		} catch (Exception exception) {
			localMessage = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
		}
	}

	private WorldContext captureWorld() throws IOException {
		if (mc.currentWorld == null || mc.isMultiplayerWorld()) {
			throw new IOException("a single-player world must be open");
		}
		LevelStorage storage = mc.currentWorld.getLevelStorage();
		if (!(storage instanceof LevelStorageBaseAccessor accessor)) {
			throw new IOException("BTA world storage path is unavailable");
		}
		Path directory = accessor.btaAnywhere$getSaveDirectory().toPath().toAbsolutePath().normalize();
		String directoryName = accessor.btaAnywhere$getWorldDirectoryName();
		String displayName = mc.currentWorld.getLevelData().getWorldName();
		return new WorldContext(mc.getMinecraftDir().toPath(), directory, directoryName, displayName);
	}

	private void changeWhitelist() {
		if (!whitelistEnabled) {
			whitelistEnabled = true;
			whitelistButton.displayString = whitelistLabel();
			return;
		}
		mc.displayScreen(new ConfirmationScreen(
			this,
			"Disable the whitelist?",
			"Anyone who learns the connection address could join. Online mode remains enabled, but invited usernames will no longer restrict access.",
			() -> {
				if (!ensureController()) {
					return;
				}
				whitelistEnabled = false;
				whitelistButton.displayString = whitelistLabel();
			}
		));
	}

	private void disconnectManagedClient() {
		if (mc.currentWorld != null && mc.isMultiplayerWorld()) {
			mc.currentWorld.sendQuittingDisconnectingPacket();
			mc.changeWorld(null);
		}
	}

	private void openLogs() {
		try {
			Path logs = controller.managedDirectory().resolve("logs");
			Files.createDirectories(logs);
			if (!Desktop.isDesktopSupported()) {
				throw new IOException("desktop file opening is unavailable");
			}
			Desktop.getDesktop().open(logs.toFile());
		} catch (Exception exception) {
			localMessage = "Could not open logs: " + exception.getMessage();
		}
	}

	@Override
	public void keyPressed(char character, int key, int mouseX, int mouseY) {
		if (!ensureController()) {
			return;
		}
		if (key != Keyboard.KEY_BACK) {
			super.keyPressed(character, key, mouseX, mouseY);
		}
		for (TextFieldElement field : fields()) {
			if (field.isFocused && field.isEnabled) {
				field.textboxKeyTyped(character, key);
			}
		}
	}

	@Override
	public void mouseClicked(int mouseX, int mouseY, int button) {
		if (!ensureController()) {
			return;
		}
		super.mouseClicked(mouseX, mouseY, button);
		for (TextFieldElement field : fields()) {
			field.mouseClicked(mouseX, mouseY, button);
		}
	}

	@Override
	public void removed() {
		captureFieldValues();
		Keyboard.enableRepeatEvents(false);
	}

	@Override
	public void render(int mouseX, int mouseY, float partialTick) {
		if (!ensureController()) {
			return;
		}
		renderBackground();
		int center = width / 2;
		drawStringCenteredShadow(fontRenderer, "BTA Anywhere", center, 9, 0xFFFFFF);
		drawStringShadow(fontRenderer, "Invited usernames", center - 102, 40, 0xA0A0A0);
		drawStringShadow(fontRenderer, "Maximum players", center - 102, 72, 0xA0A0A0);
		drawStringShadow(fontRenderer, "Memory (MiB)", center + 4, 72, 0xA0A0A0);
		drawStringShadow(fontRenderer, "Server port", center - 102, 104, 0xA0A0A0);
		drawStringShadow(fontRenderer, "Access", center + 4, 104, 0xA0A0A0);
		for (TextFieldElement field : fields()) {
			field.drawTextBox();
			field.updateCursor(mc, mouseX, mouseY);
		}
		HostStatus hostStatus = controller.status();
		String message = localMessage.isBlank() ? hostStatus.message() : localMessage;
		List<StatusLine> statusLines = new ArrayList<>();
		statusLines.add(new StatusLine(message, statusColor(hostStatus.state())));
		if (!hostStatus.connectionAddress().isBlank()) {
			statusLines.add(new StatusLine(hostStatus.connectionAddress(), 0x80FF80));
		}
		if (!hostStatus.logLines().isEmpty()) {
			String last = hostStatus.logLines().get(hostStatus.logLines().size() - 1);
			statusLines.add(new StatusLine(last, 0x808080));
		}
		int firstLineY = 187;
		int backButtonY = height - 24;
		int visibleLines = Math.max(0, Math.min(statusLines.size(), ((backButtonY - firstLineY - 8) / 10) + 1));
		for (int index = 0; index < visibleLines; index++) {
			StatusLine line = statusLines.get(index);
			drawStringCenteredShadow(fontRenderer, constrain(line.text(), width - 20), center,
				firstLineY + index * 10, line.color());
		}
		super.render(mouseX, mouseY, partialTick);
	}

	private void updateControls() {
		HostStatus hostStatus = controller.status();
		boolean editable = hostStatus.state() == HostState.IDLE || hostStatus.state() == HostState.FAILED;
		for (TextFieldElement field : fields()) {
			field.isEnabled = editable;
		}
		worldModeButton.enabled = editable;
		networkModeButton.enabled = editable;
		whitelistButton.enabled = editable;
		primaryButton.displayString = primaryLabel();
		primaryButton.enabled = hostStatus.state() != HostState.STOPPING;
		copyButton.enabled = !hostStatus.connectionAddress().isBlank();
		logsButton.enabled = Files.isDirectory(controller.managedDirectory().resolve("logs"));
	}

	private String primaryLabel() {
		HostState state = controller.status().state();
		if (state == HostState.STOPPING) {
			return "Stopping...";
		}
		if (state.canStop()) {
			return "Stop Hosting";
		}
		if (mc.currentWorld == null && controller.canReopenOriginalWorld()) {
			return "Reopen Original World";
		}
		return state == HostState.IDLE || state == HostState.FAILED ? "Start Hosting" : state.name();
	}

	private NetworkMode nextAvailableNetworkMode() {
		NetworkMode candidate = networkMode.next();
		if (candidate == NetworkMode.RELAY && !config.relayConfigured(mc.getMinecraftDir().toPath())) {
			candidate = candidate.next();
		}
		return candidate;
	}

	private String networkModeLabel() {
		return "Network: " + networkMode.label();
	}

	private String whitelistLabel() {
		return whitelistEnabled ? "Whitelist: On" : "Whitelist: OFF";
	}

	private List<TextFieldElement> fields() {
		List<TextFieldElement> fields = new ArrayList<>(4);
		if (invitedField != null) {
			fields.add(invitedField);
			fields.add(maximumPlayersField);
			fields.add(memoryField);
			fields.add(portField);
		}
		return fields;
	}

	private void captureFieldValues() {
		if (invitedField == null) {
			return;
		}
		invitedValue = invitedField.getText();
		maximumPlayersValue = maximumPlayersField.getText();
		memoryValue = memoryField.getText();
		portValue = portField.getText();
	}

	private int parseInteger(String value, String label) {
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException exception) {
			throw new IllegalArgumentException(label + " must be a whole number", exception);
		}
	}

	private int statusColor(HostState state) {
		return state == HostState.FAILED ? 0xFF8080 : 0xFFFFFF;
	}

	private String constrain(String value, int maximumWidth) {
		if (fontRenderer.stringWidth(value) <= maximumWidth) {
			return value;
		}
		String suffix = "...";
		int length = value.length();
		while (length > 0 && fontRenderer.stringWidth(value.substring(0, length) + suffix) > maximumWidth) {
			length--;
		}
		return value.substring(0, length) + suffix;
	}

	private record StatusLine(String text, int color) {
	}
}
