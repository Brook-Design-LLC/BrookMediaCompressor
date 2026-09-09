# Brook Media Compressor

Desktop app to compress images, video, and audio to a target file size. Built for workflows where uploads are size-limited (for example, GitHub issue attachments).

## Requirements

- **Java 17+** (JRE or JDK). [Eclipse Temurin](https://adoptium.net/) is recommended.
- Internet on first run if ffmpeg is not already installed (downloads ~30–80 MB depending on platform).

## Download

Get the latest `brook-media-compress.jar` from [GitHub Releases](https://github.com/Brook-Design-LLC/BrookMediaCompressor/releases/latest).

## Usage

```bash
java -jar brook-media-compress.jar
```

Or double-click the JAR if your system associates `.jar` files with Java.

1. Set the **target size** in MB (default: 10).
2. Optionally adjust **quality** and **frame rate** sliders (see below).
3. Optionally enable **Convert HDR to SDR**.
4. Drag and drop a file onto the window, or click to browse.
5. Click **Start** and choose where to save the output when prompted.

A live preview estimates the output format for video files before you start.

### Quality slider (bppf)

- **Left (Pixelated):** lower bppf floor — keeps resolution but may show more compression artifacts.
- **Right (Blurry):** higher bppf floor — enforces per-frame quality and may downscale resolution.

### Frame rate slider

- **Left (Blurry):** lower frame rates — more bitrate per frame.
- **Right (Clarity):** higher frame rates — smoother motion; at a fixed file size, higher FPS reduces per-frame quality.
- **Center (auto):** lets the planner search multiple frame rates.
- **Origin:** locks encoding to the source frame rate.

Files already at or below the target size are reported as not needing compression.

## ffmpeg

On first use, the app uses a cached copy in `~/.brook-tools/ffmpeg/` (macOS) or `%LOCALAPPDATA%\BrookTools\ffmpeg\` (Windows). If not present, it downloads a static GPL build automatically (system `PATH` ffmpeg is not used):

- **macOS:** [martin-riedl.de](https://ffmpeg.martin-riedl.de/) static builds
- **Windows:** [BtbN FFmpeg-Builds](https://github.com/BtbN/FFmpeg-Builds/releases) (`win64-gpl` / `winarm64-gpl`)

## Hardware encoding

Video encoding prefers **H.265 (HEVC)** and falls back to **H.264** when hardware or software HEVC is unavailable.

| Platform | Encoders tried (in order) |
|----------|---------------------------|
| macOS | `hevc_videotoolbox` → `libx265` → `h264_videotoolbox` → `libx264` |
| Windows | `hevc_nvenc` → `hevc_qsv` → `hevc_amf` → `libx265` → `h264_nvenc` → `h264_qsv` → `h264_amf` → `libx264` |

The status area shows the planned output format (codec, resolution, fps, bitrates) and encode progress. If hardware encoding fails, the app tries the next encoder automatically.

## Build from source

Requires **Gradle 9.1+** to build. The JAR targets **Java 17** bytecode.

```bash
./gradlew fatJar
# Output: build/libs/brook-media-compress.jar
```

Run during development:

```bash
./gradlew run
```

## License

This project is licensed under the [MIT License](LICENSE).

**Third-party note:** FFmpeg binaries used at runtime are GPL-licensed and downloaded separately — they are not bundled inside the JAR. See the [FFmpeg license](https://www.ffmpeg.org/legal.html) for details.
