package dev.opalcompat.vk;

import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkan.device.DeviceManager;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.Map;
import java.util.HashMap;

import static org.lwjgl.vulkan.VK10.*;

/** An owned 1D, 2D or 3D image exposed through VMR's image interface, without forking its allocator. */
public final class PackImage extends VulkanImage {

	private final long memory;
	private final long packSampler;
	private final int depth;
	private final int dimensions;
	private final Map<Integer, Long> views = new HashMap<>();
	private boolean retired;
	private boolean freed;

	private PackImage(long image, long memory, long view, long sampler, int format, int width, int height, int depth, int usage, int dimensions) {
		super(image, format, 1, width, height, 0, usage, view);
		this.memory = memory;
		this.packSampler = sampler;
		this.depth = depth;
		this.dimensions = dimensions;
	}

	public static PackImage create(int dimensions, int width, int height, int depth, int format,
			int usage, boolean linear, boolean clamp) {
		if (dimensions < 1 || dimensions > 3 || width <= 0 || height <= 0 || depth <= 0
				|| dimensions == 1 && height != 1 || dimensions != 3 && depth != 1) {
			throw new IllegalArgumentException("Invalid pack image dimensions");
		}
		long image = 0, memory = 0, view = 0, sampler = 0;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkImageCreateInfo info = VkImageCreateInfo.calloc(stack).sType$Default().imageType(dimensions - 1)
					.flags(VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT)
					.format(format).mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT)
					.tiling(VK_IMAGE_TILING_OPTIMAL).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE)
					.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
			info.extent().set(width, height, depth);
			LongBuffer handle = stack.mallocLong(1);
			Vk.check(vkCreateImage(Vk.device(), info, null, handle), "vkCreateImage(pack)");
			image = handle.get(0);
			VkMemoryRequirements required = VkMemoryRequirements.malloc(stack);
			vkGetImageMemoryRequirements(Vk.device(), image, required);
			VkMemoryAllocateInfo allocate = VkMemoryAllocateInfo.calloc(stack).sType$Default()
					.allocationSize(required.size()).memoryTypeIndex(Vk.memoryType(required.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
			Vk.check(vkAllocateMemory(Vk.device(), allocate, null, handle), "vkAllocateMemory(pack image)");
			memory = handle.get(0);
			Vk.check(vkBindImageMemory(Vk.device(), image, memory, 0), "vkBindImageMemory(pack)");
			VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default()
					.image(image).viewType(dimensions - 1).format(format);
			viewInfo.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
			Vk.check(vkCreateImageView(Vk.device(), viewInfo, null, handle), "vkCreateImageView(pack)");
			view = handle.get(0);
			int address = clamp ? VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE : VK_SAMPLER_ADDRESS_MODE_REPEAT;
			int filter = linear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
			VkSamplerCreateInfo sample = VkSamplerCreateInfo.calloc(stack).sType$Default()
					.magFilter(filter).minFilter(filter).mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
					.addressModeU(address).addressModeV(address).addressModeW(address).maxLod(0);
			Vk.check(vkCreateSampler(Vk.device(), sample, null, handle), "vkCreateSampler(pack)");
			sampler = handle.get(0);
			return new PackImage(image, memory, view, sampler, format, width, height, depth, usage, dimensions);
		} catch (RuntimeException | Error failure) {
			if (sampler != 0) vkDestroySampler(Vk.device(), sampler, null);
			if (view != 0) vkDestroyImageView(Vk.device(), view, null);
			if (image != 0) vkDestroyImage(Vk.device(), image, null);
			if (memory != 0) vkFreeMemory(Vk.device(), memory, null);
			throw failure;
		}
	}

	public int depth() { return this.depth; }

	/** 1, 2 or 3: what a descriptor of this image's view can be declared as. */
	public int dimensions() { return this.dimensions; }

	public long view(int format) {
		if (format == this.format) return getImageView();
		return this.views.computeIfAbsent(format, requested -> {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack).sType$Default()
						.image(getId()).viewType(this.dimensions - 1).format(requested);
				info.subresourceRange().aspectMask(this.aspect).levelCount(1).layerCount(1);
				LongBuffer handle = stack.mallocLong(1);
				Vk.check(vkCreateImageView(Vk.device(), info, null, handle), "pack image typed view");
				return handle.get(0);
			}
		});
	}

	/** Upload during pack loading; wait for this transfer before releasing its staging allocation. */
	public void upload(byte[] pixels) {
		try (HostBuffer staging = new HostBuffer(pixels.length, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, 4);
				MemoryStack stack = MemoryStack.stackPush()) {
			staging.view(0, pixels.length).put(pixels);
			var commands = DeviceManager.getGraphicsQueue().beginCommands();
			VkCommandBuffer cmd = commands.getHandle();
			transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
			VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, stack);
			copy.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
			copy.imageExtent().set(this.width, this.height, this.depth);
			vkCmdCopyBufferToImage(cmd, staging.handle(), getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copy);
			transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
			long fence = DeviceManager.getGraphicsQueue().submitCommands(commands);
			Vk.check(vkWaitForFences(Vk.device(), fence, true, Long.MAX_VALUE), "pack image upload");
			commands.reset();
		}
	}

	@Override
	public long getSampler() { return this.packSampler; }

	@Override
	public void transitionImageLayout(MemoryStack stack, VkCommandBuffer cmd, int layout) {
		ImageLayouts.transition(stack, cmd, this, layout);
	}

	@Override
	public void free() {
		if (this.retired) return;
		this.retired = true;
		MemoryManager.getInstance().addFrameOp(this::doFree);
	}

	@Override
	public void doFree() {
		if (this.freed) return;
		this.freed = true;
		this.views.values().forEach(view -> vkDestroyImageView(Vk.device(), view, null));
		this.views.clear();
		vkDestroySampler(Vk.device(), this.packSampler, null);
		vkDestroyImageView(Vk.device(), getImageView(), null);
		vkDestroyImage(Vk.device(), getId(), null);
		vkFreeMemory(Vk.device(), this.memory, null);
	}
}
