package com.houqing.tools.video_compressor_backend.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.config.AppProperties;
import com.houqing.tools.video_compressor_backend.support.TestMedia;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises the actual HTTP request and SSE transport without contacting a model provider. */
class DeepSeekClientTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<JsonNode> request = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;
    private volatile boolean truncated;
    private volatile boolean noToolOutput;
    private volatile boolean emptyBody;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", ex -> {
            requests.incrementAndGet();
            request.set(mapper.readTree(ex.getRequestBody()));
            String stream = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_lesson\","
                    + "\"function\":{\"name\":\"submit_lesson\",\"arguments\":\"{\"}}]}}]}\n\n";
            if (noToolOutput) stream = emptyBody ? "" : "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n";
            if (!truncated) {
                stream += "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"}\"}}]},"
                        + "\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n";
            }
            byte[] bytes = stream.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, bytes.length);
            try (var out = ex.getResponseBody()) { out.write(bytes); }
        });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    private DeepSeekClient client() {
        AppProperties p = TestMedia.props(Path.of("target", "unused"));
        var ai = new AppProperties.Ai("local-test-key", "http://127.0.0.1:" + server.getAddress().getPort(), "test-model", "");
        return new DeepSeekClient(new AppProperties(p.dataDir(), p.retentionHours(), p.tools(), p.media(),
                p.security(), p.cors(), ai, p.asr(), p.lesson()), mapper);
    }

    private LlmClient.Result chat(String choice) {
        var messages = mapper.createArrayNode();
        messages.addObject().put("role", "user").put("content", "提交课程结构");
        var tools = mapper.createArrayNode();
        var function = tools.addObject().put("type", "function").putObject("function");
        function.put("name", "submit_lesson").putObject("parameters").put("type", "object");
        return client().chat(messages, tools, choice, null);
    }

    @Test
    void namedChoiceUsesProviderObjectContractWithThinkingDisabled() {
        var result = chat("submit_lesson");
        JsonNode body = request.get();
        assertThat(body.at("/tool_choice/type").asText()).isEqualTo("function");
        assertThat(body.at("/tool_choice/function/name").asText()).isEqualTo("submit_lesson");
        assertThat(body.at("/thinking/type").asText()).isEqualTo("disabled");
        assertThat(result.toolCalls()).containsExactly(new LlmClient.ToolCall("call_lesson", "submit_lesson", "{}"));
        assertThat(requests).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"auto", "required", "none"})
    void existingChoiceModesKeepTheirStringContract(String choice) {
        chat(choice);
        assertThat(request.get().path("tool_choice").asText()).isEqualTo(choice);
    }

    @Test
    void interruptedToolStreamFailsWithoutResubmittingTheRequest() {
        truncated = true;
        assertThatThrownBy(() -> chat("submit_lesson")).isInstanceOf(ApiException.class)
                .hasMessageContaining("AI 服务");
        assertThat(requests).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void acceptedStreamWithoutContentIsNotResubmitted(boolean empty) {
        truncated = true;
        noToolOutput = true;
        emptyBody = empty;
        assertThatThrownBy(() -> chat("submit_lesson")).isInstanceOf(ApiException.class);
        assertThat(requests).hasValue(1);
    }
}
