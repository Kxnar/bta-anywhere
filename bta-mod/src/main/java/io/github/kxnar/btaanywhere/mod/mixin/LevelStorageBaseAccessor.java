package io.github.kxnar.btaanywhere.mod.mixin;

import java.io.File;
import net.minecraft.core.world.save.LevelStorageBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelStorageBase.class)
public interface LevelStorageBaseAccessor {
	@Accessor("saveDirectory")
	File btaAnywhere$getSaveDirectory();

	@Accessor("worldDirName")
	String btaAnywhere$getWorldDirectoryName();
}
