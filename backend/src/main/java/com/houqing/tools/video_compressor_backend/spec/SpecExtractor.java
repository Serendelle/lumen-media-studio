package com.houqing.tools.video_compressor_backend.spec;

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
import java.util.Map;

/**
 * Drafts a material spec from a notice with the language model. The model may only answer through one tool whose
 * arguments are then validated by {@link SpecSchema}; if they are invalid it gets exactly one repair attempt with
 * the validator's errors. The draft is never saved here — the user confirms it. Only the notice text is sent:
 * no roster, no file names, no personal data.
 */
@Component
public class SpecExtractor {
    static final int MAX_NOTICE = 20000;
    static final int MAX_ROUNDS = 2;

    private final LlmClient llm;
    private final ObjectMapper mapper;
    private final String prompt;
    private final String promptVersion;

    public SpecExtractor(LlmClient llm, ObjectMapper mapper) {
        this.llm = llm;
        this.mapper = mapper;
        String raw;
        try {
            raw = new ClassPathResource("ai/spec/spec-extractor.md").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("missing ai/spec/spec-extractor.md", e);
        }
        int end = raw.indexOf("\n---", 3);
        String front = raw.substring(0, Math.max(0, end));
        this.promptVersion = front.lines().filter(l -> l.startsWith("version:")).map(l -> l.substring(8).trim()).findFirst().orElse("0");
        this.prompt = end > 0 ? raw.substring(end + 4).trim() : raw;
    }

    public record Draft(JsonNode spec, List<String> errors, List<String> warnings, String model, String promptVersion,
                        int rounds) {}

    public Draft extract(String notice) {
        if (notice == null || notice.isBlank()) throw new ApiException(ErrorCode.VALIDATION_FAILED, "请粘贴通知原文");
        if (notice.length() > MAX_NOTICE) throw new ApiException(ErrorCode.VALIDATION_FAILED, "通知原文不能超过 " + MAX_NOTICE + " 字");
        if (!llm.configured()) throw new ApiException(ErrorCode.AI_UNAVAILABLE, "未配置 AI，可以直接手动填写规范");

        ArrayNode msgs = mapper.createArrayNode();
        msgs.addObject().put("role", "system").put("content", prompt);
        msgs.addObject().put("role", "user").put("content", "通知原文如下（三个反引号之间）。请调用 submit_spec。\n```\n" + notice + "\n```");
        ArrayNode tools = mapper.createArrayNode().add(tool());

        SpecSchema.Result last = null;
        for (int round = 1; round <= MAX_ROUNDS; round++) {
            LlmClient.Result res = llm.chat(msgs, tools, "required", null);
            LlmClient.ToolCall call = res.hasToolCalls() ? res.toolCalls().get(0) : null;
            JsonNode args = call == null || !"submit_spec".equals(call.name()) ? null : LlmClient.args(mapper, call);
            if (args == null) {
                if (round == MAX_ROUNDS) throw new ApiException(ErrorCode.AI_UNAVAILABLE, "AI 没有返回可用的规范，请手动填写");
                msgs.addObject().put("role", "user").put("content", "请只通过调用 submit_spec 提交结果。");
                continue;
            }
            last = SpecSchema.validate(args, mapper, notice, null);
            if (last.valid()) return new Draft(last.spec(), List.of(), last.warnings(), llm.model(), promptVersion, round);
            if (round == MAX_ROUNDS) break;
            // one bounded repair: the validator's own messages go back, nothing else
            ObjectNode a = msgs.addObject().put("role", "assistant").put("content", "");
            ObjectNode tc = a.putArray("tool_calls").addObject().put("id", call.id()).put("type", "function");
            tc.putObject("function").put("name", call.name()).put("arguments", call.arguments() == null ? "{}" : call.arguments());
            msgs.addObject().put("role", "tool").put("tool_call_id", call.id())
                    .put("content", "规范未通过校验，请修正后重新调用 submit_spec：\n- " + String.join("\n- ", last.errors()));
        }
        return new Draft(last.spec(), last.errors(), last.warnings(), llm.model(), promptVersion, MAX_ROUNDS);
    }

    private ObjectNode tool() {
        ObjectNode fn = mapper.createObjectNode();
        fn.put("name", "submit_spec");
        fn.put("description", "提交从通知中提取的材料规范（只含通知明确写出的要求，每条附原文引用）。");
        ObjectNode params = fn.putObject("parameters").put("type", "object");
        ObjectNode props = params.putObject("properties");
        props.putObject("title").put("type", "string");
        props.putObject("sizeUnit").put("type", "string").set("enum", mapper.valueToTree(List.of("DECIMAL", "BINARY")));
        ObjectNode slot = mapper.createObjectNode().put("type", "object");
        ObjectNode sp = slot.putObject("properties");
        sp.putObject("key").put("type", "string");
        sp.putObject("label").put("type", "string");
        sp.putObject("kind").put("type", "string").set("enum", mapper.valueToTree(SpecSchema.KINDS));
        sp.putObject("required").put("type", "boolean");
        ObjectNode rules = sp.putObject("rules").put("type", "object");
        ObjectNode rp = rules.putObject("properties");
        rp.putObject("extensions").put("type", "array").putObject("items").put("type", "string");
        for (String k : List.of("maxBytes", "maxWidth", "maxHeight", "minWidth", "minHeight", "minDurationSec", "maxDurationSec")) {
            rp.putObject(k).put("type", "integer");
        }
        sp.putObject("evidence").put("type", "string").put("description", "通知原文中的原句（逐字复制）");
        slot.set("required", mapper.valueToTree(List.of("key", "label", "kind", "evidence")));
        props.putObject("slots").put("type", "array").set("items", slot);
        ObjectNode naming = props.putObject("naming").put("type", "object");
        naming.putObject("properties").putObject("pattern").put("type", "string");
        ((ObjectNode) naming.path("properties")).putObject("evidence").put("type", "string");
        props.putObject("notes").put("type", "array").putObject("items").put("type", "string");
        params.set("required", mapper.valueToTree(List.of("slots")));
        return mapper.createObjectNode().put("type", "function").set("function", fn);
    }

    public Map<String, Object> describe() {
        return Map.of("configured", llm.configured(), "model", llm.model(), "promptVersion", promptVersion);
    }
}
