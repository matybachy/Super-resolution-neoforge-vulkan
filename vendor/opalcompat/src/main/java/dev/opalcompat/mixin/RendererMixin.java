package dev.opalcompat.mixin;

import dev.opalcompat.dev.AutoTest;
import dev.opalcompat.render.PackRenderer;
import net.vulkanmod.vulkan.Renderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Counts the frames VulkanMod Reforged actually presents. */
@Mixin(value = Renderer.class, remap = false)
public abstract class RendererMixin {

	@Inject(method = "endFrame", at = @At("RETURN"))
	private void opalcompat$countFrame(CallbackInfoReturnable<Boolean> cir) {
		PackRenderer.frameEnded();
		if (cir.getReturnValueZ()) {
			AutoTest.presentedFrames++;
		}
	}
}
