package com.houqing.tools.video_compressor_backend.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Invokes ExifTool through a UTF-8 argument file. On Windows ExifTool decodes argv with the ANSI code page,
 * which would garble Chinese tag values; an "-@ file" argument list is read as UTF-8 bytes instead.
 */
@Component
public class ExifToolRunner {
    private static final Logger log = LoggerFactory.getLogger(ExifToolRunner.class);

    private final ToolLocator tools;
    private final ProcessRunner runner;
    private final ObjectMapper mapper;

    public ExifToolRunner(ToolLocator tools, ProcessRunner runner, ObjectMapper mapper) {
        this.tools = tools;
        this.runner = runner;
        this.mapper = mapper;
    }

    /** Runs exiftool with the given arguments (one per line in the arg file); throws PROCESS_FAILED on error. */
    public String run(List<String> args, Path argDir, Duration timeout, CancellationToken token) {
        return run(args, argDir, timeout, token, false);
    }

    private String run(List<String> args, Path argDir, Duration timeout, CancellationToken token, boolean jsonStdout) {
        Path argFile = argDir.resolve("exiftool-" + System.nanoTime() + ".args");
        try {
            List<String> lines = new ArrayList<>(args.size() + 2);
            lines.add("-charset");
            lines.add("filename=UTF8");
            for (String a : args) lines.add(stripControl(a));
            Files.write(argFile, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR);
        }
        List<String> cmd = new ArrayList<>(tools.command(Tool.EXIFTOOL));
        cmd.add("-@");
        cmd.add(argFile.toString());
        StringBuilder out = new StringBuilder();
        try {
            ProcessRunner.Result r = jsonStdout
                    ? runner.runStdout(cmd, timeout, token, line -> out.append(line).append('\n'))
                    : runner.run(cmd, timeout, token, line -> out.append(line).append('\n'));
            if (!r.ok()) {
                log.warn("exiftool exited with {}: {}", r.exitCode(), r.tail());
                throw new ApiException(ErrorCode.PROCESS_FAILED);
            }
            return out.toString();
        } finally {
            try {
                Files.deleteIfExists(argFile);
            } catch (IOException ignored) {
                // work dir is removed anyway
            }
        }
    }

    /** Reads all tags as JSON (-G1 groups, numeric values). */
    public JsonNode readJson(Path file, Path argDir, CancellationToken token) {
        String out = run(List.of("-json", "-G1", "-n", "-a", "-charset", "UTF8", file.toString()),
                argDir, Duration.ofSeconds(60), token, true);
        try {
            JsonNode arr = mapper.readTree(out);
            if (arr == null || !arr.isArray() || arr.isEmpty() || !arr.get(0).isObject()) {
                throw new ApiException(ErrorCode.MEDIA_UNREADABLE);
            }
            return arr.get(0);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.MEDIA_UNREADABLE);
        }
    }

    /** Names of tags that can reveal where a file was made (GPS / location), used to verify a scrub really worked. */
    public static List<String> locationTags(JsonNode tags) {
        List<String> found = new ArrayList<>();
        tags.fieldNames().forEachRemaining(k -> {
            String lower = k.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("composite:")) return; // derived, not stored
            if (lower.contains("gps") || lower.contains("location") || lower.contains("iso6709")) found.add(k);
        });
        return found;
    }

    /** Arg-file lines must not contain newlines; other control chars are dropped too. */
    static String stripControl(String s) {
        return s == null ? "" : s.replaceAll("[\\x00-\\x1f\\x7f]", " ");
    }
}
