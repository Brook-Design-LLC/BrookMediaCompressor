package com.brook.tools.mediacompress.win;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.jna.WString;
import com.sun.jna.platform.win32.COM.COMUtils;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.WinNT.HRESULT;
import com.sun.jna.platform.win32.WTypes;
import com.sun.jna.ptr.PointerByReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.WINDOWS)
class WindowsNativeFileDialogIntegrationTest {
    @Test
    void canCreateOpenAndSaveDialogs() {
        ShellItemFactory.initializeCom();

        PointerByReference openRef = new PointerByReference();
        HRESULT openHr = Ole32.INSTANCE.CoCreateInstance(
                WinFileDialogIds.CLSID_FileOpenDialog,
                null,
                WTypes.CLSCTX_INPROC_SERVER,
                WinFileDialogIds.IFileOpenDialog,
                openRef);
        assertTrue(COMUtils.SUCCEEDED(openHr), "open hr=0x" + Integer.toHexString(openHr.intValue()));
        assertNotNull(openRef.getValue());
        NativeFileDialog openDialog = ShellItemFactory.fileDialog(openRef.getValue());
        openDialog.SetTitle(new WString("Test Open"));
        ShellItemFactory.release(openDialog);

        PointerByReference saveRef = new PointerByReference();
        HRESULT saveHr = Ole32.INSTANCE.CoCreateInstance(
                WinFileDialogIds.CLSID_FileSaveDialog,
                null,
                WTypes.CLSCTX_INPROC_SERVER,
                WinFileDialogIds.IFileSaveDialog,
                saveRef);
        if (COMUtils.FAILED(saveHr)) {
            saveHr = Ole32.INSTANCE.CoCreateInstance(
                    WinFileDialogIds.CLSID_FileSaveDialog,
                    null,
                    WTypes.CLSCTX_INPROC_SERVER,
                    WinFileDialogIds.IFileDialog,
                    saveRef);
        }
        assertTrue(COMUtils.SUCCEEDED(saveHr), "save hr=0x" + Integer.toHexString(saveHr.intValue()));
        assertNotNull(saveRef.getValue());
        NativeFileDialog saveDialog = ShellItemFactory.fileDialog(saveRef.getValue());
        saveDialog.SetTitle(new WString("Test Save"));
        ShellItemFactory.release(saveDialog);
    }
}
