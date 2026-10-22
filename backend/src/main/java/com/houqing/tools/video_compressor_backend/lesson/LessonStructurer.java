package com.houqing.tools.video_compressor_backend.lesson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
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
import java.util.ArrayList;
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

        for (int round = 1; round <= MAX_ROUNDS; round++) {
            LlmClient.Result res = llm.chat(msgs, tools, "submit_lesson", null);
            Checked checked = check(res, duration, frames);
            if (checked.result() != null) return new Outcome(checked.result(), llm.model(), promptVersion, round);
            if (round == MAX_ROUNDS || "content_filter".equals(res.finishReason())) {
                throw new ApiException(ErrorCode.LESSON_INVALID, checked.problem());
            }
            appendRepair(msgs, res, checked.problem());
        }
        throw new IllegalStateException("lesson repair rounds exhausted");
    }

    private record Checked(LessonSchema.Result result, String problem) {}

    private Checked check(LlmClient.Result response, double duration, List<LessonSchema.OcrFrame> frames) {
        if ("length".equals(response.finishReason()) || "max_tokens".equals(response.finishReason())) {
            return new Checked(null, "AI 课程结构输出被截断，达到输出长度上限；请减少课程内容或代码片段长度后重试");
        }
        if ("content_filter".equals(response.finishReason())) {
            return new Checked(null, "AI 服务未完成课程结构生成，内容被服务过滤");
        }
        boolean malformed = false;
        String validationProblem = null;
        if (response.hasToolCalls()) {
            for (LlmClient.ToolCall call : response.toolCalls()) {
                if (call == null || !"submit_lesson".equals(call.name())) continue;
                JsonNode args = parseJson(call.arguments());
                if (args == null) {
                    malformed = true;
                    continue;
                }
                Checked checked = validate(args, duration, frames);
                if (checked.result() != null) return checked;
                if (validationProblem == null) validationProblem = checked.problem();
            }
        }
        // Some compatible providers return a JSON body despite tool_choice. Accept only a complete JSON
        // document (optionally one Markdown fence), never prose extraction or an invented empty course.
        String content = jsonContent(response.content());
        if (content != null) {
            JsonNode args = parseJson(content);
            if (args == null) malformed = true;
            else {
                Checked checked = validate(args, duration, frames);
                if (checked.result() != null) return checked;
                if (validationProblem == null) validationProblem = checked.problem();
            }
        }
        if (validationProblem != null) return new Checked(null, validationProblem);
        if (malformed) return new Checked(null, "AI 返回的课程 JSON 格式不完整或无法解析，未保存课程；请重试");
        return new Checked(null, "AI 未调用 submit_lesson，也未返回完整的课程 JSON；请重试");
    }

    private JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
        } catch (IOException e) {
            return null;
        }
    }

    private String jsonContent(String content) {
        if (content == null || content.isBlank()) return null;
        String json = content.strip();
        if (json.startsWith("```")) {
            int newline = json.indexOf('\n');
            if (newline < 0 || !json.endsWith("```")) return null;
            String language = json.substring(3, newline).strip();
            if (!language.isEmpty() && !language.equalsIgnoreCase("json")) return null;
            json = json.substring(newline + 1, json.length() - 3).strip();
        }
        return json.startsWith("{") || json.startsWith("[") ? json : null;
    }

    private Checked validate(JsonNode args, double duration, List<LessonSchema.OcrFrame> frames) {
        List<String> errors = shapeErrors(args);
        if (errors.isEmpty()) {
            LessonSchema.Result result = LessonSchema.validate(args, duration, frames, mapper);
            if (result.valid()) return new Checked(result, null);
            errors = result.errors();
        }
        return new Checked(null, "课程结构未通过校验：" + String.join("；", errors.subList(0, Math.min(3, errors.size()))));
    }

    /** Validate the required tool fields before the normalizer can supply display defaults. */
    private List<String> shapeErrors(JsonNode args) {
        List<String> errors = new ArrayList<>();
        if (!args.isObject()) return List.of("课程结构必须是 JSON 对象");
        requireText(args, "title", "课程", errors);
        optionalText(args, "summary", "课程", errors);
        if (!args.path("nodes").isArray()) errors.add("nodes 必须是大纲数组");
        else for (JsonNode node : args.path("nodes")) {
            if (!node.isObject()) { errors.add("大纲节点必须是对象"); continue; }
            requireText(node, "id", "大纲节点", errors);
            requireText(node, "title", "大纲节点", errors);
            requireNumber(node, "start", "大纲节点", errors);
            requireNumber(node, "end", "大纲节点", errors);
            optionalText(node, "summary", "大纲节点", errors);
            if (node.has("parentId") && !node.path("parentId").isNull() && !node.path("parentId").isTextual()) {
                errors.add("大纲节点的 parentId 必须是字符串或 null");
            }
            if (node.has("keywords")) {
                if (!node.path("keywords").isArray()) errors.add("大纲节点的 keywords 必须是字符串数组");
                else for (JsonNode keyword : node.path("keywords")) {
                    if (!keyword.isTextual()) { errors.add("大纲节点的 keywords 必须是字符串数组"); break; }
                }
            }
        }
        if (!args.path("snippets").isArray()) errors.add("snippets 必须是代码片段数组，无代码时提交空数组");
        else for (JsonNode snippet : args.path("snippets")) {
            if (!snippet.isObject()) { errors.add("代码片段必须是对象"); continue; }
            requireText(snippet, "title", "代码片段", errors);
            requireText(snippet, "code", "代码片段", errors);
            requireNumber(snippet, "start", "代码片段", errors);
            if (!List.of("python", "javascript", "text").contains(snippet.path("language").asText())) {
                errors.add("代码片段的 language 必须是 python、javascript 或 text");
            }
            if (snippet.has("end")) requireNumber(snippet, "end", "代码片段", errors);
            optionalText(snippet, "explanation", "代码片段", errors);
            if (snippet.has("runnable") && !snippet.path("runnable").isBoolean()) errors.add("代码片段的 runnable 必须是布尔值");
        }
        return errors;
    }

    private static void requireText(JsonNode in, String key, String where, List<String> errors) {
        if (!in.path(key).isTextual() || in.path(key).asText().isBlank()) errors.add(where + "的 " + key + " 必须是非空字符串");
    }

    private static void optionalText(JsonNode in, String key, String where, List<String> errors) {
        if (in.has(key) && !in.path(key).isTextual()) errors.add(where + "的 " + key + " 必须是字符串");
    }

    private static void requireNumber(JsonNode in, String key, String where, List<String> errors) {
        if (!in.path(key).isNumber() || !Double.isFinite(in.path(key).asDouble())) errors.add(where + "的 " + key + " 必须是有限数值");
    }

    private void appendRepair(ArrayNode messages, LlmClient.Result response, String problem) {
        String feedback = problem + "\n请修正后只调用一次 submit_lesson。保留必填的 title、nodes、snippets，"
                + "使用真实的时间范围；减少重复描述和非必要代码，确保完整提交。没有代码时 snippets 为 []。";
        ObjectNode assistant = messages.addObject().put("role", "assistant")
                .put("content", response.content() == null ? "" : response.content());
        boolean replayCalls = response.hasToolCalls() && response.toolCalls().stream().allMatch(call -> call != null
                && call.id() != null && !call.id().isBlank() && call.name() != null && !call.name().isBlank());
        if (replayCalls) {
            ArrayNode calls = assistant.putArray("tool_calls");
            for (LlmClient.ToolCall call : response.toolCalls()) {
                ObjectNode tc = calls.addObject().put("id", call.id()).put("type", "function");
                tc.putObject("function").put("name", call.name()).put("arguments", call.arguments() == null ? "{}" : call.arguments());
                messages.addObject().put("role", "tool").put("tool_call_id", call.id()).put("content", feedback);
            }
        } else {
            if (assistant.path("content").asText().isBlank()) messages.remove(messages.size() - 1);
            messages.addObject().put("role", "user").put("content", feedback);
        }
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
        np.putObject("parentId").put("description", "顶层节点为 null").set("type", mapper.valueToTree(List.of("string", "null")));
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
