package dev.matybachy.srvmr.ngx;

import dev.matybachy.srvmr.Srvmr;
import dev.matybachy.srvmr.SrvmrConfig;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * NGX for the instance and the device VulkanMod Reforged makes: the extensions it needs when each is created, then NGX
 * itself, then one DLSS feature at a time. Render thread.
 * <p>
 * Nothing of NVIDIA's is loaded or called where there is no NVIDIA driver (before the instance exists) or the GPU
 * VulkanMod Reforged picked is not NVIDIA's (before the device exists): a crash in native code cannot be caught, and on
 * another GPU DLSS would not run anyway. Nothing here may throw into VulkanMod Reforged's start: every failure is
 * caught, logged, and leaves VulkanMod Reforged's own setup as it was.
 */
public final class Ngx {

	/**
	 * This project's identifier for NGX. NVIDIA's SDK: "If your NVIDIA contact did not provide you an ID for this
	 * purpose, use ProjectDesc with NVSDK_NGX_ENGINE_TYPE_CUSTOM as the engine type". Made for this mod; not Super
	 * Resolution's.
	 */
	static final String PROJECT_ID = "7202a848-a92f-4f7c-9ef2-f7a997984d1b";

	private static final int NVIDIA = 0x10DE;

	/** What NGX asks for DLSS on NVIDIA's drivers, in case it cannot be asked. */
	private static final List<String> FALLBACK = List.of("VK_NVX_binary_import", "VK_NVX_image_view_handle");

	/** Feature flags: motion vectors at render resolution, not jittered; DLSS works out the exposure itself. */
	public static final int CREATE_FLAGS = (1 << 1) | (1 << 6);

	/** Which buffer device address the device was created with for NGX: none, the core one (KHR), or the EXT one. */
	public enum Address { NONE, CORE, EXT }

	private static boolean deviceReady;
	private static Address bufferDeviceAddress = Address.NONE;
	/** VulkanMod Reforged's own device extensions, for creating the device again without DLSS's if it fails with them. */
	private static Collection<String> vulkanModExtensions;
	private static int state;

	private Ngx() {
	}

	public static Address bufferDeviceAddress() {
		return bufferDeviceAddress;
	}

	/** VulkanMod Reforged's device extensions as they were, where DLSS added to them; null where it did not. */
	public static Collection<String> vulkanModExtensions() {
		return deviceReady ? vulkanModExtensions : null;
	}

	/** The device could not be created with DLSS's extensions: it is created without them, and DLSS stays off. */
	public static void deviceFailed() {
		deviceReady = false;
		bufferDeviceAddress = Address.NONE;
		state = -1;
	}

	/** An NVIDIA driver is installed: its API library is in the system folder, whichever GPU drives the screen. */
	private static boolean nvidiaDriver() {
		String root = System.getenv("SystemRoot");
		return root != null && Files.isRegularFile(Path.of(root, "System32", "nvapi64.dll"));
	}

	/**
	 * The instance extensions VulkanMod Reforged asks for, with DLSS's added where the loader has them. VulkanMod Reforged
	 * asks for nothing but what the window needs, and NGX checks its extensions by name.
	 */
	public static PointerBuffer withDlssInstanceExtensions(PointerBuffer extensions, MemoryStack stack) {
		try {
			// Whatever the setting: DLSS can be turned on from the settings page later, and then needs them.
			if (extensions == null || !nvidiaDriver() || !Natives.load()) return extensions;
			String[] asked = NgxBridge.instanceExtensions(PROJECT_ID, Natives.dataDirectory().toString(),
					Natives.directory().toString());
			if (asked == null) {
				Srvmr.LOGGER.info("[dlss] NGX could not list its instance extensions ({})",
						NgxBridge.describe(NgxBridge.lastResult()));
				return extensions;
			}
			Set<String> present = new HashSet<>();
			for (int i = extensions.position(); i < extensions.limit(); i++) present.add(extensions.getStringUTF8(i));
			Set<String> available = availableInstance();
			List<String> added = new ArrayList<>();
			List<String> missing = new ArrayList<>();
			for (String name : asked) {
				if (name == null || present.contains(name)) continue;
				if (available.contains(name)) added.add(name); else missing.add(name);
			}
			if (!missing.isEmpty()) Srvmr.LOGGER.info("[dlss] the Vulkan loader has no {}, which NGX lists", missing);
			if (added.isEmpty()) return extensions;
			PointerBuffer out = stack.mallocPointer(extensions.remaining() + added.size());
			for (int i = extensions.position(); i < extensions.limit(); i++) out.put(extensions.get(i));
			for (String name : added) out.put(stack.UTF8(name));
			out.flip();
			Srvmr.LOGGER.info("[dlss] instance extensions added for DLSS: {}", added);
			return out;
		} catch (Throwable e) {
			Srvmr.LOGGER.warn("[dlss] cannot add DLSS's instance extensions", e);
			return extensions;
		}
	}

	/**
	 * VulkanMod Reforged's device extensions with DLSS's added, where the GPU has them. Nothing is added where DLSS is
	 * off, where the GPU is not NVIDIA's, where the native bridge is not there, or where the GPU lacks one of them: then
	 * it is not an RTX card and DLSS would not run anyway.
	 */
	public static Collection<String> withDlssExtensions(Collection<String> extensions) {
		try {
			VkPhysicalDevice physical = DeviceManager.physicalDevice;
			if (physical == null) return extensions;
			VkInstance instance = physical.getInstance();
			int vendor = vendor(physical);
			if (vendor != NVIDIA) {
				Srvmr.LOGGER.info("[dlss] the GPU is not NVIDIA's (vendor 0x{}); DLSS is off", Integer.toHexString(vendor));
				return extensions;
			}
			if (!Natives.load()) return extensions;
			Set<String> available = availableDevice(physical);
			String[] asked = NgxBridge.deviceExtensions(PROJECT_ID, Natives.dataDirectory().toString(),
					Natives.directory().toString(), instance.address(), physical.address());
			List<String> wanted = asked != null ? Arrays.asList(asked) : FALLBACK;
			if (asked == null) {
				Srvmr.LOGGER.info("[dlss] NGX could not list its device extensions ({}); asking for {}",
						NgxBridge.describe(NgxBridge.lastResult()), FALLBACK);
			}
			List<String> missing = new ArrayList<>();
			for (String name : wanted) if (name == null || !available.contains(name)) missing.add(name);
			if (!missing.isEmpty()) {
				Srvmr.LOGGER.info("[dlss] the GPU has no {}; DLSS is off (it needs an NVIDIA RTX card)", missing);
				return extensions;
			}
			Set<String> out = new LinkedHashSet<>(extensions);
			out.addAll(wanted);
			// The core feature and the EXT one have structures of their own and cannot both be on.
			if (wanted.contains("VK_EXT_buffer_device_address")) bufferDeviceAddress = Address.EXT;
			else if (wanted.contains("VK_KHR_buffer_device_address")) bufferDeviceAddress = Address.CORE;
			vulkanModExtensions = new ArrayList<>(extensions);
			deviceReady = true;
			Srvmr.LOGGER.info("[dlss] device extensions added for DLSS: {}", wanted);
			return out;
		} catch (Throwable e) {
			Srvmr.LOGGER.warn("[dlss] cannot add DLSS's device extensions; DLSS is off", e);
			deviceReady = false;
			bufferDeviceAddress = Address.NONE;
			return extensions;
		}
	}

	private static int vendor(VkPhysicalDevice physical) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc(stack);
			VK10.vkGetPhysicalDeviceProperties(physical, properties);
			return properties.vendorID();
		}
	}

	/**
	 * The GPU's device extensions. On the heap: an RTX card has some three hundred of them, 260 bytes each, more than
	 * LWJGL's 64 KB thread stack holds; that overflow is an Error, and in VulkanMod Reforged's device creation it ended
	 * the game.
	 */
	private static Set<String> availableDevice(VkPhysicalDevice physical) {
		int count;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer counted = stack.mallocInt(1);
			VK10.vkEnumerateDeviceExtensionProperties(physical, (String) null, counted, null);
			count = counted.get(0);
		}
		VkExtensionProperties.Buffer properties = VkExtensionProperties.malloc(count);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VK10.vkEnumerateDeviceExtensionProperties(physical, (String) null, stack.ints(count), properties);
			Set<String> names = new HashSet<>();
			for (VkExtensionProperties property : properties) names.add(property.extensionNameString());
			return names;
		} finally {
			properties.free();
		}
	}

	private static Set<String> availableInstance() {
		int count;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer counted = stack.mallocInt(1);
			VK10.vkEnumerateInstanceExtensionProperties((String) null, counted, null);
			count = counted.get(0);
		}
		VkExtensionProperties.Buffer properties = VkExtensionProperties.malloc(count);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VK10.vkEnumerateInstanceExtensionProperties((String) null, stack.ints(count), properties);
			Set<String> names = new HashSet<>();
			for (VkExtensionProperties property : properties) names.add(property.extensionNameString());
			return names;
		} finally {
			properties.free();
		}
	}

	/** NGX up for VulkanMod Reforged's device, and DLSS there. Tried once; the log says why not. */
	public static boolean ready() {
		// Off in the settings is not a failure: it can be turned on again.
		if (!SrvmrConfig.get().enabled) return false;
		if (state != 0) return state > 0;
		state = -1;
		if (!deviceReady) {
			Srvmr.LOGGER.info("[dlss] the device was created without DLSS's extensions; DLSS is off");
			return false;
		}
		if (!Natives.load()) return false;
		try {
			VkDevice device = Vulkan.getVkDevice();
			VkPhysicalDevice physical = device.getPhysicalDevice();
			VkInstance instance = physical.getInstance();
			long getInstanceProcAddr = VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr");
			long getDeviceProcAddr = VK10.vkGetInstanceProcAddr(instance, "vkGetDeviceProcAddr");
			int result = NgxBridge.init(PROJECT_ID, Natives.dataDirectory().toString(), Natives.directory().toString(),
					instance.address(), physical.address(), device.address(), getInstanceProcAddr, getDeviceProcAddr);
			if (!NgxBridge.succeeded(result)) {
				Srvmr.LOGGER.warn("[dlss] NGX did not start: {}; DLSS is off. NGX's own log is in {}",
						NgxBridge.describe(result), Natives.dataDirectory());
				return false;
			}
			int[] availability = NgxBridge.availability();
			if (availability == null || availability[0] != 1) {
				Srvmr.LOGGER.warn("[dlss] DLSS is not available on this GPU{}; DLSS is off",
						availability != null && availability[1] != 0 ? " (NGX asks for a newer NVIDIA driver)" : "");
				return false;
			}
			state = 1;
			Srvmr.LOGGER.info("[dlss] NGX ready on {}", DeviceManager.deviceProperties != null
					? DeviceManager.deviceProperties.deviceNameString() : "the GPU");
			return true;
		} catch (Throwable e) {
			Srvmr.LOGGER.warn("[dlss] NGX did not start; DLSS is off", e);
			return false;
		}
	}

	/** After a failure in the middle of a frame: DLSS stays off for the rest of the session. */
	public static void giveUp() {
		state = -1;
	}

	public static boolean failed() {
		return state < 0;
	}

	/** Before VulkanMod Reforged tears its device down: NGX lets go of it. The device must be idle. */
	public static void shutdown() {
		if (state > 0) {
			NgxBridge.shutdown(Vulkan.getVkDevice().address());
			state = -1;
		}
	}

	/** The render sizes DLSS takes for an output and quality: optimal, largest, smallest (w, h each). Null if none. */
	public static int[] renderSizes(int outputWidth, int outputHeight, int quality) {
		return NgxBridge.renderSizes(outputWidth, outputHeight, quality);
	}

	public static long create(long commandBuffer, int renderWidth, int renderHeight, int outputWidth, int outputHeight,
			int quality) {
		return NgxBridge.create(commandBuffer, renderWidth, renderHeight, outputWidth, outputHeight, quality, CREATE_FLAGS);
	}

	public static int evaluate(long commandBuffer, long feature, long[] images, int[] info, float jitterX, float jitterY,
			boolean reset, float frameMillis, int renderWidth, int renderHeight) {
		return NgxBridge.evaluate(commandBuffer, feature, images, info, jitterX, jitterY, reset ? 1 : 0, frameMillis,
				renderWidth, renderHeight);
	}

	public static void release(long feature) {
		if (feature != 0L) NgxBridge.release(feature);
	}

	public static int lastResult() {
		return NgxBridge.lastResult();
	}

	public static String describe(int result) {
		return NgxBridge.describe(result);
	}

	public static boolean succeeded(int result) {
		return NgxBridge.succeeded(result);
	}
}
