package com.brook.tools.mediacompress;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class HwEncoderDetector {

    public enum Encoder {
        HEVC_VIDEOTOOLBOX("hevc_videotoolbox", CodecFamily.HEVC),
        HEVC_NVENC("hevc_nvenc", CodecFamily.HEVC),
        HEVC_QSV("hevc_qsv", CodecFamily.HEVC),
        HEVC_AMF("hevc_amf", CodecFamily.HEVC),
        HEVC_VAAPI("hevc_vaapi", CodecFamily.HEVC),
        LIBX265("libx265", CodecFamily.HEVC),

        H264_VIDEOTOOLBOX("h264_videotoolbox", CodecFamily.H264),
        H264_NVENC("h264_nvenc", CodecFamily.H264),
        H264_QSV("h264_qsv", CodecFamily.H264),
        H264_AMF("h264_amf", CodecFamily.H264),
        H264_VAAPI("h264_vaapi", CodecFamily.H264),
        LIBX264("libx264", CodecFamily.H264);

        private final String codec;
        private final CodecFamily family;

        Encoder(String codec, CodecFamily family) {
            this.codec = codec;
            this.family = family;
        }

        public String codec() {
            return codec;
        }

        public CodecFamily codecFamily() {
            return family;
        }

        public boolean isVideoToolbox() {
            return this == HEVC_VIDEOTOOLBOX || this == H264_VIDEOTOOLBOX;
        }

        public boolean isNvenc() {
            return this == HEVC_NVENC || this == H264_NVENC;
        }

        public boolean isQsv() {
            return this == HEVC_QSV || this == H264_QSV;
        }

        public boolean isVaapi() {
            return this == HEVC_VAAPI || this == H264_VAAPI;
        }

        public boolean isAmf() {
            return this == HEVC_AMF || this == H264_AMF;
        }

        public boolean isHevc() {
            return family == CodecFamily.HEVC;
        }
    }

    public static final class DetectResult {
        public final List<Encoder> chain;
        public final boolean hasLibplacebo;
        public final boolean isMac;
        public final boolean isWindows;

        public DetectResult(List<Encoder> chain,
                boolean hasLibplacebo,
                boolean isMac,
                boolean isWindows) {
            this.chain = chain;
            this.hasLibplacebo = hasLibplacebo;
            this.isMac = isMac;
            this.isWindows = isWindows;
        }
    }

    private final List<Encoder> chain = new ArrayList<>();
    private boolean hasLibplacebo = false;
    private final boolean isMac;
    private final boolean isWindows;

    public HwEncoderDetector() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        this.isMac = os.contains("mac") || os.contains("darwin");
        this.isWindows = os.contains("win");
    }

    /**
     * 偵測可用 encoder 與 libplacebo 支援。
     */
    public DetectResult detectWithFeatures(Path ffmpeg) throws Exception {
        if (!chain.isEmpty()) {
            return new DetectResult(chain, hasLibplacebo, isMac, isWindows);
        }

        String encodersOutput = readEncoderList(ffmpeg);
        String filtersOutput = readFilterList(ffmpeg);

        // 偵測 libplacebo
        this.hasLibplacebo = filtersOutput.contains("libplacebo");

        if (isMac) {
            addIfPresent(encodersOutput, Encoder.HEVC_VIDEOTOOLBOX);
            addIfPresent(encodersOutput, Encoder.LIBX265);
            addIfPresent(encodersOutput, Encoder.H264_VIDEOTOOLBOX);
        } else if (isWindows) {
            addIfPresent(encodersOutput, Encoder.HEVC_NVENC);
            addIfPresent(encodersOutput, Encoder.HEVC_QSV);
            addIfPresent(encodersOutput, Encoder.HEVC_AMF);
            addIfPresent(encodersOutput, Encoder.LIBX265);
            addIfPresent(encodersOutput, Encoder.H264_NVENC);
            addIfPresent(encodersOutput, Encoder.H264_QSV);
            addIfPresent(encodersOutput, Encoder.H264_AMF);
        } else {
            // Linux
            addIfPresent(encodersOutput, Encoder.HEVC_NVENC);
            addIfPresent(encodersOutput, Encoder.HEVC_QSV);
            addIfPresent(encodersOutput, Encoder.HEVC_AMF);
            addIfPresent(encodersOutput, Encoder.HEVC_VAAPI);
            addIfPresent(encodersOutput, Encoder.LIBX265);
            addIfPresent(encodersOutput, Encoder.H264_NVENC);
            addIfPresent(encodersOutput, Encoder.H264_QSV);
            addIfPresent(encodersOutput, Encoder.H264_AMF);
            addIfPresent(encodersOutput, Encoder.H264_VAAPI);
        }
        addIfPresent(encodersOutput, Encoder.LIBX264);

        if (chain.isEmpty()) {
            chain.add(Encoder.LIBX265);
            chain.add(Encoder.LIBX264);
        }

        return new DetectResult(chain, hasLibplacebo, isMac, isWindows);
    }

    // 若你還有其他地方用舊的 detect()，可以保留並內部呼叫 detectWithFeatures
    public List<Encoder> detect(Path ffmpeg) throws Exception {
        return detectWithFeatures(ffmpeg).chain;
    }

    private void addIfPresent(String encodersOutput, Encoder encoder) {
        if (encodersOutput.contains(" " + encoder.codec())
                || encodersOutput.contains("\t" + encoder.codec())) {
            chain.add(encoder);
        }
    }

    private String readEncoderList(Path ffmpeg) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                ffmpeg.toString(), "-hide_banner", "-encoders");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        StringBuilder sb = new StringBuilder();
        try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        process.waitFor();
        return sb.toString();
    }

    private String readFilterList(Path ffmpeg) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                ffmpeg.toString(), "-hide_banner", "-filters");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        StringBuilder sb = new StringBuilder();
        try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        process.waitFor();
        return sb.toString();
    }
}