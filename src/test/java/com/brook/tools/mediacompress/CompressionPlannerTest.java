package com.brook.tools.mediacompress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CompressionPlannerTest {
    private static final long TEN_MB = 10L * 1024 * 1024;

    @BeforeEach
    void resetPlannerState() {
        CompressionPlanner.setSliderValue(CompressionPlanner.bppfDefaultSliderValue());
        CompressionPlanner.setFpsSliderValue(CompressionPlanner.fpsDefaultSliderValue());
    }

    @Test
    void prefersOriginResolutionFor8kSource() throws Exception {
        CompressionPlanner.setFpsSliderValue(0);
        VideoMetadata meta = sample8k(8, 5.0);
        long generousBudget = 50L * 1024 * 1024;
        EncodePlan plan = CompressionPlanner.plan(meta, meta, generousBudget, CodecFamily.HEVC, false, false);

        int shortSide = Math.min(plan.outputWidth(), plan.outputHeight());
        assertEquals(4320, shortSide);
    }

    @Test
    void doesNotReturnClampedKbpsPlanForImpossible8kBudget() throws Exception {
        VideoMetadata meta = sample8k(60, 30.0);
        EncodePlan plan = CompressionPlanner.plan(meta, meta, TEN_MB, CodecFamily.HEVC, false, false);

        double bppf = CompressionPlanner.bitsPerPixelPerFrame(
                plan.videoKbps(), plan.outputWidth(), plan.outputHeight(), plan.outFps());
        assertTrue(bppf >= CompressionPlanner.bppfFloor - 1e-6);
        assertTrue(plan.estimatedBytes(meta.durationSeconds()) <= TEN_MB);
        assertTrue(plan.videoKbps() <= 10_000);
    }

    @Test
    void downgradesWhenOriginCannotFitBudget() throws Exception {
        VideoMetadata meta = sample8k(60, 120.0);
        EncodePlan plan = CompressionPlanner.plan(meta, meta, TEN_MB, CodecFamily.HEVC, false, false);

        int shortSide = Math.min(plan.outputWidth(), plan.outputHeight());
        assertTrue(shortSide < 4320);
        assertTrue(plan.estimatedBytes(meta.durationSeconds()) <= TEN_MB);
    }

    @Test
    void parseProbeJsonDetectsDolbyVisionSideData() {
        String json = """
                {
                  "format": { "duration": "8.000000" },
                  "streams": [
                    { "codec_type": "video", "width": "3840", "height": "2160",
                      "r_frame_rate": "24/1", "pix_fmt": "yuv420p10le",
                      "color_primaries": "bt2020", "color_space": "bt2020nc",
                      "side_data_list": [
                        { "side_data_type": "DOVI configuration record", "dv_profile": 8 }
                      ] },
                    { "codec_type": "audio", "channels": "2", "bit_rate": "128000" }
                  ]
                }
                """;

        VideoMetadata meta = VideoMetadata.parseProbeJson(json);
        assertTrue(meta.isHdr());
    }

    @Test
    void parseProbeJsonUsesFormatDurationOnly() {
        String json = """
                {
                  "format": { "duration": "12.345000" },
                  "streams": [
                    { "codec_type": "video", "duration": "99.0", "width": "7680", "height": "4320",
                      "r_frame_rate": "60/1", "bit_rate": "150000000",
                      "color_transfer": "smpte2084", "color_primaries": "bt2020", "color_space": "bt2020nc" },
                    { "codec_type": "audio", "channels": "2", "bit_rate": "128000" }
                  ]
                }
                """;

        VideoMetadata meta = VideoMetadata.parseProbeJson(json);
        assertEquals(12.345, meta.durationSeconds(), 0.001);
        assertEquals(7680, meta.width());
        assertEquals(4320, meta.height());
        assertEquals(60.0, meta.sourceFps(), 0.01);
        assertTrue(meta.isHdr());
    }

    @Test
    void keepAudioQualityUsesSourceBitrate() throws Exception {
        VideoMetadata meta = sample1080p(30, 60.0, 256);
        long generousBudget = 50L * 1024 * 1024;
        EncodePlan plan = CompressionPlanner.plan(meta, meta, generousBudget, CodecFamily.HEVC, false, true);

        assertEquals(256, plan.audioKbps());
    }

    @Test
    void keepAudioQualityDoesNotReduceAudioBitrateUnderTightBudget() throws Exception {
        VideoMetadata meta = sample1080p(30, 120.0, 256);
        EncodePlan plan = CompressionPlanner.plan(meta, meta, TEN_MB, CodecFamily.HEVC, false, true);

        assertEquals(256, plan.audioKbps());
        assertTrue(plan.estimatedBytes(meta.durationSeconds()) <= TEN_MB);
    }

    @Test
    void allowsAudioFallbackWhenKeepAudioQualityDisabled() throws Exception {
        VideoMetadata meta = sample1080p(30, 120.0, 256);
        EncodePlan plan = CompressionPlanner.plan(meta, meta, TEN_MB, CodecFamily.HEVC, false, false);

        assertTrue(plan.audioKbps() < 256);
        assertTrue(plan.estimatedBytes(meta.durationSeconds()) <= TEN_MB);
    }

    private static VideoMetadata sample8k(int fps, double durationSec) {
        return new VideoMetadata(
                durationSec,
                7680,
                4320,
                fps,
                true,
                "smpte2084",
                "bt2020",
                "bt2020nc",
                2,
                150_000,
                128);
    }

    private static VideoMetadata sample1080p(int fps, double durationSec, int sourceAudioKbps) {
        return new VideoMetadata(
                durationSec,
                1920,
                1080,
                fps,
                false,
                null,
                null,
                null,
                2,
                5_000,
                sourceAudioKbps);
    }
}
