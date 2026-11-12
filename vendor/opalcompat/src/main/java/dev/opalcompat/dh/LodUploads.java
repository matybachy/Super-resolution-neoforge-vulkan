package dev.opalcompat.dh;

import dev.opalcompat.OpalCompat;
import net.vulkanmod.render.chunk.buffer.UploadManager;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.memory.Buffer;
import net.vulkanmod.vulkan.memory.StagingBuffer;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Far terrain uploaded on the render thread, and how much of a frame DH's render thread tasks may take.
 * <p>
 * Most uploads no longer come here: {@link LodTransfer} copies them from DH's own threads. What still does is what DH
 * queues from the render thread itself, and everything where the transfer queue cannot be used.
 * <p>
 * DH allows its render thread tasks half of a frame at the frame limit, 1.5 ms at an unlimited 260, and stops once the
 * next task might not fit. VulkanMod's {@code VertexBuffer.copyToVertexBuffer} submits to the transfer queue for every
 * buffer, so that came to about one task a frame. Here one frame's copies go in one submission to the graphics queue,
 * ahead of the frame that draws them, the way VulkanMod's own chunks do ({@code UploadManager}), and the tasks have a
 * budget of their own.
 */
public final class LodUploads {

	/** A few milliseconds of a frame; only spent while there is something waiting. */
	private static final long BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(5);
	private static final long LOG_EVERY_NANOS = TimeUnit.SECONDS.toNanos(10);

	private static final LongAdder BUFFERS = new LongAdder();
	private static final LongAdder BYTES = new LongAdder();
	private static final LongAdder FROM_THREADS = new LongAdder();

	private static boolean batching;
	private static boolean recorded;
	private static long loggedAt = System.nanoTime();

	private LodUploads() {
	}

	/** Whether DH's tasks run now. It asks from its shadow stage as well, and a frame has one budget. */
	public static boolean tasksRunHere() {
		return !VkDhBridge.shadowPass();
	}

	public static long budgetNanos() {
		return BUDGET_NANOS;
	}

	public static void begin() {
		batching = true;
	}

	/** Before anything of this frame's is submitted, so what was just uploaded can be drawn in it. */
	public static void end() {
		batching = false;
		if (recorded) {
			recorded = false;
			UploadManager.INSTANCE.submitUploads();
		}
		long now = System.nanoTime();
		if (now - loggedAt < LOG_EVERY_NANOS) return;
		long buffers = BUFFERS.sumThenReset();
		long bytes = BYTES.sumThenReset();
		long fromThreads = FROM_THREADS.sumThenReset();
		if (buffers > 0) {
			OpalCompat.LOGGER.info("[dh] {} LOD buffers, {} MB uploaded in {} s, {} of them from Distant Horizons' threads",
					buffers, bytes >> 20, TimeUnit.NANOSECONDS.toSeconds(now - loggedAt), fromThreads);
		}
		loggedAt = now;
	}

	static void counted(int size, boolean fromThreads) {
		BUFFERS.increment();
		BYTES.add(size);
		if (fromThreads) FROM_THREADS.increment();
	}

	/** On the render thread. */
	static void copy(Buffer to, int size, ByteBuffer data) {
		counted(size, false);
		if (batching) {
			UploadManager.INSTANCE.recordUpload(to, 0, size, data);
			recorded = true;
		} else {
			// DH's timer runs them outside a frame when no frame asks, as on a screen with no level. That frame's
			// staging buffer is about to be reset, so the copy is finished here.
			StagingBuffer staging = Vulkan.getStagingBuffer();
			staging.copyBuffer(size, data);
			DeviceManager.getTransferQueue().uploadBufferImmediate(staging.getId(), staging.getOffset(), to.getId(), 0, size);
		}
	}
}
