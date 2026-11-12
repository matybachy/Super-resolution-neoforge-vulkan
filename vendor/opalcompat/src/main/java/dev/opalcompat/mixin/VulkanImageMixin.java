package dev.opalcompat.mixin;

import dev.opalcompat.vk.ImageLayouts;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.lwjgl.vulkan.VK10.*;

@Mixin(value = VulkanImage.class, remap = false)
public abstract class VulkanImageMixin {

	// VulkanMod has no case for GENERAL and throws, and its read barrier only covers the fragment stage,
	// while packs sample in vertex stages as well. Its own images go through here too, which costs a wider
	// barrier and nothing else: the layout it tracks stays true (ImageLayouts sets it).
	@Inject(method = "transitionImageLayout(Lorg/lwjgl/system/MemoryStack;Lorg/lwjgl/vulkan/VkCommandBuffer;Lnet/vulkanmod/vulkan/texture/VulkanImage;I)V",
			at = @At("HEAD"), cancellable = true)
	private static void opalcompat$storageLayout(MemoryStack stack, VkCommandBuffer cmd, VulkanImage image,
			int layout, CallbackInfo ci) {
		if (layout == VK_IMAGE_LAYOUT_GENERAL || image.getCurrentLayout() == VK_IMAGE_LAYOUT_GENERAL
				|| layout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL || image.getCurrentLayout() == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
			ImageLayouts.transition(stack, cmd, image, layout);
			ci.cancel();
		}
	}
}
