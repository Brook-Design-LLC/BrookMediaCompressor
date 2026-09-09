package com.brook.tools.mediacompress;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.prefs.Preferences;

final class UserPreferences {
    private static final String KEY_LAST_OUTPUT_DIR = "lastOutputDirectory";
    private static final Preferences PREFS = Preferences.userNodeForPackage(UserPreferences.class);

    private UserPreferences() {
    }

    static Path lastOutputDirectory(Path fallback) {
        String stored = PREFS.get(KEY_LAST_OUTPUT_DIR, null);
        if (stored != null) {
            Path path = Path.of(stored);
            if (Files.isDirectory(path)) {
                return path;
            }
        }
        if (fallback != null && fallback.getParent() != null) {
            Path parent = fallback.getParent();
            if (Files.isDirectory(parent)) {
                return parent;
            }
        }
        return Path.of(".");
    }

    static void saveLastOutputDirectory(Path outputFile) {
        if (outputFile == null) {
            return;
        }
        Path dir = outputFile.getParent();
        if (dir != null && Files.isDirectory(dir)) {
            PREFS.put(KEY_LAST_OUTPUT_DIR, dir.toString());
        }
    }
}
