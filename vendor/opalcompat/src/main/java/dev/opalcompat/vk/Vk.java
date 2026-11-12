package dev.opalcompat.vk;

import net.vulkanmod.vulkan.Vulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;

import static org.lwjgl.vulkan.VK10.*;

/** Small helpers over VulkanMod Reforged's device. */
public final class Vk {

	private Vk() {
	}

	public static VkDevice device() {
		return Vulkan.getVkDevice();
	}

	public static void check(int result, String what) {
		if (result != VK_SUCCESS) {
			throw new IllegalStateException(what + " failed: VkResult " + result);
		}
	}

	/**
	 * The storage usage bit where this format can carry it. Asking for it where it cannot fails the image
	 * outright, and most targets are never bound as images at all.
	 */
	public static int storageUsage(int format) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkFormatProperties properties = VkFormatProperties.malloc(stack);
			vkGetPhysicalDeviceFormatProperties(device().getPhysicalDevice(), format, properties);
			return (properties.optimalTilingFeatures() & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) != 0
					? VK_IMAGE_USAGE_STORAGE_BIT : 0;
		}
	}

	public static int memoryType(int typeBits, int properties) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPhysicalDeviceMemoryProperties memory = VkPhysicalDeviceMemoryProperties.malloc(stack);
			vkGetPhysicalDeviceMemoryProperties(device().getPhysicalDevice(), memory);
			for (int i = 0; i < memory.memoryTypeCount(); i++) {
				if ((typeBits & (1 << i)) != 0 && (memory.memoryTypes(i).propertyFlags() & properties) == properties) {
					return i;
				}
			}
		}
		throw new IllegalStateException("No Vulkan memory type with properties " + properties);
	}
}
