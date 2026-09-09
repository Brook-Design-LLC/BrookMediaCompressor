package com.brook.tools.mediacompress;

public record EncodePlan(
        int outputWidth,
        int outputHeight,
        int videoKbps,
        int audioKbps,
        int outFps,
        boolean hdrToSdr,
        CodecFamily codec
) {
    public boolean isAtLeast720p() {
        return outputHeight >= 720;
    }

    public long estimatedBytes(double durationSec) {
        return CompressionPlanner.estimateCbrBytes(videoKbps, audioKbps, durationSec);
    }

    public long estimatedCbrBytes(double durationSec) {
        return estimatedBytes(durationSec);
    }

    public boolean fitsBudget(long maxBytes, double durationSec) {
        return estimatedBytes(durationSec) <= maxBytes;
    }

    public String formatLabel(double durationSec) {
        StringBuilder sb = new StringBuilder();
        sb.append(codec.label());
        sb.append(" · ");
        sb.append(outputWidth).append('×').append(outputHeight);
        sb.append(" @ ").append(outFps()).append("fps");
        sb.append(" · ").append(videoKbps).append("k video");
        sb.append(" · ").append(audioKbps).append("k mono");
        if (hdrToSdr) {
            sb.append(" · HDR→SDR");
        }
        sb.append(" · ~").append(AppConstants.formatMegabytes(estimatedBytes(durationSec))).append(" est");
        return sb.toString();
    }

    public String formatLabel() {
        return codec.label()
                + " · " + outputWidth + '×' + outputHeight
                + " @ " + outFps + "fps"
                + " · " + videoKbps + "k video"
                + " · " + audioKbps + "k mono"
                + (hdrToSdr ? " · HDR→SDR" : "");
    }

    public String summary() {
        return formatLabel();
    }
}
