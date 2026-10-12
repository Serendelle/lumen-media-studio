package com.houqing.tools.video_compressor_backend.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.List;
import java.util.function.Consumer;

/** Minimal chat-completions contract the agents depend on (implemented by {@link DeepSeekClient}). */
public interface LlmClient {

    record ToolCall(String id, String name, String arguments) {}

    record Result(String content, List<ToolCall> toolCalls, String finishReason, int promptTokens, int completionTokens) {
        public boolean hasToolCalls() { return toolCalls != null && !toolCalls.isEmpty(); }
    }

    /**
     * @param messages OpenAI-format messages
     * @param tools    OpenAI-format tool definitions (may be null)
     * @param toolChoice "auto", "required" or null
     * @param onText   receives streamed content deltas (may be null)
     */
    Result chat(ArrayNode messages, ArrayNode tools, String toolChoice, Consumer<String> onText);

    boolean configured();

    String model();

    /** Convenience for the few places that only need the parsed arguments of a tool call. */
    static JsonNode args(com.fasterxml.jackson.databind.ObjectMapper mapper, ToolCall call) {
        try {
            return mapper.readTree(call.arguments() == null || call.arguments().isBlank() ? "{}" : call.arguments());
        } catch (Exception e) {
            return null;
        }
    }
}
