package dev.opalcompat.compat.mixin;

import dev.opalcompat.compat.CustomImageUnits;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Opal gives each custom image of a pack one image unit for good, so it refuses a pack with more than seven: Complementary
 * Unbound with Euphoria Patches has eight with its colored lighting on, and no program of it uses them all. Here the count
 * is not limited and units go per program, see {@link CustomImageUnits}.
 */
@Pseudo
@Mixin(targets = "dev.opal.client.CustomImages", remap = false)
public abstract class OpalImagesMixin {

	@Shadow
	@Final
	private List<?> images;

	@Shadow
	private boolean unitsBound;

	@ModifyConstant(method = "<init>", constant = @Constant(intValue = 7))
	private int opalcompat$anyCount(int limit) {
		return Integer.MAX_VALUE;
	}

	@Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL46C;glGetInteger(I)I"))
	private int opalcompat$anyUnits(int name) {
		return Integer.MAX_VALUE;
	}

	@Inject(method = "<init>", at = @At("TAIL"))
	private void opalcompat$newPrograms(CallbackInfo ci) {
		// beginFrame would bind every image to a unit of its own once; there may be more images than units.
		this.unitsBound = true;
		CustomImageUnits.forget();
	}

	@Inject(method = "bind(Ldev/opal/client/GlProgram;)V", at = @At("HEAD"), cancellable = true)
	private void opalcompat$bindProgram(CallbackInfo ci) {
		CustomImageUnits.bind(this.images);
		ci.cancel();
	}

	@Inject(method = "bind(I)V", at = @At("HEAD"), cancellable = true)
	private void opalcompat$bindCompute(CallbackInfo ci) {
		CustomImageUnits.bind(this.images);
		ci.cancel();
	}
}
