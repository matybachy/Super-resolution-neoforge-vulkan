package dev.opalcompat.render;

import dev.vitrail.pack.model.TargetFormat;
import dev.vitrail.pack.model.TargetSize;
import dev.vitrail.pack.target.TargetDirectives;
import dev.vitrail.pack.target.TargetPlan;
import dev.vitrail.pack.target.TargetSchedule;
import dev.opalcompat.vk.Vk;
import net.vulkanmod.vulkan.texture.VulkanImage;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.lwjgl.vulkan.VK10.*;

/**
 * The pack's colour targets (colortex0..N), allocated as VulkanMod Reforged images. A target the
 * schedule doubles gets a second image for the other half of its ping-pong.
 * <p>
 * Row 0 of every target is the bottom of the picture, as in OpenGL: the pack's passes draw without a
 * viewport flip so that a texture coordinate reads back the row it was written to.
 */
final class PackTargets implements AutoCloseable {

	record Target(int index, TargetFormat format, int vkFormat, TargetSize size, VulkanImage main, VulkanImage alt) {
		VulkanImage image(TargetSchedule.Side side) {
			return side == TargetSchedule.Side.ALT && this.alt != null ? this.alt : this.main;
		}
	}

	private final Map<Integer, Target> targets = new TreeMap<>();
	private final int width;
	private final int height;

	PackTargets(TargetPlan plan, int width, int height) {
		this.width = width;
		this.height = height;
		TargetDirectives directives = plan.directives();
		Set<Integer> doubled = plan.schedule().doubled();
		Set<Integer> mipmapped = directives.mipmapped();
		for (int index : plan.allocated()) {
			TargetFormat format = directives.format(index).used();
			TargetSize size = directives.size(index);
			int w = sized(size.relative() ? size.width() * width : size.width());
			int h = sized(size.relative() ? size.height() * height : size.height());
			int vkFormat = vkFormat(format);
			int levels = mipmapped.contains(index) && !format.integer() ? mipLevels(w, h) : 1;
			VulkanImage main = create(w, h, vkFormat, format.integer(), levels);
			VulkanImage alt = doubled.contains(index) ? create(w, h, vkFormat, format.integer(), levels) : null;
			this.targets.put(index, new Target(index, format, vkFormat, size, main, alt));
		}
	}

	private static int mipLevels(int width, int height) {
		return 32 - Integer.numberOfLeadingZeros(Math.max(width, height));
	}

	private static int sized(float value) {
		return Math.max(1, Math.round(value));
	}

	private static VulkanImage create(int width, int height, int format, boolean integer, int levels) {
		VulkanImage.Builder builder = VulkanImage.builder(width, height)
				.setFormat(format)
				.setUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
						| VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT
						| Vk.storageUsage(format))
				.setLinearFiltering(!integer)
				.setClamp(true);
		if (levels > 1) builder.setMipLevels(levels).setLevelViews(true);
		return builder.createVulkanImage();
	}

	boolean matches(int width, int height) {
		return this.width == width && this.height == height;
	}

	Target target(int index) {
		return this.targets.get(index);
	}

	Iterable<Target> all() {
		return this.targets.values();
	}

	static int vkFormat(TargetFormat format) {
		return switch (format) {
			case R8_UNORM -> VK_FORMAT_R8_UNORM;
			case R8_SNORM -> VK_FORMAT_R8_SNORM;
			case RG8_UNORM -> VK_FORMAT_R8G8_UNORM;
			case RG8_SNORM -> VK_FORMAT_R8G8_SNORM;
			case RGBA8_UNORM -> VK_FORMAT_R8G8B8A8_UNORM;
			case RGBA8_SNORM -> VK_FORMAT_R8G8B8A8_SNORM;
			case R16_UNORM -> VK_FORMAT_R16_UNORM;
			case R16_SNORM -> VK_FORMAT_R16_SNORM;
			case RG16_UNORM -> VK_FORMAT_R16G16_UNORM;
			case RG16_SNORM -> VK_FORMAT_R16G16_SNORM;
			case RGBA16_UNORM -> VK_FORMAT_R16G16B16A16_UNORM;
			case RGBA16_SNORM -> VK_FORMAT_R16G16B16A16_SNORM;
			case R8_UINT -> VK_FORMAT_R8_UINT;
			case R8_SINT -> VK_FORMAT_R8_SINT;
			case RG8_UINT -> VK_FORMAT_R8G8_UINT;
			case RG8_SINT -> VK_FORMAT_R8G8_SINT;
			case RGBA8_UINT -> VK_FORMAT_R8G8B8A8_UINT;
			case RGBA8_SINT -> VK_FORMAT_R8G8B8A8_SINT;
			case R16_UINT -> VK_FORMAT_R16_UINT;
			case R16_SINT -> VK_FORMAT_R16_SINT;
			case RG16_UINT -> VK_FORMAT_R16G16_UINT;
			case RG16_SINT -> VK_FORMAT_R16G16_SINT;
			case RGBA16_UINT -> VK_FORMAT_R16G16B16A16_UINT;
			case RGBA16_SINT -> VK_FORMAT_R16G16B16A16_SINT;
			case R32_UINT -> VK_FORMAT_R32_UINT;
			case R32_SINT -> VK_FORMAT_R32_SINT;
			case RG32_UINT -> VK_FORMAT_R32G32_UINT;
			case RG32_SINT -> VK_FORMAT_R32G32_SINT;
			case RGBA32_UINT -> VK_FORMAT_R32G32B32A32_UINT;
			case RGBA32_SINT -> VK_FORMAT_R32G32B32A32_SINT;
			case R16_FLOAT -> VK_FORMAT_R16_SFLOAT;
			case RG16_FLOAT -> VK_FORMAT_R16G16_SFLOAT;
			case RGBA16_FLOAT -> VK_FORMAT_R16G16B16A16_SFLOAT;
			case R32_FLOAT -> VK_FORMAT_R32_SFLOAT;
			case RG32_FLOAT -> VK_FORMAT_R32G32_SFLOAT;
			case RGBA32_FLOAT -> VK_FORMAT_R32G32B32A32_SFLOAT;
			case RGB10A2_UNORM -> VK_FORMAT_A2B10G10R10_UNORM_PACK32;
			case RGB10A2_UINT -> VK_FORMAT_A2B10G10R10_UINT_PACK32;
			case RG11B10_FLOAT -> VK_FORMAT_B10G11R11_UFLOAT_PACK32;
		};
	}

	@Override
	public void close() {
		for (Target target : this.targets.values()) {
			target.main().free();
			if (target.alt() != null) target.alt().free();
		}
		this.targets.clear();
	}
}
