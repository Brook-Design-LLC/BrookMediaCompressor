package com.brook.tools.mediacompress.win;

import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinNT.HRESULT;
import com.sun.jna.platform.win32.COM.Unknown;
import com.sun.jna.ptr.PointerByReference;

final class NativeFileDialog extends Unknown {
    NativeFileDialog(Pointer pointer) {
        super(pointer);
    }

    HRESULT Show(HWND hwndParent) {
        return (HRESULT) _invokeNativeObject(3, new Object[] { getPointer(), hwndParent }, HRESULT.class);
    }

    HRESULT SetFileTypes(int count, COMDLG_FILTERSPEC[] filters) {
        return (HRESULT) _invokeNativeObject(4, new Object[] { getPointer(), count, filters }, HRESULT.class);
    }

    HRESULT SetFileTypeIndex(int index) {
        return (HRESULT) _invokeNativeObject(5, new Object[] { getPointer(), index }, HRESULT.class);
    }

    HRESULT SetOptions(int flags) {
        return (HRESULT) _invokeNativeObject(9, new Object[] { getPointer(), flags }, HRESULT.class);
    }

    HRESULT SetFolder(Pointer folder) {
        return (HRESULT) _invokeNativeObject(12, new Object[] { getPointer(), folder }, HRESULT.class);
    }

    HRESULT SetFileName(WString fileName) {
        return (HRESULT) _invokeNativeObject(15, new Object[] { getPointer(), fileName }, HRESULT.class);
    }

    HRESULT SetTitle(WString title) {
        return (HRESULT) _invokeNativeObject(17, new Object[] { getPointer(), title }, HRESULT.class);
    }

    HRESULT GetResult(PointerByReference item) {
        return (HRESULT) _invokeNativeObject(20, new Object[] { getPointer(), item }, HRESULT.class);
    }
}
