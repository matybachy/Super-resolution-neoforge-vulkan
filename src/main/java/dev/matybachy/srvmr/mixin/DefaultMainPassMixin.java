package dev.matybachy.srvmr.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.matybachy.srvmr.render.DlssResolve;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.pass.DefaultMainPass;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The one place VulkanMod Reforged turns the world of its Render Scale into the full-size picture: it binds the scaled
 * colour and draws it over the swapchain. DLSS runs just before, outside any render pass, and its full-size output is
 * what gets bound instead. Where DLSS does not run the method does what it always did.
 */
@Mixin(value = DefaultMainPass.class, remap = false)
public abstract class DefaultMainPassMixin {

	@Shadow
	private Framebuffer mainFramebuffer;

	@Shadow
	@Final
	private SwapChain swapChain;

	@Unique
	private VulkanImage srvmr$upscaled;

	@WrapMethod(method = "resolveScaledFramebufferToSwapchain")
	private void srvmr$dlss(VkCommandBuffer commandBuffer, boolean keepRendering, Operation<Void> original) {
		this.srvmr$upscaled = DlssResolve.resolve(commandBuffer, this.mainFramebuffer, this.swapChain);
		try {
			original.call(commandBuffer, keepRendering);
		} finally {
			this.srvmr$upscaled = null;
		}
	}

	@WrapOperation(method = "resolveScaledFramebufferToSwapchain", at = @At(value = "INVOKE",
			target = "Lnet/vulkanmod/vulkan/texture/VTextureSelector;bindTexture(Lnet/vulkanmod/vulkan/texture/VulkanImage;)V"))
	private void srvmr$bindUpscaled(VulkanImage scaled, Operation<Void> original) {
		original.call(this.srvmr$upscaled != null ? this.srvmr$upscaled : scaled);
	}
}
