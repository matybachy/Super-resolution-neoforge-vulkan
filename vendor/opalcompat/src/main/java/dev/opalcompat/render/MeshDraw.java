package dev.opalcompat.render;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import dev.vitrail.render.EntityFrame;
import net.vulkanmod.vulkan.Drawer;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.memory.AutoIndexBuffer;
import net.vulkanmod.vulkan.memory.IndexBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

/**
 * What one mesh draw binds. A mesh the game built this frame is copied, since it is closed when the
 * call returns; a {@code VBO} is already on the GPU and only referred to.
 * <p>
 * Binding 0 is the mesh, binding 1 one {@link PackPipeline.Constants} record for what its format has
 * not got, binding 2 (only where a program reads them and the vertices are at hand)
 * {@link PackPipeline.Derived}: the owner's ids, the middle of the quad's sprite, its tangent. A vanilla
 * format has no room for those, and a batch mixes owners, so they cannot be one record per draw.
 */
final class MeshDraw {
	private final long vertexBuffer;
	private final long vertexOffset;
	private final long indexBuffer;
	private final long indexOffset;
	private final int indexType;
	private final int count;
	private final FrameArena.Slice constants;
	private final FrameArena.Slice derived;

	private MeshDraw(long vertexBuffer, long vertexOffset, long indexBuffer, long indexOffset, int indexType, int count,
			FrameArena.Slice constants, FrameArena.Slice derived) {
		this.vertexBuffer = vertexBuffer;
		this.vertexOffset = vertexOffset;
		this.indexBuffer = indexBuffer;
		this.indexOffset = indexOffset;
		this.indexType = indexType;
		this.count = count;
		this.constants = constants;
		this.derived = derived;
	}

	/** Null for an empty mesh. */
	static MeshDraw immediate(FrameArena arena, MeshData.DrawState state, ByteBuffer sourceVertices,
			ByteBuffer sourceIndices, DrawIds[] ids, boolean derive, float lineWidth) {
		int vertices = state.vertexCount();
		if (vertices <= 0) return null;
		int vertexBytes = Math.multiplyExact(vertices, state.format().getVertexSize());
		FrameArena.Slice vertexSlice = copyBytes(arena, sourceVertices, vertexBytes);
		FrameArena.Slice constants = constants(arena, lineWidth);
		FrameArena.Slice derived = derive ? derive(arena, state, sourceVertices, ids) : null;
		if (sourceIndices != null) {
			int count = state.indexCount();
			if (count <= 0) return null;
			int type = state.indexType() == VertexFormat.IndexType.SHORT ? VK_INDEX_TYPE_UINT16 : VK_INDEX_TYPE_UINT32;
			FrameArena.Slice indexSlice = copyBytes(arena, sourceIndices, Math.multiplyExact(count, state.indexType().bytes));
			return new MeshDraw(vertexSlice.buffer(), vertexSlice.offset(), indexSlice.buffer(), indexSlice.offset(), type,
					count, constants, derived);
		}
		return automatic(arena, state.mode(), vertices, vertexSlice.buffer(), vertexSlice.offset(), constants, derived);
	}

	/** The same vertices, drawn through other indices: one run of a batch that mixes owners. */
	MeshDraw withIndices(FrameArena arena, ByteBuffer indices, int indexCount) {
		FrameArena.Slice slice = copyBytes(arena, indices, Math.multiplyExact(indexCount, Integer.BYTES));
		return new MeshDraw(this.vertexBuffer, this.vertexOffset, slice.buffer(), slice.offset(), VK_INDEX_TYPE_UINT32,
				indexCount, this.constants, this.derived);
	}

	/**
	 * A {@code VBO}'s geometry: its own buffers, read where they are.
	 *
	 * @param indices    its index buffer, or null where it is drawn in order
	 * @param indexCount how many of them, or how many vertices where it is drawn in order
	 */
	static MeshDraw stored(FrameArena arena, long vertexBuffer, long vertexOffset, IndexBuffer indices, int indexCount,
			float lineWidth) {
		FrameArena.Slice constants = constants(arena, lineWidth);
		if (indices == null) {
			return new MeshDraw(vertexBuffer, vertexOffset, 0L, 0L, VK_INDEX_TYPE_UINT32, indexCount, constants, null);
		}
		return new MeshDraw(vertexBuffer, vertexOffset, indices.getId(), indices.getOffset(), indices.indexType.type,
				indexCount, constants, null);
	}

	static int topology(VertexFormat.Mode mode) {
		return switch (mode) {
			case DEBUG_LINES, DEBUG_LINE_STRIP -> VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
			default -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
		};
	}

	/** The shared index buffers VulkanMod's own draws use, with its own counts ({@code Drawer.draw}). */
	private static MeshDraw automatic(FrameArena arena, VertexFormat.Mode mode, int vertices, long vertexBuffer,
			long vertexOffset, FrameArena.Slice constants, FrameArena.Slice derived) {
		Drawer drawer = Renderer.getDrawer();
		AutoIndexBuffer shared;
		int count;
		switch (mode) {
			case QUADS -> {
				count = vertices / 4 * 6;
				if (count > AutoIndexBuffer.U16_MAX_INDEX_COUNT) {
					FrameArena.Slice slice = longQuads(arena, vertices, count);
					return new MeshDraw(vertexBuffer, vertexOffset, slice.buffer(), slice.offset(), VK_INDEX_TYPE_UINT32, count,
							constants, derived);
				}
				shared = drawer.getQuadsIndexBuffer();
			}
			case LINES -> {
				count = vertices / 4 * 6;
				shared = drawer.getLinesIndexBuffer();
			}
			case TRIANGLE_FAN -> {
				count = Math.max(0, vertices - 2) * 3;
				shared = drawer.getTriangleFanIndexBuffer();
			}
			case TRIANGLE_STRIP, LINE_STRIP -> {
				count = Math.max(0, vertices - 2) * 3;
				shared = drawer.getTriangleStripIndexBuffer();
			}
			case DEBUG_LINE_STRIP -> {
				count = Math.max(0, vertices - 1) * 2;
				shared = drawer.getDebugLineStripIndexBuffer();
			}
			default -> {
				return new MeshDraw(vertexBuffer, vertexOffset, 0L, 0L, VK_INDEX_TYPE_UINT32, vertices, constants, derived);
			}
		}
		if (count == 0) return null;
		shared.checkCapacity(vertices);
		IndexBuffer buffer = shared.getIndexBuffer();
		return new MeshDraw(vertexBuffer, vertexOffset, buffer.getId(), buffer.getOffset(), buffer.indexType.type, count,
				constants, derived);
	}

	/** Past what the 16 bit quad buffer holds; the 32 bit one has no getter on {@code Drawer}. */
	private static FrameArena.Slice longQuads(FrameArena arena, int vertices, int count) {
		FrameArena.Slice slice = arena.allocate(Math.multiplyExact(count, Integer.BYTES));
		IntBuffer out = slice.bytes().asIntBuffer();
		for (int quad = 0; quad + 3 < vertices; quad += 4) {
			out.put(quad).put(quad + 1).put(quad + 2).put(quad).put(quad + 2).put(quad + 3);
		}
		return slice;
	}

	private static FrameArena.Slice copyBytes(FrameArena arena, ByteBuffer source, int bytes) {
		ByteBuffer copy = source.duplicate();
		if (copy.remaining() < bytes) throw new IllegalArgumentException("Mesh buffer is shorter than its draw state");
		copy.limit(copy.position() + bytes);
		FrameArena.Slice slice = arena.allocate(bytes);
		slice.bytes().put(copy);
		return slice;
	}

	/** Iris's stand-ins for an attribute the format has not got ({@code VanillaTransformer}). */
	private static FrameArena.Slice constants(FrameArena arena, float lineWidth) {
		FrameArena.Slice slice = arena.allocate(PackPipeline.Constants.STRIDE);
		ByteBuffer out = slice.bytes();
		for (int i = 0; i < 4; i++) out.putFloat(PackPipeline.Constants.COLOR + i * 4, 1.0F);
		out.putFloat(PackPipeline.Constants.UV0, 0.5F).putFloat(PackPipeline.Constants.UV0 + 4, 0.5F);
		// OverlayTexture.NO_OVERLAY: the row of the overlay image that tints nothing.
		out.putInt(PackPipeline.Constants.UV1, 0).putInt(PackPipeline.Constants.UV1 + 4, 10);
		out.putInt(PackPipeline.Constants.UV2, 240).putInt(PackPipeline.Constants.UV2 + 4, 240);
		out.putFloat(PackPipeline.Constants.NORMAL, 0.0F).putFloat(PackPipeline.Constants.NORMAL + 4, 0.0F)
				.putFloat(PackPipeline.Constants.NORMAL + 8, 1.0F).putFloat(PackPipeline.Constants.NORMAL + 12, 0.0F);
		PackRenderer.currentDrawIds().put(out, PackPipeline.Constants.IDENTIFIERS);
		out.putFloat(PackPipeline.Constants.MID_TEX, 0.5F).putFloat(PackPipeline.Constants.MID_TEX + 4, 0.5F);
		out.putFloat(PackPipeline.Constants.TANGENT, 1.0F).putFloat(PackPipeline.Constants.TANGENT + 4, 0.0F)
				.putFloat(PackPipeline.Constants.TANGENT + 8, 0.0F).putFloat(PackPipeline.Constants.TANGENT + 12, 1.0F);
		out.putFloat(PackPipeline.Constants.LINE_WIDTH, lineWidth);
		return slice;
	}

	private static FrameArena.Slice derive(FrameArena arena, MeshData.DrawState state, ByteBuffer source, DrawIds[] ids) {
		int vertices = state.vertexCount();
		FrameArena.Slice slice = arena.allocate(Math.multiplyExact(vertices, PackPipeline.Derived.STRIDE));
		ByteBuffer out = slice.bytes();
		for (int vertex = 0; vertex < vertices; vertex++) {
			int at = vertex * PackPipeline.Derived.STRIDE;
			DrawIds id = ids != null && vertex < ids.length ? ids[vertex] : DrawIds.NONE;
			id.put(out, at + PackPipeline.Derived.IDENTIFIERS);
			out.putFloat(at + PackPipeline.Derived.MID_TEX, 0.5F).putFloat(at + PackPipeline.Derived.MID_TEX + 4, 0.5F);
			out.putInt(at + PackPipeline.Derived.TANGENT, EntityFrame.FLAT);
		}
		if (state.mode() == VertexFormat.Mode.QUADS) fillQuadFrames(out, state, source);
		return slice;
	}

	/** Each quad's sprite middle and tangent. Other primitives keep the stand-ins above. */
	private static void fillQuadFrames(ByteBuffer out, MeshData.DrawState state, ByteBuffer source) {
		VertexFormat format = state.format();
		List<String> names = format.getElementAttributeNames();
		int positionIndex = names.indexOf("Position");
		int uvIndex = names.indexOf("UV0");
		int normalIndex = names.indexOf("Normal");
		if (positionIndex < 0 || uvIndex < 0) return;
		VertexFormatElement position = format.getElements().get(positionIndex);
		VertexFormatElement uv = format.getElements().get(uvIndex);
		if (position.type() != VertexFormatElement.Type.FLOAT || position.count() < 3
				|| uv.type() != VertexFormatElement.Type.FLOAT || uv.count() < 2) return;
		VertexFormatElement normal = normalIndex < 0 ? null : format.getElements().get(normalIndex);
		if (normal != null && (normal.type() != VertexFormatElement.Type.BYTE || normal.count() < 3)) normal = null;
		int stride = format.getVertexSize();
		ByteBuffer in = source.duplicate().order(java.nio.ByteOrder.nativeOrder());
		int base = in.position();
		if ((long) state.vertexCount() * stride > in.remaining()) return;
		int p = format.getOffset(position);
		int t = format.getOffset(uv);
		int n = normal == null ? -1 : format.getOffset(normal);
		float[] xyz = new float[12];
		float[] uvs = new float[8];
		float[] face = new float[3];
		for (int first = 0; first + 3 < state.vertexCount(); first += 4) {
			float midU = 0.0F;
			float midV = 0.0F;
			for (int corner = 0; corner < 4; corner++) {
				int at = base + (first + corner) * stride;
				for (int axis = 0; axis < 3; axis++) xyz[corner * 3 + axis] = in.getFloat(at + p + axis * 4);
				float u = in.getFloat(at + t);
				float v = in.getFloat(at + t + 4);
				uvs[corner * 2] = u;
				uvs[corner * 2 + 1] = v;
				midU += u * 0.25F;
				midV += v * 0.25F;
			}
			boolean hasFace = n < 0 && EntityFrame.faceNormal(face, xyz[0], xyz[1], xyz[2], xyz[3], xyz[4], xyz[5],
					xyz[6], xyz[7], xyz[8], xyz[9], xyz[10], xyz[11]);
			for (int corner = 0; corner < 4; corner++) {
				int at = base + (first + corner) * stride;
				float nx = n >= 0 ? Math.max(-1.0F, in.get(at + n) / 127.0F) : hasFace ? face[0] : 0.0F;
				float ny = n >= 0 ? Math.max(-1.0F, in.get(at + n + 1) / 127.0F) : hasFace ? face[1] : 0.0F;
				float nz = n >= 0 ? Math.max(-1.0F, in.get(at + n + 2) / 127.0F) : hasFace ? face[2] : 1.0F;
				int tangent = EntityFrame.tangent(nx, ny, nz, n >= 0,
						xyz[0], xyz[1], xyz[2], uvs[0], uvs[1],
						xyz[3], xyz[4], xyz[5], uvs[2], uvs[3],
						xyz[6], xyz[7], xyz[8], uvs[4], uvs[5]);
				int dst = (first + corner) * PackPipeline.Derived.STRIDE;
				out.putFloat(dst + PackPipeline.Derived.MID_TEX, midU).putFloat(dst + PackPipeline.Derived.MID_TEX + 4, midV);
				out.putInt(dst + PackPipeline.Derived.TANGENT, tangent);
			}
		}
	}

	boolean derived() {
		return this.derived != null;
	}

	void draw(VkCommandBuffer cmd, MemoryStack stack) {
		if (this.derived != null) {
			vkCmdBindVertexBuffers(cmd, 0, stack.longs(this.vertexBuffer, this.constants.buffer(), this.derived.buffer()),
					stack.longs(this.vertexOffset, this.constants.offset(), this.derived.offset()));
		} else {
			vkCmdBindVertexBuffers(cmd, 0, stack.longs(this.vertexBuffer, this.constants.buffer()),
					stack.longs(this.vertexOffset, this.constants.offset()));
		}
		if (this.indexBuffer == 0L) {
			vkCmdDraw(cmd, this.count, 1, 0, 0);
		} else {
			vkCmdBindIndexBuffer(cmd, this.indexBuffer, this.indexOffset, this.indexType);
			vkCmdDrawIndexed(cmd, this.count, 1, 0, 0, 0);
		}
	}
}
