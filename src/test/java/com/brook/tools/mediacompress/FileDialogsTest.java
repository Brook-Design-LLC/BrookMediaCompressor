package com.brook.tools.mediacompress;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.brook.tools.mediacompress.win.WindowsNativeFileDialog;

import org.junit.jupiter.api.Test;

class FileDialogsTest {
    @Test
    void createReturnsPlatformImplementation() {
        FileDialogService service = FileDialogs.create();
        if (FileDialogs.isWindows()) {
            assertInstanceOf(WindowsNativeFileDialog.class, service);
        } else {
            assertInstanceOf(SwingFileDialog.class, service);
        }
    }

    @Test
    void isWindowsMatchesOsName() {
        boolean expected = System.getProperty("os.name", "").toLowerCase().contains("win");
        assertTrue(FileDialogs.isWindows() == expected);
    }
}
