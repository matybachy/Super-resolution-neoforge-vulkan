package dev.opalcompat.compat.mixin;

import dev.opal.client.Opal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Flywheel's backends draw with programs of their own, past the pack. They ask this before they call themselves supported
 * and it knows only Iris, so with an Opal pack Flywheel stayed on and Create's moving parts missed the pack's lighting. Told
 * the truth Flywheel falls back to {@code flywheel:off}, the same as under Iris without a compatibility mod, and Create draws
 * through its block entity renderers, which Opal maps to the pack's programs. Opal's own reload of the level renderer makes
 * Flywheel ask again when a pack goes on or off.
 */
@Pseudo
@Mixin(targets = "dev.engine_room.flywheel.lib.util.ShadersModHelper", remap = false)
public abstract class FlywheelShaderPackMixin {

	@Inject(method = "isShaderPackInUse", at = @At("RETURN"), cancellable = true)
	private static void opalcompat$opal(CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() && Opal.active()) cir.setReturnValue(true);
	}
}
