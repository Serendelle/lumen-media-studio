package com.houqing.tools.video_compressor_backend.media;

import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import com.houqing.tools.video_compressor_backend.support.TestMedia;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A generated child process makes machine output/diagnostic separation deterministic on every platform. */
class ProcessRunnerOutputTests {
    @TempDir Path temp;
    private ProcessRunner runner;

    @BeforeEach void setup() { runner = new ProcessRunner(new MagickPolicy(TestMedia.props(temp))); }

    private List<String> child(String mode) throws Exception {
        boolean windows = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
        Path executable = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        Path testClasses = Path.of(ProcessRunnerOutputTests.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return List.of(executable.toString(), "-cp", testClasses.toString(), Fixture.class.getName(), mode);
    }

    @Test void machineJsonKeepsStderrSeparateAndRetainsOnlyItsBoundedTail() throws Exception {
        StringBuilder stdout = new StringBuilder();
        ProcessRunner.Result result = runner.runStdout(child("json"), Duration.ofSeconds(10), null, s -> stdout.append(s).append('\n'));
        assertThat(result.ok()).isTrue();
        assertThat(stdout.toString()).isEqualTo("[{\"latitude\":31.2304,\"title\":\"测试\"}]\n");
        assertThat(result.tail()).contains("diagnostic-99", "stderr:").doesNotContain("diagnostic-0\n");
        assertThat(result.tail().lines().filter(s -> s.startsWith("diagnostic-")).count()).isEqualTo(40);
    }

    @Test void perProcessCacheOverrideDoesNotChangeParentEnvironment() throws Exception {
        String previous = System.getenv("U2NET_HOME");
        String taskCache = temp.resolve("task-owned-model-cache").toString();
        StringBuilder stdout = new StringBuilder();
        runner.runChecked(child("env"), Duration.ofSeconds(10), null, s -> stdout.append(s), null, Map.of("U2NET_HOME", taskCache));
        assertThat(stdout.toString()).isEqualTo(taskCache);
        assertThat(System.getenv("U2NET_HOME")).isEqualTo(previous);
    }

    @Test void cancellationStillTerminatesMachineOutputProcesses() throws Exception {
        CancellationToken token = new CancellationToken();
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.schedule(token::cancel, 500, TimeUnit.MILLISECONDS);
        List<String> command = child("wait");
        try {
            assertThatThrownBy(() -> runner.runStdout(command, Duration.ofSeconds(20), token, ignored -> {}))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.CANCELLED));
        } finally { scheduler.shutdownNow(); }
        long deadline = System.currentTimeMillis() + 3000;
        while (fixtureRunning() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertThat(fixtureRunning()).isFalse();
    }

    private boolean fixtureRunning() {
        return ProcessHandle.current().descendants().anyMatch(p -> p.info().arguments()
                .map(args -> java.util.Arrays.asList(args).contains(Fixture.class.getName())).orElse(false));
    }

    public static class Fixture {
        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "json" -> {
                    System.out.write("[{\"latitude\":31.2304,\"title\":\"测试\"}]\n".getBytes(StandardCharsets.UTF_8));
                    System.out.flush();
                    for (int i = 0; i < 100; i++) System.err.println("diagnostic-" + i);
                }
                case "env" -> { System.out.write(System.getenv("U2NET_HOME").getBytes(StandardCharsets.UTF_8)); System.out.flush(); }
                case "wait" -> { System.out.println("ready"); System.err.println("waiting"); Thread.sleep(30000); }
                default -> throw new IllegalArgumentException("unknown fixture mode");
            }
        }
    }
}
