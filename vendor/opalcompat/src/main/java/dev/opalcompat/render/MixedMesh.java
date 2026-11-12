package dev.opalcompat.render;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits one batch into consecutive runs of entity and block entity triangles, without reordering them:
 * the game's shared buffers take both, and the two have programs of their own.
 */
final class MixedMesh {
	private static final int[] QUAD = {0, 1, 2, 0, 2, 3};

	record Run(boolean blockEntity, ByteBuffer indices, int count) {
	}

	private MixedMesh() {
	}

	/** What the whole batch is: one of the two, or {@code null} if it holds both. */
	static DrawIds.Kind kindOf(DrawIds[] ids, int vertices) {
		if (ids == null) return DrawIds.Kind.NONE;
		DrawIds.Kind found = DrawIds.Kind.NONE;
		int count = Math.min(ids.length, vertices);
		for (int i = 0; i < count; i++) {
			DrawIds.Kind kind = ids[i].kind();
			if (kind == DrawIds.Kind.NONE || kind == found) continue;
			if (found != DrawIds.Kind.NONE) return null;
			found = kind;
		}
		return found;
	}

	/** Empty if the batch cannot be split, which its caller answers by drawing it whole. */
	static List<Run> split(MeshData mesh, DrawIds[] ids) {
		MeshData.DrawState state = mesh.drawState();
		if (ids == null || ids.length != state.vertexCount()) return List.of();
		if (state.mode() != VertexFormat.Mode.QUADS && state.mode() != VertexFormat.Mode.TRIANGLES) return List.of();
		ByteBuffer input = mesh.indexBuffer();
		int count;
		if (input == null) {
			if (state.mode() == VertexFormat.Mode.QUADS && state.vertexCount() % 4 != 0) return List.of();
			count = state.mode() == VertexFormat.Mode.QUADS ? Math.multiplyExact(state.vertexCount() / 4, 6) : state.vertexCount();
		} else {
			count = state.indexCount();
			input = input.duplicate().order(ByteOrder.nativeOrder());
			if (count < 0 || input.remaining() < (long) count * state.indexType().bytes) return List.of();
		}
		if (count == 0 || count % 3 != 0) return List.of();
		int[] all = new int[count];
		for (int i = 0; i < count; i++) {
			int index;
			if (input != null) {
				index = state.indexType() == VertexFormat.IndexType.SHORT ? Short.toUnsignedInt(input.getShort()) : input.getInt();
			} else if (state.mode() == VertexFormat.Mode.QUADS) {
				index = i / 6 * 4 + QUAD[i % 6];
			} else {
				index = i;
			}
			if (index < 0 || index >= ids.length) return List.of();
			all[i] = index;
		}
		List<Run> runs = new ArrayList<>();
		int start = 0;
		boolean current = ids[all[0]].kind() == DrawIds.Kind.BLOCK_ENTITY;
		for (int i = 3; i < count; i += 3) {
			boolean triangle = ids[all[i]].kind() == DrawIds.Kind.BLOCK_ENTITY;
			if (triangle != current) {
				runs.add(run(current, all, start, i));
				start = i;
				current = triangle;
			}
		}
		runs.add(run(current, all, start, count));
		return List.copyOf(runs);
	}

	private static Run run(boolean blockEntity, int[] indices, int start, int end) {
		ByteBuffer bytes = ByteBuffer.allocate(Math.multiplyExact(end - start, Integer.BYTES)).order(ByteOrder.nativeOrder());
		for (int i = start; i < end; i++) bytes.putInt(indices[i]);
		bytes.flip();
		return new Run(blockEntity, bytes, end - start);
	}
}
