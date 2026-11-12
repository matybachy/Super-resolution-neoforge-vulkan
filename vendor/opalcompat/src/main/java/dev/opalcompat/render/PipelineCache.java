package dev.opalcompat.render;

import dev.opalcompat.OpalCompat;
import dev.opalcompat.vk.Vk;
import net.neoforged.fml.loading.FMLPaths;
import net.vulkanmod.vulkan.device.DeviceManager;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPipelineCacheCreateInfo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.lwjgl.vulkan.VK10.*;

/**
 * What the driver has built of the pack's pipelines, kept on disk between runs. A pipeline of a large pack takes
 * the driver up to a second and a frame waits for it, so without this the game stops for a moment at the first mob
 * or the first broken block of every session. Only a new pack or new pack settings pay for it now, and only once.
 * It lives as long as the device; the driver itself refuses data from another driver or GPU, and the header is
 * checked here first because a driver is not obliged to survive a bad file.
 */
final class PipelineCache {
	private static final int HEADER_SIZE = 32;
	private static final long LARGEST_FILE = 256L * 1024 * 1024;
	/** Saved once no new pipeline has come for this long, so a burst of them costs one write. */
	private static final long QUIET_NANOS = 10_000_000_000L;

	private static boolean opened;
	private static long handle = VK_NULL_HANDLE;
	private static boolean unsaved;
	private static long lastCreated;
	private static int built;
	private static long buildNanos;

	private PipelineCache() {
	}

	private static Path file() {
		return FMLPaths.CONFIGDIR.get().resolve("opalcompat_pipeline_cache.bin");
	}

	static synchronized long handle() {
		if (!opened) {
			opened = true;
			handle = open();
		}
		return handle;
	}

	/** A pipeline was built: the cache has something the file has not. */
	static synchronized void created(long startedNanos) {
		unsaved = true;
		lastCreated = System.nanoTime();
		built++;
		buildNanos += lastCreated - startedNanos;
	}

	/** Once a frame. The writing itself is off the render thread. */
	static synchronized void saveWhenQuiet() {
		if (!unsaved || handle == VK_NULL_HANDLE || System.nanoTime() - lastCreated < QUIET_NANOS) return;
		unsaved = false;
		OpalCompat.LOGGER.info("[pack] {} pipelines built in {} ms", built, buildNanos / 1_000_000L);
		built = 0;
		buildNanos = 0L;
		byte[] data = read();
		if (data == null) return;
		Thread writer = new Thread(() -> write(data), "OpalCompat pipeline cache");
		writer.setDaemon(true);
		writer.start();
	}

	private static long open() {
		ByteBuffer initial = null;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			byte[] stored = stored();
			if (stored != null) initial = MemoryUtil.memAlloc(stored.length).put(stored).flip();
			VkPipelineCacheCreateInfo info = VkPipelineCacheCreateInfo.calloc(stack).sType$Default().pInitialData(initial);
			LongBuffer created = stack.mallocLong(1);
			int result = vkCreatePipelineCache(Vk.device(), info, null, created);
			if (result != VK_SUCCESS) {
				OpalCompat.LOGGER.warn("No pipeline cache: VkResult {}", result);
				return VK_NULL_HANDLE;
			}
			if (stored != null) OpalCompat.LOGGER.info("Pipeline cache of {} kB read", stored.length / 1024);
			return created.get(0);
		} finally {
			if (initial != null) MemoryUtil.memFree(initial);
		}
	}

	/** What the file holds, if it was written by this driver on this GPU. */
	private static byte[] stored() {
		Path file = file();
		try {
			if (!Files.isRegularFile(file) || Files.size(file) > LARGEST_FILE) return null;
			byte[] bytes = Files.readAllBytes(file);
			return matches(bytes) ? bytes : null;
		} catch (IOException e) {
			OpalCompat.LOGGER.warn("Could not read the pipeline cache", e);
			return null;
		}
	}

	private static boolean matches(byte[] bytes) {
		if (bytes.length < HEADER_SIZE) return false;
		ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		VkPhysicalDeviceProperties properties = DeviceManager.deviceProperties;
		if (header.getInt(0) < HEADER_SIZE || header.getInt(4) != VK_PIPELINE_CACHE_HEADER_VERSION_ONE
				|| header.getInt(8) != properties.vendorID() || header.getInt(12) != properties.deviceID()) {
			return false;
		}
		ByteBuffer uuid = properties.pipelineCacheUUID();
		for (int i = 0; i < VK_UUID_SIZE; i++) {
			if (bytes[16 + i] != uuid.get(uuid.position() + i)) return false;
		}
		return true;
	}

	private static byte[] read() {
		ByteBuffer data = null;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer size = stack.mallocPointer(1);
			if (vkGetPipelineCacheData(Vk.device(), handle, size, null) != VK_SUCCESS) return null;
			data = MemoryUtil.memAlloc((int) size.get(0));
			if (vkGetPipelineCacheData(Vk.device(), handle, size, data) != VK_SUCCESS) return null;
			byte[] bytes = new byte[(int) size.get(0)];
			data.get(bytes);
			return bytes;
		} finally {
			if (data != null) MemoryUtil.memFree(data);
		}
	}

	/** To the side first: a game closed half way through must not leave half a file for the driver. */
	private static void write(byte[] data) {
		Path file = file();
		Path part = file.resolveSibling(file.getFileName() + ".part");
		try {
			Files.write(part, data);
			Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			OpalCompat.LOGGER.warn("Could not save the pipeline cache", e);
		}
	}
}
