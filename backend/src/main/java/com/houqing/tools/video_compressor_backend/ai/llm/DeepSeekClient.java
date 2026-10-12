package com.houqing.tools.video_compressor_backend.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import com.houqing.tools.video_compressor_backend.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/**
 * DeepSeek chat-completions client (OpenAI-compatible, streaming). The API key is only read here and only
 * placed in the Authorization header: it is never logged, never put in an exception message and never returned.
 * Transient failures (connect errors, 429, 5xx) are retried with backoff — but only before any output streamed,
 * so a retry can never duplicate text the user already saw.
 */
@Component
public class DeepSeekClient implements LlmClient {
    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);
    static final int MAX_ATTEMPTS = 3;

    private final AppProperties.Ai cfg;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public DeepSeekClient(AppProperties props, ObjectMapper mapper) {
        this.cfg = props.ai();
        this.mapper = mapper;
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15));
        // optional outbound proxy (DEEPSEEK_PROXY=http://host:port); java.net.http uses no proxy unless told to
        java.net.InetSocketAddress proxy = parseProxy(props.ai().proxy());
        if (proxy != null) {
            b.proxy(java.net.ProxySelector.of(proxy));
            log.info("DeepSeek requests use proxy {}:{}", proxy.getHostString(), proxy.getPort());
        }
        this.http = b.build();
    }

    @Override public boolean configured() { return cfg.configured(); }
    @Override public String model() { return cfg.model(); }

    @Override
    public Result chat(ArrayNode messages, ArrayNode tools, String toolChoice, Consumer<String> onText) {
        if (!configured()) throw new ApiException(ErrorCode.AI_UNAVAILABLE, "未配置 DeepSeek API Key，请在后端 .env 中设置 DEEPSEEK_API_KEY");
        ObjectNode body = mapper.createObjectNode();
        body.put("model", cfg.model());
        body.set("messages", messages);
        body.put("stream", true);
        body.put("temperature", 0.2);
        body.putObject("stream_options").put("include_usage", true);
        if (tools != null && !tools.isEmpty()) {
            body.set("tools", tools);
            if (toolChoice != null) body.put("tool_choice", toolChoice);
        }
        String payload = body.toString();
        String base = cfg.baseUrl() == null || cfg.baseUrl().isBlank() ? "https://api.deepseek.com" : cfg.baseUrl().replaceAll("/+$", "");

        ApiException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Streamed s = new Streamed();
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
                        .timeout(Duration.ofSeconds(120))
                        .header("Authorization", "Bearer " + cfg.apiKey())
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                int status = res.statusCode();
                if (status != 200) {
                    String detail = readSmall(res.body());
                    last = mapStatus(status, detail);
                    if (retryable(status) && attempt < MAX_ATTEMPTS) {
                        backoff(attempt);
                        continue;
                    }
                    throw last;
                }
                parseStream(res.body(), s, onText);
                return s.toResult();
            } catch (IOException e) {
                log.warn("DeepSeek request failed (attempt {}): {}", attempt, e.getClass().getSimpleName());
                last = new ApiException(ErrorCode.AI_UNAVAILABLE, "无法连接 AI 服务，请检查网络");
                if (s.emittedText || attempt == MAX_ATTEMPTS) throw last;
                backoff(attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(ErrorCode.CANCELLED);
            }
        }
        throw last == null ? new ApiException(ErrorCode.AI_UNAVAILABLE) : last;
    }

    /** Accumulates a streamed response, including tool calls whose arguments arrive in fragments. */
    private static final class Streamed {
        final StringBuilder content = new StringBuilder();
        final TreeMap<Integer, String[]> calls = new TreeMap<>(); // index -> [id, name, args]
        String finish;
        int promptTokens, completionTokens;
        boolean emittedText;

        Result toResult() {
            List<ToolCall> list = new ArrayList<>();
            for (String[] c : calls.values()) list.add(new ToolCall(c[0], c[1], c[2]));
            return new Result(content.toString(), list, finish, promptTokens, completionTokens);
        }
    }

    /** Parses a complete SSE body (used by tests with recorded provider output). */
    Result parse(InputStream in, Consumer<String> onText) throws IOException {
        Streamed s = new Streamed();
        parseStream(in, s, onText);
        return s.toResult();
    }

    void parseStream(InputStream in, Streamed s, Consumer<String> onText) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty()) continue;
                if (data.equals("[DONE]")) break;
                JsonNode chunk = mapper.readTree(data);
                JsonNode usage = chunk.path("usage");
                if (usage.isObject()) {
                    s.promptTokens = usage.path("prompt_tokens").asInt(s.promptTokens);
                    s.completionTokens = usage.path("completion_tokens").asInt(s.completionTokens);
                }
                for (JsonNode choice : chunk.path("choices")) {
                    JsonNode delta = choice.path("delta");
                    String text = delta.path("content").isTextual() ? delta.path("content").asText() : null;
                    if (text != null && !text.isEmpty()) {
                        s.content.append(text);
                        s.emittedText = true;
                        if (onText != null) onText.accept(text);
                    }
                    for (JsonNode tc : delta.path("tool_calls")) {
                        int idx = tc.path("index").asInt(s.calls.size());
                        String[] c = s.calls.computeIfAbsent(idx, k -> new String[]{null, null, ""});
                        if (tc.hasNonNull("id")) c[0] = tc.path("id").asText();
                        JsonNode fn = tc.path("function");
                        if (fn.hasNonNull("name")) c[1] = fn.path("name").asText();
                        if (fn.hasNonNull("arguments")) c[2] = c[2] + fn.path("arguments").asText();
                    }
                    if (choice.hasNonNull("finish_reason")) s.finish = choice.path("finish_reason").asText();
                }
            }
        }
        for (Map.Entry<Integer, String[]> e : s.calls.entrySet()) {
            if (e.getValue()[0] == null) e.getValue()[0] = "call_" + e.getKey();
        }
    }

    public static java.net.InetSocketAddress parseProxy(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            URI u = URI.create(value.contains("://") ? value.trim() : "http://" + value.trim());
            if (u.getHost() == null || u.getPort() <= 0) return null;
            return java.net.InetSocketAddress.createUnresolved(u.getHost(), u.getPort());
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring invalid DEEPSEEK_PROXY value");
            return null;
        }
    }

    private static boolean retryable(int status) { return status == 429 || status >= 500; }

    /** Maps HTTP errors to safe user-facing messages; the provider's raw body only goes to the log (truncated). */
    private static ApiException mapStatus(int status, String detail) {
        log.warn("DeepSeek HTTP {}: {}", status, detail);
        return switch (status) {
            case 401 -> new ApiException(ErrorCode.AI_UNAVAILABLE, "DeepSeek API Key 无效，请检查 .env 配置");
            case 402 -> new ApiException(ErrorCode.AI_UNAVAILABLE, "DeepSeek 账户余额不足");
            case 429 -> new ApiException(ErrorCode.RATE_LIMITED, "AI 服务请求过于频繁，请稍后再试");
            case 400, 422 -> new ApiException(ErrorCode.AI_UNAVAILABLE, "AI 请求格式被拒绝");
            default -> new ApiException(ErrorCode.AI_UNAVAILABLE, "AI 服务暂时不可用（" + status + "）");
        };
    }

    private static String readSmall(InputStream in) {
        try (in) {
            byte[] b = in.readNBytes(600);
            return new String(b, StandardCharsets.UTF_8).replaceAll("sk-[A-Za-z0-9]{8,}", "sk-***");
        } catch (IOException e) {
            return "";
        }
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(600L * attempt * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
