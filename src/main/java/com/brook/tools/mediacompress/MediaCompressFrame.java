package com.brook.tools.mediacompress;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Color;
import java.awt.Container;
import java.awt.Component;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Optional;

public final class MediaCompressFrame extends JFrame {
    /**
     * Horizontal inset so full-width rows align with JButton content edges on
     * macOS.
     */
    private static final int CONTENT_H_INSET = 14;
    private static final Dimension DROP_PANEL_SIZE = new Dimension(480, 120);
    private static final String DROP_PLACEHOLDER = "<html><center>Drag and drop or click to select a file</center></html>";
    private static final String EMPTY_LABEL = "\u00a0";

    private final JLabel dropLabel = new JLabel(DROP_PLACEHOLDER, JLabel.CENTER);
    private final JLabel formatLabel = new JLabel(" ");
    private final JLabel hdrMethodLabel = new JLabel(" ");
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JTextField targetSizeField = new JTextField("10", 4);
    private final JCheckBox hdrToSdrCheckBox = new JCheckBox("Convert HDR to SDR", false);
    private final JCheckBox keepAudioQualityCheckBox = new JCheckBox("Keep audio quality", false);
    private final JSlider bppfSlider = new JSlider(
            0, CompressionPlanner.bppfSliderMax(), CompressionPlanner.bppfDefaultSliderValue());
    private final JLabel bppfFloorLabel = new JLabel(CompressionPlanner.formatMinBppfLabel());
    private final JSlider fpsSlider = new JSlider(
            0, CompressionPlanner.fpsSliderMax(), CompressionPlanner.fpsDefaultSliderValue());
    private final JLabel preferredFpsLabel = new JLabel(CompressionPlanner.formatPreferredFpsLabel());
    private final JButton startButton = new JButton("Start");
    private final JButton stopButton = new JButton("Stop");
    private final FfmpegLocator locator = new FfmpegLocator();
    private final FileDialogService fileDialogs = FileDialogs.create();
    private final Timer previewDebounceTimer;

    private JPanel dropPanel;
    private DropTarget dropTarget;
    private Color dropPanelEnabledBg;
    private Path selectedFile;
    private SwingWorker<?, ?> previewWorker;
    private SwingWorker<Void, Integer> compressWorker;
    private MediaCompressor activeCompressor;
    private volatile boolean busy;
    private volatile boolean previewing;

    public MediaCompressFrame() {
        super("Brook Media Compressor");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(700, 360));
        setSize(new Dimension(700, 480));
        setLocationRelativeTo(null);

        previewDebounceTimer = new Timer(300, event -> schedulePreview());
        previewDebounceTimer.setRepeats(false);

        dropPanel = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getPreferredSize() {
                Container parent = getParent();
                int width = parent != null && parent.getWidth() > 0
                        ? parent.getWidth()
                        : DROP_PANEL_SIZE.width;
                return new Dimension(width, DROP_PANEL_SIZE.height);
            }

            @Override
            public Dimension getMinimumSize() {
                return new Dimension(0, DROP_PANEL_SIZE.height);
            }

            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, DROP_PANEL_SIZE.height);
            }
        };
        dropPanel.setOpaque(true);
        dropPanelEnabledBg = dropPanel.getBackground();
        dropPanel.setBorder(BorderFactory.createDashedBorder(null, 2f, 4f, 4f, true));
        dropLabel.setVerticalAlignment(JLabel.CENTER);
        dropPanel.add(dropLabel, BorderLayout.CENTER);
        dropPanel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        dropPanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (!busy) {
                    browse();
                }
            }
        });

        dropTarget = new DropTarget(dropPanel, new DropTargetAdapter() {
            @Override
            public void drop(DropTargetDropEvent event) {
                if (busy) {
                    event.rejectDrop();
                    return;
                }
                event.acceptDrop(DnDConstants.ACTION_COPY);
                try {
                    var transferable = event.getTransferable();
                    if (transferibleHasFiles(transferable)) {
                        @SuppressWarnings("unchecked")
                        java.util.List<File> files = (java.util.List<File>) transferable
                                .getTransferData(DataFlavor.javaFileListFlavor);
                        if (!files.isEmpty()) {
                            selectFile(files.get(0).toPath());
                        }
                    }
                } catch (Exception ex) {
                    showError(ex.getMessage());
                }
                event.dropComplete(true);
            }
        });

        progressBar.setStringPainted(true);
        progressBar.setValue(0);
        progressBar.setString("");
        progressBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        progressBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, progressBar.getPreferredSize().height));
        progressBar.setMinimumSize(new Dimension(0, progressBar.getPreferredSize().height));

        int specLineHeight = formatLabel.getFontMetrics(formatLabel.getFont()).getHeight();
        Dimension specLineSize = new Dimension(0, specLineHeight);
        formatLabel.setMinimumSize(specLineSize);
        formatLabel.setPreferredSize(specLineSize);
        hdrMethodLabel.setMinimumSize(specLineSize);
        hdrMethodLabel.setPreferredSize(specLineSize);
        formatLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        hdrMethodLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        clearSpecLabels();

        stopButton.setEnabled(false);
        startButton.addActionListener(event -> startCompression());
        stopButton.addActionListener(event -> stopCompression());
        Insets buttonMargin = new Insets(6, 0, 6, 0);
        startButton.setMargin(buttonMargin);
        stopButton.setMargin(buttonMargin);
        int buttonHeight = startButton.getPreferredSize().height;
        Dimension stretchButton = new Dimension(0, buttonHeight);
        startButton.setMinimumSize(stretchButton);
        stopButton.setMinimumSize(stretchButton);
        startButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, buttonHeight));
        stopButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, buttonHeight));

        JPanel buttonBar = new JPanel(new GridBagLayout());
        GridBagConstraints btnGbc = new GridBagConstraints();
        btnGbc.fill = GridBagConstraints.BOTH;
        btnGbc.weighty = 1.0;
        btnGbc.gridy = 0;
        btnGbc.gridx = 0;
        btnGbc.weightx = 1.0;
        buttonBar.add(startButton, btnGbc);
        btnGbc.gridx = 1;
        btnGbc.insets = new Insets(0, 15, 0, 0);
        buttonBar.add(stopButton, btnGbc);

        JPanel bottom = new JPanel(new GridBagLayout());
        bottom.setAlignmentX(Component.LEFT_ALIGNMENT);
        GridBagConstraints bottomGbc = new GridBagConstraints();
        bottomGbc.gridx = 0;
        bottomGbc.weightx = 1.0;
        bottomGbc.fill = GridBagConstraints.HORIZONTAL;
        bottomGbc.anchor = GridBagConstraints.LINE_START;

        bottomGbc.gridy = 0;
        bottomGbc.insets = new Insets(0, 0, 0, 0);
        bottom.add(formatLabel, bottomGbc);
        bottomGbc.gridy = 1;
        bottom.add(hdrMethodLabel, bottomGbc);
        bottomGbc.gridy = 2;
        bottomGbc.insets = new Insets(8, 0, 0, 0);
        bottom.add(progressBar, bottomGbc);

        JPanel bppfLabelRow = new JPanel(new BorderLayout());
        bppfLabelRow.add(new JLabel("Pixelated"), BorderLayout.WEST);
        bppfFloorLabel.setHorizontalAlignment(JLabel.CENTER);
        bppfLabelRow.add(bppfFloorLabel, BorderLayout.CENTER);
        bppfLabelRow.add(new JLabel("Blurry"), BorderLayout.EAST);

        JLabel bppfHintLabel = new JLabel(
                "*bppf (Bit Pre Pixel Per Frame): Compression quality threshold (values below ~0.02 typically cause heavy artifacts)");
        bppfHintLabel.setForeground(new Color(0x888888));
        bppfHintLabel.setFont(bppfHintLabel.getFont().deriveFont(11f));
        bppfHintLabel.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));

        JPanel bppfPanel = new JPanel(new BorderLayout(4, 2));
        bppfPanel.add(bppfLabelRow, BorderLayout.NORTH);
        bppfPanel.add(bppfSlider, BorderLayout.CENTER);
        bppfPanel.add(bppfHintLabel, BorderLayout.SOUTH);
        bppfSlider.setMajorTickSpacing(1);
        bppfSlider.setSnapToTicks(true);
        bppfSlider.setToolTipText(
                "bppf quality floor: left keeps resolution but may look pixelated; right enforces quality and may downscale (blur)");
        ChangeListener bppfListener = event -> {
            CompressionPlanner.setSliderValue(bppfSlider.getValue());
            updateBppfFloorLabel();
            if (!bppfSlider.getValueIsAdjusting()) {
                onPreviewInputsChanged();
            }
        };
        bppfSlider.addChangeListener(bppfListener);
        CompressionPlanner.setSliderValue(CompressionPlanner.bppfDefaultSliderValue());
        updateBppfFloorLabel();

        JPanel fpsLabelRow = new JPanel(new BorderLayout());
        fpsLabelRow.add(new JLabel("Blurry"), BorderLayout.WEST);
        preferredFpsLabel.setHorizontalAlignment(JLabel.CENTER);
        fpsLabelRow.add(preferredFpsLabel, BorderLayout.CENTER);
        fpsLabelRow.add(new JLabel("Clarity"), BorderLayout.EAST);

        JLabel fpsHintLabel = new JLabel(
                "*At a fixed file size, higher frame rates leave less bitrate per frame and reduce visual quality");
        fpsHintLabel.setForeground(new Color(0x888888));
        fpsHintLabel.setFont(fpsHintLabel.getFont().deriveFont(11f));
        fpsHintLabel.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));

        JPanel fpsPanel = new JPanel(new BorderLayout(4, 2));
        fpsPanel.add(fpsLabelRow, BorderLayout.NORTH);
        fpsPanel.add(fpsSlider, BorderLayout.CENTER);
        fpsPanel.add(fpsHintLabel, BorderLayout.SOUTH);
        fpsSlider.setMajorTickSpacing(1);
        fpsSlider.setSnapToTicks(true);
        fpsSlider.setToolTipText(
                "Preferred FPS: left lowers frame rate (Blurry); right raises it (Clarity). At a fixed file size, higher FPS reduces per-frame quality");
        ChangeListener fpsListener = event -> {
            CompressionPlanner.setFpsSliderValue(fpsSlider.getValue());
            updatePreferredFpsLabel();
            if (!fpsSlider.getValueIsAdjusting()) {
                onPreviewInputsChanged();
            }
        };
        fpsSlider.addChangeListener(fpsListener);
        CompressionPlanner.setFpsSliderValue(CompressionPlanner.fpsDefaultSliderValue());
        updatePreferredFpsLabel();

        hdrToSdrCheckBox.setToolTipText(
                "Tone-map HDR video to SDR. Off by default; HDR often compresses as well or better.");
        hdrToSdrCheckBox.setMargin(new Insets(0, 0, 0, 0));
        hdrToSdrCheckBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        hdrToSdrCheckBox.addActionListener(event -> onPreviewInputsChanged());

        keepAudioQualityCheckBox.setToolTipText(
                "Use source audio bitrate when planning; video settings may downgrade to fit budget.");
        keepAudioQualityCheckBox.setMargin(new Insets(0, 0, 0, 0));
        keepAudioQualityCheckBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        keepAudioQualityCheckBox.addActionListener(event -> onPreviewInputsChanged());

        JPanel optionCheckboxes = new JPanel();
        optionCheckboxes.setLayout(new BoxLayout(optionCheckboxes, BoxLayout.X_AXIS));
        optionCheckboxes.add(hdrToSdrCheckBox);
        optionCheckboxes.add(Box.createHorizontalStrut(12));
        optionCheckboxes.add(keepAudioQualityCheckBox);

        JPanel targetFields = new JPanel();
        targetFields.setLayout(new BoxLayout(targetFields, BoxLayout.X_AXIS));
        targetFields.add(new JLabel("Media compress target"));
        targetFields.add(Box.createHorizontalStrut(4));
        targetSizeField.setToolTipText("Target output size in megabytes");
        targetSizeField.setMaximumSize(targetSizeField.getPreferredSize());
        targetFields.add(targetSizeField);
        targetFields.add(Box.createHorizontalStrut(4));
        targetFields.add(new JLabel("MB"));
        targetSizeField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                onTargetSizeDocumentChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                onTargetSizeDocumentChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                onTargetSizeDocumentChanged();
            }
        });

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gbc.weightx = 1.0;
        gbc.anchor = GridBagConstraints.LINE_START;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridy = 0;
        gbc.insets = new Insets(0, 0, 6, 0);
        form.add(wrapContent(targetFields), gbc);

        gbc.gridy = 1;
        gbc.insets = new Insets(0, 0, 6, 0);
        form.add(wrapContent(optionCheckboxes), gbc);

        gbc.gridy = 2;
        gbc.insets = new Insets(0, 0, 6, 0);
        form.add(wrapContent(bppfPanel), gbc);

        gbc.gridy = 3;
        gbc.insets = new Insets(0, 0, 6, 0);
        form.add(wrapContent(fpsPanel), gbc);

        gbc.gridy = 4;
        gbc.insets = new Insets(0, 0, 8, 0);
        form.add(wrapContent(dropPanel), gbc);

        gbc.gridy = 5;
        gbc.insets = new Insets(0, 0, 8, 0);
        form.add(wrapContent(bottom), gbc);

        gbc.gridy = 6;
        gbc.insets = new Insets(0, 0, 0, 0);
        form.add(wrapContent(buttonBar), gbc);
        setContentPane(form);
        updateButtonStates();
    }

    private void onTargetSizeDocumentChanged() {
        updateButtonStates();
        if (busy || selectedFile == null || !isVideoFile(selectedFile)) {
            return;
        }
        if (!isTargetValid()) {
            clearSpecLabels();
            previewDebounceTimer.stop();
            return;
        }
        previewDebounceTimer.restart();
    }

    private void onPreviewInputsChanged() {
        updateButtonStates();
        if (busy || selectedFile == null || !isVideoFile(selectedFile) || !isTargetValid()) {
            return;
        }
        schedulePreview();
    }

    private void schedulePreview() {
        if (busy || selectedFile == null || !isVideoFile(selectedFile) || !isTargetValid()) {
            return;
        }

        CompressionPlanner.setSliderValue(bppfSlider.getValue());
        CompressionPlanner.setFpsSliderValue(fpsSlider.getValue());

        long maxBytes;
        try {
            maxBytes = parseTargetBytes();
        } catch (IllegalArgumentException ex) {
            clearSpecLabels();
            updateButtonStates();
            return;
        }

        if (previewWorker != null) {
            previewWorker.cancel(true);
        }

        Path file = selectedFile;
        boolean hdrToSdr = hdrToSdrCheckBox.isSelected();
        boolean keepAudioQuality = keepAudioQualityCheckBox.isSelected();

        previewing = true;
        updateButtonStates();
        setFormatLabel("Estimating…");
        setHdrMethodLabel(EMPTY_LABEL);

        previewWorker = new SwingWorker<PreviewResult, Void>() {
            @Override
            protected PreviewResult doInBackground() throws Exception {
                long size = Files.size(file);
                if (size <= maxBytes) {
                    return PreviewResult.message("Already ≤ target — no compression needed");
                }

                Path ffmpeg = locator.resolve(p -> {
                });
                if (isCancelled()) {
                    return null;
                }

                VideoMetadata meta = VideoMetadata.probe(ffmpeg, file);
                if (isCancelled()) {
                    return null;
                }

                FfmpegCapabilities caps = FfmpegCapabilities.detect(ffmpeg);
                if (caps.encoders.isEmpty()) {
                    return PreviewResult.message("Preview unavailable: no encoders detected");
                }

                HwEncoderDetector.Encoder encoder = caps.encoders.get(0);
                EncodePlan plan = CompressionPlanner.plan(
                        meta,
                        meta,
                        maxBytes,
                        encoder.codecFamily(),
                        hdrToSdr,
                        keepAudioQuality);
                String formatLine = MediaCompressor.buildPlanSummary(0, meta, plan);
                String hdrLine = MediaCompressor.buildEncoderSummary(
                        encoder.codec(),
                        VideoFilterGraph.candidates(meta, plan, encoder, caps, true)
                                .get(0)
                                .hdrMethodLabel());
                return PreviewResult.spec(formatLine, hdrLine);
            }

            @Override
            protected void done() {
                if (this != previewWorker) {
                    return;
                }
                previewing = false;
                try {
                    if (isCancelled()) {
                        return;
                    }
                    PreviewResult result = get();
                    if (result == null) {
                        return;
                    }
                    if (result.formatLine != null) {
                        setFormatLabel(result.formatLine);
                        setHdrMethodLabel(result.hdrLine);
                    } else {
                        setFormatLabel(result.message);
                        setHdrMethodLabel(EMPTY_LABEL);
                    }
                } catch (Exception ex) {
                    if (isCancelled()) {
                        return;
                    }
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    String message = cause instanceof NoFeasiblePlanException
                            ? cause.getMessage()
                            : "Preview unavailable: " + cause.getMessage();
                    setFormatLabel(message);
                    setHdrMethodLabel(EMPTY_LABEL);
                } finally {
                    updateButtonStates();
                }
            }
        };
        previewWorker.execute();
    }

    private void selectFile(Path file) {
        if (busy) {
            return;
        }

        selectedFile = file;
        setDropFileName(file);
        updateButtonStates();

        if (isVideoFile(file)) {
            schedulePreview();
        } else {
            clearSpecLabels();
        }
    }

    private void startCompression() {
        if (busy || selectedFile == null || !isTargetValid()) {
            return;
        }

        Path file = selectedFile;
        long maxBytes;
        try {
            maxBytes = parseTargetBytes();
        } catch (IllegalArgumentException ex) {
            showError(ex.getMessage());
            return;
        }

        if (previewWorker != null) {
            previewWorker.cancel(true);
            previewing = false;
        }

        CompressionPlanner.setSliderValue(bppfSlider.getValue());
        CompressionPlanner.setFpsSliderValue(fpsSlider.getValue());

        busy = true;
        setDropZoneEnabled(false);
        targetSizeField.setEnabled(false);
        hdrToSdrCheckBox.setEnabled(false);
        keepAudioQualityCheckBox.setEnabled(false);
        bppfSlider.setEnabled(false);
        fpsSlider.setEnabled(false);
        progressBar.setIndeterminate(false);
        progressBar.setValue(0);
        setProgressText("Processing " + file.getFileName() + "…");
        updateButtonStates();

        compressWorker = new SwingWorker<Void, Integer>() {
            private String finalFormatLine = "";
            private String finalHdrMethod = "";
            private String finalMessage = "";
            private Path tempOutputPath;
            private long finalInputSize;
            private long finalOutputSize;
            private boolean needsSaveDialog;

            @Override
            protected Void doInBackground() throws Exception {
                long size = Files.size(file);
                finalInputSize = size;
                if (size <= maxBytes) {
                    finalMessage = "File is already ≤ " + AppConstants.formatMegabytes(maxBytes)
                            + " (" + formatSize(size) + "). No compression needed.";
                    needsSaveDialog = false;
                    return null;
                }

                String name = file.getFileName().toString().toLowerCase();
                if (isImage(name)) {
                    setProgressAsync("Compressing image…");
                    ImageCompressor.Result result = new ImageCompressor().compress(file, maxBytes);
                    tempOutputPath = result.output();
                    finalOutputSize = result.outputSize();
                    needsSaveDialog = true;
                    publish(100);
                    return null;
                }

                setProgressAsync("Preparing ffmpeg…");
                Path ffmpeg = locator.resolve(p -> setProgressAsync("Downloading ffmpeg… " + p + "%"));
                if (isCancelled()) {
                    return null;
                }

                MediaCompressor compressor = new MediaCompressor(ffmpeg, maxBytes);
                activeCompressor = compressor;
                try {
                    MediaCompressor.ProgressListener listener = new MediaCompressor.ProgressListener() {
                        @Override
                        public void onProgress(int percent, String statusLine) {
                            publish(percent);
                            setProgressAsync(statusLine);
                        }

                        @Override
                        public void onEncoderSelected(String encoderName) {
                            setHdrMethodAsync("Encoder: " + encoderName);
                        }

                        @Override
                        public void onPlanSelected(int attempt, String planSummary) {
                            setFormatAsync(planSummary);
                        }

                        @Override
                        public void onEncodeStarted(int attempt, String planSummary, String encoderSummary) {
                            setFormatAsync(planSummary);
                            setHdrMethodAsync(encoderSummary);
                        }

                        @Override
                        public void onEncodeFinished(int attempt, long outputBytes, boolean success) {
                            String resultLine = String.format(
                                    Locale.US,
                                    "Attempt %d finished: %d bytes (%s)%s",
                                    attempt + 1,
                                    outputBytes,
                                    formatSize(outputBytes),
                                    success ? "" : " — retrying");
                            setProgressAsync(resultLine);
                            logLine(resultLine);
                        }

                        @Override
                        public void onAudioFormatSelected(int audioKbps) {
                            setFormatAsync("AAC · " + audioKbps + "k mono");
                        }
                    };

                    MediaCompressor.Result result;
                    if (isAudio(name)) {
                        result = compressor.compressAudio(file, listener);
                    } else if (isVideo(name)) {
                        result = compressor.compressVideo(
                                file,
                                hdrToSdrCheckBox.isSelected(),
                                keepAudioQualityCheckBox.isSelected(),
                                listener);
                    } else {
                        throw new IllegalArgumentException(
                                "Unsupported file type. Use an image, video, or audio file.");
                    }

                    if (isCancelled()) {
                        return null;
                    }

                    if (result.formatLabel() != null) {
                        finalFormatLine = result.formatLabel();
                    }
                    if (result.encoder() != null && result.hdrMethod() != null) {
                        finalHdrMethod = MediaCompressor.buildEncoderSummary(
                                result.encoder(), result.hdrMethod());
                    } else if (result.encoder() != null) {
                        finalHdrMethod = "Encoder: " + result.encoder();
                    }
                    tempOutputPath = result.output();
                    finalOutputSize = result.outputSize();
                    needsSaveDialog = true;
                    publish(100);
                } finally {
                    if (activeCompressor == compressor) {
                        activeCompressor = null;
                    }
                }
                return null;
            }

            @Override
            protected void process(java.util.List<Integer> chunks) {
                if (!chunks.isEmpty()) {
                    progressBar.setValue(chunks.get(chunks.size() - 1));
                }
            }

            @Override
            protected void done() {
                restoreIdleUi();
                if (isCancelled()) {
                    setProgressText("Stopped.");
                    progressBar.setValue(0);
                    progressBar.setString("Stopped.");
                    return;
                }

                try {
                    get();
                    if (!finalFormatLine.isEmpty()) {
                        setFormatLabel(finalFormatLine);
                    }
                    if (!finalHdrMethod.isEmpty()) {
                        setHdrMethodLabel(finalHdrMethod);
                    }

                    if (needsSaveDialog && tempOutputPath != null) {
                        Path saved = promptSaveOutput(tempOutputPath);
                        if (saved != null) {
                            finalMessage = "Done: " + formatSize(finalInputSize)
                                    + " → " + formatSize(finalOutputSize);
                        } else {
                            finalMessage = "Save cancelled. Output kept at "
                                    + tempOutputPath.getFileName();
                        }
                    } else if (finalMessage.isEmpty() && tempOutputPath != null) {
                        finalMessage = "Done: " + formatSize(finalInputSize)
                                + " → " + formatSize(finalOutputSize);
                    }

                    setProgressText(finalMessage);
                    progressBar.setValue(100);
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof java.util.concurrent.CancellationException) {
                        setProgressText("Stopped.");
                        progressBar.setValue(0);
                        progressBar.setString("Stopped.");
                        return;
                    }
                    showError(cause.getMessage());
                    progressBar.setValue(0);
                    progressBar.setString("");
                }
            }
        };
        compressWorker.execute();
    }

    private void stopCompression() {
        if (!busy) {
            return;
        }
        if (activeCompressor != null) {
            activeCompressor.requestCancel();
        }
        if (compressWorker != null) {
            compressWorker.cancel(true);
        }
    }

    private void restoreIdleUi() {
        busy = false;
        setDropZoneEnabled(true);
        targetSizeField.setEnabled(true);
        hdrToSdrCheckBox.setEnabled(true);
        keepAudioQualityCheckBox.setEnabled(true);
        bppfSlider.setEnabled(true);
        fpsSlider.setEnabled(true);
        updateButtonStates();
    }

    private void updateButtonStates() {
        boolean canStart = selectedFile != null && !busy && !previewing && isTargetValid();
        startButton.setEnabled(canStart);
        stopButton.setEnabled(busy);
    }

    private boolean isTargetValid() {
        try {
            parseTargetBytes();
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private void updateBppfFloorLabel() {
        bppfFloorLabel.setText(CompressionPlanner.formatMinBppfLabel());
    }

    private void updatePreferredFpsLabel() {
        preferredFpsLabel.setText(CompressionPlanner.formatPreferredFpsLabel());
    }

    private static JPanel wrapContent(JComponent child) {
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBorder(BorderFactory.createEmptyBorder(0, CONTENT_H_INSET, 0, CONTENT_H_INSET));
        wrap.add(child, BorderLayout.CENTER);
        return wrap;
    }

    private boolean transferibleHasFiles(java.awt.datatransfer.Transferable transferable) {
        return transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
    }

    private void browse() {
        fileDialogs.chooseOpenFile(this, "Select media file", FileTypeFilter.MEDIA)
                .ifPresent(this::selectFile);
    }

    private long parseTargetBytes() {
        String text = targetSizeField.getText().trim();
        try {
            double megabytes = Double.parseDouble(text);
            if (megabytes <= 0) {
                throw new NumberFormatException("non-positive");
            }
            return (long) Math.floor(megabytes * 1024 * 1024);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Enter a positive target size in MB.");
        }
    }

    private void setDropFileName(Path file) {
        dropLabel.setText("<html><center>Drag and drop or click to select a file<br>"
                + escapeHtml(file.getFileName().toString()) + "</center></html>");
    }

    private Path promptSaveOutput(Path tempOutput) {
        Path defaultDir = UserPreferences.lastOutputDirectory(tempOutput);
        Optional<Path> chosen = fileDialogs.chooseSaveFile(
                this,
                "Save compressed file",
                defaultDir,
                tempOutput.getFileName().toString());
        if (chosen.isEmpty()) {
            return null;
        }

        Path target = chosen.get();
        try {
            moveOutput(tempOutput, target);
            UserPreferences.saveLastOutputDirectory(target);
            return target;
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(
                    this,
                    "Could not save file: " + ex.getMessage(),
                    "Save failed",
                    JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }

    private static void moveOutput(Path source, Path target) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            Files.delete(source);
        }
    }

    private void setDropZoneEnabled(boolean enabled) {
        dropPanel.setEnabled(enabled);
        dropLabel.setEnabled(enabled);
        if (enabled) {
            dropPanel.setBackground(dropPanelEnabledBg);
            dropPanel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        } else {
            Color disabledBg = UIManager.getColor("TextField.inactiveBackground");
            dropPanel.setBackground(disabledBg != null ? disabledBg : new Color(0xE5E5E5));
            dropPanel.setCursor(Cursor.getDefaultCursor());
        }
        if (dropTarget != null) {
            dropTarget.setActive(enabled);
        }
    }

    private void clearSpecLabels() {
        setFormatLabel(EMPTY_LABEL);
        setHdrMethodLabel(EMPTY_LABEL);
    }

    private void setFormatAsync(String message) {
        SwingUtilities.invokeLater(() -> setFormatLabel(message));
    }

    private void setHdrMethodAsync(String message) {
        SwingUtilities.invokeLater(() -> setHdrMethodLabel(message));
    }

    private void setFormatLabel(String message) {
        formatLabel.setText(message == null || message.isBlank() ? EMPTY_LABEL : message);
    }

    private void setHdrMethodLabel(String message) {
        hdrMethodLabel.setText(message == null || message.isBlank() ? EMPTY_LABEL : message);
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void logLine(String message) {
        System.out.println("[media-compress] " + message);
    }

    private void setProgressAsync(String message) {
        SwingUtilities.invokeLater(() -> progressBar.setString(message));
    }

    private void setProgressText(String message) {
        progressBar.setString(message);
    }

    private void showError(String message) {
        restoreIdleUi();
        clearSpecLabels();
        progressBar.setString("");
        JOptionPane.showMessageDialog(this, message, "Compression failed", JOptionPane.ERROR_MESSAGE);
    }

    private boolean isVideoFile(Path file) {
        return isVideo(file.getFileName().toString().toLowerCase(Locale.ROOT));
    }

    private boolean isImage(String name) {
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".gif") || name.endsWith(".webp");
    }

    private boolean isVideo(String name) {
        return name.endsWith(".mp4") || name.endsWith(".mov") || name.endsWith(".webm");
    }

    private boolean isAudio(String name) {
        return name.endsWith(".m4a") || name.endsWith(".mp3") || name.endsWith(".aac") || name.endsWith(".wav");
    }

    private String formatSize(long bytes) {
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0));
    }

    private static final class PreviewResult {
        private final String formatLine;
        private final String hdrLine;
        private final String message;

        private PreviewResult(String formatLine, String hdrLine, String message) {
            this.formatLine = formatLine;
            this.hdrLine = hdrLine;
            this.message = message;
        }

        static PreviewResult spec(String formatLine, String hdrLine) {
            return new PreviewResult(formatLine, hdrLine, null);
        }

        static PreviewResult message(String message) {
            return new PreviewResult(null, null, message);
        }
    }
}
