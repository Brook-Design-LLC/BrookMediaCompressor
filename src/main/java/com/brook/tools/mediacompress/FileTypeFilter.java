package com.brook.tools.mediacompress;

public record FileTypeFilter(String label, String[] extensions) {
    public static final FileTypeFilter MEDIA = new FileTypeFilter(
            "Images, video, audio",
            new String[] {
                    "png", "jpg", "jpeg", "gif", "webp",
                    "mp4", "mov", "webm",
                    "m4a", "mp3", "aac", "wav"
            });
}
