package dev.opalcompat.mixin;

import net.vulkanmod.vulkan.device.DeviceManager;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The device features shader packs use, turned on where the GPU has them. VulkanMod Reforged asks for
 * what it needs itself and none of these: per-attachment blending, geometry stages, and stores and
 * image formats for images and buffers a pack writes.
 */
@Mixin(value = DeviceManager.class, remap = false)
public abstract class DeviceManagerMixin {

	@Redirect(method = "createLogicalDevice", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/VkDeviceCreateInfo;pEnabledFeatures(Lorg/lwjgl/vulkan/VkPhysicalDeviceFeatures;)Lorg/lwjgl/vulkan/VkDeviceCreateInfo;"))
	private static VkDeviceCreateInfo opalcompat$features(VkDeviceCreateInfo info, VkPhysicalDeviceFeatures features) {
		VkPhysicalDeviceFeatures available = DeviceManager.device.availableFeatures.features();
		features.independentBlend(available.independentBlend());
		features.geometryShader(available.geometryShader());
		features.fragmentStoresAndAtomics(available.fragmentStoresAndAtomics());
		features.vertexPipelineStoresAndAtomics(available.vertexPipelineStoresAndAtomics());
		features.shaderStorageImageExtendedFormats(available.shaderStorageImageExtendedFormats());
		features.shaderStorageImageReadWithoutFormat(available.shaderStorageImageReadWithoutFormat());
		features.shaderStorageImageWriteWithoutFormat(available.shaderStorageImageWriteWithoutFormat());
		features.depthClamp(available.depthClamp());
		return info.pEnabledFeatures(features);
	}
}
