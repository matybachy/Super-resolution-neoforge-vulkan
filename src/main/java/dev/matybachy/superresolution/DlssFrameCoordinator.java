package dev.matybachy.superresolution;

/**
 * Frame-order coordinator for the renderer seam: world color/depth/velocity
 * are produced first, DLSS evaluates, and GUI is drawn by the caller after a
 * successful evaluation or native-resolution fallback.
 */
public final class DlssFrameCoordinator implements AutoCloseable {
    private final NativeDlssUpscaler upscaler;
    private final CameraMotionVectorPass motionVectors = new CameraMotionVectorPass();
    private boolean resetHistory = true;

    public DlssFrameCoordinator(DlssConfig config) {
        upscaler = new NativeDlssUpscaler(config);
    }

    public NativeDlssUpscaler upscaler() {
        return upscaler;
    }

    public CameraMotionVectorPass motionVectors() {
        return motionVectors;
    }

    public void beginWorldFrame(float[] viewProjection) {
        motionVectors.beginFrame(viewProjection);
    }

    public boolean evaluateWorld(long commandBuffer, DlssImage color, DlssImage depth,
                                 DlssImage motionVectors, DlssImage output,
                                 FrameInput input) {
        if (resetHistory) {
            input = new FrameInput(input.jitterX(), input.jitterY(), input.motionVectorScaleX(),
                    input.motionVectorScaleY(), input.preExposure(), input.exposureScale(),
                    input.frameTimeSeconds(), true, input.renderWidth(), input.renderHeight(),
                    input.displayWidth(), input.displayHeight());
            resetHistory = false;
        }
        return upscaler.evaluate(commandBuffer, color, depth, motionVectors, output, input);
    }

    public void endWorldFrame() {
        motionVectors.endFrame();
    }

    public void onTeleportOrDimensionChange() {
        resetHistory = true;
        motionVectors.reset();
        upscaler.resetHistory();
    }

    public void onResize(int displayWidth, int displayHeight) {
        resetHistory = true;
        upscaler.resize(displayWidth, displayHeight);
    }

    @Override
    public void close() {
        upscaler.close();
    }
}
