package com.brook.tools.mediacompress;

public final class AppConstants {
    public static final long DEFAULT_MAX_BYTES = 10L * 1024 * 1024;

    private AppConstants() {
    }

    public static String formatMegabytes(long bytes) {
        return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
    }
}
