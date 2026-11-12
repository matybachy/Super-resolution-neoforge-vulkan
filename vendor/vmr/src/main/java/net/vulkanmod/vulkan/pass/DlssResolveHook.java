package net.vulkanmod.vulkan.pass;

import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkan.texture.DlssRenderTargets;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Optional replacement for the built-in scaled-framebuffer resolve. The
 * resolver is invoked after world rendering and before native-resolution GUI.
 */
public final class DlssResolveHook {
    public interface Resolver {
        boolean resolve(VkCommandBuffer commandBuffer, VulkanImage color, VulkanImage depth,
                        VulkanImage output, VulkanImage motionVectors,
                        int displayWidth, int displayHeight);
    }

    private static volatile Resolver resolver;

    private DlssResolveHook() {
    }

    public static void install(Resolver value) {
        resolver = value;
    }

    public static boolean resolve(VulkanImage color, VulkanImage depth, VulkanImage output,
                                  int displayWidth, int displayHeight) {
        Resolver value = resolver;
        return value != null && value.resolve(Renderer.getCommandBuffer(), color, depth, output,
                DlssRenderTargets.activeMotionVectorImage(),
                displayWidth, displayHeight);
    }
}
