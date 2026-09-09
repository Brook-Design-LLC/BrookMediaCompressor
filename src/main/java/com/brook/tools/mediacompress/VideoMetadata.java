package com.brook.tools.mediacompress;

import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record VideoMetadata(
        double durationSeconds,
        int width,
        int height,
        double sourceFps,
        boolean isHdr,
        String colorTransfer,
        String colorPrimaries,
        String colorSpace,
        int audioChannels,
        int sourceVideoKbps,
        int sourceAudioKbps) {
    public boolean isHlg() {
        return "arib-std-b67".equalsIgnoreCase(colorTransfer);
    }

    public boolean isPq() {
        return "smpte2084".equalsIgnoreCase(colorTransfer);
    }

    public static VideoMetadata probe(Path ffmpeg, Path input) throws Exception {
        String fileName = ffmpeg.getFileName().toString();
        Path ffprobe = ffmpeg.resolveSibling(fileName.replace("ffmpeg", "ffprobe"));

        ProcessBuilder pb = new ProcessBuilder(
                ffprobe.toString(),
                "-v", "error",
                "-print_format", "json",
                "-show_format",
                "-show_streams",
                input.toString());
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String json = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("ffprobe failed with exit code " + exitCode);
        }

        VideoMetadata meta = parseProbeJson(json);
        logProbeResult(input, meta);
        return meta;
    }

    static VideoMetadata parseProbeJson(String json) {
        double duration = parseFormatDuration(json);
        StreamInfo video = parseVideoStream(json);
        StreamInfo audio = parseAudioStream(json);

        int width = video.width;
        int height = video.height;
        int rotation = video.rotation;
        if (Math.abs(rotation) == 90 || Math.abs(rotation) == 270) {
            int temp = width;
            width = height;
            height = temp;
        }

        if (duration <= 0) {
            throw new IllegalStateException("Could not read video duration from input file.");
        }

        int sourceAudioKbps = audio.bitrateKbps > 0 ? audio.bitrateKbps : 128;
        return new VideoMetadata(
                duration,
                width,
                height,
                video.fps > 0 ? video.fps : 30.0,
                video.isHdr,
                video.colorTransfer,
                video.colorPrimaries,
                video.colorSpace,
                audio.channels > 0 ? audio.channels : 2,
                video.bitrateKbps,
                sourceAudioKbps);
    }

    private static void logProbeResult(Path input, VideoMetadata meta) {
        System.out.println(String.format(
                Locale.US,
                "[media-compress] Probe %s: %dx%d, %.3fs, %.3ffps, audio %dkbps",
                input.getFileName(),
                meta.width(),
                meta.height(),
                meta.durationSeconds(),
                meta.sourceFps(),
                meta.sourceAudioKbps()));
    }

    private static double parseFormatDuration(String json) {
        Matcher matcher = Pattern.compile("\"duration\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(extractSection(json, "\"format\""));
        if (matcher.find()) {
            try {
                return Double.parseDouble(matcher.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private static StreamInfo parseVideoStream(String json) {
        String section = findStreamSection(json, "video");
        StreamInfo info = new StreamInfo();
        if (section == null) {
            return info;
        }
        info.width = parseIntField(section, "width");
        info.height = parseIntField(section, "height");
        info.fps = parseFps(parseStringField(section, "r_frame_rate"));
        info.bitrateKbps = parseKbps(parseStringField(section, "bit_rate"));
        info.colorTransfer = parseStringField(section, "color_transfer");
        info.colorPrimaries = parseStringField(section, "color_primaries");
        info.colorSpace = parseStringField(section, "color_space");
        info.pixFmt = parseStringField(section, "pix_fmt");
        info.bitsPerRawSample = parseIntField(section, "bits_per_raw_sample");
        info.isHdr = detectHdr(info, section);
        info.rotation = parseRotation(section);
        return info;
    }

    private static StreamInfo parseAudioStream(String json) {
        String section = findStreamSection(json, "audio");
        StreamInfo info = new StreamInfo();
        if (section == null) {
            return info;
        }
        info.channels = parseIntField(section, "channels");
        info.bitrateKbps = parseKbps(parseStringField(section, "bit_rate"));
        return info;
    }

    private static String findStreamSection(String json, String codecType) {
        int streamsIndex = json.indexOf("\"streams\"");
        if (streamsIndex < 0) {
            return null;
        }
        int arrayStart = json.indexOf('[', streamsIndex);
        if (arrayStart < 0) {
            return null;
        }
        int index = arrayStart + 1;
        while (index < json.length()) {
            int objectStart = json.indexOf('{', index);
            if (objectStart < 0) {
                break;
            }
            String objectJson = extractBalancedObject(json, objectStart);
            if (objectJson != null && objectJson.contains("\"codec_type\": \"" + codecType + "\"")) {
                return objectJson;
            }
            index = objectStart + Math.max(1, objectJson == null ? 1 : objectJson.length());
        }
        return null;
    }

    private static String extractBalancedObject(String json, int start) {
        int depth = 0;
        for (int i = start; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return json.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private static String extractSection(String json, String key) {
        int keyIndex = json.indexOf(key);
        if (keyIndex < 0) {
            return "";
        }
        int start = json.indexOf('{', keyIndex);
        if (start < 0) {
            return "";
        }
        String section = extractBalancedObject(json, start);
        return section == null ? "" : section;
    }

    private static int parseRotation(String streamJson) {
        int rotation = parseIntField(streamJson, "rotation");
        if (rotation != 0) {
            return rotation;
        }
        String tagsSection = extractSection(streamJson, "\"tags\"");
        String rotate = parseStringField(tagsSection, "rotate");
        if (rotate != null && !rotate.isEmpty()) {
            try {
                return (int) Math.round(Double.parseDouble(rotate));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private static String parseStringField(String json, String field) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private static int parseIntField(String json, String field) {
        String value = parseStringField(json, field);
        if (value == null || value.isEmpty() || "N/A".equalsIgnoreCase(value)) {
            Matcher numeric = Pattern.compile("\"" + field + "\"\\s*:\\s*(\\d+)").matcher(json);
            if (numeric.find()) {
                return Integer.parseInt(numeric.group(1));
            }
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int parseKbps(String val) {
        if (val == null || val.isEmpty() || "N/A".equalsIgnoreCase(val)) {
            return 0;
        }
        try {
            long bps = Long.parseLong(val);
            return (int) Math.max(1, bps / 1000);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double parseFps(String val) {
        if (val == null || val.isEmpty()) {
            return 30.0;
        }
        try {
            String[] parts = val.split("/");
            if (parts.length == 2) {
                return Double.parseDouble(parts[0]) / Double.parseDouble(parts[1]);
            }
            return Double.parseDouble(val);
        } catch (Exception e) {
            return 30.0;
        }
    }

    private static boolean detectHdr(StreamInfo info, String section) {
        if (isHdrTransfer(info.colorTransfer)) {
            return true;
        }
        if (hasDolbyVisionSideData(section)) {
            return true;
        }
        if (isBt2020(info.colorPrimaries) && (info.bitsPerRawSample >= 10 || isHdrPixelFormat(info.pixFmt))) {
            return true;
        }
        return isBt2020(info.colorSpace) && isHdrPixelFormat(info.pixFmt);
    }

    private static boolean isHdrTransfer(String transfer) {
        return "smpte2084".equalsIgnoreCase(transfer)
                || "arib-std-b67".equalsIgnoreCase(transfer);
    }

    private static boolean isBt2020(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).contains("bt2020");
    }

    private static boolean isHdrPixelFormat(String pixFmt) {
        if (pixFmt == null || pixFmt.isBlank()) {
            return false;
        }
        String fmt = pixFmt.toLowerCase(Locale.ROOT);
        return fmt.contains("10le") || fmt.contains("p010") || fmt.contains("yuv444p10");
    }

    private static boolean hasDolbyVisionSideData(String section) {
        if (section == null || section.isEmpty()) {
            return false;
        }
        return section.contains("DOVI configuration record")
                || section.contains("Dolby Vision RPU")
                || section.contains("\"dv_profile\"")
                || section.contains("dolby_vision");
    }

    private static final class StreamInfo {
        int width;
        int height;
        double fps;
        int bitrateKbps;
        boolean isHdr;
        String colorTransfer = "unknown";
        String colorPrimaries = "unknown";
        String colorSpace = "unknown";
        String pixFmt;
        int bitsPerRawSample;
        int channels;
        int rotation;
    }
}
