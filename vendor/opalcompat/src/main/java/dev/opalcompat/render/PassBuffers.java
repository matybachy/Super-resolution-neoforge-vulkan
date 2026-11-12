package dev.opalcompat.render;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderBuffers;

/**
 * Buffers for what a pack draws outside the game's own walk: the hand, and entities as the light sees them. A set like the
 * game's, with its own buffer for each glint and sheet type: an enchanted item writes to itself and to its glint at once, and
 * a source with one shared buffer ends the first when asked for the second, which fails with "Not building!". Apart from the
 * game's because these run in the middle of its frame. Iris's shadow pass keeps a {@code RenderBuffers} of its own too.
 */
final class PassBuffers {

	private static RenderBuffers buffers;

	private PassBuffers() {
	}

	static MultiBufferSource.BufferSource source() {
		if (buffers == null) buffers = new RenderBuffers(1);
		return buffers.bufferSource();
	}
}
