package dev.opalcompat.render;

import com.mojang.blaze3d.platform.NativeImage;
import dev.opalcompat.OpalCompat;
import dev.opalcompat.vk.HostBuffer;
import dev.opalcompat.vk.Vk;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkImageBlit;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

/**
 * The pack's targets as PNG, for the autotest. Each colour target goes through a blit into eight bits a channel, which
 * every format that can be blitted converts for itself; depth is copied as it is and written as grey. Recorded at the
 * end of a frame and read back at the head of a later one, once the device has finished it.
 */
final class TargetDump implements AutoCloseable {

	private record Pending(String file, HostBuffer pixels, int width, int height, boolean depth, VulkanImage through) {
	}

	private final List<Pending> pending = new ArrayList<>();

	void colour(VkCommandBuffer cmd, MemoryStack stack, VulkanImage image, String file) {
		if ((blitFeatures(image.format) & VK_FORMAT_FEATURE_BLIT_SRC_BIT) == 0) {
			OpalCompat.LOGGER.info("[autotest] {} cannot be blitted, not dumped", file);
			return;
		}
		VulkanImage target = VulkanImage.builder(image.width, image.height).setFormat(VK_FORMAT_R8G8B8A8_UNORM)
				.setUsage(VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
				.setClamp(true).createVulkanImage();
		image.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
		target.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
		VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
		region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
		region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
		region.srcOffsets(1).set(image.width, image.height, 1);
		region.dstOffsets(1).set(image.width, image.height, 1);
		vkCmdBlitImage(cmd, image.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, target.getId(),
				VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region, VK_FILTER_NEAREST);
		target.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
		this.pending.add(new Pending(file, copy(cmd, stack, target, VK_IMAGE_ASPECT_COLOR_BIT), image.width, image.height, false, target));
	}

	void depth(VkCommandBuffer cmd, MemoryStack stack, VulkanImage image, String file) {
		image.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
		this.pending.add(new Pending(file, copy(cmd, stack, image, VK_IMAGE_ASPECT_DEPTH_BIT), image.width, image.height, true, null));
	}

	private static HostBuffer copy(VkCommandBuffer cmd, MemoryStack stack, VulkanImage image, int aspect) {
		HostBuffer pixels = new HostBuffer(image.width * image.height * 4L, VK_BUFFER_USAGE_TRANSFER_DST_BIT, 4);
		VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
		region.imageSubresource().aspectMask(aspect).layerCount(1);
		region.imageExtent().set(image.width, image.height, 1);
		vkCmdCopyImageToBuffer(cmd, image.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, pixels.handle(), region);
		return pixels;
	}

	private static int blitFeatures(int format) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkFormatProperties properties = VkFormatProperties.malloc(stack);
			vkGetPhysicalDeviceFormatProperties(Vk.device().getPhysicalDevice(), format, properties);
			return properties.optimalTilingFeatures();
		}
	}

	/** Once the commands have run: waits for the device, and writes everything out. Row 0 of a target is the bottom. */
	@Override
	public void close() {
		dev.opalcompat.vk.QueueLock.deviceWaitIdle();
		for (Pending dump : this.pending) {
			ByteBuffer bytes = dump.pixels().view(0, dump.width() * dump.height() * 4).order(ByteOrder.LITTLE_ENDIAN);
			try (NativeImage png = new NativeImage(dump.width(), dump.height(), false)) {
				float near = 1.0F, far = 0.0F;
				if (dump.depth()) {
					for (int i = 0; i < dump.width() * dump.height(); i++) {
						float value = bytes.getFloat(i * 4);
						if (value < 1.0F) near = Math.min(near, value);
						if (value < 1.0F) far = Math.max(far, value);
					}
				}
				float span = Math.max(1.0e-6F, far - near);
				for (int y = 0; y < dump.height(); y++) {
					for (int x = 0; x < dump.width(); x++) {
						int at = (x + y * dump.width()) * 4;
						int abgr;
						if (dump.depth()) {
							// The depth that holds anything, stretched: most of a depth buffer sits near one.
							float value = bytes.getFloat(at);
							int grey = value >= 1.0F ? 255 : (int) ((value - near) / span * 200.0F);
							abgr = 0xFF000000 | grey << 16 | grey << 8 | grey;
						} else {
							abgr = bytes.getInt(at) | 0xFF000000;
						}
						png.setPixelRGBA(x, dump.height() - 1 - y, abgr);
					}
				}
				png.writeToFile(Path.of(dump.file()));
			} catch (IOException e) {
				OpalCompat.LOGGER.error("[autotest] cannot write {}", dump.file(), e);
			}
			dump.pixels().close();
			if (dump.through() != null) dump.through().free();
		}
		this.pending.clear();
	}
}
