package dev.matybachy.superresolution;

public record DlssSupport(boolean supported, String reason) {
    public static DlssSupport available() {
        return new DlssSupport(true, "");
    }

    public static DlssSupport unavailable(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        return new DlssSupport(false, reason);
    }
}
