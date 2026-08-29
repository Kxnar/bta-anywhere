package io.github.kxnar.btaanywhere.mod.mixin;

import io.github.kxnar.btaanywhere.mod.BtaAnywhereMod;
import io.github.kxnar.btaanywhere.mod.gui.RecoveryScreen;
import net.minecraft.client.gui.ButtonElement;
import net.minecraft.client.gui.Screen;
import net.minecraft.client.gui.ScreenMainMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ScreenMainMenu.class)
public abstract class ScreenMainMenuMixin {
	private static final int RECOVERY_BUTTON_ID = 0x425442;

	@Inject(method = "init", at = @At("TAIL"))
	private void btaAnywhere$addRecoveryButton(CallbackInfo callback) {
		Screen screen = (Screen) (Object) this;
		if (BtaAnywhereMod.controller(screen.mc).recovery().isEmpty()) {
			return;
		}
		screen.buttons.add(new ButtonElement(
			RECOVERY_BUTTON_ID,
			screen.width / 2 - 100,
			screen.height - 48,
			"BTA Anywhere Recovery"
		));
	}

	@Inject(method = "buttonClicked", at = @At("HEAD"), cancellable = true)
	private void btaAnywhere$openRecovery(ButtonElement button, CallbackInfo callback) {
		if (button.id != RECOVERY_BUTTON_ID) {
			return;
		}
		Screen screen = (Screen) (Object) this;
		screen.mc.displayScreen(new RecoveryScreen(screen));
		callback.cancel();
	}
}
