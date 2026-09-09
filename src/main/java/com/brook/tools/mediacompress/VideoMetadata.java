package com.brook.tools.mediacompress;

import java.nio.file.Path;

public record VideoMetadata(
        double durationSeconds,
        int width,
        int height,
        double sourceFps,
        boolean isHdr,
        String colorTransfer, // 新增：如 arib-std-b67, smpte2084
        String colorPrimaries, // 新增：如 bt2020
        String colorSpace, // 新增：如 bt2020nc
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
                "-show_entries", "format=duration",
                "-show_entries",
                "stream=codec_type,width,height,r_frame_rate,bit_rate,color_transfer,color_primaries,color_space,channels",
                "-show_entries", "stream_tags=rotate",
                "-show_entries", "stream_side_data=rotation",
                "-of", "default=noprint_wrappers=1:nokey=0",
                input.toString());
        pb.redirectErrorStream(true);
        Process process = pb.start();

        double duration = 0;
        int width = 0;
        int height = 0;
        double sourceFps = 30.0;
        boolean isHdr = false;
        String colorTransfer = "unknown";
        String colorPrimaries = "unknown";
        String colorSpace = "unknown";
        int audioChannels = 2;
        int sourceVideoKbps = 0;
        int sourceAudioKbps = 0;

        boolean inVideo = false;
        boolean inAudio = false;
        int rotation = 0;

        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("=", 2);
                if (parts.length < 2)
                    continue;

                String key = parts[0].trim();
                String val = parts[1].trim();

                if ("codec_type".equals(key)) {
                    inVideo = "video".equals(val);
                    inAudio = "audio".equals(val);
                } else if ("duration".equals(key)) {
                    if (!"N/A".equalsIgnoreCase(val)) {
                        duration = Double.parseDouble(val);
                    }
                } else if (inVideo) {
                    switch (key) {
                        case "width" -> width = Integer.parseInt(val);
                        case "height" -> height = Integer.parseInt(val);
                        case "rotate", "rotation" -> {
                            try {
                                rotation = (int) Math.round(Double.parseDouble(val));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        case "r_frame_rate" -> sourceFps = parseFps(val);
                        case "color_transfer" -> {
                            colorTransfer = val;
                            isHdr = val.equalsIgnoreCase("smpte2084") || val.equalsIgnoreCase("arib-std-b67");
                        }
                        case "color_primaries" -> colorPrimaries = val;
                        case "color_space" -> colorSpace = val;
                        case "bit_rate" -> sourceVideoKbps = parseKbps(val);
                    }
                } else if (inAudio) {
                    switch (key) {
                        case "channels" -> audioChannels = Integer.parseInt(val);
                        case "bit_rate" -> sourceAudioKbps = parseKbps(val);
                    }
                }
            }
        }
        process.waitFor();

        if (Math.abs(rotation) == 90 || Math.abs(rotation) == 270) {
            int temp = width;
            width = height;
            height = temp;
        }

        if (duration <= 0) {
            throw new IllegalStateException("Could not read video duration from input file.");
        }
        if (sourceAudioKbps <= 0) {
            sourceAudioKbps = 128;
        }
        return new VideoMetadata(
                duration,
                width,
                height,
                sourceFps,
                isHdr,
                colorTransfer,
                colorPrimaries,
                colorSpace,
                audioChannels,
                sourceVideoKbps,
                sourceAudioKbps);
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
}