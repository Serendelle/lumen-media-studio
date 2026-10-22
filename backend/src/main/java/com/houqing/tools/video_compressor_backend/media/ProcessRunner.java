package com.houqing.tools.video_compressor_backend.media;

import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Runs an external tool safely:
 * - arguments are passed as a list (no shell), stdin is closed so tools can never block on a prompt;
 * - output is drained on a separate thread so the timeout is always enforced;
 * - on timeout or cancellation the whole process tree is killed.
 */
@Component
public class ProcessRunner {
    private static final Logger log = LoggerFactory.getLogger(ProcessRunner.class);
    private static final int TAIL_LINES = 40;

    private final Path magickPolicyDir;

    public ProcessRunner(MagickPolicy policy) {
        this.magickPolicyDir = policy.directory();
    }

    public record Result(int exitCode, String tail) {
        public boolean ok() { return exitCode == 0; }
    }

    public Result run(List<String> command, Duration timeout, CancellationToken token, Consumer<String> onLine) {
        return run(command, timeout, token, onLine, null);
    }

    public Result run(List<String> command, Duration timeout, CancellationToken token, Consumer<String> onLine,
                      Path workingDir) {
        return execute(command, timeout, token, onLine, workingDir, Map.of(), false);
    }

    /** Machine-readable tools must keep diagnostics out of stdout; stderr is drained into a bounded tail. */
    public Result runStdout(List<String> command, Duration timeout, CancellationToken token, Consumer<String> onLine) {
        return execute(command, timeout, token, onLine, null, Map.of(), true);
    }

    private Result execute(List<String> command, Duration timeout, CancellationToken token, Consumer<String> onLine,
                           Path workingDir, Map<String, String> environmentOverrides, boolean separateStderr) {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(!separateStderr);
        if (workingDir != null) pb.directory(workingDir.toFile());
        Map<String, String> env = pb.environment();
        // every ImageMagick call is confined by our restrictive policy.xml
        env.put("MAGICK_CONFIGURE_PATH", magickPolicyDir.toString());
        env.put("PYTHONIOENCODING", "utf-8");
        if (environmentOverrides != null) env.putAll(environmentOverrides);
        if (log.isDebugEnabled()) log.debug("exec: {}", String.join(" ", command));

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            log.warn("Cannot start {}: {}", command.get(0), e.getMessage());
            throw new ApiException(ErrorCode.TOOL_UNAVAILABLE, "处理工具无法启动，请检查依赖安装");
        }
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // nothing to close
        }
        if (token != null) token.attach(process);

        Deque<String> tail = new ArrayDeque<>();
        Deque<String> errorTail = new ArrayDeque<>();
        Thread reader = drain(process.getInputStream(), tail, onLine, "proc-reader");
        Thread errorReader = separateStderr ? drain(process.getErrorStream(), errorTail, null, "proc-stderr") : null;

        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (true) {
                if (process.waitFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)) break;
                if (token != null && token.isCancelled()) {
                    kill(process);
                    throw new ApiException(ErrorCode.CANCELLED);
                }
                if (System.nanoTime() > deadline) {
                    kill(process);
                    throw new ApiException(ErrorCode.PROCESS_TIMEOUT, "处理超时（超过 " + timeout.toMinutes() + " 分钟）");
                }
            }
            reader.join(2000);
            if (errorReader != null) errorReader.join(2000);
        } catch (InterruptedException e) {
            kill(process);
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.CANCELLED);
        } finally {
            if (token != null) token.detach();
            try { process.getInputStream().close(); } catch (IOException ignored) { }
            try { process.getErrorStream().close(); } catch (IOException ignored) { }
        }
        if (token != null && token.isCancelled()) throw new ApiException(ErrorCode.CANCELLED);
        String tailText;
        synchronized (tail) {
            tailText = String.join("\n", tail);
        }
        if (separateStderr) synchronized (errorTail) {
            if (!errorTail.isEmpty()) tailText += "\nstderr:\n" + String.join("\n", errorTail);
        }
        return new Result(process.exitValue(), tailText);
    }

    private Thread drain(InputStream input, Deque<String> tail, Consumer<String> onLine, String name) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null;) {
                    synchronized (tail) {
                        tail.addLast(line.length() > 4096 ? line.substring(line.length() - 4096) : line);
                        if (tail.size() > TAIL_LINES) tail.removeFirst();
                    }
                    if (onLine != null) try { onLine.accept(line); }
                    catch (RuntimeException e) { log.debug("line listener failed: {}", e.getMessage()); }
                }
            } catch (IOException ignored) { /* timeout, cancellation, or stream closure */ }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** Runs and throws PROCESS_FAILED on non-zero exit; tool output only goes to the log. */
    public Result runChecked(List<String> command, Duration timeout, CancellationToken token, Consumer<String> onLine) {
        return runChecked(command, timeout, token, onLine, null);
    }

    public Result runChecked(List<String> command, Duration timeout, CancellationToken token, Consumer<String> onLine,
                             Path workingDir) {
        return runChecked(command, timeout, token, onLine, workingDir, Map.of());
    }

    /** Per-process overrides, e.g. a task-owned model cache; overrides are never printed in logs. */
    public Result runChecked(List<String> command, Duration timeout, CancellationToken token, Consumer<String> onLine,
                             Path workingDir, Map<String, String> environmentOverrides) {
        Result r = execute(command, timeout, token, onLine, workingDir, environmentOverrides, false);
        if (!r.ok()) {
            log.warn("{} exited with {}; output tail:\n{}", Path.of(command.get(0)).getFileName(), r.exitCode(), r.tail());
            throw new ApiException(ErrorCode.PROCESS_FAILED);
        }
        return r;
    }

    static void kill(Process p) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }
}
