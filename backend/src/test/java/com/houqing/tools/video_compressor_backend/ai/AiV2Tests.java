package com.houqing.tools.video_compressor_backend.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.houqing.tools.video_compressor_backend.ai.llm.LlmClient;
import com.houqing.tools.video_compressor_backend.media.Tool;
import com.houqing.tools.video_compressor_backend.support.ApiTestBase;
import com.houqing.tools.video_compressor_backend.support.TestMedia;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** V2 P0 item 5: document tools, structured constraints checked on deliverables, forbidden operations. */
class AiV2Tests extends ApiTestBase {

    @Autowired AiOrchestrator orchestrator;
    private Object realLlm;
    private Scripted llm;

    static final class Scripted implements LlmClient {
        final Deque<Function<ArrayNode, Result>> turns = new ArrayDeque<>();
        final List<ArrayNode> seen = new CopyOnWriteArrayList<>();
        Scripted then(Function<ArrayNode, Result> f) { turns.add(f); return this; }
        @Override public Result chat(ArrayNode messages, ArrayNode tools, String toolChoice, Consumer<String> onText) {
            seen.add(messages.deepCopy());
            Function<ArrayNode, Result> f = turns.poll();
            return f == null ? new Result("好的。", List.of(), "stop", 1, 1) : f.apply(messages);
        }
        @Override public boolean configured() { return true; }
        @Override public String model() { return "scripted"; }
        static Result call(String name, String args) {
            return new Result("", List.of(new ToolCall("c_" + UUID.randomUUID().toString().substring(0, 6), name, args)), "tool_calls", 1, 1);
        }
    }

    @BeforeEach
    void swap() {
        realLlm = ReflectionTestUtils.getField(orchestrator, "llm");
        llm = new Scripted();
        ReflectionTestUtils.setField(orchestrator, "llm", llm);
    }

    @AfterEach
    void restore() { ReflectionTestUtils.setField(orchestrator, "llm", realLlm); }

    private JsonNode waitState(String id, User u, Set<String> states, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        JsonNode c;
        do {
            c = json(get("/api/ai/conversations/" + id, u)).path("conversation");
            if (states.contains(c.path("state").asText())) return c;
            Thread.sleep(150);
        } while (System.currentTimeMillis() < end);
        return c;
    }

    private JsonNode runToReview(User u, String fileId, String requirements, String plan) throws Exception {
        String id = json(postJson("/api/ai/conversations", Map.of(), u)).path("id").asText();
        llm.then(m -> Scripted.call("submit_requirements", requirements)).then(m -> Scripted.call("submit_plan", plan));
        postJson("/api/ai/conversations/" + id + "/messages", Map.of("text", "处理一下", "fileIds", List.of(fileId)), u);
        JsonNode c = waitState(id, u, Set.of("AWAITING_CONFIRMATION", "FAILED"), 30_000);
        assertThat(c.path("state").asText()).as(c.toString()).isEqualTo("AWAITING_CONFIRMATION");
        postJson("/api/ai/conversations/" + id + "/confirm", Map.of("version", c.path("planVersion").asInt(), "approve", true), u);
        return waitState(id, u, Set.of("COMPLETED", "FAILED"), 180_000);
    }

    /** Contents of the tool-result messages the model was shown (unescaped). */
    private static String toolResults(ArrayNode messages) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode m : messages) if ("tool".equals(m.path("role").asText())) sb.append(m.path("content").asText());
        return sb.toString();
    }

    private static JsonNode check(JsonNode review, String name) {
        for (JsonNode c : review.path("checks")) if (c.path("name").asText().equals(name)) return c;
        throw new AssertionError("no check " + name + " in " + review);
    }

    static final String COMPRESS_PLAN = "{\"summary\":\"压缩到约 1.4MB\",\"steps\":[{\"id\":1,\"tool\":\"video.compress\",\"title\":\"压缩\",\"input\":\"%s\","
            + "\"params\":{\"mode\":\"size\",\"targetSizeMB\":1.4,\"codec\":\"h264\",\"audioBitrateKbps\":64},\"qualityImpact\":\"medium\"}]}";

    @Test
    void constraintsAreNormalisedAndProvenOnWhatTheUserReceives() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE);
        User u = register();
        String file = json(upload("/api/files/upload", TestMedia.get("demo.mp4"), "demo.mp4", u)).path("fileId").asText();
        String req = "{\"understood\":\"你想要 1.5MB 以内、保留声音的 MP4\",\"ready\":true,\"requests\":[{\"action\":\"compress_video\",\"fileIds\":[\"" + file + "\"]}],"
                + "\"constraints\":{\"video\":{\"container\":\"MP4\",\"maxSizeMB\":1.5,\"keepAudio\":true,\"bogus\":1},\"audio\":{\"format\":\"ogg\"}}}";
        JsonNode c = runToReview(u, file, req, String.format(COMPRESS_PLAN, file));
        assertThat(c.path("state").asText()).isEqualTo("COMPLETED");
        JsonNode cons = c.path("context").path("requirements").path("constraints");
        assertThat(cons.path("video").path("container").asText()).isEqualTo("mp4");
        assertThat(cons.path("video").path("maxBytes").asLong()).isEqualTo((long) (1.5 * 1048576));
        assertThat(cons.path("video").has("bogus")).isFalse();
        assertThat(cons.has("audio")).as("unsupported format dropped, not guessed").isFalse();
        assertThat(c.path("plan").path("constraintsText").asText()).contains("MP4", "1.50 MiB", "保留音轨");

        JsonNode review = c.path("context").path("review");
        assertThat(check(review, "约束·视频格式").path("ok").asBoolean()).isTrue();
        assertThat(check(review, "约束·视频体积").path("ok").asBoolean()).isTrue();
        assertThat(check(review, "约束·音轨").path("ok").asBoolean()).isTrue();
        assertThat(review.path("verdict").asText()).isEqualTo("VERIFIED");
    }

    @Test
    void aConstraintTheDeliverableMissesFailsTheVerdictEvenWhenEveryStepSucceeded() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE);
        User u = register();
        String file = json(upload("/api/files/upload", TestMedia.get("demo.mp4"), "demo.mp4", u)).path("fileId").asText();
        String req = "{\"understood\":\"你想要 WebM\",\"ready\":true,\"requests\":[{\"action\":\"compress_video\",\"fileIds\":[\"" + file + "\"]}],"
                + "\"constraints\":{\"video\":{\"container\":\"webm\"},\"document\":{\"format\":\"pdf\"}}}";
        JsonNode c = runToReview(u, file, req, String.format(COMPRESS_PLAN, file)); // the planner produces MP4 anyway
        assertThat(c.path("state").asText()).isEqualTo("COMPLETED");
        JsonNode review = c.path("context").path("review");
        assertThat(review.path("outputs").get(0).path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(check(review, "约束·视频格式").path("ok").asBoolean()).isFalse();
        assertThat(check(review, "约束·视频格式").path("actual").asText()).isEqualTo("MP4");
        assertThat(check(review, "约束·PDF").path("ok").asBoolean()).isFalse(); // nothing delivered for it
        assertThat(review.path("allPassed").asBoolean()).isFalse();
        assertThat(review.path("verdict").asText()).isEqualTo("FAILED");
    }

    @Test
    void projectLevelOperationsCanNeverBePlannedByAnAgent() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE);
        User u = register();
        String file = json(upload("/api/files/upload", TestMedia.get("demo.mp4"), "demo.mp4", u)).path("fileId").asText();
        String id = json(postJson("/api/ai/conversations", Map.of(), u)).path("id").asText();
        String req = "{\"understood\":\"你想压缩\",\"ready\":true,\"requests\":[{\"action\":\"compress_video\",\"fileIds\":[\"" + file + "\"]}]}";
        String forbidden = "{\"summary\":\"压缩并直接交付\",\"steps\":[{\"id\":1,\"tool\":\"spec.deliver\",\"title\":\"生成交付包\",\"input\":\"" + file + "\",\"params\":{}}]}";
        llm.then(m -> Scripted.call("submit_requirements", req))
           .then(m -> Scripted.call("submit_plan", forbidden))
           .then(m -> Scripted.call("submit_plan", String.format(COMPRESS_PLAN, file)));
        postJson("/api/ai/conversations/" + id + "/messages", Map.of("text", "压缩完直接帮我生成交付包", "fileIds", List.of(file)), u);
        JsonNode c = waitState(id, u, Set.of("AWAITING_CONFIRMATION", "FAILED"), 30_000);
        assertThat(c.path("state").asText()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(llm.seen.get(2).toString()).contains("只能由你在「材料规范官」页面确认后执行");
        assertThat(c.path("plan").toString()).doesNotContain("spec.deliver");
        assertThat(json(get("/api/tasks", u)).path("totalElements").asInt()).isZero();
    }

    @Test
    void documentsAreInspectedConvertedAndReviewedLikeAnyOtherOutput() throws Exception {
        assumeTrue(TestMedia.tools().available(Tool.SOFFICE), "LibreOffice not installed");
        User u = register();
        byte[] docx;
        try (XWPFDocument d = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            d.createParagraph().createRun().setText("活动说明");
            d.createTable(2, 2);
            d.write(out);
            docx = out.toByteArray();
        }
        String file = json(uploadBytes("/api/files/upload", docx, "说明.docx", u)).path("fileId").asText();
        String id = json(postJson("/api/ai/conversations", Map.of(), u)).path("id").asText();
        String req = "{\"understood\":\"你想把说明转成 PDF\",\"ready\":true,\"requests\":[{\"action\":\"convert_document\",\"fileIds\":[\"" + file + "\"]}],"
                + "\"constraints\":{\"document\":{\"format\":\"pdf\"}}}";
        String plan = "{\"summary\":\"转为 PDF\",\"steps\":[{\"id\":1,\"tool\":\"document.convert\",\"title\":\"转 PDF\",\"input\":\"" + file
                + "\",\"params\":{\"targetFormat\":\"pdf\",\"options\":{}},\"qualityImpact\":\"none\"}]}";
        llm.then(m -> Scripted.call("inspect_document", "{\"fileId\":\"" + file + "\"}"))
           .then(m -> Scripted.call("submit_requirements", req))
           .then(m -> Scripted.call("get_conversion_matrix", "{}"))
           .then(m -> Scripted.call("submit_plan", plan));
        postJson("/api/ai/conversations/" + id + "/messages", Map.of("text", "转成 PDF", "fileIds", List.of(file)), u);
        JsonNode c = waitState(id, u, Set.of("AWAITING_CONFIRMATION", "FAILED"), 30_000);
        assertThat(c.path("state").asText()).as(c.toString()).isEqualTo("AWAITING_CONFIRMATION");
        // the agents got the real document structure and the real conversion routes
        assertThat(toolResults(llm.seen.get(1))).contains("\"tables\"").contains("\"rows\":2");
        assertThat(toolResults(llm.seen.get(3))).contains("\"from\":\"docx\"");

        postJson("/api/ai/conversations/" + id + "/confirm", Map.of("version", c.path("planVersion").asInt(), "approve", true), u);
        c = waitState(id, u, Set.of("COMPLETED", "FAILED"), 180_000);
        JsonNode review = c.path("context").path("review");
        assertThat(check(review, "PDF 文件头").path("ok").asBoolean()).isTrue();
        assertThat(check(review, "页数").path("ok").asBoolean()).isTrue();
        assertThat(check(review, "约束·PDF").path("ok").asBoolean()).isTrue();
        assertThat(review.path("verdict").asText()).isEqualTo("VERIFIED");
        assertThat(review.path("outputs").get(0).path("mediaType").asText()).isEqualTo("PDF");
    }
}
