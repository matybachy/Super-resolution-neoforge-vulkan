package dev.opalcompat.render;

import dev.vitrail.pack.model.TargetFormat;
import dev.vitrail.pack.target.PackDirectives;
import dev.opalcompat.vk.Vk;
import dev.opalcompat.OpalCompat;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkFormatProperties;

import java.nio.LongBuffer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.HashSet;

import static org.lwjgl.vulkan.VK10.*;

/**
 * The maps drawn from the light: {@code shadowtex0} with everything in it, {@code shadowtex1} copied
 * before the translucents, and the {@code shadowcolor} targets the pack names. All of one square size.
 * Row 0 is the bottom, as in the pack's other targets.
 */
final class ShadowMaps implements AutoCloseable {

	private static final int DEPTH_FORMAT = VK_FORMAT_D32_SFLOAT;

	private final int resolution;
	private final VulkanImage depth;
	private final VulkanImage depthNoTranslucent;
	private final Map<Integer, VulkanImage> colours = new TreeMap<>();
	private final Map<Integer, VulkanImage> alternateColours = new TreeMap<>();
	private final Set<Integer> flipped = new HashSet<>();
	private final Set<Integer> mipmapped;
	private final List<PackDirectives.ShadowColour> directives;
	private final long[] compareSamplers = new long[2];

	ShadowMaps(int resolution, Set<Integer> named, List<PackDirectives.ShadowColour> directives,
			List<PackDirectives.ShadowDepth> depths, Set<Integer> mipmapped) {
		this.resolution = resolution;
		this.mipmapped = Set.copyOf(mipmapped);
		this.directives = directives;
		int usage = VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
				| VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
		boolean supported = supportsDepthBlit();
		this.depth = depthImage(resolution, usage, depths, 0, supported);
		this.depthNoTranslucent = depthImage(resolution, usage, depths, 1, supported);
		for (int index : named) colour(index);
		this.compareSamplers[0] = compareSampler(this.depth.mipLevels);
		this.compareSamplers[1] = compareSampler(this.depthNoTranslucent.mipLevels);
	}

	private static boolean supportsDepthBlit() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkFormatProperties properties = VkFormatProperties.malloc(stack);
			vkGetPhysicalDeviceFormatProperties(Vk.device().getPhysicalDevice(), DEPTH_FORMAT, properties);
			int required = VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK_FORMAT_FEATURE_BLIT_DST_BIT;
			return (properties.optimalTilingFeatures() & required) == required;
		}
	}

	private static VulkanImage depthImage(int resolution, int usage, List<PackDirectives.ShadowDepth> depths,
			int index, boolean supported) {
		boolean mipmap = index < depths.size() && depths.get(index).mipmap();
		if (mipmap && !supported) OpalCompat.LOGGER.warn("[pack] shadowtex{} mipmaps unavailable: depth format cannot blit", index);
		int levels = mipmap && supported ? Integer.SIZE - Integer.numberOfLeadingZeros(resolution) : 1;
		return VulkanImage.builder(resolution, resolution).setFormat(DEPTH_FORMAT).setUsage(usage)
				.setMipLevels(levels).setLevelViews(levels > 1).setLinearFiltering(linear(depths, index))
				.setClamp(true).createVulkanImage();
	}
	private static boolean linear(List<PackDirectives.ShadowDepth> depths, int index) {
		return index >= depths.size() || !depths.get(index).nearest();
	}

	/** {@code GL_LINEAR} plus {@code GL_COMPARE_REF_TO_TEXTURE}, LEQUAL: what a {@code sampler2DShadow} is written against. */
	private static long compareSampler(int levels) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack).sType$Default()
					.magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR)
					.mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
					.addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
					.addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
					.addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
					.compareEnable(true).compareOp(VK_COMPARE_OP_LESS_OR_EQUAL)
					.minLod(0.0F).maxLod(levels - 1);
			LongBuffer handle = stack.mallocLong(1);
			Vk.check(vkCreateSampler(Vk.device(), info, null, handle), "vkCreateSampler");
			return handle.get(0);
		}
	}

	int resolution() {
		return this.resolution;
	}

	int depthFormat() {
		return DEPTH_FORMAT;
	}

	VulkanImage depth() {
		return this.depth;
	}

	VulkanImage depthNoTranslucent() {
		return this.depthNoTranslucent;
	}

	long compare(VulkanImage image) {
		return this.compareSamplers[image == this.depthNoTranslucent ? 1 : 0];
	}

	Map<Integer, VulkanImage> colours() {
		return this.colours;
	}

	/** The target, made the first time anything names it: a program's draw buffer may be the only mention. */
	VulkanImage colour(int index) {
		return this.colours.computeIfAbsent(index, this::createColour);
	}

	VulkanImage alternateColour(int index) {
		return this.alternateColours.computeIfAbsent(index, this::createColour);
	}

	// Iris's rule (ShadowCompositeRenderer, ShadowRenderTargets): a flipped target reads its alternate half
	// and writes its main one, and the flips start over each frame.
	VulkanImage sampledColour(int index) {
		return this.flipped.contains(index) ? alternateColour(index) : colour(index);
	}

	VulkanImage nextColour(int index) {
		return this.flipped.contains(index) ? colour(index) : alternateColour(index);
	}

	void flip(int index) {
		if (!this.flipped.add(index)) this.flipped.remove(index);
	}

	void resetFlips() {
		this.flipped.clear();
	}

	Map<Integer, VulkanImage> alternateColours() {
		return this.alternateColours;
	}

	private VulkanImage createColour(int index) {
		TargetFormat format = index < this.directives.size()
				? this.directives.get(index).format().used() : TargetFormat.RGBA8_UNORM;
		int levels = this.mipmapped.contains(index) ? Integer.SIZE - Integer.numberOfLeadingZeros(this.resolution) : 1;
		int vkFormat = PackTargets.vkFormat(format);
		return VulkanImage.builder(this.resolution, this.resolution)
				.setMipLevels(levels).setLevelViews(levels > 1)
				.setFormat(vkFormat)
				.setUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
						| VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT
						| Vk.storageUsage(vkFormat))
				.setLinearFiltering(!format.integer())
				.setClamp(true)
				.createVulkanImage();
	}

	PackDirectives.ShadowColour directive(int index) {
		return index < this.directives.size() ? this.directives.get(index) : null;
	}

	@Override
	public void close() {
		this.depth.free();
		this.depthNoTranslucent.free();
		this.colours.values().forEach(VulkanImage::free);
		this.colours.clear();
		this.alternateColours.values().forEach(VulkanImage::free);
		this.alternateColours.clear();
		this.flipped.clear();
		for (long sampler : this.compareSamplers) vkDestroySampler(Vk.device(), sampler, null);
	}
}
