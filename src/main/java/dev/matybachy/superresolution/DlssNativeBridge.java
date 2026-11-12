package dev.matybachy.superresolution;

final class DlssNativeBridge {
    static {
        try {
            System.loadLibrary("superresolution_dlss");
        } catch (UnsatisfiedLinkError ignored) {
            // The caller reports an explicit native-resolution fallback.
        }
    }

    private DlssNativeBridge() {
    }

    static native int initialize(long instance, long physicalDevice, long device,
                                 long getInstanceProcAddr, long getDeviceProcAddr);

    static native int evaluate(long commandBuffer, DlssImage color, DlssImage depth,
                               DlssImage motionVectors, DlssImage output, FrameInput input);

    static native int recordMotionVectors(long commandBuffer, DlssImage depth, DlssImage motionVectors,
                                          float[] inverseCurrentViewProjection,
                                          float[] previousViewProjection,
                                          float[] currentViewProjection);

    static native void reset();

    static native void shutdown();
}
