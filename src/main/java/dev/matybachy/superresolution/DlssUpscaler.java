package dev.matybachy.superresolution;

public interface DlssUpscaler extends AutoCloseable {
    DlssSupport support();

    boolean initialize(long instance, long physicalDevice, long device, long getInstanceProcAddr,
                       long getDeviceProcAddr, int displayWidth, int displayHeight);

    boolean resize(int displayWidth, int displayHeight);

    boolean evaluate(long commandBuffer, DlssImage color, DlssImage depth,
                     DlssImage motionVectors, DlssImage output, FrameInput input);

    void resetHistory();

    @Override
    void close();
}
