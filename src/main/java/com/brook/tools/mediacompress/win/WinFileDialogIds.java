package com.brook.tools.mediacompress.win;

import com.sun.jna.platform.win32.Guid;

final class WinFileDialogIds {
    static final Guid.GUID IFileDialog = new Guid.GUID("42F85136-DB7E-439C-85F1-E4075D135FC8");
    static final Guid.GUID IFileOpenDialog = new Guid.GUID("D57C7288-D4AD-4768-BE02-9D969532D960");
    static final Guid.GUID CLSID_FileOpenDialog = new Guid.GUID("DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7");
    static final Guid.GUID IFileSaveDialog = new Guid.GUID("84BCCD23-5EFC-47CE-8840-0A55DD66F45C");
    static final Guid.GUID CLSID_FileSaveDialog = new Guid.GUID("C0B4E2F3-BA21-4773-8DBA-335EC946EB8B");
    static Guid.IID shellItemIid() {
        return new Guid.IID("43826D1E-E718-42EE-B55E-7CD55CB24065");
    }

    private WinFileDialogIds() {
    }
}
