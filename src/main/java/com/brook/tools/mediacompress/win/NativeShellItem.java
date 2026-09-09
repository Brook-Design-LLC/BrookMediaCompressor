package com.brook.tools.mediacompress.win;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinNT.HRESULT;
import com.sun.jna.platform.win32.COM.Unknown;
import com.sun.jna.ptr.PointerByReference;

final class NativeShellItem extends Unknown {
    NativeShellItem(Pointer pointer) {
        super(pointer);
    }

    HRESULT GetDisplayName(int sigdnName, PointerByReference name) {
        return (HRESULT) _invokeNativeObject(5, new Object[] { getPointer(), sigdnName, name }, HRESULT.class);
    }
}
