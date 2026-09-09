package com.brook.tools.mediacompress;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.List;

public final class FfmpegCapabilities {
    public final List<HwEncoderDetector.Encoder> encoders;
    public final boolean hasLibplacebo;
    public final boolean hasScaleVt;
    public final boolean hasTonemapVideotoolbox;
    public final boolean hasZscale;
    public final boolean hasTonemap;
    public final boolean isMac;
    public final boolean isWindows;

    public FfmpegCapabilities(
            List<HwEncoderDetector.Encoder> encoders,
            boolean hasLibplacebo,
            boolean hasScaleVt,
            boolean hasTonemapVideotoolbox,
            boolean hasZscale,
            boolean hasTonemap,
            boolean isMac,
            boolean isWindows) {
        this.encoders = encoders;
        this.hasLibplacebo = hasLibplacebo;
        this.hasScaleVt = hasScaleVt;
        this.hasTonemapVideotoolbox = hasTonemapVideotoolbox;
        this.hasZscale = hasZscale;
        this.hasTonemap = hasTonemap;
        this.isMac = isMac;
        this.isWindows = isWindows;
    }

    public static FfmpegCapabilities detect(Path ffmpeg) throws Exception {
        HwEncoderDetector detector = new HwEncoderDetector();
        HwEncoderDetector.DetectResult base = detector.detectWithFeatures(ffmpeg);
        String filters = readFilterList(ffmpeg);
        return new FfmpegCapabilities(
                base.chain,
                base.hasLibplacebo,
                containsFilter(filters, "scale_vt"),
                containsFilter(filters, "tonemap_videotoolbox"),
                containsFilter(filters, "zscale"),
                containsFilter(filters, "tonemap"),
                base.isMac,
                base.isWindows);
    }

    private static boolean containsFilter(String filters, String name) {
        return filters.contains(name);
    }

    private static String readFilterList(Path ffmpeg) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-filters");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        process.waitFor();
        return sb.toString();
    }
}
