package com.houqing.tools.video_compressor_backend.lesson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.houqing.tools.video_compressor_backend.ai.llm.LlmClient;
import com.houqing.tools.video_compressor_backend.media.CancellationToken;
import com.houqing.tools.video_compressor_backend.media.Tool;
import com.houqing.tools.video_compressor_backend.support.ApiTestBase;
import com.houqing.tools.video_compressor_backend.support.TestMedia;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Learning player pipeline with real FFmpeg keyframes and real local OCR. Only the two network services are test
 * doubles: speech recognition (sentences with timestamps) and the language model (scripted tool calls).
 */
class LessonPipelineTests extends ApiTestBase {

    @Autowired LessonAnalyzeHandler handler;
    @Autowired LessonStructurer structurer;
    @Autowired LessonController controller;
    @Autowired OcrRunner ocr;
    private Object realAsr, realLlm, realControllerAsr;
    private FakeAsr asr;
    private Scripted llm;

    static final String CODE = "def fib(n):\n    a, b = 0, 1\n    for _ in range(n):\n        a, b = b, a + b\n    return a\n\nprint(fib(10))";

    static final class FakeAsr implements AsrClient {
        boolean configured = true;
        @Override public List<Sentence> transcribe(Path audio, CancellationToken token, Consumer<String> stage) {
            assertThat(audio).exists(); // the real extracted track is what would be uploaded
            stage.accept("识别中");
            return List.of(new Sentence(0, 4000, "今天我们讲斐波那契数列。"), new Sentence(4000, 9000, "先看递推的定义。"),
                    new Sentence(9000, 15000, "然后用 Python 写一个循环版本。"), new Sentence(15000, 19500, "运行一下看看结果。"));
        }
        @Override public boolean configured() { return configured; }
        @Override public String model() { return "fake-asr"; }
    }

    static final class Scripted implements LlmClient {
        final Deque<Function<ArrayNode, Result>> turns = new ArrayDeque<>();
        final List<ArrayNode> seen = new ArrayList<>();
        @Override public Result chat(ArrayNode messages, ArrayNode tools, String toolChoice, Consumer<String> onText) {
            seen.add(messages.deepCopy());
            return turns.removeFirst().apply(messages);
        }
        @Override public boolean configured() { return true; }
        @Override public String model() { return "scripted"; }
        static Result call(String args) {
            return new Result("", List.of(new ToolCall("c" + UUID.randomUUID().toString().substring(0, 5), "submit_lesson", args)), "tool_calls", 1, 1);
        }
    }

    @BeforeEach
    void swap() {
        realAsr = ReflectionTestUtils.getField(handler, "asr");
        realControllerAsr = ReflectionTestUtils.getField(controller, "asr");
        realLlm = ReflectionTestUtils.getField(structurer, "llm");
        asr = new FakeAsr();
        llm = new Scripted();
        ReflectionTestUtils.setField(handler, "asr", asr);
        ReflectionTestUtils.setField(controller, "asr", asr);
        ReflectionTestUtils.setField(structurer, "llm", llm);
    }

    @AfterEach
    void restore() {
        ReflectionTestUtils.setField(handler, "asr", realAsr);
        ReflectionTestUtils.setField(controller, "asr", realControllerAsr);
        ReflectionTestUtils.setField(structurer, "llm", realLlm);
    }

    /** 20 s: a title card for 8 s, then the code on screen until the end; a sine tone as the "voice". */
    static Path lessonVideo() throws Exception {
        Path v = TestMedia.DIR.resolve("lesson.mp4");
        if (Files.exists(v)) return v;
        Files.createDirectories(TestMedia.DIR);
        Path code = TestMedia.DIR.resolve("lesson-code.txt");
        Files.writeString(code, CODE);
        String font = "C\\:/Windows/Fonts/consola.ttf";
        String vf = "drawtext=fontfile='" + font + "':text='Fibonacci':fontcolor=white:fontsize=60:x=80:y=300:enable='lt(t,8)',"
                + "drawtext=fontfile='" + font + "':textfile='" + code.toString().replace("\\", "/").replace(":", "\\:")
                + "':fontcolor=white:fontsize=34:x=80:y=120:line_spacing=12:enable='gte(t,8)'";
        Process p = new ProcessBuilder(TestMedia.tools().command(Tool.FFMPEG).get(0), "-v", "error", "-y",
                "-f", "lavfi", "-i", "color=c=0x1e1e1e:s=1280x720:r=10", "-f", "lavfi", "-i", "sine=frequency=300:sample_rate=16000",
                "-t", "20", "-vf", vf, "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", v.toString())
                .redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).as(log).isZero();
        return v;
    }

    static String lesson(String code, double nodeEnd) {
        return "{\"title\":\"斐波那契数列\",\"summary\":\"从定义到循环实现。\",\"nodes\":["
                + "{\"id\":\"n1\",\"parentId\":null,\"title\":\"数列定义\",\"summary\":\"递推关系。\",\"start\":0,\"end\":9,\"keywords\":[\"递推\"]},"
                + "{\"id\":\"n2\",\"parentId\":null,\"title\":\"Python 实现\",\"summary\":\"循环版本。\",\"start\":9,\"end\":" + nodeEnd + "},"
                + "{\"id\":\"n3\",\"parentId\":\"n2\",\"title\":\"运行结果\",\"start\":15,\"end\":19.5}],"
                + "\"snippets\":[{\"title\":\"循环求斐波那契\",\"language\":\"python\",\"start\":9,\"end\":19,\"runnable\":true,"
                + "\"explanation\":\"用两个变量滚动。\",\"code\":" + com.fasterxml.jackson.databind.node.TextNode.valueOf(code) + "}]}";
    }

    private JsonNode analyze(User u) throws Exception {
        String fileId = json(upload("/api/files/upload", lessonVideo(), "斐波那契.mp4", u)).path("fileId").asText();
        var r = postJson("/api/v2/lessons", Map.of("videoFileId", fileId), u);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(202);
        return waitTask(json(r).path("taskId").asText(), u, 300_000);
    }

    @Test
    void codeSeenOnScreenIsLabelledAsRecognisedAndTheTimelineIsRepairedOnce() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE);
        assumeTrue(ocr.available(), "RapidOCR environment not installed");
        User u = register(), other = register();
        // round 1: a chapter ends after the video → validator error fed back; round 2: fixed
        llm.turns.add(m -> Scripted.call(lesson(CODE, 999)));
        llm.turns.add(m -> {
            assertThat(m.toString()).contains("结束时间需大于开始时间且不超过视频时长");
            return Scripted.call(lesson(CODE, 19.5));
        });
        JsonNode t = analyze(u);
        assertThat(t.path("status").asText()).as(t.toString()).isEqualTo("SUCCEEDED");
        String lessonId = t.path("result").path("lessonId").asText();

        // the model saw real measurements: transcript with timestamps and the OCR'd code with its indentation
        String input = llm.seen.get(0).get(1).path("content").asText();
        assertThat(input).contains("[00:09] 然后用 Python 写一个循环版本。");
        assertThat(input).contains("def fib(n):").contains("    a, b = 0, 1").contains("        a, b = b, a + b");

        JsonNode l = json(get("/api/v2/lessons/" + lessonId, u));
        JsonNode lesson = l.path("lesson");
        assertThat(lesson.path("nodes").size()).isEqualTo(3);
        assertThat(lesson.path("nodes").get(1).path("end").asDouble()).isEqualTo(19.5);
        JsonNode sn = lesson.path("snippets").get(0);
        assertThat(sn.path("source").asText()).isEqualTo("SCREEN");
        assertThat(sn.path("ocrSupport").asDouble()).isGreaterThanOrEqualTo(0.7);
        assertThat(sn.path("evidenceTime").asDouble()).isBetween(8.0, 20.0);
        assertThat(l.path("transcript").size()).isEqualTo(4);
        assertThat(l.path("screen").toString()).contains("def fib(n):");
        assertThat(l.path("meta").path("asrModel").asText()).isEqualTo("fake-asr");

        // the evidence frame is served only with this lesson's ticket
        String frameUrl = json(postJson("/api/v2/lessons/" + lessonId + "/ticket", Map.of(), u)).path("frameUrl").asText()
                .replace("{frame}", sn.path("evidenceFrame").asText());
        var img = get(frameUrl, null);
        assertThat(img.getResponse().getStatus()).isEqualTo(200);
        assertThat(img.getResponse().getContentType()).isEqualTo("image/jpeg");
        assertThat(get("/api/v2/lessons/" + lessonId + "/frames/" + sn.path("evidenceFrame").asText() + "?ticket=bogus", null).getResponse().getStatus()).isEqualTo(401);
        assertThat(get("/api/v2/lessons/" + lessonId, other).getResponse().getStatus()).isEqualTo(404);
        assertThat(json(get("/api/v2/lessons", other)).path("items").size()).isZero();
        assertThat(json(get("/api/v2/lessons", u)).path("items").get(0).path("snippets").asInt()).isEqualTo(1);
    }

    @Test
    void codeTheScreenNeverShowedIsLabelledAsModelWritten() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE);
        assumeTrue(ocr.available(), "RapidOCR environment not installed");
        User u = register();
        String invented = "import numpy as np\nmatrix = np.array([[1, 1], [1, 0]])\nresult = np.linalg.matrix_power(matrix, 10)\nprint(result)";
        llm.turns.add(m -> Scripted.call(lesson(invented, 19.5)));
        JsonNode t = analyze(u);
        assertThat(t.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(t.path("result").path("warnings").toString()).contains("AI 根据讲解整理");
        JsonNode sn = json(get("/api/v2/lessons/" + t.path("result").path("lessonId").asText(), u)).path("lesson").path("snippets").get(0);
        assertThat(sn.path("source").asText()).isEqualTo("MODEL");
        assertThat(sn.path("ocrSupport").asDouble()).isLessThan(0.3);
    }

    @Test
    void withoutSpeechRecognitionNothingIsQueuedAndTheReasonIsClear() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE);
        asr.configured = false;
        User u = register();
        assertThat(json(get("/api/v2/lessons/capabilities", u)).path("asr").path("configured").asBoolean()).isFalse();
        String fileId = json(upload("/api/files/upload", TestMedia.get("demo.mp4"), "demo.mp4", u)).path("fileId").asText();
        var r = postJson("/api/v2/lessons", Map.of("videoFileId", fileId), u);
        assertThat(r.getResponse().getStatus()).isEqualTo(503);
        assertThat(json(r).path("code").asText()).isEqualTo("ASR_UNAVAILABLE");
        assertThat(json(r).path("message").asText()).contains("DASHSCOPE_API_KEY");
        assertThat(json(get("/api/tasks", u)).path("totalElements").asInt()).isZero();
    }

    @Test
    void indentationIsRebuiltFromTheBoxesAndInventedTimesAreRejected() {
        var frame = new LessonSchema.OcrFrame(10, List.of(
                new LessonSchema.OcrLine("def f(x):", 80, 100, 233, 130),
                new LessonSchema.OcrLine("if x:", 150, 140, 235, 170),
                new LessonSchema.OcrLine("return 1", 220, 180, 356, 210)));
        assertThat(LessonSchema.layout(frame)).isEqualTo("def f(x):\n    if x:\n        return 1");

        var bad = mapper.createObjectNode();
        bad.putArray("nodes").addObject().put("id", "n1").put("title", "x").put("start", 50).put("end", 40);
        bad.putArray("snippets");
        var r = LessonSchema.validate(bad, 30, List.of(), mapper);
        assertThat(r.valid()).isFalse();
        assertThat(String.join(" ", r.errors())).contains("开始时间", "结束时间");
    }
}
