package com.brook.tools.mediacompress.win;

final class WinFileDialogFlags {
    static final int FOS_OVERWRITEPROMPT = 0x00000002;
    static final int FOS_FORCEFILESYSTEM = 0x00000040;
    static final int FOS_FILEMUSTEXIST = 0x00001000;
    static final int FOS_PATHMUSTEXIST = 0x00000800;
    static final int SIGDN_FILESYSPATH = 0x80058000;
    static final int SIGDN_DESKTOPABSOLUTEPARSING = 0x8004C000;

    private WinFileDialogFlags() {
    }
}
