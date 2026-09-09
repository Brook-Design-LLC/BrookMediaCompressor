package com.brook.tools.mediacompress;

import java.awt.Component;
import java.nio.file.Path;
import java.util.Optional;

public interface FileDialogService {
    Optional<Path> chooseOpenFile(Component parent, String title, FileTypeFilter filter);

    Optional<Path> chooseSaveFile(
            Component parent,
            String title,
            Path defaultDir,
            String defaultFileName);
}
