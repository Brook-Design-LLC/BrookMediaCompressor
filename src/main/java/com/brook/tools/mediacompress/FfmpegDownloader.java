package com.brook.tools.mediacompress;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class FfmpegDownloader {

    // --- 下載來源配置 ---
    // macOS: Martin Riedl 官方構建源 (具備完整 VideoToolbox 硬解支援與公證)
    private static final String MAC_ARM64_FFMPEG_URL = "https://ffmpeg.martin-riedl.de/redirect/latest/macos/arm64/release/ffmpeg.zip";
    private static final String MAC_ARM64_FFPROBE_URL = "https://ffmpeg.martin-riedl.de/redirect/latest/macos/arm64/release/ffprobe.zip";

    private static final String MAC_AMD64_FFMPEG_URL = "https://ffmpeg.martin-riedl.de/redirect/latest/macos/amd64/release/ffmpeg.zip";
    private static final String MAC_AMD64_FFPROBE_URL = "https://ffmpeg.martin-riedl.de/redirect/latest/macos/amd64/release/ffprobe.zip";

    // Windows: BtbN 官方 Release (GPL 完整靜態版，支援全套 NVENC/QSV/AMF/CPU 濾鏡)
    private static final String WIN_X64_URL = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip";
    private static final String WIN_ARM64_URL = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-winarm64-gpl.zip";

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private FfmpegDownloader() {
    }

    /**
     * 下載並安裝 FFmpeg 與 FFprobe。若快取中已有可用執行檔則直接返回。
     *
     * @param cacheDir        目標快取目錄
     * @param progressPercent 下載進度回調 (0~100)
     * @return ffmpeg 執行檔的 Path
     */
    public static Path downloadAndInstall(Path cacheDir, IntConsumer progressPercent) throws Exception {
        Files.createDirectories(cacheDir);

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean isMac = os.contains("mac") || os.contains("darwin");
        boolean isWin = os.contains("win");

        if (!isMac && !isWin) {
            throw new UnsupportedOperationException("Unsupported OS for automatic ffmpeg download: " + os);
        }

        String ffmpegExeName = isWin ? "ffmpeg.exe" : "ffmpeg";
        String ffprobeExeName = isWin ? "ffprobe.exe" : "ffprobe";

        Path ffmpegPath = cacheDir.resolve(ffmpegExeName);
        Path ffprobePath = cacheDir.resolve(ffprobeExeName);

        // 1. 快取命中檢查：如果兩者都存在且大於 0 bytes，直接視為可用
        if (isValidExecutable(ffmpegPath) && isValidExecutable(ffprobePath)) {
            if (progressPercent != null) {
                progressPercent.accept(100);
            }
            return ffmpegPath;
        }

        // 2. 依作業系統分流下載與解壓
        if (isMac) {
            installMac(cacheDir, ffmpegPath, ffprobePath, progressPercent);
        } else {
            installWindows(cacheDir, ffmpegPath, ffprobePath, progressPercent);
        }

        return ffmpegPath;
    }

    private static void installMac(Path cacheDir, Path ffmpegPath, Path ffprobePath, IntConsumer progressPercent)
            throws Exception {
        boolean isArm = isAppleSilicon();
        String ffmpegUrl = isArm ? MAC_ARM64_FFMPEG_URL : MAC_AMD64_FFMPEG_URL;
        String ffprobeUrl = isArm ? MAC_ARM64_FFPROBE_URL : MAC_AMD64_FFPROBE_URL;

        // 進度比例：FFmpeg 佔 0%~50%，FFprobe 佔 50%~100%
        Path tempFfmpegZip = cacheDir.resolve("ffmpeg-mac.zip.tmp");
        try {
            downloadFile(ffmpegUrl, tempFfmpegZip, 0, 50, progressPercent);
            extractTargetFiles(tempFfmpegZip, cacheDir, Set.of("ffmpeg"));
        } finally {
            Files.deleteIfExists(tempFfmpegZip);
        }

        Path tempFfprobeZip = cacheDir.resolve("ffprobe-mac.zip.tmp");
        try {
            downloadFile(ffprobeUrl, tempFfprobeZip, 50, 50, progressPercent);
            extractTargetFiles(tempFfprobeZip, cacheDir, Set.of("ffprobe"));
        } finally {
            Files.deleteIfExists(tempFfprobeZip);
        }

        makeExecutable(ffmpegPath);
        makeExecutable(ffprobePath);
    }

    private static void installWindows(Path cacheDir, Path ffmpegPath, Path ffprobePath, IntConsumer progressPercent)
            throws Exception {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean isArm = arch.contains("aarch64") || arch.contains("arm64");
        String downloadUrl = isArm ? WIN_ARM64_URL : WIN_X64_URL;

        Path tempZip = cacheDir.resolve("ffmpeg-win.zip.tmp");
        try {
            downloadFile(downloadUrl, tempZip, 0, 100, progressPercent);
            // 同時從 BtbN 的 bin 目錄中提取出 ffmpeg.exe 與 ffprobe.exe 至 cacheDir
            extractTargetFiles(tempZip, cacheDir, Set.of("ffmpeg.exe", "ffprobe.exe"));
        } finally {
            Files.deleteIfExists(tempZip);
        }

        makeExecutable(ffmpegPath);
        makeExecutable(ffprobePath);
    }

    /**
     * 下載檔案並回報自訂區間的進度條
     */
    private static void downloadFile(String url, Path destination, int progressBase, int progressWeight,
            IntConsumer progressPercent) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", "Mozilla/5.0 (FFmpegDownloader)")
                .GET()
                .build();

        HttpResponse<InputStream> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() >= 400) {
            throw new IOException("Download failed HTTP " + response.statusCode() + ": " + url);
        }

        long totalBytes = response.headers().firstValueAsLong("content-length").orElse(-1L);
        try (InputStream in = new BufferedInputStream(response.body());
                OutputStream out = Files.newOutputStream(destination)) {
            byte[] buffer = new byte[16384];
            long downloadedBytes = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                downloadedBytes += read;
                if (totalBytes > 0 && progressPercent != null) {
                    int step = (int) ((downloadedBytes * progressWeight) / totalBytes);
                    progressPercent.accept(Math.min(progressBase + progressWeight, progressBase + step));
                }
            }
        }

        if (progressPercent != null) {
            progressPercent.accept(progressBase + progressWeight);
        }
    }

    /**
     * 從 ZIP 封裝包中尋找目標檔名（不論處於根目錄或子資料夾），扁平化萃取至目標資料夾
     */
    private static void extractTargetFiles(Path zipPath, Path destDir, Set<String> targetFileNames) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zipPath)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }

                String entryFileName = Path.of(entry.getName()).getFileName().toString();
                for (String target : targetFileNames) {
                    if (entryFileName.equalsIgnoreCase(target)) {
                        Path destinationFile = destDir.resolve(target);

                        // 防範 Zip Slip 漏洞路徑穿越攻擊
                        if (!destinationFile.normalize().startsWith(destDir.normalize())) {
                            throw new SecurityException("Zip Slip detected: " + entry.getName());
                        }

                        Files.copy(zis, destinationFile, StandardCopyOption.REPLACE_EXISTING);
                        break;
                    }
                }
            }
        }
    }

    /**
     * 辨識是否為 Apple Silicon。
     * 包含若在 M 系列 Mac 上以 x86_64 JDK 執行時，識別 Rosetta 2 轉譯狀態。
     */
    private static boolean isAppleSilicon() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return true;
        }
        // 若 Java 回報 x86_64，向系統確認是否為 Rosetta 2 虛擬轉譯環境
        try {
            Process process = new ProcessBuilder("sysctl", "-in", "sysctl.proc_translated").start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            if ("1".equals(output)) {
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static boolean isValidExecutable(Path file) {
        try {
            return Files.isRegularFile(file) && Files.size(file) > 1024 * 1024; // 至少大於 1MB 避免空檔
        } catch (IOException e) {
            return false;
        }
    }

    private static void makeExecutable(Path file) {
        if (!Files.exists(file)) {
            return;
        }
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
            perms.add(PosixFilePermission.OWNER_READ);
            perms.add(PosixFilePermission.OWNER_WRITE);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_READ);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_READ);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException e) {
            // Windows fallback
            file.toFile().setExecutable(true, false);
        } catch (IOException ignored) {
            file.toFile().setExecutable(true, false);
        }
    }

    public static void wipeCache(Path cacheDir) throws IOException {
        if (!Files.exists(cacheDir)) {
            return;
        }
        try (var walk = Files.walk(cacheDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        }
    }
}