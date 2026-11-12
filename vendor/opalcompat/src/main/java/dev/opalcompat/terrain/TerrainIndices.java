package dev.opalcompat.terrain;

import dev.opalcompat.OpalCompat;
import dev.opalcompat.mixin.DrawerAccessor;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Drawer;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.memory.AutoIndexBuffer;
import net.vulkanmod.vulkan.memory.IndexBuffer;

/**
 * The quad indices terrain is drawn with: 32-bit ones once a chunk section needs them.
 * <p>
 * VulkanMod Reforged draws every opaque layer of every section through one shared buffer of 16-bit quad indices, made for 65536
 * vertices ({@code Drawer}: {@code new AutoIndexBuffer(QUAD_U16_MAX_VERTEX_COUNT, QUADS)}) and bound in
 * {@code WorldRenderer.renderSectionLayer}. That is 16384 quads. Nothing looks at a section's size when it is uploaded
 * ({@code DrawBuffers.stageUpload} takes {@code indexCount} as it comes), and VulkanMod Reforged's own immediate draws switch to
 * a second, 32-bit buffer past that size ({@code Drawer}: {@code count > U16_MAX_INDEX_COUNT ? quadsIntIndexBuffer :
 * quadsIndexBuffer}); terrain does not. So a layer with more quads is drawn with indices read past the end of the buffer, and
 * 16-bit indices could not name its vertices anyway: its first 16384 quads appear and the blocks meshed after them are in the
 * level, with their outline and their name in Jade, and are not drawn, with a shader pack or without one, however often the
 * section is built again.
 * <p>
 * With {@code uniqueOpaqueLayer} every opaque block of a section goes into the one layer, and a base full of machines and other
 * blocks with detailed models passes 16384 quads. In the user's debug log of 2026-11-11 18:11 the builders' 4 MB buffers for
 * that layer were outgrown on all eight threads and the 8 MB ones on two ({@code Needed to grow BufferBuilder buffer}), which at
 * this mod's 40 bytes a vertex is more than 26214 and more than 52430 quads in one layer, right after joining at their base.
 * <p>
 * So the largest layer uploaded is kept, and from the first one past the 16-bit reach terrain is bound to VulkanMod Reforged's
 * 32-bit quad indices, grown to that size, in its own draw and in this mod's. The translucent layer has indices of its own,
 * bound as 16-bit by {@code DrawBuffers.bindBuffers}; one with more than 65536 indices is said in the log and is not helped
 * here. Off with {@code chunkFlow=false}, as the rest of {@link ChunkFlow}.
 */
public final class TerrainIndices {

	private static final int SHORT_REACH = AutoIndexBuffer.QUAD_U16_MAX_VERTEX_COUNT;
	private static final int MOST_LOGGED = 10;

	/** The most vertices any layer drawn with the shared indices has had. Render thread. */
	private static int vertices;
	private static int logged;
	private static boolean translucentSaid;

	private TerrainIndices() {
	}

	/** Render thread, from {@code DrawBuffers.stageUpload}: a layer of a section is about to be uploaded. */
	public static void uploading(RenderSection section, TerrainRenderType layer, int indexCount, boolean sharedIndices) {
		if (!ChunkFlow.ENABLED) return;
		if (!sharedIndices) {
			if (indexCount > AutoIndexBuffer.U16_MAX_INDEX_COUNT && !translucentSaid) {
				translucentSaid = true;
				OpalCompat.LOGGER.warn("[chunks] the {} layer of the section at {} {} {} has {} quads, more than the {} that VulkanMod Reforged's 16-bit indices for sorted layers reach; what is past them is not drawn, and this mod does not change that",
						layer, section.xOffset(), section.yOffset(), section.zOffset(), indexCount / 6, AutoIndexBuffer.U16_MAX_INDEX_COUNT / 6);
			}
			return;
		}
		int needed = indexCount / 6 * 4;
		if (needed > SHORT_REACH && logged < MOST_LOGGED) {
			logged++;
			OpalCompat.LOGGER.info("[chunks] the {} layer of the section at {} {} {} has {} quads, more than the {} that VulkanMod Reforged's 16-bit quad indices reach; terrain is drawn with 32-bit indices{}",
					layer, section.xOffset(), section.yOffset(), section.zOffset(), indexCount / 6, SHORT_REACH / 4,
					logged == MOST_LOGGED ? "; no more of these lines are written" : "");
		}
		if (needed <= vertices) return;
		vertices = needed;
		// Grown here, ahead of the frame's draws, rather than in the middle of them.
		if (needed > SHORT_REACH) wide().checkCapacity(needed);
	}

	/** The index buffer for terrain layers drawn with automatic quad indices, to bind as {@code Drawer.bindIndexBuffer} does. */
	public static IndexBuffer buffer() {
		Drawer drawer = Renderer.getDrawer();
		if (!ChunkFlow.ENABLED || vertices <= SHORT_REACH) return drawer.getQuadsIndexBuffer().getIndexBuffer();
		AutoIndexBuffer wide = wide();
		wide.checkCapacity(vertices);
		return wide.getIndexBuffer();
	}

	private static AutoIndexBuffer wide() {
		return ((DrawerAccessor) Renderer.getDrawer()).opalcompat$quadsIntIndexBuffer();
	}
}
