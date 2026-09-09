package com.brook.tools.mediacompress;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

public final class MediaCompressor {
    private static final int MAX_ENCODE_ATTEMPTS = 8;
    private static final int MAX_ENCODE_PROGRESS = 95;
    private static final long PROCESS_EXIT_TIMEOUT_MS = 5000;
    private static final int DELETE_RETRY_ATTEMPTS = 12;
    private static final long DELETE_RETRY_BASE_MS = 50;

    public interface ProgressListener {
        void onProgress(int percent, String statusLine);

        void onEncoderSelected(String encoderName);

        default void onPlanSelected(int attempt, String planSummary) {
        }

        default void onEncodeStarted(int attempt, String planSummary, String encodeDetails) {
        }

        default void onEncodeFinished(int attempt, long outputBytes, boolean success) {
        }

        default void onAudioFormatSelected(int audioKbps) {
        }
    }

    public record Result(
            Path output,
            long inputSize,
            long outputSize,
            String encoder,
            String formatLabel,
            String hdrMethod) {
        public Result(Path output, long inputSize, long outputSize, String encoder) {
            this(output, inputSize, outputSize, encoder, null, null);
        }

        public Result(Path output, long inputSize, long outputSize, String encoder, String formatLabel) {
            this(output, inputSize, outputSize, encoder, formatLabel, null);
        }
    }

    private enum EncodeOutcome {
        SUCCESS,
        SIZE_EXCEEDED,
        FAILED
    }

    private final Path ffmpeg;
    private final long maxBytes;
    private volatile Process activeProcess;
    private volatile boolean cancelled;

    public MediaCompressor(Path ffmpeg, long maxBytes) {
        this.ffmpeg = ffmpeg;
        this.maxBytes = maxBytes;
    }

    public void requestCancel() {
        cancelled = true;
        Process process = activeProcess;
        if (process != null && process.isAlive()) {
            destroyProcess(process);
        }
    }

    public void cancelActiveEncode() {
        requestCancel();
    }

    public Result compressVideo(Path input, boolean hdrToSdr, ProgressListener listener) throws Exception {
        cancelled = false;
        long inputSize = Files.size(input);
        if (inputSize <= maxBytes) {
            throw new IllegalStateException("File is already ≤ " + AppConstants.formatMegabytes(maxBytes) + ".");
        }

        VideoMetadata meta = VideoMetadata.probe(ffmpeg, input);
        FfmpegCapabilities caps = FfmpegCapabilities.detect(ffmpeg);
        logLine(String.format(
                Locale.US,
                "Source: %d×%d @ %.2ffps, %.1fs · target ≤ %s",
                meta.width(),
                meta.height(),
                meta.sourceFps(),
                meta.durationSeconds(),
                AppConstants.formatMegabytes(maxBytes)));

        Path output = outputPath(input, "mp4");
        Path tempOutput = tempOutputPath(input, "mp4");
        Exception lastError = null;
        String lastFormatLabel = null;

        try {
        for (HwEncoderDetector.Encoder encoder : caps.encoders) {
            ensureNotCancelled();
            if (meta.isHdr() && !hdrToSdr && !encoder.isHevc()) {
                logLine("Skipping " + encoder.codec() + " for HDR passthrough.");
                continue;
            }
            if (listener != null) {
                listener.onEncoderSelected(encoder.codec());
            }

            CodecFamily codec = encoder.codecFamily();
            EncodePlan plan = null;

            for (int attempt = 0; attempt < MAX_ENCODE_ATTEMPTS; attempt++) {
                ensureNotCancelled();
                try {
                    plan = CompressionPlanner.plan(
                            plan == null ? meta : plan,
                            meta,
                            maxBytes,
                            codec,
                            hdrToSdr);
                } catch (NoFeasiblePlanException ex) {
                    logLine("Planner: " + ex.getMessage());
                    break;
                }

                String planSummary = buildPlanSummary(attempt, meta, plan);
                if (listener != null) {
                    listener.onPlanSelected(attempt, planSummary);
                }
                lastFormatLabel = planSummary;
                logLine(planSummary);

                deleteOutputArtifacts(output, tempOutput);
                StringBuilder errorLog = new StringBuilder();

                EncodeAttemptResult attemptResult = runEncodeWithFallbacks(
                        input,
                        tempOutput,
                        meta,
                        plan,
                        encoder,
                        caps,
                        attempt,
                        listener,
                        errorLog,
                        (percent, line) -> {
                            if (listener != null) {
                                listener.onProgress(Math.min(MAX_ENCODE_PROGRESS, percent), line);
                            }
                        });

                if (attemptResult.outcome == EncodeOutcome.FAILED) {
                    lastError = new IOException(
                            "FFmpeg failed with " + encoder.codec() + ".\nReason:\n" + errorLog);
                    deleteOutputArtifacts(output, tempOutput);
                    break;
                }

                if (attemptResult.outcome == EncodeOutcome.SUCCESS) {
                    long outSize = attemptResult.sizeBytes();
                    finalizeOutput(tempOutput, output);
                    logEncodeOutcome(attempt, outSize, "accepted");
                    if (listener != null) {
                        listener.onEncodeFinished(attempt, outSize, true);
                    }
                    return new Result(
                            output,
                            inputSize,
                            outSize,
                            encoder.codec(),
                            lastFormatLabel,
                            attemptResult.hdrMethod());
                }

                if (attemptResult.outcome == EncodeOutcome.SIZE_EXCEEDED) {
                    String decision = attemptResult.encodeCompleted()
                            ? "oversize, retrying"
                            : "projected oversize, retrying";
                    logEncodeOutcome(attempt, attemptResult.sizeBytes(), decision);
                    if (listener != null) {
                        listener.onEncodeFinished(attempt, attemptResult.sizeBytes(), false);
                    }
                    deleteOutputArtifacts(output, tempOutput);
                    continue;
                }

                logEncodeOutcome(attempt, 0, "no output file, retrying");
                if (listener != null) {
                    listener.onEncodeFinished(attempt, 0, false);
                }
                deleteOutputArtifacts(output, tempOutput);
            }
        }

        deleteOutputArtifacts(output, tempOutput);
        throw new IOException(buildCompressionFailureMessage(meta, lastError));
        } catch (CancellationException ex) {
            deleteOutputArtifacts(output, tempOutput);
            throw ex;
        }
    }

    public Result compressAudio(Path input, ProgressListener listener) throws Exception {
        cancelled = false;
        long inputSize = Files.size(input);
        if (inputSize <= maxBytes) {
            throw new IllegalStateException("File is already ≤ " + AppConstants.formatMegabytes(maxBytes) + ".");
        }

        int[] bitrates = { 128, 96, 64, 48 };
        Path output = outputPath(input, "m4a");
        Path tempOutput = tempOutputPath(input, "m4a");
        String lastFormatLabel = null;
        try {
        for (int kbps : bitrates) {
            ensureNotCancelled();
            lastFormatLabel = "AAC · " + kbps + "k mono";
            logLine("Audio attempt: " + lastFormatLabel);
            if (listener != null) {
                listener.onAudioFormatSelected(kbps);
            }
            List<String> args = new ArrayList<>();
            args.add(ffmpeg.toString());
            args.add("-y");
            args.add("-i");
            args.add(input.toString());
            args.add("-ac");
            args.add("1");
            args.add("-c:a");
            args.add("aac");
            args.add("-b:a");
            args.add(kbps + "k");
            args.add(tempOutput.toString());

            deleteOutputArtifacts(output, tempOutput);
            StringBuilder audioErrorLog = new StringBuilder();
            ProcessResult outcome = runProcess(
                    args,
                    0,
                    tempOutput,
                    listener == null ? null : (p, line) -> listener.onProgress(Math.min(MAX_ENCODE_PROGRESS, p), line),
                    audioErrorLog);

            if (outcome.outcome == EncodeOutcome.FAILED) {
                deleteOutputArtifacts(output, tempOutput);
                throw new IOException("ffmpeg audio encode failed. Reason:\n" + audioErrorLog);
            }
            if (outcome.outcome == EncodeOutcome.SUCCESS && outcome.sizeBytes <= maxBytes) {
                finalizeOutput(tempOutput, output);
                return new Result(output, inputSize, outcome.sizeBytes, "aac", lastFormatLabel);
            }
            deleteOutputArtifacts(output, tempOutput);
        }
        deleteOutputArtifacts(output, tempOutput);
        throw new IOException("unknown error. audio compress failed.");
        } catch (CancellationException ex) {
            deleteOutputArtifacts(output, tempOutput);
            throw ex;
        }
    }

    static String buildPlanSummary(int attempt, VideoMetadata meta, EncodePlan plan) {
        // int shortSide = ScaleFilter.targetShortSide(plan);
        double bppf = CompressionPlanner.bitsPerPixelPerFrame(
                plan.videoKbps(), plan.outputWidth(), plan.outputHeight(), plan.outFps());
        return String.format(
                Locale.US,
                "Attempt %d · %s x %s @ %dfps · bitrate %dkbps · audio %dk · bppf=%.4f",
                attempt + 1,
                plan.outputWidth(),
                plan.outputHeight(),
                plan.outFps(),
                plan.videoKbps(),
                plan.audioKbps(),
                bppf);
    }

    static String buildEncoderSummary(String encoderCodec, String hdrMethod) {
        return "Encoder: " + encoderCodec + " · " + hdrMethod;
    }

    private static void logLine(String message) {
        System.out.println("[media-compress] " + message);
    }

    private void logEncodeOutcome(int attempt, long outputBytes, String decision) {
        logLine(String.format(
                Locale.US,
                "Attempt %d finished: %d bytes (%s), limit %d bytes (%s) -> %s",
                attempt + 1,
                outputBytes,
                AppConstants.formatMegabytes(outputBytes),
                maxBytes,
                AppConstants.formatMegabytes(maxBytes),
                decision));
    }

    private record EncodeAttemptResult(
            EncodeOutcome outcome,
            String hdrMethod,
            long sizeBytes,
            boolean encodeCompleted) {
    }

    private record ProcessResult(EncodeOutcome outcome, long sizeBytes, boolean encodeCompleted) {
    }

    private EncodeAttemptResult runEncodeWithFallbacks(
            Path input,
            Path output,
            VideoMetadata meta,
            EncodePlan plan,
            HwEncoderDetector.Encoder encoder,
            FfmpegCapabilities caps,
            int attempt,
            ProgressListener listener,
            StringBuilder errorLog,
            BiConsumer<Integer, String> progress) throws Exception {

        for (boolean allowHwaccel : new boolean[] { true, false }) {
            List<VideoFilterGraph.FilterCandidate> candidates = VideoFilterGraph.candidates(
                    meta, plan, encoder, caps, allowHwaccel);

            for (VideoFilterGraph.FilterCandidate candidate : candidates) {
                ensureNotCancelled();
                if (candidate.pathType() == VideoFilterGraph.PathType.MAC_VT && !allowHwaccel) {
                    continue;
                }

                deleteIfExistsQuiet(output);
                ProcessResult processResult = runEncode(
                        input,
                        output,
                        meta,
                        plan,
                        encoder,
                        caps,
                        allowHwaccel,
                        candidate.filter(),
                        candidate.hdrMethodLabel(),
                        attempt,
                        listener,
                        progress,
                        errorLog);

                if (processResult.outcome != EncodeOutcome.FAILED) {
                    return new EncodeAttemptResult(
                            processResult.outcome,
                            candidate.hdrMethodLabel(),
                            processResult.sizeBytes,
                            processResult.encodeCompleted);
                }
            }
        }

        return new EncodeAttemptResult(EncodeOutcome.FAILED, null, 0, false);
    }

    private ProcessResult runEncode(
            Path input,
            Path output,
            VideoMetadata meta,
            EncodePlan plan,
            HwEncoderDetector.Encoder encoder,
            FfmpegCapabilities caps,
            boolean allowHwaccel,
            String videoFilter,
            String hdrMethod,
            int attempt,
            ProgressListener listener,
            BiConsumer<Integer, String> progress,
            StringBuilder errorLog) throws Exception {

        List<String> args = new ArrayList<>();
        args.add(ffmpeg.toString());
        args.add("-y");

        if (allowHwaccel) {
            addHwaccelArgs(args, encoder, caps, videoFilter, meta, plan);
        }

        args.add("-i");
        args.add(input.toString());

        args.add("-vf");
        args.add(videoFilter);

        configureVideoEncoder(args, encoder, plan, meta);

        if (plan.hdrToSdr()) {
            addSdrColorMetadata(args);
        } else if (meta.isHdr()) {
            addHdrColorMetadata(args, meta);
        }

        args.add("-pix_fmt");
        args.add(resolvePixFmt(meta, plan, encoder, caps, allowHwaccel, videoFilter));

        args.add("-ac");
        args.add("1");
        args.add("-c:a");
        args.add("aac");
        args.add("-b:a");
        args.add(plan.audioKbps() + "k");

        args.add("-movflags");
        args.add("+faststart");
        args.add(output.toString());

        String planSummary = buildPlanSummary(attempt, meta, plan);
        String encoderSummary = buildEncoderSummary(encoder.codec(), hdrMethod);
        logLine(encoderSummary + " · -vf " + videoFilter);
        logLine("ffmpeg " + String.join(" ", args));
        if (listener != null) {
            listener.onEncodeStarted(attempt, planSummary, encoderSummary);
        }

        return runProcess(args, meta.durationSeconds(), output, progress, errorLog);
    }

    private void configureVideoEncoder(
            List<String> args,
            HwEncoderDetector.Encoder encoder,
            EncodePlan plan,
            VideoMetadata meta) {
        args.add("-c:v");
        args.add(encoder.codec());

        if (!plan.hdrToSdr() && meta.isHdr() && encoder.isHevc()) {
            args.add("-profile:v");
            args.add("main10");
        }

        switch (encoder) {
            case HEVC_VIDEOTOOLBOX, H264_VIDEOTOOLBOX -> addVbrVideoArgs(args, plan.videoKbps());
            case HEVC_NVENC, H264_NVENC -> {
                args.add("-preset");
                args.add("p4");
                addVbrVideoArgs(args, plan.videoKbps());
            }
            case HEVC_QSV, H264_QSV, HEVC_AMF, H264_AMF -> addVbrVideoArgs(args, plan.videoKbps());
            case LIBX265 -> {
                args.add("-preset");
                args.add("superfast");
                addVbrVideoArgs(args, plan.videoKbps());
            }
            case LIBX264 -> {
                args.add("-preset");
                args.add("ultrafast");
                args.add("-tune");
                args.add("fastdecode");
                addVbrVideoArgs(args, plan.videoKbps());
            }
            default -> addVbrVideoArgs(args, plan.videoKbps());
        }

        if (encoder.isHevc()) {
            args.add("-tag:v");
            args.add("hvc1");
        }
    }

    private String resolvePixFmt(
            VideoMetadata meta,
            EncodePlan plan,
            HwEncoderDetector.Encoder encoder,
            FfmpegCapabilities caps,
            boolean allowHwaccel,
            String videoFilter) {
        if (caps.isMac && encoder.isVideoToolbox() && allowHwaccel
                && VideoFilterGraph.usesVtHardware(videoFilter)) {
            return "nv12";
        }
        if (!plan.hdrToSdr() && meta.isHdr() && encoder.isHevc()) {
            if (caps.isWindows && (encoder.isNvenc() || encoder.isQsv() || encoder.isAmf())) {
                return "p010le";
            }
            return "yuv420p10le";
        }
        return "yuv420p";
    }

    private static void addSdrColorMetadata(List<String> args) {
        args.add("-color_primaries");
        args.add("bt709");
        args.add("-color_trc");
        args.add("bt709");
        args.add("-colorspace");
        args.add("bt709");
    }

    private static void addHdrColorMetadata(List<String> args, VideoMetadata meta) {
        args.add("-color_primaries");
        args.add(normalizeColorValue(meta.colorPrimaries(), "bt2020"));
        args.add("-color_trc");
        args.add(normalizeHdrTransfer(meta));
        args.add("-colorspace");
        args.add(normalizeColorValue(meta.colorSpace(), "bt2020nc"));
    }

    private static String normalizeHdrTransfer(VideoMetadata meta) {
        if (meta.isHlg()) {
            return "arib-std-b67";
        }
        if (meta.isPq()) {
            return "smpte2084";
        }
        return normalizeColorValue(meta.colorTransfer(), "smpte2084");
    }

    private static String normalizeColorValue(String value, String fallback) {
        if (value == null || value.isBlank() || "unknown".equalsIgnoreCase(value)) {
            return fallback;
        }
        return value;
    }

    private void addHwaccelArgs(
            List<String> args,
            HwEncoderDetector.Encoder encoder,
            FfmpegCapabilities caps,
            String videoFilter,
            VideoMetadata meta,
            EncodePlan plan) {
        if (caps.isMac && encoder.isVideoToolbox()) {
            args.add("-hwaccel");
            args.add("videotoolbox");
            if (VideoFilterGraph.usesVtHardware(videoFilter)) {
                args.add("-hwaccel_output_format");
                args.add("videotoolbox_vld");
            }
        } else if (caps.isWindows && !plan.hdrToSdr() && !meta.isHdr()) {
            args.add("-hwaccel");
            args.add("auto");
        }
    }

    private ProcessResult runProcess(
            List<String> args,
            double durationSec,
            Path outputFile,
            BiConsumer<Integer, String> progress,
            StringBuilder errorLog) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        activeProcess = process;

        String lastFps = null;
        String lastSpeed = null;
        List<String> recentLogLines = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (cancelled) {
                    process.destroyForcibly();
                    break;
                }
                recentLogLines.add(line);
                if (recentLogLines.size() > 15) {
                    recentLogLines.remove(0);
                }

                if (!process.isAlive()) {
                    break;
                }
                Double time = ProgressParser.parseTimeSeconds(line);
                String fps = ProgressParser.parseFps(line);
                if (fps != null) {
                    lastFps = fps;
                }
                String speed = ProgressParser.parseSpeed(line);
                if (speed != null) {
                    lastSpeed = speed;
                }

                if (time != null && durationSec > 0 && progress != null) {
                    int percent = (int) Math.min(MAX_ENCODE_PROGRESS, (time / durationSec) * 100);
                    progress.accept(percent, buildStatus(percent, time, lastFps, lastSpeed));
                }
            }
        } finally {
            waitForProcessExit(process);
            if (activeProcess == process) {
                activeProcess = null;
            }
        }

        if (cancelled) {
            deleteIfExistsQuiet(outputFile);
            throw new CancellationException("Encoding cancelled.");
        }

        int code = process.exitValue();

        if (code != 0) {
            errorLog.append(String.join("\n", recentLogLines));
            deleteIfExistsQuiet(outputFile);
            return new ProcessResult(EncodeOutcome.FAILED, 0, false);
        }

        if (!Files.isRegularFile(outputFile)) {
            return new ProcessResult(EncodeOutcome.FAILED, 0, false);
        }

        long size = Files.size(outputFile);
        if (size > maxBytes) {
            deleteIfExistsQuiet(outputFile);
            return new ProcessResult(EncodeOutcome.SIZE_EXCEEDED, size, true);
        }
        return new ProcessResult(EncodeOutcome.SUCCESS, size, true);
    }

    private void addVbrVideoArgs(List<String> args, int videoKbps) {
        args.add("-b:v");
        args.add(videoKbps + "k");
        args.add("-maxrate");
        args.add(videoKbps + "k");
        args.add("-bufsize");
        args.add(videoKbps * 2 + "k");
    }

    private String buildCompressionFailureMessage(VideoMetadata meta, Exception lastError) {
        long maxSeconds = CompressionPlanner.maxSupportedDurationSeconds(maxBytes);
        String base = "Could not compress below " + AppConstants.formatMegabytes(maxBytes) + ".";
        if (meta.durationSeconds() > maxSeconds) {
            base += String.format(
                    Locale.US,
                    " At minimum settings, videos longer than ~%d seconds may not fit in %s.",
                    maxSeconds,
                    AppConstants.formatMegabytes(maxBytes));
        }
        if (lastError != null && lastError.getMessage() != null) {
            base = lastError.getMessage();
        }
        return base;
    }

    private String buildStatus(int percent, double timeSec, String fps, String speed) {
        int h = (int) (timeSec / 3600);
        int m = (int) ((timeSec % 3600) / 60);
        double s = timeSec % 60;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "Compressing… %d%% · time %02d:%02d:%04.1f", percent, h, m, s));
        if (fps != null) {
            sb.append(" · ").append(fps).append(" fps");
        }
        if (speed != null) {
            sb.append(" · ").append(speed).append("× realtime");
        }
        return sb.toString();
    }

    private Path outputPath(Path input, String ext) {
        String name = input.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        Path parent = input.getParent() != null ? input.getParent() : Path.of(".");
        return parent.resolve(base + ".compressed." + ext);
    }

    private Path tempOutputPath(Path input, String ext) {
        String name = input.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        Path parent = input.getParent() != null ? input.getParent() : Path.of(".");
        return parent.resolve(base + ".compressing." + ext);
    }

    private void finalizeOutput(Path tempOutput, Path output) throws IOException {
        deleteIfExistsQuiet(output);
        if (Files.isRegularFile(tempOutput)) {
            Files.move(tempOutput, output, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteOutputArtifacts(Path output, Path tempOutput) throws IOException {
        deleteIfExistsQuiet(tempOutput);
        deleteIfExistsQuiet(output);
    }

    private void deleteIfExistsQuiet(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        int attempts = isWindows() ? DELETE_RETRY_ATTEMPTS : 1;
        IOException lastError = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                Files.deleteIfExists(path);
                if (!Files.exists(path)) {
                    return;
                }
            } catch (IOException ex) {
                lastError = ex;
            }
            if (attempt + 1 < attempts) {
                try {
                    Thread.sleep(DELETE_RETRY_BASE_MS * (attempt + 1));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while deleting " + path, ex);
                }
            }
        }
        if (Files.exists(path) && lastError != null) {
            throw lastError;
        }
    }

    private void ensureNotCancelled() throws CancellationException {
        if (cancelled) {
            throw new CancellationException("Encoding cancelled.");
        }
    }

    private void waitForProcessExit(Process process) throws InterruptedException {
        if (!process.waitFor(PROCESS_EXIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            destroyProcess(process);
            process.waitFor(2, TimeUnit.SECONDS);
        }
    }

    private void destroyProcess(Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }
        if (isWindows()) {
            try {
                Process killer = new ProcessBuilder(
                        "taskkill", "/F", "/T", "/PID", Long.toString(process.pid()))
                        .redirectErrorStream(true)
                        .start();
                killer.waitFor(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
        process.destroyForcibly();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
