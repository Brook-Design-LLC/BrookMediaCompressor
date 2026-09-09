package com.brook.tools.mediacompress;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public final class CompressionPlanner {
    private static final int[] RES_TIERS = { 2160, 1440, 1080, 720, 540, 480, 360 };
    public static final int[] FPS_FALLBACK = { 60, 30, 24, 20, 15, 12, 8 };
    private static final int[] AUDIO_FALLBACK = { 128, 96, 64 };
    public static final double BPPF_STRIDE = 0.001;
    public static final double BPPF_FLOOR_MIN = BPPF_STRIDE;
    public static final double BPPF_FLOOR_DEFAULT = 0.02;
    public static final double BPPF_FLOOR_MAX = 0.1;
    public static int sliderValue = bppfDefaultSliderValue();
    public static double bppfFloor = BPPF_FLOOR_DEFAULT;
    private static int fpsSliderValue = fpsDefaultSliderValue();
    private static final int MAX_VIDEO_KBPS = 10_000;
    private static final int MIN_VIDEO_KBPS = 80;
    private static final int MIN_AUDIO_KBPS = 64;

    private CompressionPlanner() {
    }

    /**
     * @param seed {@link VideoMetadata} to search from the start, or previous
     *             {@link EncodePlan} for the next combo
     */
    public static int bppfSliderMax() {
        return (int) Math.round((BPPF_FLOOR_MAX - BPPF_FLOOR_MIN) / BPPF_STRIDE);
    }

    public static int bppfDefaultSliderValue() {
        return (int) Math.round((BPPF_FLOOR_DEFAULT - BPPF_FLOOR_MIN) / BPPF_STRIDE);
    }

    public static void setSliderValue(int value) {
        sliderValue = Math.max(0, Math.min(bppfSliderMax(), value));
        bppfFloor = BPPF_FLOOR_MIN + sliderValue * BPPF_STRIDE;
    }

    public static String formatBppfFloor(double bppf) {
        return String.format(Locale.US, "%.3f", bppf);
    }

    public static String formatMinBppfLabel() {
        return "min bppf: " + formatBppfFloor(bppfFloor);
    }

    public static int fpsSliderMax() {
        // Origin (0) + Balance (mid) + one tick per FPS_FALLBACK entry → max = length +
        // 1
        return FPS_FALLBACK.length + 1;
    }

    public static int fpsDefaultSliderValue() {
        return fpsSliderMax() / 2;
    }

    public static void setFpsSliderValue(int value) {
        fpsSliderValue = Math.max(0, Math.min(fpsSliderMax(), value));
    }

    public static boolean isFpsBalance() {
        return fpsSliderValue == fpsDefaultSliderValue();
    }

    public static String formatPreferredFpsLabel() {
        int balance = fpsDefaultSliderValue();
        if (fpsSliderValue == balance) {
            return "preferred frame rate: auto";
        }
        if (fpsSliderValue == 0) {
            return "preferred frame rate: Origin";
        }
        int index = fpsSliderValue < balance ? fpsSliderValue - 1 : fpsSliderValue - 2;
        index = Math.max(0, Math.min(FPS_FALLBACK.length - 1, index));
        return "preferred frame rate: " + FPS_FALLBACK[index];
    }

    public static EncodePlan plan(
            Object seed,
            VideoMetadata meta,
            long maxBytes,
            CodecFamily codec,
            boolean hdrToSdr) throws NoFeasiblePlanException {
        if (seed != null && !(seed instanceof VideoMetadata) && !(seed instanceof EncodePlan)) {
            throw new IllegalArgumentException("seed must be VideoMetadata, EncodePlan, or null");
        }
        if (seed == null) {
            seed = meta;
        }

        boolean skipPastPrevious = seed instanceof EncodePlan;
        EncodePlan previous = skipPastPrevious ? (EncodePlan) seed : null;
        boolean pastPrevious = !skipPastPrevious;

        double duration = meta.durationSeconds();
        int sourceWidth = meta.width() > 0 ? meta.width() : 1280;
        int sourceHeight = meta.height() > 0 ? meta.height() : 720;

        for (int res : resCandidates(meta)) {
            int[] dimensions = computeOutputDimensions(sourceWidth, sourceHeight, res);
            int pixelCount = dimensions[0] * dimensions[1];
            for (int fps : fpsValuesForPlanning(meta)) {
                for (double bppf : bppfValues(
                        calculateBppfMax(pixelCount, fps, duration, maxBytes, bppfFloor), bppfFloor)) {
                    int videoKbps = videoKbpsFromBppf(bppf, pixelCount, fps);
                    for (int audioKbps : audioCandidates(meta)) {
                        if (!pastPrevious) {
                            if (matchesCombination(previous, res, fps, videoKbps, audioKbps)) {
                                pastPrevious = true;
                            }
                            continue;
                        }
                        if (calculateSize(videoKbps, audioKbps, duration) <= maxBytes) {
                            return new EncodePlan(
                                    dimensions[0],
                                    dimensions[1],
                                    videoKbps,
                                    audioKbps,
                                    fps,
                                    hdrToSdr,
                                    codec);
                        }
                    }
                }
            }
        }

        throw new NoFeasiblePlanException(
                "No encode plan fits within " + AppConstants.formatMegabytes(maxBytes));
    }

    public static long estimateCbrBytes(int videoKbps, int audioKbps, double durationSec) {
        return (long) Math.ceil((videoKbps + audioKbps) * 1000.0 / 8.0 * durationSec);
    }

    public static long calculateSize(int videoKbps, int audioKbps, double durationSec) {
        return estimateCbrBytes(videoKbps, audioKbps, durationSec);
    }

    public static double bitsPerPixelPerFrame(int videoKbps, int outputWidth, int outputHeight, int fps) {
        if (outputWidth <= 0 || outputHeight <= 0 || fps <= 0) {
            return 0;
        }
        return videoKbps * 1000.0 / (outputWidth * outputHeight * fps);
    }

    static String tierLabel(int shortSide) {
        return shortSide + "p";
    }

    public static long maxSupportedDurationSeconds(long maxBytes) {
        return (long) Math.floor(maxBytes * 8.0 / ((MIN_VIDEO_KBPS + MIN_AUDIO_KBPS) * 1000.0));
    }

    private static boolean matchesCombination(
            EncodePlan plan,
            int res,
            int fps,
            int videoKbps,
            int audioKbps) {
        int shortSide = Math.min(plan.outputWidth(), plan.outputHeight());
        return shortSide == res
                && plan.outFps() == fps
                && plan.videoKbps() == videoKbps
                && plan.audioKbps() == audioKbps;
    }

    private static List<Double> bppfValues(double bppfMax, double bppfFloor) {
        double floor = clampBppfFloor(bppfFloor);
        List<Double> values = new ArrayList<>();
        if (bppfMax <= 0) {
            return values;
        }
        for (double bppf = bppfMax; bppf >= floor - 1e-9; bppf -= BPPF_STRIDE) {
            values.add(bppf);
        }
        if (values.isEmpty()) {
            values.add(Math.max(bppfMax, floor));
        }
        return values;
    }

    private static double clampBppfFloor(double bppfFloor) {
        return Math.max(BPPF_FLOOR_MIN, Math.min(BPPF_FLOOR_MAX, bppfFloor));
    }

    private static double calculateBppfMax(
            int pixelCount, int fps, double durationSec, long maxBytes, double bppfFloor) {
        int budgetVideoKbps = budgetVideoKbps(durationSec, 0, maxBytes);
        if (pixelCount <= 0 || fps <= 0) {
            return clampBppfFloor(bppfFloor);
        }
        return budgetVideoKbps * 1000.0 / (pixelCount * fps);
    }

    private static int videoKbpsFromBppf(double bppf, int pixelCount, int fps) {
        int kbps = (int) Math.floor(bppf * pixelCount * fps / 1000.0);
        return clampVideoKbps(kbps);
    }

    private static int budgetVideoKbps(double durationSec, int audioKbps, long maxBytes) {
        if (durationSec <= 0) {
            return MIN_VIDEO_KBPS;
        }
        double maxTotalKbps = maxBytes * 8.0 / durationSec / 1000.0;
        double videoKbps = maxTotalKbps - audioKbps;
        if (videoKbps <= MIN_VIDEO_KBPS) {
            return MIN_VIDEO_KBPS;
        }
        return Math.max(MIN_VIDEO_KBPS, (int) Math.floor(videoKbps));
    }

    private static List<Integer> resCandidates(VideoMetadata meta) {
        int origin = sourceShortSide(meta);
        LinkedHashSet<Integer> ordered = new LinkedHashSet<>();
        ordered.add(origin);
        for (int tier : RES_TIERS) {
            if (tier <= origin) {
                ordered.add(tier);
            }
        }
        List<Integer> result = new ArrayList<>(ordered);
        result.sort((a, b) -> Integer.compare(b, a));
        moveOriginFirst(result, origin);
        return result;
    }

    private static List<Integer> fpsValuesForPlanning(VideoMetadata meta) {
        if (isFpsBalance()) {
            return fpsCandidates(meta);
        }
        if (fpsSliderValue == 0) {
            return List.of(roundFps(meta.sourceFps()));
        }
        int balance = fpsDefaultSliderValue();
        int index = fpsSliderValue < balance ? fpsSliderValue - 1 : fpsSliderValue - 2;
        index = Math.max(0, Math.min(FPS_FALLBACK.length - 1, index));
        return List.of(FPS_FALLBACK[index]);
    }

    private static List<Integer> fpsCandidates(VideoMetadata meta) {
        int origin = roundFps(meta.sourceFps());
        LinkedHashSet<Integer> ordered = new LinkedHashSet<>();
        ordered.add(origin);
        for (int fps : FPS_FALLBACK) {
            ordered.add(fps);
        }
        List<Integer> result = new ArrayList<>(ordered);
        result.sort((a, b) -> Integer.compare(b, a));
        moveOriginFirst(result, origin);
        return result;
    }

    private static List<Integer> audioCandidates(VideoMetadata meta) {
        int origin = meta.sourceAudioKbps() > 0 ? meta.sourceAudioKbps() : 128;
        LinkedHashSet<Integer> ordered = new LinkedHashSet<>();
        ordered.add(origin);
        for (int audio : AUDIO_FALLBACK) {
            ordered.add(audio);
        }
        List<Integer> result = new ArrayList<>(ordered);
        result.sort((a, b) -> Integer.compare(b, a));
        moveOriginFirst(result, origin);
        return result;
    }

    /**
     * Use Integer overload — List.remove(int) treats the argument as an index, not
     * a value.
     */
    private static void moveOriginFirst(List<Integer> values, int origin) {
        if (values.remove(Integer.valueOf(origin))) {
            values.add(0, origin);
        }
    }

    private static int[] computeOutputDimensions(int sourceWidth, int sourceHeight, int targetShortSide) {
        double aspectRatio = (double) sourceWidth / (double) sourceHeight;
        int width;
        int height;

        if (sourceWidth <= sourceHeight) {
            width = targetShortSide;
            height = evenDimension((int) Math.round(width / aspectRatio));
        } else {
            height = targetShortSide;
            width = evenDimension((int) Math.round(height * aspectRatio));
        }

        return new int[] { width, height };
    }

    private static int sourceShortSide(VideoMetadata meta) {
        return Math.min(
                meta.width() > 0 ? meta.width() : 1280,
                meta.height() > 0 ? meta.height() : 720);
    }

    private static int roundFps(double sourceFps) {
        int fps = (int) Math.round(sourceFps);
        return fps > 0 ? fps : 30;
    }

    private static int clampVideoKbps(int videoKbps) {
        return Math.min(MAX_VIDEO_KBPS, Math.max(MIN_VIDEO_KBPS, videoKbps));
    }

    private static int evenDimension(int value) {
        return value % 2 == 0 ? value : value - 1;
    }
}
