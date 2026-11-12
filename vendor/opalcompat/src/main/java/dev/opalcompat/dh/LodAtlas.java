package dev.opalcompat.dh;

import com.seibel.distanthorizons.core.render.AbstractBlockTextureAtlas;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.texture.SamplerManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;

/**
 * DH's block textures on VulkanMod's images, for the far terrain with no pack: what its {@code GlBlockTextureAtlas} is on
 * OpenGL, with the same filtering. A pack has no use for it, so with a pack on the tiles just wait in DH's registry.
 * <p>
 * Where OpenGL regenerates the mip chain of the whole atlas, each tile's levels are worked out and uploaded here along
 * with it: VulkanMod's {@code ImageUtil.generateMipmaps} waits for the queue to finish, once for every frame that brings
 * new tiles, and on a new world that is many frames in a row.
 */
final class LodAtlas extends AbstractBlockTextureAtlas {

	static final LodAtlas INSTANCE = new LodAtlas();

	/** A tile is sixteen texels. One level further and a texel would mix neighbouring tiles. */
	private static final int MIP_LEVELS = 5;

	private VulkanImage image;
	private ByteBuffer levels;
	private boolean recording;

	private LodAtlas() {
	}

	/** What the frame reads; null until the first {@link #upload}. */
	VulkanImage image() {
		return this.image;
	}

	/** On the render thread. Its copies are submitted here, ahead of the frame that reads them. */
	void upload() {
		try {
			uploadPendingTiles();
		} finally {
			// Left open, every upload of VulkanMod's would go into a command buffer nothing ever submits.
			endRecording();
		}
	}

	@Override
	protected void tryCreateOrResize(int width, int height) {
		// VulkanMod frees an image through its queue of frames: a recorded draw may still use it.
		if (this.image != null) this.image.free();
		this.image = VulkanImage.builder(width, height)
				.setFormat(VK_FORMAT_R8G8B8A8_UNORM)
				.setMipLevels(MIP_LEVELS)
				.setLinearFiltering(false)
				.setClamp(false)
				.createVulkanImage();
		this.image.updateTextureSampler((byte) (SamplerManager.USE_MIPMAPS_BIT | SamplerManager.MIPMAP_LINEAR_FILTERING_BIT));
	}

	/** One submission for all of this frame's tiles, and on a new world that can be thousands of them. */
	@Override
	protected void beforeWriteToTexture() {
		DeviceManager.getGraphicsQueue().startRecording();
		this.recording = true;
	}

	@Override
	protected void writeToTexture(ByteBuffer pixels, int x, int y, int width, int height) {
		this.image.uploadSubTextureAsync(0, width, height, x, y, 0, 0, width, pixels);

		int bytes = width * height * 4;
		if (this.levels == null || this.levels.capacity() < bytes) {
			if (this.levels != null) MemoryUtil.memFree(this.levels);
			this.levels = MemoryUtil.memAlloc(bytes);
		}
		ByteBuffer level = this.levels.clear();
		MemoryUtil.memCopy(MemoryUtil.memAddress(pixels), MemoryUtil.memAddress(level), bytes);
		int w = width;
		int h = height;
		for (int mip = 1; mip < MIP_LEVELS; mip++) {
			halve(level, w, h);
			w /= 2;
			h /= 2;
			level.position(0).limit(w * h * 4);
			this.image.uploadSubTextureAsync(mip, w, h, x >> mip, y >> mip, 0, 0, w, level);
			level.clear();
		}
	}

	/** Each texel the average of four, in place: what is written lies behind everything still to be read. */
	private static void halve(ByteBuffer pixels, int width, int height) {
		for (int row = 0; row < height / 2; row++) {
			for (int column = 0; column < width / 2; column++) {
				int to = (row * (width / 2) + column) * 4;
				int near = (row * 2 * width + column * 2) * 4;
				int far = ((row * 2 + 1) * width + column * 2) * 4;
				for (int channel = 0; channel < 4; channel++) {
					int sum = (pixels.get(near + channel) & 0xFF) + (pixels.get(near + 4 + channel) & 0xFF)
							+ (pixels.get(far + channel) & 0xFF) + (pixels.get(far + 4 + channel) & 0xFF);
					pixels.put(to + channel, (byte) ((sum + 2) / 4));
				}
			}
		}
	}

	@Override
	protected void afterWriteToTexture() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			this.image.readOnlyLayout(stack, DeviceManager.getGraphicsQueue().getCommandBuffer().getHandle());
		}
		endRecording();
	}

	private void endRecording() {
		if (!this.recording) return;
		this.recording = false;
		DeviceManager.getGraphicsQueue().endRecordingAndSubmit();
	}
}
