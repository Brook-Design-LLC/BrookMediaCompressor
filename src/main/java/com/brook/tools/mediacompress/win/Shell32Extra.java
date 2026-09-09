package com.brook.tools.mediacompress.win;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.WinNT.HRESULT;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

interface Shell32Extra extends StdCallLibrary {
    Shell32Extra INSTANCE = Native.load("shell32", Shell32Extra.class, W32APIOptions.UNICODE_OPTIONS);

    HRESULT SHCreateItemFromParsingName(
            WString pszPath,
            Pointer pbc,
            Pointer riid,
            PointerByReference ppv);
}
