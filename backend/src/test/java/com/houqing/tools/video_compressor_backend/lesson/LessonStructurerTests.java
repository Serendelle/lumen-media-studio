package com.houqing.tools.video_compressor_backend.lesson;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.houqing.tools.video_compressor_backend.ai.llm.LlmClient;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Response fixtures only: these tests do not invoke an external model, speech recognition, or media tools. */
class LessonStructurerTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String LESSON = """
            {"title":"视频压缩演示","summary":"演示压缩参数与结果。","nodes":[
              {"id":"n1","parentId":null,"title":"选择参数","start":0,"end":30},
              {"id":"n2","parentId":null,"title":"检查结果","start":30,"end":60}
            ],"snippets":[]}
            """;
    private static final List<AsrClient.Sentence> TRANSCRIPT = List.of(
            new AsrClient.Sentence(0, 30_000, "先选择视频压缩参数。"),
            new AsrClient.Sentence(30_000, 60_000, "再检查压缩后的文件。"));

    @Test
    void requestsTheNamedFunctionAndPermitsNullRootParents() {
        Scripted model = new Scripted(call("ok", LESSON));
        LessonStructurer.Outcome outcome = structure(model);

        assertThat(outcome.rounds()).isOne();
        assertThat(outcome.result().lesson().path("nodes")).hasSize(2);
        assertThat(model.choices).containsExactly("submit_lesson");
        assertThat(model.tools.get(0).get(0).path("function").path("parameters").path("properties")
                .path("nodes").path("items").path("properties").path("parentId").path("type").toString())
                .isEqualTo("[\"string\",\"null\"]");
    }

    @Test
    void findsAValidNamedCallAfterOtherCallsAndMalformedArguments() {
        Scripted model = new Scripted(new LlmClient.Result("", List.of(
                new LlmClient.ToolCall("other", "unrelated_tool", "{}"),
                new LlmClient.ToolCall("broken", "submit_lesson", "{\"title\":"),
                new LlmClient.ToolCall("valid", "submit_lesson", LESSON)), "tool_calls", 10, 10));

        assertThat(structure(model).result().lesson().path("title").asText()).isEqualTo("视频压缩演示");
        assertThat(model.messages).hasSize(1);
    }

    @Test
    void acceptsCompleteValidatedJsonFromAProviderWithoutToolCalls() {
        for (String content : List.of(LESSON, "```json\n" + LESSON + "```", "```\n" + LESSON + "```")) {
            Scripted model = new Scripted(text(content, "stop"));
            LessonStructurer.Outcome outcome = structure(model);
            assertThat(outcome.rounds()).isOne();
            assertThat(outcome.result().lesson().path("nodes").get(1).path("start").asInt()).isEqualTo(30);
        }
    }

    @Test
    void neverExtractsAnExampleCourseFromSurroundingProse() {
        String prose = "这是一个课程结构示例，请自己填写：\n" + LESSON;
        Scripted model = new Scripted(text(prose, "stop"), text(prose, "stop"));

        assertInvalid(model, "未调用 submit_lesson");
        assertThat(model.messages).hasSize(2);
    }

    @Test
    void rejectsTrailingJsonDocumentsRatherThanAcceptingTheFirstOne() {
        Scripted model = new Scripted(call("one", LESSON + " {}"), call("two", LESSON + " {}"));

        assertInvalid(model, "JSON 格式不完整或无法解析");
    }

    @Test
    void requiresTheSameFieldsForJsonFallbackAndToolArguments() {
        String incomplete = """
                {"title":"未完成的课程","nodes":[{"id":"n1","title":"参数","start":0,"end":60}]}
                """;
        Scripted model = new Scripted(text(incomplete, "stop"), call("repair", incomplete));

        assertInvalid(model, "snippets 必须是代码片段数组");
        assertThat(model.messages.get(1).toString()).contains("snippets 必须是代码片段数组");
    }

    @Test
    void rejectsQuotedTimestampsInsteadOfLettingTheNormalizerCoerceThem() {
        String quoted = LESSON.replace("\"start\":30", "\"start\":\"30\"");
        Scripted model = new Scripted(text(quoted, "stop"), call("repair", quoted));

        assertInvalid(model, "start 必须是有限数值");
    }

    @Test
    void repairsOutOfRangeTimesAndAcknowledgesEveryToolCallInHistory() {
        String invalid = LESSON.replace("\"end\":60", "\"end\":999");
        Scripted model = new Scripted(new LlmClient.Result("", List.of(
                new LlmClient.ToolCall("other", "unrelated_tool", "{}"),
                new LlmClient.ToolCall("lesson", "submit_lesson", invalid)), "tool_calls", 10, 10), call("fixed", LESSON));

        assertThat(structure(model).rounds()).isEqualTo(2);
        ArrayNode repair = model.messages.get(1);
        assertThat(repair.get(2).path("tool_calls")).hasSize(2);
        assertThat(repair.get(3).path("tool_call_id").asText()).isEqualTo("other");
        assertThat(repair.get(4).path("tool_call_id").asText()).isEqualTo("lesson");
        assertThat(repair.get(4).path("content").asText()).contains("结束时间需大于开始时间且不超过视频时长");
    }

    @Test
    void incompleteJsonGetsOneDirectedRepairAndNeverCreatesAnEmptyCourse() {
        Scripted model = new Scripted(call("broken", "{\"title\":\"课程\",\"nodes\":["), call("fixed", LESSON));

        assertThat(structure(model).rounds()).isEqualTo(2);
        assertThat(model.messages.get(1).toString()).contains("JSON 格式不完整或无法解析");
        assertThat(model.messages.get(1).get(3).path("tool_call_id").asText()).isEqualTo("broken");
    }

    @Test
    void reportsMalformedJsonAfterTheSingleRepairAlsoFails() {
        Scripted model = new Scripted(call("broken1", "{\"title\":"), call("broken2", "{\"title\":"));

        assertInvalid(model, "JSON 格式不完整或无法解析");
        assertThat(model.messages).hasSize(2);
    }

    @Test
    void reportsTruncationAndRequestsAShorterCompleteResponseOnlyOnce() {
        LlmClient.Result truncated = new LlmClient.Result("", List.of(
                new LlmClient.ToolCall("partial", "submit_lesson", "{\"title\":\"课程\",")), "length", 10, 4096);
        Scripted model = new Scripted(truncated, truncated);

        assertInvalid(model, "输出被截断", "输出长度上限");
        assertThat(model.messages).hasSize(2);
        assertThat(model.messages.get(1).toString()).contains("减少重复描述和非必要代码");
    }

    @Test
    void doesNotSaveAResponseKnownToBeTruncatedEvenIfItsJsonHappensToClose() {
        LlmClient.Result truncated = new LlmClient.Result(LESSON, List.of(), "length", 10, 4096);
        Scripted model = new Scripted(truncated, truncated);

        assertInvalid(model, "输出被截断");
    }

    @Test
    void preservesTimelineAndTreeValidationForFallbackJson() {
        String invalid = LESSON.replace("\"parentId\":null", "\"parentId\":\"missing\"")
                .replace("\"end\":60", "\"end\":999");
        Scripted model = new Scripted(text(invalid, "stop"), text(invalid, "stop"));

        assertInvalid(model, "未通过校验", "结束时间", "父节点");
    }

    @Test
    void aFilteredResponseDoesNotTriggerAnotherModelRequest() {
        Scripted model = new Scripted(text("", "content_filter"));

        assertInvalid(model, "内容被服务过滤");
        assertThat(model.messages).hasSize(1);
    }

    private static LessonStructurer.Outcome structure(Scripted model) {
        return new LessonStructurer(model, MAPPER).structure(TRANSCRIPT, List.of(), 60);
    }

    private static void assertInvalid(Scripted model, String... reasons) {
        assertThatThrownBy(() -> structure(model)).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.getCode()).isEqualTo(ErrorCode.LESSON_INVALID);
            assertThat(error.getMessage()).contains(reasons);
        });
    }

    private static LlmClient.Result call(String id, String arguments) {
        return new LlmClient.Result("", List.of(new LlmClient.ToolCall(id, "submit_lesson", arguments)), "tool_calls", 10, 10);
    }

    private static LlmClient.Result text(String content, String finishReason) {
        return new LlmClient.Result(content, List.of(), finishReason, 10, 10);
    }

    private static final class Scripted implements LlmClient {
        final Deque<Result> replies;
        final List<ArrayNode> messages = new ArrayList<>();
        final List<ArrayNode> tools = new ArrayList<>();
        final List<String> choices = new ArrayList<>();

        Scripted(Result... replies) { this.replies = new ArrayDeque<>(List.of(replies)); }

        @Override public Result chat(ArrayNode messages, ArrayNode tools, String toolChoice, Consumer<String> onText) {
            this.messages.add(messages.deepCopy());
            this.tools.add(tools.deepCopy());
            choices.add(toolChoice);
            if (replies.isEmpty()) throw new AssertionError("Unexpected extra model request");
            return replies.removeFirst();
        }

        @Override public boolean configured() { return true; }
        @Override public String model() { return "fixture-model"; }
    }
}
