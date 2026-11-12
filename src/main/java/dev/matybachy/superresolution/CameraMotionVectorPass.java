package dev.matybachy.superresolution;

/**
 * Supplies the camera-only velocity transform used by the native DLSS bridge.
 * The Vulkan image allocation and draw are owned by the renderer integration.
 */
public final class CameraMotionVectorPass {
    private final float[] previousViewProjection = new float[16];
    private final float[] currentViewProjection = new float[16];
    private boolean initialized;

    public void beginFrame(float[] viewProjection) {
        requireMatrix(viewProjection);
        if (!initialized) {
            System.arraycopy(viewProjection, 0, previousViewProjection, 0, 16);
            initialized = true;
        }
        System.arraycopy(viewProjection, 0, currentViewProjection, 0, 16);
    }

    public void endFrame() {
        if (initialized) {
            System.arraycopy(currentViewProjection, 0, previousViewProjection, 0, 16);
        }
    }

    public void reset() {
        initialized = false;
    }

    public boolean initialized() {
        return initialized;
    }

    public float[] motionVector(float[] currentWorldPosition, float[] previousWorldPosition) {
        if (currentWorldPosition.length != 3 || previousWorldPosition.length != 3) {
            throw new IllegalArgumentException("world positions must have three components");
        }
        if (!initialized) {
            return new float[3];
        }
        float[] current = transform(currentViewProjection, currentWorldPosition);
        float[] previous = transform(previousViewProjection, previousWorldPosition);
        return new float[]{current[0] - previous[0], current[1] - previous[1], current[2] - previous[2]};
    }

    public float[] previousViewProjection() {
        return previousViewProjection.clone();
    }

    public float[] currentViewProjection() {
        return currentViewProjection.clone();
    }

    private static float[] transform(float[] matrix, float[] position) {
        float x = matrix[0] * position[0] + matrix[4] * position[1] + matrix[8] * position[2] + matrix[12];
        float y = matrix[1] * position[0] + matrix[5] * position[1] + matrix[9] * position[2] + matrix[13];
        float z = matrix[2] * position[0] + matrix[6] * position[1] + matrix[10] * position[2] + matrix[14];
        float w = matrix[3] * position[0] + matrix[7] * position[1] + matrix[11] * position[2] + matrix[15];
        if (w != 0.0f && w != 1.0f) {
            x /= w;
            y /= w;
            z /= w;
        }
        return new float[]{x, y, z};
    }

    private static void requireMatrix(float[] matrix) {
        if (matrix == null || matrix.length != 16) {
            throw new IllegalArgumentException("view-projection matrix must have 16 components");
        }
    }
}
