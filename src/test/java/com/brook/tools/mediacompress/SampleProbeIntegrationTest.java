package com.brook.tools.mediacompress;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

class SampleProbeIntegrationTest {
    private static final Path SAMPLE = Path.of("sample", "Test Jellyfin 8K HEVC HDR10 150M.mp4");
    private static final long TEN_MB = 10L * 1024 * 1024;

    @BeforeEach
    void resetPlannerState() {
        CompressionPlanner.setSliderValue(CompressionPlanner.bppfDefaultSliderValue());
        CompressionPlanner.setFpsSliderValue(CompressionPlanner.fpsDefaultSliderValue());
    }

    @Test
    @EnabledIf("sampleExists")
    void probeAndPlanSample8k() throws Exception {
        Path ffmpeg;
        try {
            ffmpeg = new FfmpegLocator().resolve(progress -> {
            });
        } catch (Exception ex) {
            org.junit.jupiter.api.Assumptions.abort("ffmpeg unavailable: " + ex.getMessage());
            return;
        }
        VideoMetadata meta = VideoMetadata.probe(ffmpeg, SAMPLE);
        EncodePlan plan = CompressionPlanner.plan(meta, meta, TEN_MB, CodecFamily.HEVC, false, false);

        double bppf = CompressionPlanner.bitsPerPixelPerFrame(
                plan.videoKbps(), plan.outputWidth(), plan.outputHeight(), plan.outFps());
        System.out.printf(
                "Sample plan: %dx%d @ %dfps, video %dkbps, audio %dkbps, bppf=%.4f, est=%s%n",
                plan.outputWidth(),
                plan.outputHeight(),
                plan.outFps(),
                plan.videoKbps(),
                plan.audioKbps(),
                bppf,
                AppConstants.formatMegabytes(plan.estimatedBytes(meta.durationSeconds())));

        assertTrue(plan.estimatedBytes(meta.durationSeconds()) <= TEN_MB);
        assertTrue(bppf >= CompressionPlanner.bppfFloor - 1e-6);
        assertTrue(plan.videoKbps() <= 10_000);
    }

    static boolean sampleExists() {
        return Files.isRegularFile(SAMPLE);
    }
}
