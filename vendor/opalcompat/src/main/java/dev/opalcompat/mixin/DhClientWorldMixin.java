package dev.opalcompat.mixin;

import com.seibel.distanthorizons.core.level.DhClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * On a server the level key arrives just after the join and DH unloads the level from the render thread, while its
 * request ticker finds the same stale level and unloads it too. The second one takes null out of the map and throws,
 * {@code Unexpected error in [LodRequestModule] tick loop}. The level is closed either way.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.world.DhClientWorld", remap = false)
public abstract class DhClientWorldMixin {

	@Redirect(method = "unloadLevel", at = @At(value = "INVOKE",
			target = "Lcom/seibel/distanthorizons/core/level/DhClientLevel;close()V"))
	private void opalcompat$closeOnce(DhClientLevel level) {
		if (level != null) level.close();
	}
}
