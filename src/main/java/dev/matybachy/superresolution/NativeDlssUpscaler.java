package dev.matybachy.superresolution;

import java.util.logging.Level;
import java.util.logging.Logger;

public final class NativeDlssUpscaler implements DlssUpscaler {
    private static final Logger LOGGER = Logger.getLogger(NativeDlssUpscaler.class.getName());
    private final DlssConfig config;
    private DlssSupport support = DlssSupport.unavailable("DLSS is not initialized");
    private boolean initialized;
    private int displayWidth;
    private int displayHeight;

    public NativeDlssUpscaler(DlssConfig config) {
        this.config = config;
    }

    @Override
    public DlssSupport support() {
        return support;
    }

    @Override
    public boolean initialize(long instance, long physicalDevice, long device, long getInstanceProcAddr,
                              long getDeviceProcAddr, int displayWidth, int displayHeight) {
        if (!config.enabled()) {
            return fail("DLSS is disabled");
        }
        if (instance == 0 || physicalDevice == 0 || device == 0 || getInstanceProcAddr == 0
                || getDeviceProcAddr == 0) {
            return fail("Vulkan handles are unavailable");
        }
        if (displayWidth <= 0 || displayHeight <= 0) {
            return fail("display dimensions are invalid");
        }
        try {
            int result = DlssNativeBridge.initialize(instance, physicalDevice, device,
                    getInstanceProcAddr, getDeviceProcAddr);
            if (result != 0) {
                return fail("NGX initialization failed (" + result + ")");
            }
        } catch (LinkageError error) {
            return fail("DLSS native bridge is unavailable");
        }
        this.displayWidth = displayWidth;
        this.displayHeight = displayHeight;
        initialized = true;
        support = DlssSupport.available();
        return true;
    }

    @Override
    public boolean resize(int displayWidth, int displayHeight) {
        if (!initialized || displayWidth <= 0 || displayHeight <= 0) {
            return fail("DLSS is not initialized or dimensions are invalid");
        }
        this.displayWidth = displayWidth;
        this.displayHeight = displayHeight;
        resetHistory();
        return true;
    }

    @Override
    public boolean evaluate(long commandBuffer, DlssImage color, DlssImage depth,
                            DlssImage motionVectors, DlssImage output, FrameInput input) {
        if (!initialized) {
            return fail("DLSS is not initialized");
        }
        if (commandBuffer == 0 || color == null || depth == null
                || motionVectors == null || output == null) {
            return fail("DLSS input or output image is unavailable");
        }
        if (input.displayWidth() != displayWidth || input.displayHeight() != displayHeight) {
            return fail("frame dimensions do not match the initialized DLSS output");
        }
        try {
            int result = DlssNativeBridge.evaluate(commandBuffer, color, depth, motionVectors, output, input,
                    config.sharpness());
            if (result != 0) {
                return fail("DLSS evaluation failed (" + result + ")");
            }
            return true;
        } catch (LinkageError error) {
            return fail("DLSS native bridge is unavailable");
        }
    }

    public boolean recordMotionVectors(long commandBuffer, DlssImage depth, DlssImage motionVectors,
                                       float[] inverseCurrentViewProjection,
                                       float[] previousViewProjection,
                                       float[] currentViewProjection) {
        if (!initialized || depth == null || motionVectors == null) {
            return fail("DLSS motion-vector resources are unavailable");
        }
        try {
            int result = DlssNativeBridge.recordMotionVectors(commandBuffer, depth, motionVectors,
                    inverseCurrentViewProjection, previousViewProjection, currentViewProjection);
            if (result != 0) return fail("camera motion-vector pass failed (" + result + ")");
            return true;
        } catch (LinkageError error) {
            return fail("DLSS native bridge is unavailable");
        }
    }

    @Override
    public void resetHistory() {
        if (initialized) {
            DlssNativeBridge.reset();
        }
    }

    @Override
    public void close() {
        if (initialized) {
            try {
                DlssNativeBridge.shutdown();
            } catch (LinkageError error) {
                LOGGER.log(Level.FINE, "DLSS native bridge was unavailable during shutdown", error);
            } finally {
                initialized = false;
                support = DlssSupport.unavailable("DLSS is shut down");
            }
        }
    }

    private boolean fail(String reason) {
        support = DlssSupport.unavailable(reason);
        LOGGER.warning("DLSS disabled; using native-resolution rendering: " + reason);
        return false;
    }
}
