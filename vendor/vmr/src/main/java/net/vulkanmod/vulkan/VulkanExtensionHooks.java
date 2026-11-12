package net.vulkanmod.vulkan;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Optional extension registration point for integrations that must participate
 * before Vulkan instance/device creation. Callers must register requirements
 * before Vulkan initializes; unsupported requirements remain a normal startup
 * failure and must be handled by the caller.
 */
public final class VulkanExtensionHooks {
	private static final Set<String> INSTANCE = new LinkedHashSet<>();
	private static final Set<String> DEVICE = new LinkedHashSet<>();

	private VulkanExtensionHooks() {
	}

	public static synchronized void requireInstanceExtension(String extension) {
		require(extension);
		INSTANCE.add(extension);
	}

	public static synchronized void requireDeviceExtension(String extension) {
		require(extension);
		DEVICE.add(extension);
	}

	public static synchronized Set<String> instanceExtensions() {
		return Collections.unmodifiableSet(new LinkedHashSet<>(INSTANCE));
	}

	public static synchronized Set<String> deviceExtensions() {
		return Collections.unmodifiableSet(new LinkedHashSet<>(DEVICE));
	}

	private static void require(String extension) {
		if (extension == null || extension.isBlank()) {
			throw new IllegalArgumentException("Vulkan extension must not be blank");
		}
	}
}
