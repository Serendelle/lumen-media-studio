package com.houqing.tools.video_compressor_backend.support;

import com.houqing.tools.video_compressor_backend.config.AppProperties;
import com.houqing.tools.video_compressor_backend.media.Tool;
import com.houqing.tools.video_compressor_backend.media.ToolLocator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Generates small but real media files with the installed FFmpeg/ExifTool (no binary fixtures in git).
 * Tests that need them call {@link #assumeTools} so a machine without the tools reports them as skipped.
 */
public final class TestMedia {
    private TestMedia() {}

    public static final Path DIR = Path.of("target", "test-media").toAbsolutePath();
    private static final ToolLocator TOOLS = new ToolLocator(props(DIR.resolve("unused")));
    private static boolean generated;

    public static AppProperties props(Path dataDir) {
        return new AppProperties(dataDir.toString(), 24,
                new AppProperties.Tools("ffmpeg", "ffprobe", "exiftool", "magick", "", "", "rembg-env", "", "u2net"),
                new AppProperties.Media(2, 50, 4, 0, 60, 1),
                new AppProperties.Security("unit-test-secret", 72, true),
                new AppProperties.Cors(List.of("http://localhost:5173")),
                new AppProperties.Ai("", "https://api.deepseek.com", "deepseek-chat"));
    }

    public static ToolLocator tools() { return TOOLS; }

    public static boolean available(Tool... tools) {
        return Arrays.stream(tools).allMatch(TOOLS::available);
    }

    public static void assumeTools(Tool... tools) {
        org.junit.jupiter.api.Assumptions.assumeTrue(available(tools),
                "skipped: required tools not installed " + Arrays.toString(tools));
    }

    /** demo.mp4 (6s 1280x720 H.264 6Mbps + AAC), photo.jpg (with GPS + Model), logo.png, song.mp3, truncated.mp4 */
    public static synchronized Path get(String name) {
        if (!generated) {
            generate();
            generated = true;
        }
        return DIR.resolve(name);
    }

    private static void generate() {
        try {
            Files.createDirectories(DIR);
            String ff = TOOLS.command(Tool.FFMPEG).get(0);
            if (!Files.exists(DIR.resolve("demo.mp4"))) {
                run(ff, "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=30",
                        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", "6",
                        "-c:v", "libx264", "-b:v", "6M", "-c:a", "aac", "-b:a", "192k", "-shortest",
                        DIR.resolve("demo.mp4").toString());
            }
            if (!Files.exists(DIR.resolve("photo.jpg"))) {
                run(ff, "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=1600x1200", "-frames:v", "1", "-q:v", "2",
                        DIR.resolve("photo.jpg").toString());
                run(TOOLS.command(Tool.EXIFTOOL).get(0), "-q", "-overwrite_original",
                        "-GPSLatitude=31.2304", "-GPSLatitudeRef=N", "-GPSLongitude=121.4737", "-GPSLongitudeRef=E",
                        "-Model=TestCam", DIR.resolve("photo.jpg").toString());
            }
            if (!Files.exists(DIR.resolve("logo.png"))) {
                run(ff, "-v", "error", "-y", "-f", "lavfi", "-i", "color=c=white:s=200x80", "-frames:v", "1",
                        DIR.resolve("logo.png").toString());
            }
            if (!Files.exists(DIR.resolve("song.mp3"))) {
                run(ff, "-v", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=660:sample_rate=44100", "-t", "3",
                        "-c:a", "libmp3lame", "-b:a", "128k", DIR.resolve("song.mp3").toString());
            }
            if (!Files.exists(DIR.resolve("truncated.mp4"))) {
                byte[] all = Files.readAllBytes(DIR.resolve("demo.mp4"));
                Files.write(DIR.resolve("truncated.mp4"), Arrays.copyOf(all, 4096)); // valid magic, broken body
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void run(String... cmd) {
        try {
            List<String> c = new ArrayList<>(Arrays.asList(cmd));
            Process p = new ProcessBuilder(c).redirectErrorStream(true).start();
            p.getOutputStream().close();
            String out = new String(p.getInputStream().readAllBytes());
            if (!p.waitFor(120, TimeUnit.SECONDS) || p.exitValue() != 0) {
                throw new IllegalStateException("media generation failed: " + String.join(" ", c) + "\n" + out);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
