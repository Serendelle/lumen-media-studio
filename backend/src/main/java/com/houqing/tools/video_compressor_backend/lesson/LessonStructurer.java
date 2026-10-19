package com.houqing.tools.video_compressor_backend.lesson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.houqing.tools.video_compressor_backend.ai.llm.LlmClient;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Turns transcript + screen text into a knowledge outline and code snippets with the language model. The model
 * answers through one tool; {@link LessonSchema} validates it, and validation errors get one bounded repair round.
 */
@Component
public class LessonStructurer {
    static final int MAX_ROUNDS = 2;
    static final int TRANSCRIPT_CHARS = 36_000, SCREEN_CHARS = 14_000;

    private final LlmClient llm;
    private final ObjectMapper mapper;
    private final String prompt;
    private final String promptVersion;

    public LessonStructurer(LlmClient llm, ObjectMapper mapper) {
        this.llm = llm;
        this.mapper = mapper;
        String raw;
        try {
            raw = new ClassPathResource("ai/lesson/lesson-structurer.md").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("missing ai/lesson/lesson-structurer.md", e);
        }
        int end = raw.indexOf("\n---", 3);
        this.promptVersion = raw.substring(0, Math.max(0, end)).lines().filter(l -> l.startsWith("version:"))
                .map(l -> l.substring(8).trim()).findFirst().orElse("0");
        this.prompt = end > 0 ? raw.substring(end + 4).trim() : raw;
    }

    public record Outcome(LessonSchema.Result result, String model, String promptVersion, int rounds) {}

    public boolean configured() { return llm.configured(); }

    public Outcome structure(List<AsrClient.Sentence> transcript, List<LessonSchema.OcrFrame> frames, double duration) {
        if (!llm.configured()) throw new ApiException(ErrorCode.AI_UNAVAILABLE, "未配置 DeepSeek，无法整理知识大纲");
        ArrayNode msgs = mapper.createArrayNode();
        msgs.addObject().put("role", "system").put("content", prompt);
        String user = "视频时长：" + Math.round(duration) + " 秒（" + LessonSchema.clock(duration) + "）。\n\n"
                + "## 语音转写\n" + LessonSchema.transcript(transcript, TRANSCRIPT_CHARS) + "\n"
                + "## 画面文字（OCR）\n" + (frames.stream().anyMatch(f -> !f.lines().isEmpty())
                        ? LessonSchema.screenText(frames, SCREEN_CHARS) : "（画面上没有识别到文字）\n")
                + "\n请调用 submit_lesson 提交。";
        msgs.addObject().put("role", "user").put("content", user);
        ArrayNode tools = mapper.createArrayNode().add(tool());

        LessonSchema.Result last = null;
        for (int round = 1; round <= MAX_ROUNDS; round++) {
            LlmClient.Result res = llm.chat(msgs, tools, "required", null);
            LlmClient.ToolCall call = res.hasToolCalls() ? res.toolCalls().get(0) : null;
            JsonNode args = call == null || !"submit_lesson".equals(call.name()) ? null : LlmClient.args(mapper, call);
            if (args == null) {
                if (round == MAX_ROUNDS) throw new ApiException(ErrorCode.LESSON_INVALID, "AI 没有返回课程结构");
                msgs.addObject().put("role", "user").put("content", "请只通过调用 submit_lesson 提交结果。");
                continue;
            }
            last = LessonSchema.validate(args, duration, frames, mapper);
            if (last.valid()) return new Outcome(last, llm.model(), promptVersion, round);
            if (round == MAX_ROUNDS) break;
            ObjectNode a = msgs.addObject().put("role", "assistant").put("content", "");
            ObjectNode tc = a.putArray("tool_calls").addObject().put("id", call.id()).put("type", "function");
            tc.putObject("function").put("name", call.name()).put("arguments", call.arguments() == null ? "{}" : call.arguments());
            msgs.addObject().put("role", "tool").put("tool_call_id", call.id())
                    .put("content", "课程结构未通过校验，请修正后重新调用 submit_lesson：\n- " + String.join("\n- ", last.errors()));
        }
        throw new ApiException(ErrorCode.LESSON_INVALID, "课程结构未通过校验：" + String.join("；", last.errors().subList(0, Math.min(3, last.errors().size()))));
    }

    private ObjectNode tool() {
        ObjectNode fn = mapper.createObjectNode();
        fn.put("name", "submit_lesson");
        fn.put("description", "提交整理好的课程知识大纲与代码片段（时间单位：秒）。");
        ObjectNode params = fn.putObject("parameters").put("type", "object");
        ObjectNode props = params.putObject("properties");
        props.putObject("title").put("type", "string");
        props.putObject("summary").put("type", "string");
        ObjectNode node = mapper.createObjectNode().put("type", "object");
        ObjectNode np = node.putObject("properties");
        np.putObject("id").put("type", "string");
        np.putObject("parentId").put("type", "string").put("description", "顶层节点为 null");
        np.putObject("title").put("type", "string");
        np.putObject("summary").put("type", "string");
        np.putObject("start").put("type", "number");
        np.putObject("end").put("type", "number");
        np.putObject("keywords").put("type", "array").putObject("items").put("type", "string");
        node.set("required", mapper.valueToTree(List.of("id", "title", "start", "end")));
        props.putObject("nodes").put("type", "array").set("items", node);
        ObjectNode sn = mapper.createObjectNode().put("type", "object");
        ObjectNode sp = sn.putObject("properties");
        sp.putObject("title").put("type", "string");
        sp.putObject("language").put("type", "string").set("enum", mapper.valueToTree(List.of("python", "javascript", "text")));
        sp.putObject("start").put("type", "number");
        sp.putObject("end").put("type", "number");
        sp.putObject("code").put("type", "string");
        sp.putObject("explanation").put("type", "string");
        sp.putObject("runnable").put("type", "boolean");
        sn.set("required", mapper.valueToTree(List.of("title", "language", "start", "code")));
        props.putObject("snippets").put("type", "array").set("items", sn);
        params.set("required", mapper.valueToTree(List.of("title", "nodes", "snippets")));
        return mapper.createObjectNode().put("type", "function").set("function", fn);
    }
}
