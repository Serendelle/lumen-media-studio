package com.houqing.tools.video_compressor_backend.media;

import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import com.houqing.tools.video_compressor_backend.support.TestMedia;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real FFmpeg processes: timeouts, cancellation and closed stdin (audit H-04). */
class ProcessRunnerTest {
    @TempDir Path tmp;
    private ProcessRunner runner;

    @BeforeEach
    void setUp() {
        runner = new ProcessRunner(new MagickPolicy(TestMedia.props(tmp)));
    }

    /** An endless encode to the null sink. */
    private List<String> endless() {
        List<String> cmd = new ArrayList<>(TestMedia.tools().command(Tool.FFMPEG));
        cmd.addAll(List.of("-hide_banner", "-nostdin", "-re", "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=25",
                "-f", "null", "-"));
        return cmd;
    }

    @Test
    void timeoutIsEnforcedAndProcessKilled() {
        TestMedia.assumeTools(Tool.FFMPEG);
        long start = System.nanoTime();
        assertThatThrownBy(() -> runner.run(endless(), Duration.ofSeconds(2), null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.PROCESS_TIMEOUT));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
        assertNoFfmpegChildLeft();
    }

    @Test
    void cancellationKillsRunningProcess() {
        TestMedia.assumeTools(Tool.FFMPEG);
        CancellationToken token = new CancellationToken();
        ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor();
        ses.schedule(token::cancel, 1, TimeUnit.SECONDS);
        try {
            assertThatThrownBy(() -> runner.run(endless(), Duration.ofMinutes(5), token, null))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.CANCELLED));
        } finally {
            ses.shutdownNow();
        }
        assertNoFfmpegChildLeft();
    }

    @Test
    void overwritePromptCannotHangBecauseStdinIsClosed() throws Exception {
        // the original AudioService omitted -y; ffmpeg then waited forever on "Overwrite? [y/N]".
        // With stdin closed the prompt can never block: the process must finish promptly (whatever it decides).
        TestMedia.assumeTools(Tool.FFMPEG);
        Path out = tmp.resolve("exists.mp3");
        Files.writeString(out, "already here");
        List<String> cmd = new ArrayList<>(TestMedia.tools().command(Tool.FFMPEG));
        cmd.addAll(List.of("-hide_banner", "-i", TestMedia.get("demo.mp4").toString(), "-vn", out.toString())); // no -y, no -nostdin
        long start = System.nanoTime();
        ProcessRunner.Result r = runner.run(cmd, Duration.ofSeconds(30), null, null);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(20));
        assertThat(r.tail()).isNotNull();
    }

    @Test
    void outputLinesAreStreamedAndTailKept() {
        TestMedia.assumeTools(Tool.FFMPEG);
        List<String> lines = new ArrayList<>();
        List<String> cmd = new ArrayList<>(TestMedia.tools().command(Tool.FFMPEG));
        cmd.add("-version");
        ProcessRunner.Result r = runner.run(cmd, Duration.ofSeconds(20), null, lines::add);
        assertThat(r.ok()).isTrue();
        assertThat(lines).isNotEmpty();
        assertThat(r.tail()).contains("ffmpeg version");
    }

    @Test
    void missingExecutableIsAToolError() {
        assertThatThrownBy(() -> runner.run(List.of(tmp.resolve("no-such-tool.exe").toString()), Duration.ofSeconds(5), null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.TOOL_UNAVAILABLE));
    }

    private static void assertNoFfmpegChildLeft() {
        boolean alive = ProcessHandle.current().descendants()
                .anyMatch(p -> p.info().command().map(c -> c.toLowerCase().contains("ffmpeg")).orElse(false) && p.isAlive());
        assertThat(alive).as("ffmpeg child still running").isFalse();
    }
}
