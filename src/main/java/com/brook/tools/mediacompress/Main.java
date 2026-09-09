package com.brook.tools.mediacompress;

public final class Main {
    public static void main(String[] args) {
        javax.swing.SwingUtilities.invokeLater(() -> {
            MediaCompressFrame frame = new MediaCompressFrame();
            frame.setVisible(true);
        });
    }
}
