package io.github.kxnar.btaanywhere.mod.mixin;

import io.github.kxnar.btaanywhere.mod.BtaAnywhereMod;
import io.github.kxnar.btaanywhere.mod.hosting.HostState;
import net.minecraft.client.gui.ButtonElement;
import net.minecraft.client.gui.Screen;
import net.minecraft.client.gui.ScreenPause;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ScreenPause.class)
public abstract class ScreenPauseMixin {
	private static final int HOST_BUTTON_ID = 0x425441;

	@Inject(method = "init", at = @At("TAIL"))
	private void btaAnywhere$addHostButton(CallbackInfo callback) {
		Screen screen = (Screen) (Object) this;
		HostState state = BtaAnywhereMod.controller(screen.mc).status().state();
		boolean managedSession = state != HostState.IDLE;
		boolean singlePlayerWorld = screen.mc.currentWorld != null && !screen.mc.isMultiplayerWorld();
		if (!managedSession && !singlePlayerWorld) {
			return;
		}
		String label = managedSession ? "Manage Hosting" : "Host World";
		screen.buttons.add(new ButtonElement(
			HOST_BUTTON_ID,
			screen.width / 2 - 100,
			screen.height / 4 + 56,
			label
		));
	}

	@Inject(method = "buttonClicked", at = @At("HEAD"), cancellable = true)
	private void btaAnywhere$openHosting(ButtonElement button, CallbackInfo callback) {
		if (button.id != HOST_BUTTON_ID) {
			return;
		}
		Screen screen = (Screen) (Object) this;
		BtaAnywhereMod.openHostingScreen(screen.mc, screen);
		callback.cancel();
	}
}
