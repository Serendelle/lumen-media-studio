package com.houqing.tools.video_compressor_backend.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.houqing.tools.video_compressor_backend.ai.llm.LlmClient;
import com.houqing.tools.video_compressor_backend.media.Tool;
import com.houqing.tools.video_compressor_backend.support.ApiTestBase;
import com.houqing.tools.video_compressor_backend.support.TestMedia;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * State-machine tests for AI mode on the full stack (real SQLite, real FFmpeg/ExifTool, real TaskEngine).
 * Only the language model is replaced, by a scripted test double, so orchestration rules can be asserted
 * deterministically. The real DeepSeek path is covered by AiLiveTests (opt-in) and recorded runs in the report.
 */
class AiOrchestratorTests extends ApiTestBase {

    @Autowired AiOrchestrator orchestrator;
    private Object realLlm;
    private Scripted llm;

    /** Test double: each chat() call consumes the next scripted turn; records what the agent sent. */
    static final class Scripted implements LlmClient {
        final Deque<Function<ArrayNode, Result>> turns = new ArrayDeque<>();
        final List<ArrayNode> seenMessages = new CopyOnWriteArrayList<>();
        final List<Set<String>> seenTools = new CopyOnWriteArrayList<>();

        Scripted then(Function<ArrayNode, Result> f) { turns.add(f); return this; }

        @Override
        public Result chat(ArrayNode messages, ArrayNode tools, String toolChoice, Consumer<String> onText) {
            seenMessages.add(messages.deepCopy());
            Set<String> names = new LinkedHashSet<>();
            if (tools != null) tools.forEach(t -> names.add(t.path("function").path("name").asText()));
            seenTools.add(names);
            Function<ArrayNode, Result> f = turns.poll();
            Result r = f == null ? text("（没有更多脚本）") : f.apply(messages);
            if (onText != null && r.content() != null && !r.content().isEmpty()) onText.accept(r.content());
            return r;
        }

        @Override public boolean configured() { return true; }
        @Override public String model() { return "scripted-test-double"; }

        static Result call(String name, String args) {
            return new Result("", List.of(new ToolCall("c_" + UUID.randomUUID().toString().substring(0, 6), name, args)), "tool_calls", 10, 5);
        }

        static Result text(String t) { return new Result(t, List.of(), "stop", 10, 5); }
    }

    @BeforeEach
    void swapLlm() {
        realLlm = ReflectionTestUtils.getField(orchestrator, "llm");
        llm = new Scripted();
        ReflectionTestUtils.setField(orchestrator, "llm", llm);
    }

    @AfterEach
    void restoreLlm() {
        ReflectionTestUtils.setField(orchestrator, "llm", realLlm);
    }

    private String upload(User u) throws Exception {
        return json(upload("/api/files/upload", TestMedia.get("demo.mp4"), "demo.mp4", u)).path("fileId").asText();
    }

    private String conversation(User u) throws Exception {
        return json(postJson("/api/ai/conversations", Map.of(), u)).path("id").asText();
    }

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

    private static String requirements(String fileId) {
        return "{\"understood\":\"你想压缩视频并清除位置信息\",\"ready\":true,\"requests\":[{\"action\":\"compress_video\",\"fileIds\":[\"" + fileId
                + "\"],\"goals\":{\"crf\":32}},{\"action\":\"remove_location\",\"fileIds\":[\"" + fileId + "\"],\"goals\":{}}]}";
    }

    private static String plan(String fileId, int crf) {
        return "{\"summary\":\"压缩后清除位置\",\"steps\":[{\"id\":1,\"tool\":\"video.compress\",\"title\":\"压缩\",\"input\":\"" + fileId
                + "\",\"params\":{\"mode\":\"crf\",\"crf\":" + crf + ",\"resolution\":\"360p\"},\"qualityImpact\":\"medium\"},"
                + "{\"id\":2,\"tool\":\"metadata.scrub\",\"title\":\"清除位置\",\"input\":\"step:1\",\"params\":{\"mode\":\"location\"},\"qualityImpact\":\"none\"}]}";
    }

    @Test
    void fullRunExecutesOnlyAfterConfirmationOfTheExactPlanVersion() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE, Tool.EXIFTOOL);
        User u = register();
        String file = upload(u);
        String id = conversation(u);
        llm.then(m -> Scripted.call("inspect_media", "{\"fileId\":\"" + file + "\"}"))
           .then(m -> Scripted.call("submit_requirements", requirements(file)))
           .then(m -> Scripted.call("submit_plan", plan(file, 32)))
           .then(m -> Scripted.text("全部核验通过，结果可以下载。"));

        assertThat(postJson("/api/ai/conversations/" + id + "/messages", Map.of("text", "压缩一下再清掉位置", "fileIds", List.of(file)), u)
                .getResponse().getStatus()).isEqualTo(200);
        JsonNode c = waitState(id, u, Set.of("AWAITING_CONFIRMATION", "FAILED"), 30_000);
        assertThat(c.path("state").asText()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(c.path("plan").path("steps").size()).isEqualTo(2);
        // the analyst really received the tool result (real ffprobe data) before submitting
        JsonNode toolMsg = null;
        for (JsonNode m : llm.seenMessages.get(1)) if ("tool".equals(m.path("role").asText())) toolMsg = m;
        assertThat(toolMsg).isNotNull();
        assertThat(toolMsg.path("content").asText()).contains("\"width\":1280").contains("\"durationSec\":6.0");
        // nothing has been executed yet
        assertThat(json(get("/api/tasks", u)).path("totalElements").asInt()).isZero();

        int version = c.path("planVersion").asInt();
        assertThat(postJson("/api/ai/conversations/" + id + "/confirm", Map.of("version", version + 1, "approve", true), u)
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(postJson("/api/ai/conversations/" + id + "/confirm", Map.of("version", version, "approve", true), u)
                .getResponse().getStatus()).isEqualTo(200);

        c = waitState(id, u, Set.of("COMPLETED", "FAILED"), 120_000);
        assertThat(c.path("state").asText()).isEqualTo("COMPLETED");
        JsonNode review = c.path("context").path("review");
        assertThat(review.path("allPassed").asBoolean()).isTrue();
        assertThat(review.path("outputs").size()).isEqualTo(2);
        JsonNode tasks = json(get("/api/tasks", u)).path("content");
        assertThat(tasks.size()).isEqualTo(2);
        tasks.forEach(t -> assertThat(t.path("origin").asText()).isEqualTo("AI")); // same engine as Professional mode
        // step 2 really consumed step 1's output
        String step1Out = review.path("outputs").get(0).path("fileId").asText();
        assertThat(json(get("/api/files/" + review.path("outputs").get(1).path("fileId").asText(), u)).path("sourceTaskId").asText())
                .isNotBlank();
        assertThat(step1Out).isNotEqualTo(file);

        // the full history is replayable after a refresh
        JsonNode snap = json(get("/api/ai/conversations/" + id, u));
        List<String> types = new ArrayList<>();
        snap.path("events").forEach(e -> types.add(e.path("type").asText()));
        assertThat(types).contains("agent", "tool.start", "tool.done", "plan", "confirm.required", "confirmed", "step", "review", "message", "usage");
        assertThat(snap.path("conversation").path("promptVersion").asText()).isNotBlank();
    }

    @Test
    void agentCannotUseToolsOutsideItsSkill() throws Exception {
        User u = register();
        String file = TestMedia.available(Tool.FFMPEG) ? upload(u) : "none";
        String id = conversation(u);
        // the analyst tries to jump ahead by submitting a plan itself; then behaves
        llm.then(m -> Scripted.call("submit_plan", plan(file, 30)))
           .then(m -> Scripted.call("submit_requirements", "{\"understood\":\"你想做什么？\",\"ready\":false,\"requests\":[],"
                   + "\"clarifications\":[{\"question\":\"要压缩还是转格式？\",\"options\":[\"压缩\",\"转格式\"]}]}"));
        postJson("/api/ai/conversations/" + id + "/messages", Map.of("text", "处理一下"), u);
        JsonNode c = waitState(id, u, Set.of("AWAITING_INPUT", "FAILED"), 20_000);
        assertThat(c.path("state").asText()).isEqualTo("AWAITING_INPUT");
        assertThat(llm.seenTools.get(0)).doesNotContain("submit_plan");
        // the refusal was fed back to the model
        assertThat(llm.seenMessages.get(1).toString()).contains("不在").contains("权限范围内");
        assertThat(c.path("planVersion").asInt()).isZero();
        assertThat(json(get("/api/tasks", u)).path("totalElements").asInt()).isZero();
        // the clarification reached the user
        JsonNode msgs = json(get("/api/ai/conversations/" + id, u)).path("messages");
        assertThat(msgs.get(msgs.size() - 1).path("content").asText()).contains("要压缩还是转格式");
    }

    @Test
    void invalidPlanIsRepairedThroughValidatorFeedback() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE);
        User u = register();
        String file = upload(u);
        String id = conversation(u);
        llm.then(m -> Scripted.call("submit_requirements", requirements(file)))
           .then(m -> Scripted.call("submit_plan", plan(file, 99)))       // crf out of range
           .then(m -> Scripted.call("submit_plan", plan(file, 30)));      // repaired
        postJson("/api/ai/conversations/" + id + "/messages", Map.of("text", "压缩", "fileIds", List.of(file)), u);
        JsonNode c = waitState(id, u, Set.of("AWAITING_CONFIRMATION", "FAILED"), 30_000);
        assertThat(c.path("state").asText()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(llm.seenMessages.get(2).toString()).contains("CRF 需在 0-51 之间"); // the real handler's validation message
        assertThat(c.path("plan").path("steps").get(0).path("params").path("crf").asInt()).isEqualTo(30);
    }

    @Test
    void planReferencingAnotherUsersFileIsRejected() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE);
        User victim = register();
        String foreign = upload(victim);
        User u = register();
        String own = upload(u);
        String id = conversation(u);
        llm.then(m -> Scripted.call("submit_requirements", requirements(own)))
           .then(m -> Scripted.call("submit_plan", plan(foreign, 30)))
           .then(m -> Scripted.call("submit_plan", plan(foreign, 30)))
           .then(m -> Scripted.call("submit_plan", plan(foreign, 30)));
        postJson("/api/ai/conversations/" + id + "/messages", Map.of("text", "压缩", "fileIds", List.of(own)), u);
        JsonNode c = waitState(id, u, Set.of("AWAITING_CONFIRMATION", "FAILED"), 30_000);
        assertThat(c.path("state").asText()).isEqualTo("FAILED");
        String err = json(get("/api/ai/conversations/" + id, u)).path("events").toString();
        assertThat(err).contains("不存在或不属于当前用户");
        assertThat(json(get("/api/tasks", victim)).path("totalElements").asInt()).isZero();
        assertThat(json(get("/api/tasks", u)).path("totalElements").asInt()).isZero();
    }

    @Test
    void runawayModelIsStoppedByRoundAndBudgetLimits() throws Exception {
        User u = register();
        String id = conversation(u);
        for (int i = 0; i < 30; i++) llm.then(m -> Scripted.text("我再想想……"));
        postJson("/api/ai/conversations/" + id + "/messages", Map.of("text", "随便处理一下"), u);
        JsonNode c = waitState(id, u, Set.of("FAILED"), 20_000);
        assertThat(c.path("state").asText()).isEqualTo("FAILED");
        assertThat(llm.seenMessages.size()).isLessThanOrEqualTo(AiOrchestrator.MAX_LLM_CALLS_PER_RUN);
        assertThat(json(get("/api/ai/conversations/" + id, u)).path("events").toString()).contains("未能在限定轮数内给出结果");
    }

    @Test
    void cancelWhileAwaitingConfirmationPreventsExecution() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE);
        User u = register();
        String file = upload(u);
        String id = conversation(u);
        llm.then(m -> Scripted.call("submit_requirements", requirements(file)))
           .then(m -> Scripted.call("submit_plan", plan(file, 30)));
        postJson("/api/ai/conversations/" + id + "/messages", Map.of("text", "压缩", "fileIds", List.of(file)), u);
        JsonNode c = waitState(id, u, Set.of("AWAITING_CONFIRMATION", "FAILED"), 30_000);
        postJson("/api/ai/conversations/" + id + "/cancel", Map.of(), u);
        assertThat(waitState(id, u, Set.of("CANCELLED"), 5_000).path("state").asText()).isEqualTo("CANCELLED");
        MvcResult late = postJson("/api/ai/conversations/" + id + "/confirm", Map.of("version", c.path("planVersion").asInt(), "approve", true), u);
        assertThat(late.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(get("/api/tasks", u)).path("totalElements").asInt()).isZero();
    }

    @Test
    void conversationsAreIsolatedAndInputIsValidated() throws Exception {
        User a = register(), b = register();
        String id = conversation(a);
        assertThat(get("/api/ai/conversations/" + id, b).getResponse().getStatus()).isEqualTo(404);
        assertThat(get("/api/ai/conversations/" + id + "/events", b).getResponse().getStatus()).isEqualTo(404);
        assertThat(postJson("/api/ai/conversations/" + id + "/messages", Map.of("text", "x"), b).getResponse().getStatus()).isEqualTo(404);
        assertThat(postJson("/api/ai/conversations/" + id + "/messages", Map.of("text", ""), a).getResponse().getStatus()).isEqualTo(400);
        assertThat(postJson("/api/ai/conversations/" + id + "/messages", Map.of("text", "x".repeat(2001)), a).getResponse().getStatus()).isEqualTo(400);
        // attaching someone else's file is refused before any model call
        if (TestMedia.available(Tool.FFMPEG, Tool.FFPROBE)) {
            String foreign = upload(b);
            assertThat(postJson("/api/ai/conversations/" + id + "/messages", Map.of("text", "压缩", "fileIds", List.of(foreign)), a)
                    .getResponse().getStatus()).isEqualTo(404);
        }
        assertThat(llm.seenMessages).isEmpty();
        assertThat(json(get("/api/ai/conversations", b)).path("totalElements").asInt()).isZero();
    }

    @Test
    void eventsAreReplayableFromAnySequenceNumber() throws Exception {
        User u = register();
        String id = conversation(u);
        llm.then(m -> Scripted.call("submit_requirements", "{\"understood\":\"你想做什么？\",\"ready\":false,\"requests\":[],\"clarifications\":[]}"));
        postJson("/api/ai/conversations/" + id + "/messages", Map.of("text", "嗨"), u);
        waitState(id, u, Set.of("AWAITING_INPUT"), 20_000);
        JsonNode all = json(get("/api/ai/conversations/" + id, u)).path("events");
        assertThat(all.size()).isGreaterThan(3);
        long mid = all.get(2).path("seq").asLong();
        long prev = 0;
        for (JsonNode e : all) {
            assertThat(e.path("seq").asLong()).isGreaterThan(prev); // strictly increasing: duplicates are detectable
            prev = e.path("seq").asLong();
        }
        // after=<seq> returns exactly the tail (async SSE dispatch replays the same rows)
        MvcResult async = mvc.perform(auth(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/ai/conversations/" + id + "/events").param("after", String.valueOf(mid)), u)).andReturn();
        String body = async.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("id:" + mid + "\n").contains("id:" + (all.get(3).path("seq").asLong()));
    }
}
