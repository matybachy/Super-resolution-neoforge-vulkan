package dev.opalcompat.compat.mixin;

import dev.opalcompat.compat.OpalLink;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * VulkanMod Reforged's OpenGL emulation leaves out what Opal's renderer stands on ({@code glDrawBuffers},
 * {@code glBindImageTexture}, {@code glDispatchCompute} and {@code glTexImage3D} do nothing, depth textures fail) and the
 * draws that follow lost the device within a minute. So no pipeline is ever built here, which turns every hook of Opal's
 * into nothing, and what Opal is asked to load goes to the Vulkan side.
 */
@Pseudo
@Mixin(targets = "dev.opal.client.Opal", remap = false)
public abstract class OpalOnVulkanMixin {

	@Shadow
	private static boolean dirty;

	@Shadow
	private static String dimensionDir;

	@Shadow
	static String dimensionDir() {
		throw new AssertionError();
	}

	@Inject(method = "rebuild", at = @At("HEAD"), cancellable = true)
	private static void opalcompat$noPipeline(CallbackInfo ci) {
		dirty = false;
		dimensionDir = dimensionDir();
		ci.cancel();
	}

	@Inject(method = "requestReload", at = @At("TAIL"))
	private static void opalcompat$follow(CallbackInfo ci) {
		OpalLink.opalChanged();
	}
}
