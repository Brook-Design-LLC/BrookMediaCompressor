package com.brook.tools.mediacompress;

final class ScaleFilter {
    private ScaleFilter() {
    }

    static String cpuScale(EncodePlan plan) {
        return "scale=" + plan.outputWidth() + ":-2";
    }

    static String vtScale(EncodePlan plan, boolean hdrToSdr) {
        StringBuilder sb = new StringBuilder();
        sb.append("scale_vt=").append(plan.outputWidth()).append(":-2");
        if (hdrToSdr) {
            sb.append(":color_matrix=bt709:color_primaries=bt709:color_transfer=bt709");
        }
        return sb.toString();
    }

    static String libplaceboScale(EncodePlan plan) {
        return "libplacebo=w=" + plan.outputWidth() + ":h=-2";
    }
}
