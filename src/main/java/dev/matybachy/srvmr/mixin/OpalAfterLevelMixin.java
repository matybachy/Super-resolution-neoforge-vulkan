package dev.matybachy.srvmr.mixin;

import dev.matybachy.srvmr.render.FrameGen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Opal Compat runs a shader pack's deferred and composite passes after the world ({@code PackRenderer.afterLevel}),
 * whether or not a world was drawn. A generated frame drew none and shows DLSS's last picture moved, so those passes
 * would only cost the graphics card. Opal Compat is optional: without it there is no such class and nothing is done.
 */
@Pseudo
@Mixin(targets = "dev.opalcompat.render.PackRenderer", remap = false)
public abstract class OpalAfterLevelMixin {

	@Inject(method = "afterLevel", at = @At("HEAD"), cancellable = true, require = 0)
	private static void srvmr$noPassesForGeneratedFrame(CallbackInfo ci) {
		if (FrameGen.levelSkipped()) ci.cancel();
	}
}
