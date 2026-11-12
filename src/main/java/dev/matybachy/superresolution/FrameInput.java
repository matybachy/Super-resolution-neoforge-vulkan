package dev.matybachy.superresolution;

public record FrameInput(
        float jitterX,
        float jitterY,
        float motionVectorScaleX,
        float motionVectorScaleY,
        float preExposure,
        float exposureScale,
        float frameTimeSeconds,
        boolean resetHistory,
        int renderWidth,
        int renderHeight,
        int displayWidth,
        int displayHeight
) {
    public FrameInput {
        if (renderWidth <= 0 || renderHeight <= 0 || displayWidth <= 0 || displayHeight <= 0) {
            throw new IllegalArgumentException("frame dimensions must be positive");
        }
        if (!Float.isFinite(preExposure) || !Float.isFinite(exposureScale)
                || !Float.isFinite(frameTimeSeconds)) {
            throw new IllegalArgumentException("frame values must be finite");
        }
    }

    public FrameInput withRenderDimensions(int width, int height) {
        return new FrameInput(jitterX, jitterY, motionVectorScaleX, motionVectorScaleY, preExposure,
                exposureScale, frameTimeSeconds, resetHistory, width, height, displayWidth, displayHeight);
    }
}
