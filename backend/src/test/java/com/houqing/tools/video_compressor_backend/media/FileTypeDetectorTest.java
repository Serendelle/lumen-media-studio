package com.houqing.tools.video_compressor_backend.media;

import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import com.houqing.tools.video_compressor_backend.support.TestMedia;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Upload type detection is by content, never by the client's file name (audit H-06). */
class FileTypeDetectorTest {
    private final FileTypeDetector detector = new FileTypeDetector();
    @TempDir Path tmp;

    @Test
    void isoBmffBrandDecidesBetweenMp4AndMov() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG);
        // FFmpeg's default MP4 ("isom" brand) used to be stored as .mov
        assertThat(detector.detect(TestMedia.get("demo.mp4"), "demo.mp4").ext()).isEqualTo("mp4");
        java.nio.file.Path mov = TestMedia.DIR.resolve("brand.mov");
        if (!java.nio.file.Files.exists(mov)) {
            Process p = new ProcessBuilder(TestMedia.tools().command(Tool.FFMPEG).get(0), "-v", "error", "-y", "-i",
                    TestMedia.get("demo.mp4").toString(), "-t", "1", "-c", "copy", "-f", "mov", mov.toString()).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            assertThat(p.waitFor()).isZero();
        }
        assertThat(detector.detect(mov, "x.mp4").ext()).isEqualTo("mov"); // a real QuickTime file stays .mov
    }

    @Test
    void detectsRealMediaByContent() {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.EXIFTOOL);
        assertThat(detector.detect(TestMedia.get("demo.mp4"), "demo.mp4").kind()).isEqualTo(MediaKind.VIDEO);
        assertThat(detector.detect(TestMedia.get("photo.jpg"), "photo.jpg").ext()).isEqualTo("jpg");
        assertThat(detector.detect(TestMedia.get("logo.png"), "logo.png").ext()).isEqualTo("png");
        assertThat(detector.detect(TestMedia.get("song.mp3"), "song.mp3").kind()).isEqualTo(MediaKind.AUDIO);
    }

    @Test
    void misleadingExtensionDoesNotChangeDetectedType() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.EXIFTOOL);
        Path disguised = tmp.resolve("x");
        Files.copy(TestMedia.get("photo.jpg"), disguised);
        FileTypeDetector.Detected d = detector.detect(disguised, "movie.mp4");
        assertThat(d.kind()).isEqualTo(MediaKind.IMAGE);
        assertThat(d.ext()).isEqualTo("jpg");
    }

    @Test
    void rejectsTextAndExecutables() throws Exception {
        Path txt = write("notes.mp4", "#EXTM3U\nhello world\n");
        assertCode(() -> detector.detect(txt, "notes.mp4"), ErrorCode.UNSUPPORTED_MEDIA);
        Path exe = tmp.resolve("app.jpg");
        Files.write(exe, new byte[]{'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0, 4, 0, 0, 0, (byte) 0xFF, (byte) 0xFF});
        assertCode(() -> detector.detect(exe, "app.jpg"), ErrorCode.UNSUPPORTED_MEDIA);
        Path empty = write("empty.png", "");
        assertCode(() -> detector.detect(empty, "empty.png"), ErrorCode.UNSUPPORTED_MEDIA);
    }

    @Test
    void svgMustBeSelfContained() throws Exception {
        Path clean = write("ok.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"><rect width=\"10\" height=\"10\" fill=\"#D9EDE4\"/></svg>");
        assertThat(detector.detect(clean, "ok.svg").ext()).isEqualTo("svg");

        Path ext = write("ext.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\"><image xlink:href=\"file:///C:/x.png\"/></svg>");
        assertCode(() -> detector.detect(ext, "ext.svg"), ErrorCode.UNSUPPORTED_MEDIA);

        Path remote = write("remote.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"><style>rect{fill:url(http://example.com/a)}</style></svg>");
        assertCode(() -> detector.detect(remote, "remote.svg"), ErrorCode.UNSUPPORTED_MEDIA);

        Path script = write("s.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>");
        assertCode(() -> detector.detect(script, "s.svg"), ErrorCode.UNSUPPORTED_MEDIA);

        Path dtd = write("d.svg", "<?xml version=\"1.0\"?><!DOCTYPE svg [<!ENTITY a \"b\">]><svg xmlns=\"http://www.w3.org/2000/svg\"/>");
        assertCode(() -> detector.detect(dtd, "d.svg"), ErrorCode.UNSUPPORTED_MEDIA);

        Path inlineData = write("data.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\"><image xlink:href=\"data:image/png;base64,iVBORw0KGgo=\"/><use href=\"#a\"/></svg>");
        assertThat(detector.detect(inlineData, "data.svg").ext()).isEqualTo("svg");
    }

    private Path write(String name, String content) throws Exception {
        Path p = tmp.resolve(name);
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable c, ErrorCode code) {
        assertThatThrownBy(c).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(code));
    }
}
