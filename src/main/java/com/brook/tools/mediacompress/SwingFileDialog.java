package com.brook.tools.mediacompress;

import java.awt.Component;
import java.io.File;
import java.nio.file.Path;
import java.util.Optional;

import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;

public final class SwingFileDialog implements FileDialogService {
    @Override
    public Optional<Path> chooseOpenFile(Component parent, String title, FileTypeFilter filter) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(title);
        chooser.setFileFilter(toSwingFilter(filter));
        if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
            return Optional.of(chooser.getSelectedFile().toPath());
        }
        return Optional.empty();
    }

    @Override
    public Optional<Path> chooseSaveFile(
            Component parent,
            String title,
            Path defaultDir,
            String defaultFileName) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(title);
        if (defaultDir != null) {
            chooser.setCurrentDirectory(defaultDir.toFile());
        }
        if (defaultFileName != null && !defaultFileName.isBlank()) {
            chooser.setSelectedFile(new File(defaultFileName));
        }
        if (chooser.showSaveDialog(parent) == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
            return Optional.of(chooser.getSelectedFile().toPath());
        }
        return Optional.empty();
    }

    private static FileNameExtensionFilter toSwingFilter(FileTypeFilter filter) {
        return new FileNameExtensionFilter(filter.label(), filter.extensions());
    }
}
