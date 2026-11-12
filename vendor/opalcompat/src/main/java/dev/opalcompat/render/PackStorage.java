package dev.opalcompat.render;

import dev.vitrail.pack.model.BufferObject;
import dev.vitrail.pack.model.ImageInformation;
import dev.vitrail.pack.model.PackTexture;
import dev.vitrail.pack.source.OpenedPack;
import dev.vitrail.pack.texture.CustomImageView;
import dev.vitrail.pack.texture.CustomImages;
import dev.vitrail.pack.texture.CustomStorage;
import dev.opalcompat.vk.PackImage;
import dev.opalcompat.vk.StorageBuffer;
import dev.opalcompat.vk.Vk;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.vulkan.VK10.*;

/** image.* and bufferObject.* allocations shared across all of a pack's stages. */
final class PackStorage implements AutoCloseable {

	private final List<ImageInformation> imageDeclarations;
	private final List<BufferObject> bufferDeclarations;
	private final Map<String, PackImage> images = new HashMap<>();
	private final Map<Integer, StorageBuffer> buffers = new HashMap<>();
	private final Set<String> fresh = new HashSet<>();

	PackStorage(OpenedPack pack) {
		var defines = pack.settings().globalDefines(pack.options());
		this.imageDeclarations = pack.properties().imageDirectives(defines).images();
		this.bufferDeclarations = pack.properties().bufferObjects(defines).buffers();
	}

	void resize(VkCommandBuffer cmd, int width, int height) {
		for (ImageInformation info : this.imageDeclarations) {
			int w = info.relative() ? Math.max(1, (int) (width * info.relativeWidth())) : info.width();
			int h = info.relative() ? Math.max(1, (int) (height * info.relativeHeight())) : Math.max(1, info.height());
			int d = Math.max(1, info.depth());
			PackImage old = this.images.get(info.name());
			if (old != null && old.width == w && old.height == h && old.depth() == d) continue;
			PackImage image = PackImage.create(dimensions(info.shape()), w, h, d, PackTargets.vkFormat(info.internalFormat().used()),
					VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, false, true);
			if (old != null) old.free();
			this.images.put(info.name(), image);
			this.fresh.add(info.name());
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc(stack);
			vkGetPhysicalDeviceProperties(Vk.device().getPhysicalDevice(), properties);
			for (BufferObject declaration : this.bufferDeclarations) {
				long bytes = declaration.relative() ? Math.multiplyExact(declaration.size(), Math.multiplyExact(
						(long) (width * declaration.scaleX()), (long) (height * declaration.scaleY()))) : declaration.size();
				if (bytes <= 0 || bytes > Integer.toUnsignedLong(properties.limits().maxStorageBufferRange())) {
					throw new IllegalArgumentException("bufferObject." + declaration.index() + " exceeds device storage range: " + bytes);
				}
				StorageBuffer old = this.buffers.get(declaration.index());
				if (old != null && old.size() == ((bytes + 3) & ~3L)) continue;
				StorageBuffer buffer = new StorageBuffer(bytes);
				if (old != null) old.close();
				this.buffers.put(declaration.index(), buffer);
				vkCmdFillBuffer(cmd, buffer.handle(), 0, buffer.size(), 0);
			}
		}
	}

	static int dimensions(PackTexture.Shape shape) {
		return switch (shape) {
			case TEXTURE_1D -> 1;
			case TEXTURE_3D -> 3;
			default -> 2;
		};
	}

	void beginFrame(VkCommandBuffer cmd, MemoryStack stack) {
		for (ImageInformation info : this.imageDeclarations) {
			if (!info.clear() && !this.fresh.contains(info.name())) continue;
			PackImage image = this.images.get(info.name());
			image.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
			VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack)
					.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
			vkCmdClearColorImage(cmd, image.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VkClearColorValue.calloc(stack), range);
		}
		this.fresh.clear();
		barrier(cmd, stack);
	}

	PackImage image(String name) {
		return CustomImages.image(name).map(info -> this.images.get(info.name())).orElse(null);
	}

	long view(String name) {
		PackImage image = image(name);
		var requested = CustomImageView.parse(name);
		if (requested.isEmpty()) return image.getImageView();
		var base = CustomImages.image(name).orElseThrow().internalFormat().used();
		if (!CustomImageView.compatible(base, requested.get().format())) throw new IllegalArgumentException("Incompatible image view " + name);
		return image.view(PackTargets.vkFormat(requested.get().format()));
	}

	StorageBuffer buffer(String name) { return this.buffers.get(CustomStorage.indexOf(name)); }

	StorageBuffer buffer(int index) { return this.buffers.get(index); }

	static void barrier(VkCommandBuffer cmd, MemoryStack stack) {
		VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack).sType$Default()
				.srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT).dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
		vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, barrier, null, null);
	}

	@Override
	public void close() {
		this.images.values().forEach(PackImage::free);
		this.images.clear();
		this.buffers.values().forEach(StorageBuffer::close);
		this.buffers.clear();
		this.fresh.clear();
	}
}
