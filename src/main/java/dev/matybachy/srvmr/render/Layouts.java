package dev.matybachy.srvmr.render;

import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Image layout changes with a full barrier, and VulkanMod Reforged's record of the layout kept true, so that whoever
 * changes the image's layout next starts from where it is. VulkanMod Reforged's own transition knows no {@code GENERAL},
 * the layout NGX writes its output in and the motion pass writes its images in.
 */
final class Layouts {

	private Layouts() {
	}

	static void transition(MemoryStack stack, VkCommandBuffer cmd, VulkanImage image, int layout) {
		int old = image.getCurrentLayout();
		// GENERAL to GENERAL still orders a write before the next read.
		if (old == layout && layout != VK_IMAGE_LAYOUT_GENERAL) return;
		barrier(stack, cmd, image, old, layout);
	}

	/**
	 * Into the layout to be read by a compute shader, with a barrier even when the image is in it already: whoever put it
	 * there may have made its writes visible to the fragment stage only (VulkanMod Reforged's depth snapshot does), and
	 * the motion pass and DLSS read in compute.
	 */
	static void read(MemoryStack stack, VkCommandBuffer cmd, VulkanImage image) {
		barrier(stack, cmd, image, image.getCurrentLayout(), VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
	}

	private static void barrier(MemoryStack stack, VkCommandBuffer cmd, VulkanImage image, int old, int layout) {
		VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
				.oldLayout(old).newLayout(layout).image(image.getId())
				.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
				.srcAccessMask(old == VK_IMAGE_LAYOUT_UNDEFINED ? 0 : VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT)
				.dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
		barrier.subresourceRange().aspectMask(image.aspect).baseMipLevel(0).levelCount(image.mipLevels)
				.baseArrayLayer(0).layerCount(1);
		vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, null,
				null, barrier);
		image.setCurrentLayout(layout);
	}
}
