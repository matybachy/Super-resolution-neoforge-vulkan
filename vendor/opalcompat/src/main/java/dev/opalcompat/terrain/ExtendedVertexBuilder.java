package dev.opalcompat.terrain;

import dev.vitrail.render.BlockStateIds;
import net.minecraft.world.level.block.state.BlockState;
import net.vulkanmod.render.vertex.VertexBuilder;
import org.lwjgl.system.MemoryUtil;

/**
 * Writes VulkanMod Reforged's compressed terrain vertex, byte for byte as its
 * {@code CompressedVertexBuilder} does, and fills the elements {@link TerrainFormat} appends.
 * <p>
 * Those need the whole quad: VulkanMod's block and liquid renderers write the four corners of a quad
 * one after another, so the fourth corner completes the quad and all four get the face normal, the
 * tangent, the middle of the sprite and the offset to the middle of the block.
 */
public final class ExtendedVertexBuilder implements VertexBuilder {

	private static final float POS_CONV_MUL = VertexBuilder.CompressedVertexBuilder.POS_CONV_MUL;
	private static final float POS_OFFSET_CONV = VertexBuilder.CompressedVertexBuilder.POS_OFFSET_CONV;
	private static final float UV_CONV_MUL = VertexBuilder.CompressedVertexBuilder.UV_CONV_MUL;

	private final long[] pointers = new long[4];
	private final float[] xs = new float[4];
	private final float[] ys = new float[4];
	private final float[] zs = new float[4];
	private final float[] us = new float[4];
	private final float[] vs = new float[4];
	private int corner;
	private int blockId = -1;
	private int emission;

	/** The block the following quads belong to, from {@code TerrainBufferBuilder.setBlockAttributes}. */
	public void block(BlockState state) {
		this.blockId = BlockStateIds.id(state);
		this.emission = state.getLightEmission();
	}

	@Override
	public void vertex(long ptr, float x, float y, float z, int color, float u, float v, int light, int packedNormal) {
		MemoryUtil.memPutShort(ptr, (short) (x * POS_CONV_MUL + POS_OFFSET_CONV));
		MemoryUtil.memPutShort(ptr + 2, (short) (y * POS_CONV_MUL + POS_OFFSET_CONV));
		MemoryUtil.memPutShort(ptr + 4, (short) (z * POS_CONV_MUL + POS_OFFSET_CONV));
		MemoryUtil.memPutShort(ptr + 6, (short) (((light >>> 8) & 0xFF00) | (light & 0xFF)));
		MemoryUtil.memPutInt(ptr + 8, color);
		MemoryUtil.memPutShort(ptr + 12, (short) (u * UV_CONV_MUL));
		MemoryUtil.memPutShort(ptr + 14, (short) (v * UV_CONV_MUL));
		MemoryUtil.memPutInt(ptr + 16, 0);
		MemoryUtil.memPutInt(ptr + TerrainFormat.OFFSET_BLOCK_ID, this.blockId);

		this.pointers[this.corner] = ptr;
		this.xs[this.corner] = x;
		this.ys[this.corner] = y;
		this.zs[this.corner] = z;
		this.us[this.corner] = u;
		this.vs[this.corner] = v;
		if (++this.corner == 4) {
			this.corner = 0;
			finishQuad();
		}
	}

	private void finishQuad() {
		// Face normal from the two diagonals, which also holds for a quad that is not quite planar.
		float ax = this.xs[2] - this.xs[0], ay = this.ys[2] - this.ys[0], az = this.zs[2] - this.zs[0];
		float bx = this.xs[3] - this.xs[1], by = this.ys[3] - this.ys[1], bz = this.zs[3] - this.zs[1];
		float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
		float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (length > 1.0e-8F) {
			nx /= length;
			ny /= length;
			nz /= length;
		} else {
			nx = 0.0F;
			ny = 1.0F;
			nz = 0.0F;
		}

		// Tangent along increasing u, from the first triangle (the same construction Iris uses).
		float e1x = this.xs[1] - this.xs[0], e1y = this.ys[1] - this.ys[0], e1z = this.zs[1] - this.zs[0];
		float e2x = this.xs[2] - this.xs[0], e2y = this.ys[2] - this.ys[0], e2z = this.zs[2] - this.zs[0];
		float du1 = this.us[1] - this.us[0], dv1 = this.vs[1] - this.vs[0];
		float du2 = this.us[2] - this.us[0], dv2 = this.vs[2] - this.vs[0];
		float det = du1 * dv2 - du2 * dv1;
		float r = Math.abs(det) > 1.0e-12F ? 1.0F / det : 0.0F;
		float tx = (e1x * dv2 - e2x * dv1) * r, ty = (e1y * dv2 - e2y * dv1) * r, tz = (e1z * dv2 - e2z * dv1) * r;
		float bxx = (e2x * du1 - e1x * du2) * r, byy = (e2y * du1 - e1y * du2) * r, bzz = (e2z * du1 - e1z * du2) * r;
		// Gram-Schmidt against the normal.
		float dot = tx * nx + ty * ny + tz * nz;
		tx -= nx * dot;
		ty -= ny * dot;
		tz -= nz * dot;
		float tl = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
		if (tl > 1.0e-8F) {
			tx /= tl;
			ty /= tl;
			tz /= tl;
		} else {
			tx = 1.0F;
			ty = 0.0F;
			tz = 0.0F;
		}
		float handed = ((ny * tz - nz * ty) * bxx + (nz * tx - nx * tz) * byy + (nx * ty - ny * tx) * bzz) < 0.0F ? -1.0F : 1.0F;

		float midU = (this.us[0] + this.us[1] + this.us[2] + this.us[3]) * 0.25F;
		float midV = (this.vs[0] + this.vs[1] + this.vs[2] + this.vs[3]) * 0.25F;

		// The block the face belongs to: the quad's centre nudged inwards, then its cell.
		float cx = (this.xs[0] + this.xs[1] + this.xs[2] + this.xs[3]) * 0.25F - nx * 0.01F;
		float cy = (this.ys[0] + this.ys[1] + this.ys[2] + this.ys[3]) * 0.25F - ny * 0.01F;
		float cz = (this.zs[0] + this.zs[1] + this.zs[2] + this.zs[3]) * 0.25F - nz * 0.01F;
		float blockX = (float) Math.floor(cx) + 0.5F;
		float blockY = (float) Math.floor(cy) + 0.5F;
		float blockZ = (float) Math.floor(cz) + 0.5F;

		int normal = pack(nx, ny, nz, 0.0F);
		int tangent = pack(tx, ty, tz, handed);
		int midTex = ((int) (midU * UV_CONV_MUL) & 0xFFFF) | (((int) (midV * UV_CONV_MUL) & 0xFFFF) << 16);
		for (int i = 0; i < 4; i++) {
			long ptr = this.pointers[i];
			MemoryUtil.memPutInt(ptr + TerrainFormat.OFFSET_MID_TEX, midTex);
			MemoryUtil.memPutInt(ptr + TerrainFormat.OFFSET_NORMAL, normal);
			MemoryUtil.memPutInt(ptr + TerrainFormat.OFFSET_TANGENT, tangent);
			MemoryUtil.memPutByte(ptr + TerrainFormat.OFFSET_MID_BLOCK, signedByte((blockX - this.xs[i]) * 64.0F));
			MemoryUtil.memPutByte(ptr + TerrainFormat.OFFSET_MID_BLOCK + 1, signedByte((blockY - this.ys[i]) * 64.0F));
			MemoryUtil.memPutByte(ptr + TerrainFormat.OFFSET_MID_BLOCK + 2, signedByte((blockZ - this.zs[i]) * 64.0F));
			MemoryUtil.memPutByte(ptr + TerrainFormat.OFFSET_MID_BLOCK + 3, (byte) this.emission);
		}
	}

	private static int pack(float x, float y, float z, float w) {
		return (snorm(x) & 0xFF) | ((snorm(y) & 0xFF) << 8) | ((snorm(z) & 0xFF) << 16) | ((snorm(w) & 0xFF) << 24);
	}

	private static int snorm(float value) {
		return Math.round(Math.max(-1.0F, Math.min(1.0F, value)) * 127.0F);
	}

	private static byte signedByte(float value) {
		return (byte) Math.max(-128, Math.min(127, Math.round(value)));
	}

	@Override
	public int getStride() {
		return TerrainFormat.STRIDE;
	}
}
