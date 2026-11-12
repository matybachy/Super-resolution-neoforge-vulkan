package dev.matybachy.superresolution;

import java.util.Set;
import java.util.logging.Logger;

/**
 * Registers NGX's dynamically discovered Vulkan requirements when the VMR hook
 * is present. Reflection preserves compatibility with unpatched VMR builds.
 */
public final class VulkanExtensionRegistrar {
    private static final Logger LOGGER = Logger.getLogger(VulkanExtensionRegistrar.class.getName());
    private static final String HOOK = "net.vulkanmod.vulkan.VulkanExtensionHooks";

    private VulkanExtensionRegistrar() {
    }

    public static boolean register(Set<String> instanceExtensions, Set<String> deviceExtensions) {
        try {
            Class<?> hooks = Class.forName(HOOK);
            var instance = hooks.getMethod("requireInstanceExtension", String.class);
            var device = hooks.getMethod("requireDeviceExtension", String.class);
            for (String extension : instanceExtensions) {
                instance.invoke(null, extension);
            }
            for (String extension : deviceExtensions) {
                device.invoke(null, extension);
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            LOGGER.warning("DLSS Vulkan extension registration is unavailable: " + error.getMessage());
            return false;
        }
    }
}
