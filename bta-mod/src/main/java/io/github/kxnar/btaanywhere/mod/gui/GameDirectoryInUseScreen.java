package io.github.kxnar.btaanywhere.mod.gui;

import java.util.ArrayList;
import java.util.Objects;
import net.minecraft.client.gui.ButtonElement;
import net.minecraft.client.gui.Screen;

/** Explains why this client cannot open or host worlds from a shared game directory. */
public final class GameDirectoryInUseScreen extends Screen {
	private final String reason;

	public GameDirectoryInUseScreen(Screen parent, String reason) {
		super(parent);
		this.reason = Objects.requireNonNullElse(reason,
			"BTA Anywhere could not safely lock this game directory. Use a separate game profile.");
	}

	@Override
	public void init() {
		buttons.clear();
		buttons.add(new ButtonElement(0, width / 2 - 100, height / 2 + 38, 200, 20, "Back"));
	}

	@Override
	protected void buttonClicked(ButtonElement button) {
		if (button.enabled && button.id == 0) {
			mc.displayScreen(getParentScreen());
		}
	}

	@Override
	public void render(int mouseX, int mouseY, float partialTick) {
		renderBackground();
		drawStringCenteredShadow(fontRenderer, "BTA Anywhere cannot use this profile",
			width / 2, height / 2 - 50, 0xFFFFFF);
		var lines = new ArrayList<String>();
		fontRenderer.splitCharsIntoLines(reason, Math.min(300, width - 32), lines);
		for (int index = 0; index < Math.min(5, lines.size()); index++) {
			drawStringCenteredShadow(fontRenderer, lines.get(index), width / 2,
				height / 2 - 25 + index * 10, 0xD0D0D0);
		}
		super.render(mouseX, mouseY, partialTick);
	}
}
