package io.github.kxnar.btaanywhere.mod.mixin;

import io.github.kxnar.btaanywhere.mod.BtaAnywhereMod;
import net.minecraft.client.Minecraft;
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
}
