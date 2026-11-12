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

    /**
     * Reconstructs one camera-only velocity sample from a depth-buffer sample.
     * Coordinates are normalized texture coordinates and the returned values
     * are normalized-device-coordinate deltas suitable for an RG16F target.
     */
    public float[] velocityFromDepth(float u, float v, float depth, float[] inverseCurrentViewProjection) {
        requireMatrix(inverseCurrentViewProjection);
        if (!Float.isFinite(u) || !Float.isFinite(v) || !Float.isFinite(depth)) {
            throw new IllegalArgumentException("depth coordinates must be finite");
        }
        float[] clip = new float[]{u * 2.0F - 1.0F, v * 2.0F - 1.0F, depth * 2.0F - 1.0F, 1.0F};
        float[] world = transform4(inverseCurrentViewProjection, clip);
        float[] current = transform4(currentViewProjection, world);
        float[] previous = transform4(previousViewProjection, world);
        return new float[]{current[0] / current[3] - previous[0] / previous[3],
                current[1] / current[3] - previous[1] / previous[3]};
    }

    public float[] previousViewProjection() {
        return previousViewProjection.clone();
    }

    public float[] currentViewProjection() {
        return currentViewProjection.clone();
    }

    private static float[] transform(float[] matrix, float[] position) {
        float[] transformed = transform4(matrix, new float[]{position[0], position[1], position[2], 1.0F});
        float x = transformed[0];
        float y = transformed[1];
        float z = transformed[2];
        float w = transformed[3];
        if (w != 0.0f && w != 1.0f) {
            x /= w;
            y /= w;
            z /= w;
        }
        return new float[]{x, y, z};
    }

    private static float[] transform4(float[] matrix, float[] vector) {
        return new float[]{
                matrix[0] * vector[0] + matrix[4] * vector[1] + matrix[8] * vector[2] + matrix[12] * vector[3],
                matrix[1] * vector[0] + matrix[5] * vector[1] + matrix[9] * vector[2] + matrix[13] * vector[3],
                matrix[2] * vector[0] + matrix[6] * vector[1] + matrix[10] * vector[2] + matrix[14] * vector[3],
                matrix[3] * vector[0] + matrix[7] * vector[1] + matrix[11] * vector[2] + matrix[15] * vector[3]
        };
    }

    private static void requireMatrix(float[] matrix) {
        if (matrix == null || matrix.length != 16) {
            throw new IllegalArgumentException("view-projection matrix must have 16 components");
        }
    }
}
