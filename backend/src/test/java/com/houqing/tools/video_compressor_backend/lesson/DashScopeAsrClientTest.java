package com.houqing.tools.video_compressor_backend.lesson;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import com.houqing.tools.video_compressor_backend.config.AppProperties;
import com.houqing.tools.video_compressor_backend.support.TestMedia;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The DashScope protocol as documented (temporary upload → async task → poll → result), played by a local fake
 * server so every request the client makes can be checked without a real key.
 */
class DashScopeAsrClientTest {
    private HttpServer server;
    private String base;
    private final Map<String, String> seen = new ConcurrentHashMap<>();
    private final AtomicInteger polls = new AtomicInteger();
    private volatile String finalStatus = "SUCCEEDED";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/api/v1/uploads", ex -> {
            seen.put("policy.query", ex.getRequestURI().getQuery());
            seen.put("policy.auth", header(ex, "Authorization"));
            reply(ex, 200, "{\"request_id\":\"r1\",\"data\":{\"policy\":\"POL\",\"signature\":\"SIG\",\"upload_dir\":\"dashscope-instant/abc/2026-10-19\","
                    + "\"upload_host\":\"" + base + "/oss\",\"oss_access_key_id\":\"AK\",\"x_oss_object_acl\":\"private\",\"x_oss_forbid_overwrite\":\"true\"}}");
        });
        server.createContext("/oss", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
            seen.put("oss.body", body);
            reply(ex, 200, "");
        });
        server.createContext("/api/v1/services/audio/asr/transcription", ex -> {
            seen.put("submit.async", header(ex, "X-DashScope-Async"));
            seen.put("submit.oss", header(ex, "X-DashScope-OssResourceResolve"));
            seen.put("submit.body", new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(ex, 200, "{\"output\":{\"task_status\":\"PENDING\",\"task_id\":\"task-1\"},\"request_id\":\"r2\"}");
        });
        server.createContext("/api/v1/tasks/task-1", ex -> {
            seen.put("poll.method", ex.getRequestMethod());
            if (polls.incrementAndGet() < 2) {
                reply(ex, 200, "{\"output\":{\"task_id\":\"task-1\",\"task_status\":\"RUNNING\"}}");
            } else if (finalStatus.equals("SUCCEEDED")) {
                reply(ex, 200, "{\"output\":{\"task_id\":\"task-1\",\"task_status\":\"SUCCEEDED\",\"results\":[{\"file_url\":\"oss://x\","
                        + "\"transcription_url\":\"" + base + "/result.json\",\"subtask_status\":\"SUCCEEDED\"}]}}");
            } else {
                reply(ex, 200, "{\"output\":{\"task_id\":\"task-1\",\"task_status\":\"SUCCEEDED\",\"results\":[{\"file_url\":\"oss://x\","
                        + "\"subtask_status\":\"FAILED\",\"code\":\"InvalidFile.DownloadFailed\",\"message\":\"download failed\"}]}}");
            }
        });
        server.createContext("/result.json", ex -> {
            seen.put("result.auth", String.valueOf(header(ex, "Authorization")));
            reply(ex, 200, "{\"file_url\":\"oss://x\",\"properties\":{\"original_duration_in_milliseconds\":9000},\"transcripts\":[{\"channel_id\":0,"
                    + "\"text\":\"…\",\"sentences\":[{\"begin_time\":4100,\"end_time\":9000,\"text\":\"然后写代码。\",\"sentence_id\":2},"
                    + "{\"begin_time\":120,\"end_time\":4000,\"text\":\"今天讲斐波那契。\",\"sentence_id\":1},{\"begin_time\":9000,\"end_time\":9100,\"text\":\" \"}]}]}");
        });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    private DashScopeAsrClient client(String key) {
        AppProperties p = TestMedia.props(Path.of("target"));
        AppProperties props = new AppProperties(p.dataDir(), p.retentionHours(), p.tools(), p.media(), p.security(), p.cors(), p.ai(),
                new AppProperties.Asr(key, base, "paraformer-v2"), p.lesson());
        return new DashScopeAsrClient(props, new ObjectMapper());
    }

    @Test
    void followsTheDocumentedProtocolAndReturnsSortedSentences() throws Exception {
        Path audio = Files.createTempFile("speech", ".mp3");
        Files.write(audio, new byte[]{1, 2, 3, 4});
        List<String> stages = new java.util.ArrayList<>();
        List<AsrClient.Sentence> s = client("sk-test-key").transcribe(audio, null, stages::add);

        assertThat(seen.get("policy.query")).isEqualTo("action=getPolicy&model=paraformer-v2");
        assertThat(seen.get("policy.auth")).isEqualTo("Bearer sk-test-key");
        String oss = seen.get("oss.body");
        assertThat(oss).contains("name=\"OSSAccessKeyId\"\r\n\r\nAK", "name=\"policy\"\r\n\r\nPOL", "name=\"Signature\"\r\n\r\nSIG",
                "name=\"x-oss-object-acl\"\r\n\r\nprivate", "name=\"x-oss-forbid-overwrite\"\r\n\r\ntrue", "name=\"key\"\r\n\r\ndashscope-instant/abc/2026-10-19/");
        assertThat(oss.indexOf("name=\"file\"")).as("file must be the last field").isGreaterThan(oss.indexOf("name=\"success_action_status\""));
        assertThat(seen.get("submit.async")).isEqualTo("enable");
        assertThat(seen.get("submit.oss")).isEqualTo("enable");
        assertThat(seen.get("submit.body")).contains("\"model\":\"paraformer-v2\"", "\"file_urls\":[\"oss://dashscope-instant/abc/2026-10-19/",
                "\"timestamp_alignment_enabled\":true");
        assertThat(seen.get("poll.method")).isEqualTo("POST");
        assertThat(polls.get()).isEqualTo(2);
        assertThat(seen.get("result.auth")).isEqualTo("null"); // the key is not sent to the result URL
        assertThat(stages).containsExactly("上传音频", "提交识别", "识别中", "下载结果");

        assertThat(s).extracting(AsrClient.Sentence::text).containsExactly("今天讲斐波那契。", "然后写代码。");
        assertThat(s.get(0).beginMs()).isEqualTo(120);
        assertThat(s.get(1).endMs()).isEqualTo(9000);
    }

    @Test
    void aFailedSubtaskIsReportedNotSwallowed() throws Exception {
        finalStatus = "FAILED";
        Path audio = Files.createTempFile("speech", ".mp3");
        assertThatThrownBy(() -> client("sk-test-key").transcribe(audio, null, x -> {}))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.ASR_FAILED);
                    assertThat(e.getMessage()).contains("download failed");
                });
    }

    @Test
    void withoutAKeyNothingIsSent() {
        assertThatThrownBy(() -> client("").transcribe(Path.of("x.mp3"), null, x -> {}))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.ASR_UNAVAILABLE));
        assertThat(seen).isEmpty();
    }

    private static String header(HttpExchange ex, String name) { return ex.getRequestHeaders().getFirst(name); }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) ex.getResponseBody().write(b);
        ex.close();
    }
}
