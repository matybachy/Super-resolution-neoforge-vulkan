package net.vulkanmod.vulkan.texture;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_STORAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16_SFLOAT;

/**
 * Optional DLSS-owned output and camera-velocity images. The world color and
 * depth images remain owned by the active render pipeline.
 */
public final class DlssRenderTargets implements AutoCloseable {
    private static volatile DlssRenderTargets active;
    public record Resource(long image, long imageView, int format, int width, int height, int aspectMask) {
    }
    private VulkanImage output;
    private VulkanImage motionVectors;
    private int width;
    private int height;

    public void resize(int displayWidth, int displayHeight, int renderWidth, int renderHeight) {
        if (displayWidth <= 0 || displayHeight <= 0 || renderWidth <= 0 || renderHeight <= 0) {
            throw new IllegalArgumentException("DLSS target dimensions must be positive");
        }
        if (output != null && width == displayWidth && height == displayHeight
                && motionVectors.width == renderWidth && motionVectors.height == renderHeight) {
            return;
        }
        close();
        int colorUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_STORAGE_BIT
                | VK_IMAGE_USAGE_SAMPLED_BIT;
        output = VulkanImage.builder(displayWidth, displayHeight)
                .setFormat(VulkanImage.DefaultFormat)
                .setUsage(colorUsage)
                .setLinearFiltering(true)
                .setClamp(true)
                .createVulkanImage();
        motionVectors = VulkanImage.builder(renderWidth, renderHeight)
                .setFormat(VK_FORMAT_R16G16_SFLOAT)
                .setUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_STORAGE_BIT
                        | VK_IMAGE_USAGE_SAMPLED_BIT)
                .setLinearFiltering(false)
                .setClamp(true)
                .createVulkanImage();
        active = this;
        width = displayWidth;
        height = displayHeight;
    }

    public Resource output() {
        return image(output);
    }

    public Resource motionVectors() {
        return image(motionVectors);
    }

    public static Resource activeMotionVectors() {
        DlssRenderTargets targets = active;
        return targets == null ? null : targets.motionVectors();
    }

    public static VulkanImage activeMotionVectorImage() {
        DlssRenderTargets targets = active;
        return targets == null ? null : targets.motionVectors;
    }

    public static DlssRenderTargets active() {
        return active;
    }

    public VulkanImage outputImage() {
        return output;
    }

    public VulkanImage motionVectorImage() {
        return motionVectors;
    }

    private static Resource image(VulkanImage image) {
        if (image == null) throw new IllegalStateException("DLSS targets are not allocated");
        return new Resource(image.getId(), image.getImageView(), image.format,
                image.width, image.height, image.aspect);
    }

    @Override
    public void close() {
        if (output != null) output.free();
        if (motionVectors != null) motionVectors.free();
        if (active == this) active = null;
        output = null;
        motionVectors = null;
        width = 0;
        height = 0;
    }
}
