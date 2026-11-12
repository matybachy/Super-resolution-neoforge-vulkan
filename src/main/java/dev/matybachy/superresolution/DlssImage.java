package dev.matybachy.superresolution;

public record DlssImage(long image, long imageView, int format, int width, int height, int aspectMask) {
    public DlssImage {
        if (image == 0 || imageView == 0 || width <= 0 || height <= 0 || aspectMask == 0) {
            throw new IllegalArgumentException("DLSS image metadata is invalid");
        }
    }
}
