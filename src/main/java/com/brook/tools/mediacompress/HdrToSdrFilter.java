package com.brook.tools.mediacompress;

final class HdrToSdrFilter {
    private HdrToSdrFilter() {
    }

    static String libplaceboSuffix() {
        return ":format=yuv420p"
                + ":colorspace=bt709:color_primaries=bt709:color_trc=bt709"
                + ":tonemapping=bt.2390:tonemapping_param=0.3";
    }

    static String libplaceboHdrPassthroughSuffix(VideoMetadata meta) {
        String transfer = meta.isHlg() ? "arib-std-b67" : "smpte2084";
        return ":format=yuv420p10le"
                + ":colorspace=bt2020nc:color_primaries=bt2020:color_trc=" + transfer;
    }

    /** Fallback when zscale+tonemap is unavailable or fails at runtime. */
    static String cpuTonemapChain() {
        return "format=yuv420p10le,tonemap=tonemap=hable:desat=0,format=yuv420p";
    }

    /** CPU fallback: HLG and PQ use separate transfer curves per ffmpeg zscale/tonemap docs. */
    static String cpuChain(VideoMetadata meta) {
        if (meta.isHlg()) {
            return hlgChain();
        }
        return pqChain();
    }

    private static String hlgChain() {
        return "zscale=tin=arib-std-b67:pin=bt2020:min=bt2020nc:t=linear:npl=100,format=gbrpf32le"
                + ",zscale=p=bt709"
                + ",tonemap=hable:desat=0"
                + ",zscale=t=bt709:m=bt709:r=tv,format=yuv420p";
    }

    private static String pqChain() {
        return "zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=1000,format=gbrpf32le"
                + ",zscale=p=bt709"
                + ",tonemap=hable:desat=0"
                + ",zscale=t=bt709:m=bt709:r=tv,format=yuv420p";
    }

    static String vtMetalTonemap() {
        return "tonemap_videotoolbox";
    }
}
