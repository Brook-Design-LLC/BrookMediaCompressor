package com.brook.tools.mediacompress.win;

import java.awt.Component;
import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.swing.SwingUtilities;

import com.brook.tools.mediacompress.FileDialogService;
import com.brook.tools.mediacompress.FileTypeFilter;
import com.brook.tools.mediacompress.SwingFileDialog;
import com.sun.jna.Native;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.COM.COMUtils;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinNT.HRESULT;
import com.sun.jna.platform.win32.WTypes;
import com.sun.jna.ptr.PointerByReference;

public final class WindowsNativeFileDialog implements FileDialogService {
    private static final int HRESULT_CANCEL = 0x800704C7;

    private final SwingFileDialog fallback = new SwingFileDialog();

    @Override
    public Optional<Path> chooseOpenFile(Component parent, String title, FileTypeFilter filter) {
        try {
            return chooseOpenFileNative(parent, title, filter);
        } catch (Throwable ex) {
            return fallback.chooseOpenFile(parent, title, filter);
        }
    }

    @Override
    public Optional<Path> chooseSaveFile(
            Component parent,
            String title,
            Path defaultDir,
            String defaultFileName) {
        try {
            return chooseSaveFileNative(parent, title, defaultDir, defaultFileName);
        } catch (Throwable ex) {
            return fallback.chooseSaveFile(parent, title, defaultDir, defaultFileName);
        }
    }

    private Optional<Path> chooseOpenFileNative(Component parent, String title, FileTypeFilter filter) {
        ShellItemFactory.initializeCom();
        PointerByReference dialogRef = new PointerByReference();
        HRESULT hr = Ole32.INSTANCE.CoCreateInstance(
                WinFileDialogIds.CLSID_FileOpenDialog,
                null,
                WTypes.CLSCTX_INPROC_SERVER,
                WinFileDialogIds.IFileOpenDialog,
                dialogRef);
        if (COMUtils.FAILED(hr) || dialogRef.getValue() == null) {
            return fallback.chooseOpenFile(parent, title, filter);
        }

        NativeFileDialog dialog = ShellItemFactory.fileDialog(dialogRef.getValue());
        boolean shown = false;
        try {
            applyFilter(dialog, filter);
            if (title != null && !title.isBlank()) {
                dialog.SetTitle(new WString(title));
            }
            dialog.SetOptions(
                    WinFileDialogFlags.FOS_FORCEFILESYSTEM
                            | WinFileDialogFlags.FOS_FILEMUSTEXIST
                            | WinFileDialogFlags.FOS_PATHMUSTEXIST);

            hr = dialog.Show(parentHwnd(parent));
            shown = true;
            if (isCancelled(hr)) {
                return Optional.empty();
            }
            if (COMUtils.FAILED(hr)) {
                return Optional.empty();
            }
            return shellItemPath(dialog);
        } catch (Throwable ex) {
            if (!shown) {
                return fallback.chooseOpenFile(parent, title, filter);
            }
            return Optional.empty();
        } finally {
            ShellItemFactory.release(dialog);
        }
    }

    private Optional<Path> chooseSaveFileNative(
            Component parent,
            String title,
            Path defaultDir,
            String defaultFileName) {
        ShellItemFactory.initializeCom();
        PointerByReference dialogRef = new PointerByReference();
        HRESULT hr = Ole32.INSTANCE.CoCreateInstance(
                WinFileDialogIds.CLSID_FileSaveDialog,
                null,
                WTypes.CLSCTX_INPROC_SERVER,
                WinFileDialogIds.IFileSaveDialog,
                dialogRef);
        if (COMUtils.FAILED(hr) || dialogRef.getValue() == null) {
            dialogRef = new PointerByReference();
            hr = Ole32.INSTANCE.CoCreateInstance(
                    WinFileDialogIds.CLSID_FileSaveDialog,
                    null,
                    WTypes.CLSCTX_INPROC_SERVER,
                    WinFileDialogIds.IFileDialog,
                    dialogRef);
        }
        if (COMUtils.FAILED(hr) || dialogRef.getValue() == null) {
            return fallback.chooseSaveFile(parent, title, defaultDir, defaultFileName);
        }

        NativeFileDialog dialog = ShellItemFactory.fileDialog(dialogRef.getValue());
        boolean shown = false;
        try {
            if (title != null && !title.isBlank()) {
                dialog.SetTitle(new WString(title));
            }
            dialog.SetOptions(
                    WinFileDialogFlags.FOS_FORCEFILESYSTEM
                            | WinFileDialogFlags.FOS_OVERWRITEPROMPT);

            if (defaultDir != null && Files.isDirectory(defaultDir)) {
                NativeShellItem folder = ShellItemFactory.fromPath(defaultDir);
                if (folder != null) {
                    dialog.SetFolder(folder.getPointer());
                    ShellItemFactory.release(folder);
                }
            }
            if (defaultFileName != null && !defaultFileName.isBlank()) {
                dialog.SetFileName(new WString(defaultFileName));
            }

            hr = dialog.Show(parentHwnd(parent));
            shown = true;
            if (isCancelled(hr)) {
                return Optional.empty();
            }
            if (COMUtils.FAILED(hr)) {
                return Optional.empty();
            }
            return shellItemPath(dialog);
        } catch (Throwable ex) {
            if (!shown) {
                return fallback.chooseSaveFile(parent, title, defaultDir, defaultFileName);
            }
            return Optional.empty();
        } finally {
            ShellItemFactory.release(dialog);
        }
    }

    private static boolean isCancelled(HRESULT hr) {
        return hr != null && hr.intValue() == HRESULT_CANCEL;
    }

    private static Optional<Path> shellItemPath(NativeFileDialog dialog) {
        PointerByReference resultRef = new PointerByReference();
        HRESULT hr = dialog.GetResult(resultRef);
        if (COMUtils.FAILED(hr) || resultRef.getValue() == null) {
            return Optional.empty();
        }
        NativeShellItem item = ShellItemFactory.shellItem(resultRef.getValue());
        try {
            return pathFromShellItem(item);
        } finally {
            ShellItemFactory.release(item);
        }
    }

    private static Optional<Path> pathFromShellItem(NativeShellItem item) {
        String path = ShellItemFactory.readShellItemPath(item);
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Path.of(path));
    }

    private static void applyFilter(NativeFileDialog dialog, FileTypeFilter filter) {
        if (filter == null || filter.extensions() == null || filter.extensions().length == 0) {
            return;
        }
        String spec = Stream.of(filter.extensions())
                .map(ext -> "*." + ext)
                .collect(Collectors.joining(";"));
        COMDLG_FILTERSPEC[] filters = (COMDLG_FILTERSPEC[]) new COMDLG_FILTERSPEC(
                filter.label(), spec).toArray(1);
        dialog.SetFileTypes(1, filters);
        dialog.SetFileTypeIndex(1);
    }

    private static HWND parentHwnd(Component parent) {
        if (parent == null) {
            return null;
        }
        Window window = parent instanceof Window windowComponent
                ? windowComponent
                : SwingUtilities.getWindowAncestor(parent);
        if (window == null) {
            return null;
        }
        return new HWND(Native.getWindowPointer(window));
    }
}
