package dev.matybachy.superresolution;

import java.lang.reflect.Proxy;
import java.util.logging.Logger;

/**
 * Runtime adapter for the optional VMR resolve hook. No VMR classes are linked
 * at compile time, so DLSS remains optional when the renderer is absent.
 */
public final class VulkanResolveInstaller {
    private static final Logger LOGGER = Logger.getLogger(VulkanResolveInstaller.class.getName());
    private static final String VULKAN = "net.vulkanmod.vulkan.Vulkan";
    private static final String HOOK = "net.vulkanmod.vulkan.pass.DlssResolveHook";
    private static DlssFrameCoordinator coordinator;
    private static FrameInput frameInput;
    private static Integer previousRenderScale;

    private VulkanResolveInstaller() {
    }

    public static boolean install(DlssConfig config, int displayWidth, int displayHeight) {
        try {
            Class<?> vulkan = Class.forName(VULKAN);
            long instance = address(vulkan.getMethod("getVkInstance").invoke(null));
            long physicalDevice = address(Class.forName("net.vulkanmod.vulkan.device.DeviceManager")
                    .getField("physicalDevice").get(null));
            long device = address(vulkan.getMethod("getVkDevice").invoke(null));
            long gipa = (long) vulkan.getMethod("getInstanceProcAddr").invoke(null);
            long gdpa = (long) vulkan.getMethod("getDeviceProcAddr").invoke(null);

            coordinator = new DlssFrameCoordinator(config);
            if (!coordinator.upscaler().initialize(instance, physicalDevice, device, gipa, gdpa,
                    displayWidth, displayHeight)) {
                coordinator.close();
                coordinator = null;
                return false;
            }

            forceRenderScale(config);

            Class<?> hook = Class.forName(HOOK);
            Class<?> resolverType = Class.forName(HOOK + "$Resolver");
            Object resolver = Proxy.newProxyInstance(resolverType.getClassLoader(),
                    new Class<?>[]{resolverType}, (proxy, method, args) -> {
                        if (!method.getName().equals("resolve") || frameInput == null) return false;
                        long commandBuffer = address(args[0]);
                        DlssImage color = image(args[1]);
                        DlssImage depth = image(args[2]);
                        DlssImage output = image(args[3]);
                        DlssImage motion = image(args[4]);
                        if (!coordinator.recordMotionVectors(commandBuffer, depth, motion)) return false;
                        return coordinator.evaluateWorld(commandBuffer, color, depth, motion, output,
                                frameInput.withRenderDimensions(color.width(), color.height()));
                    });
            hook.getMethod("install", resolverType).invoke(null, resolver);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            LOGGER.warning("DLSS VMR resolve hook unavailable: " + error.getMessage());
            uninstall();
            return false;
        }
    }

    public static boolean reload(DlssConfig config, int displayWidth, int displayHeight) {
        uninstall();
        return config.enabled() && install(config, displayWidth, displayHeight);
    }

    public static void setFrameInput(FrameInput input) {
        frameInput = input;
    }

    public static void beginWorldFrame(float[] viewProjection) {
        if (coordinator != null) coordinator.beginWorldFrame(viewProjection);
    }

    public static void resetHistory() {
        if (coordinator != null) coordinator.onTeleportOrDimensionChange();
    }

    public static void uninstall() {
        if (coordinator != null) coordinator.close();
        restoreRenderScale();
        coordinator = null;
        frameInput = null;
    }

    private static long address(Object object) throws ReflectiveOperationException {
        if (object == null) return 0L;
        return (long) object.getClass().getMethod("address").invoke(object);
    }

    private static DlssImage image(Object image) throws ReflectiveOperationException {
        Class<?> type = image.getClass();
        return new DlssImage(
                (long) type.getMethod("getId").invoke(image),
                (long) type.getMethod("getImageView").invoke(image),
                type.getField("format").getInt(image),
                type.getField("width").getInt(image),
                type.getField("height").getInt(image),
                type.getField("aspect").getInt(image));
    }

    private static void forceRenderScale(DlssConfig config) throws ReflectiveOperationException {
        Class<?> initializer = Class.forName("net.vulkanmod.Initializer");
        Object rendererConfig = initializer.getField("CONFIG").get(null);
        var scale = rendererConfig.getClass().getField("renderScale");
        if (previousRenderScale == null) previousRenderScale = scale.getInt(rendererConfig);
        scale.setInt(rendererConfig, Math.max(50, Math.min(100,
                Math.round(config.algorithm().renderScale() * 100.0F))));
    }

    private static void restoreRenderScale() {
        if (previousRenderScale == null) return;
        try {
            Class<?> initializer = Class.forName("net.vulkanmod.Initializer");
            Object rendererConfig = initializer.getField("CONFIG").get(null);
            rendererConfig.getClass().getField("renderScale").setInt(rendererConfig, previousRenderScale);
        } catch (ReflectiveOperationException | RuntimeException error) {
            LOGGER.warning("Unable to restore VulkanMod render scale: " + error.getMessage());
        } finally {
            previousRenderScale = null;
        }
    }
}
