package com.brook.tools.mediacompress;

import com.brook.tools.mediacompress.win.WindowsNativeFileDialog;

import java.util.Locale;

public final class FileDialogs {
    private FileDialogs() {
    }

    public static FileDialogService create() {
        if (isWindows()) {
            return new WindowsNativeFileDialog();
        }
        return new SwingFileDialog();
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
