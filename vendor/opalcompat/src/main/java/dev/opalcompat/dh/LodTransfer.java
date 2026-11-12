package dev.opalcompat.dh;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.opalcompat.OpalCompat;
import dev.opalcompat.vk.QueueLock;
import dev.opalcompat.vk.Vk;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.memory.Buffer;
import net.vulkanmod.vulkan.queue.Queue;
import net.vulkanmod.vulkan.queue.TransferQueue;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSubmitInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT;
import static org.lwjgl.util.vma.Vma.vmaCreateBuffer;
import static org.lwjgl.util.vma.Vma.vmaDestroyBuffer;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Far terrain to the device from Distant Horizons' own threads.
 * <p>
 * DH builds a section's mesh on one of its threads, hands the upload to its render thread's task queue and waits there
 * ({@code LodRenderSection}: {@code uploadToGpuAsync(...).join()}). That queue runs once a frame, so every section waits for
 * a frame, and its thread waits with it. DH has one pool of threads for loading, files, world generation and updates
 * ({@code PriorityTaskPicker}: {@code occupiedThreadsRef < numberOfThreads}), and its loader, with the highest priority
 * ({@code ThreadPoolUtil}: "Render Loader", 6), takes most of the pool and then waits in it. How fast far terrain came
 * followed the frame rate, and with a shader pack that is a fraction of a frame rate with no pack on OpenGL.
 * <p>
 * On Vulkan the render thread is not needed for this. DH's two upload tasks run on the thread that queues them
 * ({@code DhRenderTasksMixin}): the vertices go into a staging buffer and through VulkanMod's transfer queue, and the
 * thread waits for that copy alone. DH lists the section once it is on the device, as before. Where the transfer queue
 * is the graphics queue, or something here fails, the uploads go back to the render thread ({@link LodUploads}).
 */
public final class LodTransfer {

	private static final String SETUP = "LodBufferContainer Setup";
	private static final String VERTICES = "LodBufferContainer VBO Upload";
	private static final String INDICES = "LodBufferContainer IBO Upload";

	/** Staging kept between copies: about one for each of DH's threads at most. */
	private static final int KEPT_STAGING = 16;
	private static final int STAGING_BYTES = 4 << 20;

	private static final ArrayDeque<Slot> SLOTS = new ArrayDeque<>();
	private static final ConcurrentLinkedQueue<Staging> STAGING = new ConcurrentLinkedQueue<>();
	private static final AtomicInteger KEPT = new AtomicInteger();

	/** 0 until decided, then 1 for on, -1 for off. Written last, after what it guards. */
	private static volatile int state;
	private static int graphicsFamily;
	private static int transferFamily;
	private static long pool;

	private record Slot(VkCommandBuffer cmd, long fence) {
	}

	private record Staging(long buffer, long allocation, long mapped, int capacity) {
		void destroy() {
			vmaDestroyBuffer(Vulkan.getAllocator(), this.buffer, this.allocation);
		}
	}

	private LodTransfer() {
	}

	/** Whether DH's task runs where it is queued instead of waiting for the render thread. */
	public static boolean takes(String task) {
		return (VERTICES.equals(task) || SETUP.equals(task) || INDICES.equals(task))
				&& !RenderSystem.isOnRenderThread() && on();
	}

	static boolean on() {
		int decided = state;
		if (decided == 0) decided = decide();
		return decided > 0 && !QueueLock.stopped();
	}

	/** Shared by the graphics and the transfer family while copies go through the second: no handover between them. */
	static void share(VkBufferCreateInfo info, MemoryStack stack) {
		if (on()) {
			info.sharingMode(VK_SHARING_MODE_CONCURRENT).pQueueFamilyIndices(stack.ints(graphicsFamily, transferFamily));
		} else {
			info.sharingMode(VK_SHARING_MODE_EXCLUSIVE);
		}
	}

	private static synchronized int decide() {
		if (state != 0) return state;
		int decided = -1;
		try {
			Queue.QueueFamilyIndices families = Queue.getQueueFamilies();
			VkQueue transfer = DeviceManager.getTransferQueue().queue();
			VkQueue graphics = DeviceManager.getGraphicsQueue().queue();
			if (families.transferFamily == families.graphicsFamily || transfer.address() == graphics.address()) {
				OpalCompat.LOGGER.info("[dh] far terrain is uploaded on the render thread: the transfer queue is the graphics queue");
			} else {
				graphicsFamily = families.graphicsFamily;
				transferFamily = families.transferFamily;
				pool = createPool(transferFamily);
				decided = 1;
				OpalCompat.LOGGER.info("[dh] far terrain goes to the device from Distant Horizons' threads, through the transfer queue (family {})",
						transferFamily);
			}
		} catch (RuntimeException | LinkageError e) {
			OpalCompat.LOGGER.error("[dh] the transfer queue cannot take far terrain; it is uploaded on the render thread", e);
		}
		state = decided;
		return decided;
	}

	private static void turnOff(Throwable why) {
		if (state > 0) {
			state = -1;
			OpalCompat.LOGGER.error("[dh] copying far terrain from Distant Horizons' threads failed; it is uploaded on the render thread from now on", why);
		}
	}

	/** On one of DH's threads: the vertices are on the device when this returns. */
	static void copy(Buffer to, int size, ByteBuffer data) {
		if (!on()) throw new IllegalStateException("The transfer queue no longer takes far terrain");
		try {
			Staging staging = takeStaging(size);
			MemoryUtil.memCopy(MemoryUtil.memAddress(data), staging.mapped(), size);
			Slot slot = submit(staging, to, size);
			// Only this copy is waited for, never a frame.
			int waited = vkWaitForFences(Vk.device(), slot.fence(), true, -1L);
			// Not done for sure: neither the slot nor the staging is used again.
			Vk.check(waited, "vkWaitForFences (far terrain)");
			QueueLock.LOCK.lock();
			try {
				vkResetFences(Vk.device(), slot.fence());
				vkResetCommandBuffer(slot.cmd(), 0);
				SLOTS.push(slot);
			} finally {
				QueueLock.LOCK.unlock();
			}
			giveBack(staging);
			LodUploads.counted(size, true);
		} catch (RuntimeException | Error e) {
			turnOff(e);
			throw e;
		}
	}

	private static Slot submit(Staging staging, Buffer to, int size) {
		QueueLock.LOCK.lock();
		try {
			if (QueueLock.stopped()) throw new IllegalStateException("Vulkan is shutting down");
			Slot slot = takeSlot();
			try (MemoryStack stack = MemoryStack.stackPush()) {
				VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack).sType$Default()
						.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
				Vk.check(vkBeginCommandBuffer(slot.cmd(), begin), "vkBeginCommandBuffer (far terrain)");
				vkCmdCopyBuffer(slot.cmd(), staging.buffer(), to.getId(), VkBufferCopy.calloc(1, stack).size(size));
				Vk.check(vkEndCommandBuffer(slot.cmd()), "vkEndCommandBuffer (far terrain)");
				VkSubmitInfo info = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(slot.cmd()));
				TransferQueue queue = DeviceManager.getTransferQueue();
				int result;
				// The monitor VulkanMod's own submissions to this queue hold.
				synchronized (queue) {
					result = vkQueueSubmit(queue.queue(), info, slot.fence());
				}
				Vk.check(result, "vkQueueSubmit (far terrain)");
				return slot;
			} catch (RuntimeException e) {
				// Nothing was submitted: both are free again.
				vkResetCommandBuffer(slot.cmd(), 0);
				SLOTS.push(slot);
				giveBack(staging);
				throw e;
			}
		} finally {
			QueueLock.LOCK.unlock();
		}
	}

	/** Under {@link QueueLock#LOCK}, which also stands for the command pool's. */
	private static Slot takeSlot() {
		Slot slot = SLOTS.poll();
		if (slot != null) return slot;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkCommandBufferAllocateInfo allocate = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
					.commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
			PointerBuffer handle = stack.mallocPointer(1);
			Vk.check(vkAllocateCommandBuffers(Vk.device(), allocate, handle), "vkAllocateCommandBuffers (far terrain)");
			VkCommandBuffer cmd = new VkCommandBuffer(handle.get(0), Vk.device());
			LongBuffer fence = stack.mallocLong(1);
			Vk.check(vkCreateFence(Vk.device(), VkFenceCreateInfo.calloc(stack).sType$Default(), null, fence),
					"vkCreateFence (far terrain)");
			return new Slot(cmd, fence.get(0));
		}
	}

	private static long createPool(int family) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkCommandPoolCreateInfo info = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
					.queueFamilyIndex(family).flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);
			LongBuffer handle = stack.mallocLong(1);
			Vk.check(vkCreateCommandPool(Vk.device(), info, null, handle), "vkCreateCommandPool (far terrain)");
			return handle.get(0);
		}
	}

	private static Staging takeStaging(int size) {
		Staging staging = STAGING.poll();
		if (staging != null) {
			KEPT.decrementAndGet();
			if (staging.capacity() >= size) return staging;
			staging.destroy();
		}
		return createStaging(Math.max(size, STAGING_BYTES));
	}

	private static void giveBack(Staging staging) {
		if (KEPT.incrementAndGet() <= KEPT_STAGING) {
			STAGING.add(staging);
		} else {
			KEPT.decrementAndGet();
			staging.destroy();
		}
	}

	private static Staging createStaging(int capacity) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack).sType$Default().size(capacity)
					.usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
			VmaAllocationCreateInfo allocation = VmaAllocationCreateInfo.calloc(stack)
					.requiredFlags(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
					.flags(VMA_ALLOCATION_CREATE_MAPPED_BIT);
			VmaAllocationInfo out = VmaAllocationInfo.calloc(stack);
			LongBuffer buffer = stack.mallocLong(1);
			PointerBuffer memory = stack.mallocPointer(1);
			Vk.check(vmaCreateBuffer(Vulkan.getAllocator(), info, allocation, buffer, memory, out), "vmaCreateBuffer (far terrain staging)");
			if (out.pMappedData() == 0L) {
				vmaDestroyBuffer(Vulkan.getAllocator(), buffer.get(0), memory.get(0));
				throw new IllegalStateException("Staging for far terrain is not mapped");
			}
			return new Staging(buffer.get(0), memory.get(0), out.pMappedData(), capacity);
		}
	}
}
