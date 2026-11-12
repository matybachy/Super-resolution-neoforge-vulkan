package dev.opalcompat.dh;

import net.vulkanmod.render.chunk.buffer.UploadManager;
import net.vulkanmod.vulkan.memory.Buffer;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT;

/**
 * The one quad index buffer every {@link LodBuffer} is drawn with, on the device: a far view reads millions of these
 * a frame, too many to fetch over the bus each time. Grows; what it replaces lives on for the frames using it.
 */
public final class LodIndices {

	/** Enough for nearly every section from the start, about 6 MB. */
	private static final int FIRST_QUADS = 256 * 1024;
	private static final int BYTES_PER_QUAD = 24;

	private static Quads buffer;
	private static int quads;
	/** From DH's threads as well, ahead of the upload they belong to being listed. */
	private static final AtomicInteger WANTED = new AtomicInteger();

	private LodIndices() {
	}

	static void need(int vertices) {
		WANTED.accumulateAndGet(vertices / 4, Math::max);
	}

	/** The buffer, for as many quads as any upload so far holds. Its copy is submitted ahead of the frame asking. */
	public static long handle() {
		if (buffer == null || quads < WANTED.get()) grow();
		return buffer.getId();
	}

	private static void grow() {
		int size = Math.max(FIRST_QUADS, Math.max(WANTED.get(), quads * 2));
		int length = size * BYTES_PER_QUAD;
		ByteBuffer bytes = MemoryUtil.memAlloc(length).order(ByteOrder.LITTLE_ENDIAN);
		try {
			for (int quad = 0; quad < size; quad++) {
				int at = quad * 4;
				bytes.putInt(at).putInt(at + 1).putInt(at + 2).putInt(at + 2).putInt(at + 3).putInt(at);
			}
			bytes.flip();
			Quads next = new Quads(length);
			UploadManager.INSTANCE.recordUpload(next, 0, length, bytes);
			UploadManager.INSTANCE.submitUploads();
			if (buffer != null) buffer.freeBuffer();
			buffer = next;
			quads = size;
		} finally {
			MemoryUtil.memFree(bytes);
		}
	}

	private static final class Quads extends Buffer {
		Quads(int size) {
			super(VK_BUFFER_USAGE_INDEX_BUFFER_BIT, MemoryTypes.GPU_MEM);
			createBuffer(size);
		}
	}
}
