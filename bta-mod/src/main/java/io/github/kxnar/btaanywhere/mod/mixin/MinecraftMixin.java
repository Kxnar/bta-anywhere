package io.github.kxnar.btaanywhere.mod.mixin;

import io.github.kxnar.btaanywhere.mod.BtaAnywhereMod;
import net.minecraft.client.Minecraft;
import net.minecraft.core.world.settings.WorldConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Inject(method = "runTick", at = @At("TAIL"))
	private void btaAnywhere$monitorHostedWorld(CallbackInfo callback) {
		BtaAnywhereMod.clientTick((Minecraft) (Object) this);
	}

	@Inject(method = "startWorld", at = @At("HEAD"), cancellable = true)
	private void btaAnywhere$guardOriginalWorldOpen(String worldDirectoryName, CallbackInfo callback) {
		if (BtaAnywhereMod.blockSinglePlayerWorldOpen((Minecraft) (Object) this, worldDirectoryName)) {
			callback.cancel();
		}
	}

	@Inject(method = "createAndStartWorld", at = @At("HEAD"), cancellable = true)
	private void btaAnywhere$guardNewWorldOpen(WorldConfiguration configuration, CallbackInfo callback) {
		if (BtaAnywhereMod.blockNewSinglePlayerWorld((Minecraft) (Object) this)) {
			callback.cancel();
		}
	}
}
