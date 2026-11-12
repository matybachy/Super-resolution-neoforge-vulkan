package dev.opalcompat.vk;

import java.util.concurrent.locks.ReentrantLock;

import static org.lwjgl.vulkan.VK10.vkDeviceWaitIdle;

/**
 * Vulkan wants a queue used by one thread at a time, and a wait for the whole device holds every queue. VulkanMod
 * Reforged submits from its render thread, and to its transfer queue under that queue's monitor. Far terrain is copied
 * from Distant Horizons' threads as well ({@code dh/LodTransfer}), so its submissions and every wait for the device take
 * this lock: VulkanMod's own through {@code VulkanIdleMixin}, this mod's through {@link #deviceWaitIdle}.
 */
public final class QueueLock {

	public static final ReentrantLock LOCK = new ReentrantLock();

	private static volatile boolean stopped;

	private QueueLock() {
	}

	public static void deviceWaitIdle() {
		LOCK.lock();
		try {
			vkDeviceWaitIdle(Vk.device());
		} finally {
			LOCK.unlock();
		}
	}

	/** Vulkan is being torn down: nothing more is submitted from other threads. */
	public static void stop() {
		LOCK.lock();
		try {
			stopped = true;
		} finally {
			LOCK.unlock();
		}
	}

	public static boolean stopped() {
		return stopped;
	}
}
