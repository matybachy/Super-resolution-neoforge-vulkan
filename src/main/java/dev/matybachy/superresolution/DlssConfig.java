package dev.matybachy.superresolution;

import java.util.Objects;

public record DlssConfig(boolean enabled, DlssAlgorithm algorithm, float sharpness) {
    public DlssConfig {
        algorithm = Objects.requireNonNull(algorithm, "algorithm");
        if (!Float.isFinite(sharpness) || sharpness < 0.0f || sharpness > 1.0f) {
            throw new IllegalArgumentException("sharpness must be in [0, 1]");
        }
    }

    public static DlssConfig defaults() {
        return new DlssConfig(false, DlssAlgorithm.QUALITY, 0.2f);
    }
}
