package dev.matybachy.srvmr.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.matybachy.srvmr.Srvmr;
import dev.matybachy.srvmr.ngx.Ngx;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.util.VUtil;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceBufferDeviceAddressFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceBufferDeviceAddressFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Collection;

import static org.lwjgl.vulkan.VK10.VK_SUCCESS;

/**
 * DLSS needs device extensions VulkanMod Reforged does not ask for. They can only be added while the device is created,
 * in {@code DeviceManager.createLogicalDevice}: to the one set of extension names it hands to Vulkan, and, where NGX
 * asks for buffer device addresses, that feature to the create info, in the structure of the extension NGX named.
 * <p>
 * If the device cannot be created with them, it is created again exactly as VulkanMod Reforged asked, and DLSS stays
 * off: the game starts either way.
 */
@Mixin(value = DeviceManager.class, remap = false)
public abstract class DeviceExtensionsMixin {

	@WrapOperation(method = "createLogicalDevice", at = @At(value = "INVOKE",
			target = "Lnet/vulkanmod/vulkan/util/VUtil;asPointerBuffer(Ljava/util/Collection;)Lorg/lwjgl/PointerBuffer;"))
	private static PointerBuffer srvmr$dlssExtensions(Collection<String> extensions, Operation<PointerBuffer> original) {
		return original.call(Ngx.withDlssExtensions(extensions));
	}

	@WrapOperation(method = "createLogicalDevice", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/VK10;vkCreateDevice(Lorg/lwjgl/vulkan/VkPhysicalDevice;Lorg/lwjgl/vulkan/VkDeviceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Lorg/lwjgl/PointerBuffer;)I"))
	private static int srvmr$bufferDeviceAddress(VkPhysicalDevice physical, VkDeviceCreateInfo info,
			VkAllocationCallbacks allocator, PointerBuffer device, Operation<Integer> original) {
		Collection<String> vulkanMod = Ngx.vulkanModExtensions();
		if (vulkanMod == null) return original.call(physical, info, allocator, device);
		long next = info.pNext();
		int result;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			try {
				chainBufferDeviceAddress(stack, physical, info);
			} catch (Throwable e) {
				info.pNext(next);
				Srvmr.LOGGER.warn("[dlss] cannot ask for buffer device addresses", e);
			}
			result = original.call(physical, info, allocator, device);
			// What was chained here lives in this stack frame only; VulkanMod Reforged reads the create info afterwards.
			info.pNext(next);
		}
		if (result != VK_SUCCESS) {
			Srvmr.LOGGER.warn("[dlss] the device could not be created with DLSS's extensions ({}); it is created without "
					+ "them and DLSS is off", result);
			Ngx.deviceFailed();
			// Allocated in VulkanMod Reforged's own stack frame, which outlives the device's creation.
			info.ppEnabledExtensionNames(VUtil.asPointerBuffer(vulkanMod));
			result = original.call(physical, info, allocator, device);
		}
		return result;
	}

	private static void chainBufferDeviceAddress(MemoryStack stack, VkPhysicalDevice physical, VkDeviceCreateInfo info) {
		Ngx.Address address = Ngx.bufferDeviceAddress();
		if (address == Ngx.Address.NONE) return;
		boolean supported;
		if (address == Ngx.Address.EXT) {
			VkPhysicalDeviceBufferDeviceAddressFeaturesEXT query = VkPhysicalDeviceBufferDeviceAddressFeaturesEXT.calloc(stack).sType$Default();
			VK11.vkGetPhysicalDeviceFeatures2(physical, VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(query.address()));
			supported = query.bufferDeviceAddress();
			if (supported) {
				info.pNext(VkPhysicalDeviceBufferDeviceAddressFeaturesEXT.calloc(stack).sType$Default()
						.bufferDeviceAddress(true).pNext(info.pNext()).address());
			}
		} else {
			VkPhysicalDeviceBufferDeviceAddressFeatures query = VkPhysicalDeviceBufferDeviceAddressFeatures.calloc(stack).sType$Default();
			VK11.vkGetPhysicalDeviceFeatures2(physical, VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(query.address()));
			supported = query.bufferDeviceAddress();
			if (supported) {
				info.pNext(VkPhysicalDeviceBufferDeviceAddressFeatures.calloc(stack).sType$Default()
						.bufferDeviceAddress(true).pNext(info.pNext()).address());
			}
		}
		if (!supported) Srvmr.LOGGER.info("[dlss] the GPU has no buffer device addresses, which NGX listed");
	}
}
