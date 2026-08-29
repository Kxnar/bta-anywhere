package io.github.kxnar.btaanywhere.mod.gui;

import java.util.Objects;
import net.minecraft.client.gui.ButtonElement;
import net.minecraft.client.gui.Screen;

public final class ConfirmationScreen extends Screen {
	private final String title;
	private final String message;
	private final Runnable confirmed;

	public ConfirmationScreen(Screen parent, String title, String message, Runnable confirmed) {
		super(parent);
		this.title = Objects.requireNonNull(title, "title");
		this.message = Objects.requireNonNull(message, "message");
		this.confirmed = Objects.requireNonNull(confirmed, "confirmed");
	}

	@Override
	public void init() {
		buttons.clear();
		buttons.add(new ButtonElement(0, width / 2 - 102, height / 2 + 28, 100, 20, "Confirm"));
		buttons.add(new ButtonElement(1, width / 2 + 2, height / 2 + 28, 100, 20, "Cancel"));
	}

	@Override
	protected void buttonClicked(ButtonElement button) {
		if (!button.enabled) {
			return;
		}
		if (button.id == 0) {
			mc.displayScreen(getParentScreen());
			confirmed.run();
		} else if (button.id == 1) {
			mc.displayScreen(getParentScreen());
		}
	}

	@Override
	public void render(int mouseX, int mouseY, float partialTick) {
		renderBackground();
		drawStringCenteredShadow(fontRenderer, title, width / 2, height / 2 - 42, 0xFFFFFF);
		fontRenderer.splitCharsIntoLines(message, Math.min(280, width - 32), new java.util.ArrayList<>())
			.stream().limit(4).forEachOrdered(new java.util.function.Consumer<>() {
				private int y = height / 2 - 20;

				@Override
				public void accept(String line) {
					drawStringCenteredShadow(fontRenderer, line, width / 2, y, 0xD0D0D0);
					y += 10;
				}
			});
		super.render(mouseX, mouseY, partialTick);
	}
}
