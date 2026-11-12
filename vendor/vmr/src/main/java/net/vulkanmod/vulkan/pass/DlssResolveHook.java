package net.vulkanmod.vulkan.pass;

import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkan.texture.DlssRenderTargets;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.logging.Logger;

/**
 * Optional replacement for the built-in scaled-framebuffer resolve. The
 * resolver is invoked after world rendering and before native-resolution GUI.
 */
public final class DlssResolveHook {
    private static final Logger LOGGER = Logger.getLogger(DlssResolveHook.class.getName());
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

    public static boolean resolve(VulkanImage color, VulkanImage depth,
                                  int displayWidth, int displayHeight) {
        Resolver value = resolver;
        if (value == null) return false;
        DlssRenderTargets targets = DlssRenderTargets.active();
        if (targets == null) {
            targets = new DlssRenderTargets();
        }
        try {
            targets.resize(displayWidth, displayHeight, color.width, color.height);
            return value.resolve(Renderer.getCommandBuffer(), color, depth, targets.outputImage(),
                    targets.motionVectorImage(), displayWidth, displayHeight);
        } catch (RuntimeException error) {
            LOGGER.warning("DLSS render-target setup failed; using native-resolution resolve: "
                    + error.getMessage());
            return false;
        }
    }

    public static VulkanImage outputImage() {
        DlssRenderTargets targets = DlssRenderTargets.active();
        return targets == null ? null : targets.outputImage();
    }
}
