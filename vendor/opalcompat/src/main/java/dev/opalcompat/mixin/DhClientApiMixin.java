package dev.opalcompat.mixin;

import dev.opalcompat.dh.VkDhBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** With no pack the far terrain is drawn once a frame, whichever of the two hooks on the solid layer gets there first. */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.api.internal.ClientApi", remap = false)
public abstract class DhClientApiMixin {

	@Inject(method = "renderLods", at = @At("HEAD"), cancellable = true)
	private void opalcompat$once(CallbackInfo ci) {
		if (VkDhBridge.farTerrainTaken()) ci.cancel();
	}
}
