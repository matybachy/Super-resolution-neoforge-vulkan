package dev.opalcompat.mixin;

import dev.opalcompat.dh.VkDhBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** DH would pick its OpenGL renderer here, and there is no OpenGL. Applied only where {@code DhSupport} says so. */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.DependencySetup_neoforge", remap = false)
public abstract class DhDependencySetupMixin {

	@Inject(method = "setRenderingApiBindings", at = @At("HEAD"), cancellable = true)
	private static void opalcompat$vulkan(CallbackInfo ci) {
		VkDhBridge.bindRenderApi();
		ci.cancel();
	}
}
