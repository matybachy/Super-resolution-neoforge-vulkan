package dev.opalcompat.vk;

import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;

import static org.lwjgl.vulkan.VK10.*;

/** Layout transitions for pack images shared by graphics, transfers and compute. */
public final class ImageLayouts {

	private ImageLayouts() {
	}

	public static void transition(MemoryStack stack, VkCommandBuffer cmd, VulkanImage image, int layout) {
		int old = image.getCurrentLayout();
		// GENERAL -> GENERAL still needs a dependency for consecutive imageStore dispatches.
		if (old == layout && layout != VK_IMAGE_LAYOUT_GENERAL) return;
		VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
				.oldLayout(old).newLayout(layout).image(image.getId())
				.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
				.srcAccessMask(old == VK_IMAGE_LAYOUT_UNDEFINED ? 0 : VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT)
				.dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
		barrier.subresourceRange().aspectMask(image.aspect).levelCount(image.mipLevels).layerCount(1);
		vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
				0, null, null, barrier);
		image.setCurrentLayout(layout);
	}
}
