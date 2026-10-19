package com.houqing.tools.video_compressor_backend.lesson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.houqing.tools.video_compressor_backend.config.AppProperties;
import com.houqing.tools.video_compressor_backend.media.CancellationToken;
import com.houqing.tools.video_compressor_backend.media.ProcessRunner;
import com.houqing.tools.video_compressor_backend.media.Tool;
import com.houqing.tools.video_compressor_backend.media.ToolLocator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;
import java.util.function.IntConsumer;

/** Runs lesson_ocr.py (RapidOCR, local) over a folder of keyframes; parses only its "@@OCR " result lines. */
@Component
public class OcrRunner {
    private final ToolLocator tools;
    private final ProcessRunner runner;
    private final AppProperties props;
    private final ObjectMapper mapper;

    public OcrRunner(ToolLocator tools, ProcessRunner runner, AppProperties props, ObjectMapper mapper) {
        this.tools = tools;
        this.runner = runner;
        this.props = props;
        this.mapper = mapper;
    }

    public boolean available() { return tools.available(Tool.OCR); }

    /** file name → recognised lines (with boxes); onFrame receives the number of frames done so far. */
    public Map<String, List<LessonSchema.OcrLine>> run(Path framesDir, int frameCount, CancellationToken token, IntConsumer onFrame) {
        Path script = script();
        List<String> cmd = new ArrayList<>(tools.command(Tool.OCR));
        cmd.add(script.toString());
        cmd.add(framesDir.toString());
        Map<String, List<LessonSchema.OcrLine>> out = new LinkedHashMap<>();
        int[] done = {0};
        // first use downloads the models; afterwards ~0.3-1 s per changed frame on a laptop CPU
        Duration timeout = Duration.ofSeconds(180 + 3L * frameCount);
        runner.runChecked(cmd, timeout, token, line -> {
            if (!line.startsWith("@@OCR ")) return;
            try {
                JsonNode n = mapper.readTree(line.substring(6));
                List<LessonSchema.OcrLine> lines = new ArrayList<>();
                for (JsonNode l : n.path("lines")) {
                    JsonNode b = l.path("box");
                    lines.add(new LessonSchema.OcrLine(l.path("text").asText(""),
                            b.path(0).asInt(0), b.path(1).asInt(0), b.path(2).asInt(0), b.path(3).asInt(0)));
                }
                out.put(n.path("file").asText(), lines);
                onFrame.accept(++done[0]);
            } catch (Exception ignored) {
                // a malformed line is skipped, not fatal
            }
        }, framesDir);
        return out;
    }

    /** The script ships inside the jar; it is copied once to the data dir so Python can run it. */
    private synchronized Path script() {
        Path target = props.dataRoot().resolve("run").resolve("lesson_ocr.py");
        try (InputStream in = new ClassPathResource("lesson/lesson_ocr.py").getInputStream()) {
            Files.createDirectories(target.getParent());
            byte[] bytes = in.readAllBytes();
            if (!Files.exists(target) || !Arrays.equals(Files.readAllBytes(target), bytes)) {
                Path tmp = target.resolveSibling("lesson_ocr.py.tmp");
                Files.write(tmp, bytes);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } catch (IOException e) {
            throw new IllegalStateException("cannot prepare OCR script", e);
        }
    }
}
