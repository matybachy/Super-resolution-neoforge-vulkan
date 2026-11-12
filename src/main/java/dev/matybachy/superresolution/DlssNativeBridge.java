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

    static native int evaluate(long commandBuffer, long colorImageView, long depthImageView,
                               long motionVectorImageView, long outputImageView, FrameInput input);

    static native void reset();

    static native void shutdown();
}
