package com.brook.tools.mediacompress;

public enum CodecFamily {
    HEVC("HEVC"),
    H264("H.264");

    private final String label;

    CodecFamily(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
