package dev.opalcompat.dh;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMemoryBarrier;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_VERTEX_INPUT_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdPipelineBarrier;

/** One buffer of far terrain on the device, in Distant Horizons' own sixteen byte vertex. */
public class LodBuffer {

	public static final int STRIDE = 16;

	/**
	 * Ahead of a draw of far terrain, outside a render pass. The copies from the transfer queue were waited for before DH
	 * listed their sections, which leaves their writes available; this makes them visible to the vertex input of what
	 * the graphics queue draws next.
	 */
	public static void afterCopies(VkCommandBuffer cmd, MemoryStack stack) {
		VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack).sType$Default()
				.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT);
		vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_VERTEX_INPUT_BIT, 0, barrier, null, null);
	}

	/** Set once the vertices are on their way to the device. DH's threads write it, the render thread reads it. */
	private volatile Uploaded uploaded;

	private record Uploaded(LodDeviceBuffer buffer, int vertices) {
	}

	/**
	 * On one of DH's threads through {@link LodTransfer}, the copy done when this returns, or on the render thread through
	 * {@link LodUploads}, the copy submitted ahead of the frame that draws it. DH lists the section afterwards either way.
	 */
	protected final void upload(ByteBuffer data, int vertexCount) {
		free();
		if (vertexCount <= 0) return;
		int size = vertexCount * STRIDE;
		LodDeviceBuffer buffer = new LodDeviceBuffer(size);
		try {
			if (RenderSystem.isOnRenderThread()) {
				LodUploads.copy(buffer, size, data);
			} else {
				LodTransfer.copy(buffer, size, data);
			}
		} catch (RuntimeException | Error e) {
			buffer.release();
			throw e;
		}
		LodIndices.need(vertexCount);
		this.uploaded = new Uploaded(buffer, vertexCount);
	}

	/** Through VulkanMod's queue of frames: a recorded draw may still use it. */
	protected final void free() {
		Uploaded old = this.uploaded;
		this.uploaded = null;
		if (old != null) old.buffer().release();
	}

	public final boolean ready() {
		return this.uploaded != null;
	}

	public final long handle() {
		return this.uploaded.buffer().getId();
	}

	public final long offset() {
		return 0L;
	}

	public final int vertices() {
		Uploaded uploaded = this.uploaded;
		return uploaded == null ? 0 : uploaded.vertices();
	}

	/** Two triangles a quad, four vertices apart, DH's own order ({@code IndexBufferBuilder}). */
	public final int indices() {
		return vertices() / 4 * 6;
	}
}
