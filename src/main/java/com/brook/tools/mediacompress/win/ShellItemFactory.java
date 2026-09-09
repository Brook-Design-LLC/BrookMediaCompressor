package com.brook.tools.mediacompress.win;

import java.nio.file.Path;

import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.COM.COMUtils;
import com.sun.jna.platform.win32.COM.IUnknown;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.WinNT.HRESULT;
import com.sun.jna.ptr.PointerByReference;

final class ShellItemFactory {
    private static final int RPC_E_CHANGED_MODE = 0x80010106;

    private ShellItemFactory() {
    }

    static NativeShellItem fromPath(Path path) {
        if (path == null) {
            return null;
        }
        PointerByReference itemRef = new PointerByReference();
        Guid.IID shellItemIid = WinFileDialogIds.shellItemIid();
        HRESULT hr = Shell32Extra.INSTANCE.SHCreateItemFromParsingName(
                new WString(path.toString()),
                null,
                shellItemIid.getPointer(),
                itemRef);
        if (COMUtils.FAILED(hr)) {
            return null;
        }
        return shellItem(itemRef.getValue());
    }

    static NativeFileDialog fileDialog(Pointer pointer) {
        return new NativeFileDialog(pointer);
    }

    static NativeShellItem shellItem(Pointer pointer) {
        return new NativeShellItem(pointer);
    }

    static void initializeCom() {
        HRESULT hr = Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED);
        if (COMUtils.FAILED(hr) && hr.intValue() != RPC_E_CHANGED_MODE) {
            throw new IllegalStateException("CoInitializeEx failed: 0x" + Integer.toHexString(hr.intValue()));
        }
    }

    static void release(IUnknown comObject) {
        if (comObject != null) {
            comObject.Release();
        }
    }

    static String readShellItemPath(NativeShellItem item) {
        if (item == null) {
            return null;
        }
        String path = readDisplayName(item, WinFileDialogFlags.SIGDN_FILESYSPATH);
        if (path != null && !path.isBlank()) {
            return path;
        }
        return readDisplayName(item, WinFileDialogFlags.SIGDN_DESKTOPABSOLUTEPARSING);
    }

    private static String readDisplayName(NativeShellItem item, int sigdn) {
        PointerByReference nameRef = new PointerByReference();
        HRESULT hr = item.GetDisplayName(sigdn, nameRef);
        if (COMUtils.FAILED(hr) || nameRef.getValue() == null) {
            return null;
        }
        Pointer namePointer = nameRef.getValue();
        try {
            return namePointer.getWideString(0);
        } finally {
            Ole32.INSTANCE.CoTaskMemFree(namePointer);
        }
    }
}
