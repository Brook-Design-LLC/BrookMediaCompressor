package com.brook.tools.mediacompress;

import java.util.ArrayList;
import java.util.List;

final class VideoFilterGraph {
    enum PathType {
        MAC_VT,
        LIBPLACEBO,
        CPU
    }

    record FilterCandidate(String filter, String hdrMethodLabel, PathType pathType) {
    }

    private VideoFilterGraph() {
    }

    static List<FilterCandidate> candidates(
            VideoMetadata meta,
            EncodePlan plan,
            HwEncoderDetector.Encoder encoder,
            FfmpegCapabilities caps,
            boolean allowHwaccel) {

        List<FilterCandidate> list = new ArrayList<>();
        String fps = "fps=" + plan.outFps();
        boolean macVt = caps.isMac && encoder.isVideoToolbox() && allowHwaccel;
        boolean macHwDecode = caps.isMac && encoder.isVideoToolbox() && allowHwaccel;

        if (macVt && caps.hasScaleVt) {
            if (plan.hdrToSdr()) {
                list.add(new FilterCandidate(
                        fps + "," + ScaleFilter.vtScale(plan, true),
                        "VideoToolbox: scale_vt + native HDR→SDR",
                        PathType.MAC_VT));

                if (caps.hasTonemapVideotoolbox) {
                    list.add(new FilterCandidate(
                            fps + "," + ScaleFilter.vtScale(plan, false) + "," + HdrToSdrFilter.vtMetalTonemap(),
                            "VideoToolbox: Metal tonemap (HDR→SDR)",
                            PathType.MAC_VT));
                }
            } else {
                list.add(new FilterCandidate(
                        fps + "," + ScaleFilter.vtScale(plan, false),
                        "VideoToolbox: scale_vt (HDR passthrough)",
                        PathType.MAC_VT));
            }
        }

        if (caps.hasLibplacebo && (!macVt || !caps.hasScaleVt)) {
            StringBuilder filter = new StringBuilder(fps);
            filter.append(',').append(ScaleFilter.libplaceboScale(plan));
            if (plan.hdrToSdr()) {
                filter.append(HdrToSdrFilter.libplaceboSuffix());
                list.add(new FilterCandidate(
                        filter.toString(),
                        "libplacebo: scale + BT.2390 tonemap (HDR→SDR)",
                        PathType.LIBPLACEBO));
            } else {
                filter.append(HdrToSdrFilter.libplaceboSdrSuffix());
                list.add(new FilterCandidate(
                        filter.toString(),
                        "libplacebo: scale (HDR passthrough)",
                        PathType.LIBPLACEBO));
            }
        }

        String cpuPrefix = macHwDecode && plan.hdrToSdr() ? "hwdownload,format=yuv420p," : "";

        if (plan.hdrToSdr() && caps.hasZscale && caps.hasTonemap) {
            String label = meta.isHlg()
                    ? "CPU: zscale + tonemap (HLG→SDR)"
                    : "CPU: zscale + tonemap (PQ→SDR)";
            list.add(new FilterCandidate(
                    fps + "," + cpuPrefix + ScaleFilter.cpuScale(plan) + "," + HdrToSdrFilter.cpuChain(meta),
                    label,
                    PathType.CPU));
        } else if (!plan.hdrToSdr() || !macVt || !caps.hasScaleVt) {
            String pixFmt = meta.isHdr() ? "yuv420p10le" : "yuv420p";
            list.add(new FilterCandidate(
                    fps + "," + cpuPrefix + ScaleFilter.cpuScale(plan) + ",format=" + pixFmt,
                    meta.isHdr() ? "HDR passthrough" : "CPU: scale (SDR)",
                    PathType.CPU));
        }

        return list;
    }

    static boolean usesVtHardware(String filter) {
        return filter.contains("scale_vt=");
    }

    static boolean needsHwdownload(String filter) {
        return filter.contains("hwdownload");
    }
}
