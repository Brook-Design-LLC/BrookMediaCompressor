package com.brook.tools.mediacompress;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.IntConsumer;

public final class FfmpegLocator {
    private Path cachedFfmpeg;

    public Path cacheDir() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String local = System.getenv("LOCALAPPDATA");
            if (local != null && !local.isBlank()) {
                return Path.of(local, "BrookTools", "ffmpeg");
            }
        }
        return Path.of(System.getProperty("user.home"), ".brook-tools", "ffmpeg");
    }

    public synchronized Path resolve(IntConsumer downloadProgress) throws Exception {
        if (cachedFfmpeg != null && Files.isRegularFile(cachedFfmpeg)) {
            return cachedFfmpeg;
        }

        Path fromPath = findOnPath();
        if (fromPath != null) {
            verifyFfmpeg(fromPath);
            cachedFfmpeg = fromPath;
            return cachedFfmpeg;
        }

        Path cacheDir = cacheDir();
        Path cached = defaultCachedBinary(cacheDir);
        if (Files.isRegularFile(cached)) {
            verifyFfmpeg(cached);
            cachedFfmpeg = cached;
            return cachedFfmpeg;
        }

        Path installed = FfmpegDownloader.downloadAndInstall(cacheDir, downloadProgress);
        verifyFfmpeg(installed);
        cachedFfmpeg = installed;
        return installed;
    }

    private Path defaultCachedBinary(Path cacheDir) {
        if (isWindows()) {
            return cacheDir.resolve("bin").resolve("ffmpeg.exe");
        }
        return cacheDir.resolve("ffmpeg");
    }

    private Path findOnPath() {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) {
            return null;
        }
        String executable = isWindows() ? "ffmpeg.exe" : "ffmpeg";
        for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
            Path candidate = Path.of(dir.trim(), executable);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private void verifyFfmpeg(Path ffmpeg) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(ffmpeg.toString(), "-version");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        int code = process.waitFor();
        if (code != 0) {
            throw new IOException("ffmpeg verification failed: " + ffmpeg);
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
