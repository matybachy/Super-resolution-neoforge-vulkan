package dev.matybachy.superresolution;

public enum DlssAlgorithm {
    QUALITY(0.6667f),
    BALANCED(0.58f),
    PERFORMANCE(0.5f),
    ULTRA_PERFORMANCE(0.3333f);

    private final float renderScale;

    DlssAlgorithm(float renderScale) {
        this.renderScale = renderScale;
    }

    public float renderScale() {
        return renderScale;
    }
}
