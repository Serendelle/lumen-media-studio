package com.houqing.tools.video_compressor_backend.lesson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import com.houqing.tools.video_compressor_backend.config.AppProperties;
import com.houqing.tools.video_compressor_backend.media.CancellationToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Paraformer recorded-speech recognition on Alibaba Cloud Model Studio (DashScope), over plain HTTP:
 * 1) getPolicy → upload the audio to DashScope's temporary OSS storage (oss:// URL, 48 h),
 * 2) submit an async transcription task, 3) poll it, 4) download the result with millisecond sentence timestamps.
 * The API key only ever goes into the Authorization header; it is never logged or returned.
 */
@Component
public class DashScopeAsrClient implements AsrClient {
    private static final Logger log = LoggerFactory.getLogger(DashScopeAsrClient.class);
    static final Duration MAX_WAIT = Duration.ofMinutes(30);

    private final AppProperties.Asr cfg;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public DashScopeAsrClient(AppProperties props, ObjectMapper mapper) {
        this.cfg = props.asr();
        this.mapper = mapper;
    }

    @Override public boolean configured() { return cfg != null && cfg.configured(); }
    @Override public String model() { return cfg == null ? "" : cfg.model(); }

    private String base() {
        String b = cfg.baseUrl() == null || cfg.baseUrl().isBlank() ? "https://dashscope.aliyuncs.com" : cfg.baseUrl();
        return b.replaceAll("/+$", "");
    }

    @Override
    public List<Sentence> transcribe(Path audio, CancellationToken token, Consumer<String> stage) {
        if (!configured()) throw new ApiException(ErrorCode.ASR_UNAVAILABLE);
        try {
            stage.accept("上传音频");
            String ossUrl = upload(audio);
            check(token);
            stage.accept("提交识别");
            String taskId = submit(ossUrl);
            stage.accept("识别中");
            String resultUrl = poll(taskId, token);
            stage.accept("下载结果");
            return parseResult(getJson(resultUrl, false));
        } catch (IOException e) {
            log.warn("DashScope ASR request failed: {}", e.getClass().getSimpleName());
            throw new ApiException(ErrorCode.ASR_UNAVAILABLE, "无法连接语音识别服务，请检查网络");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.CANCELLED);
        }
    }

    // ---------------- 1) temporary upload ----------------

    String upload(Path audio) throws IOException, InterruptedException {
        String q = "action=getPolicy&model=" + URLEncoder.encode(model(), StandardCharsets.UTF_8);
        JsonNode policy = getJson(base() + "/api/v1/uploads?" + q, true).path("data");
        if (!policy.hasNonNull("upload_host")) throw new ApiException(ErrorCode.ASR_UNAVAILABLE, "语音识别服务未返回上传凭证");
        // ASCII name: the key becomes part of an oss:// URL
        String key = policy.path("upload_dir").asText() + "/" + UUID.randomUUID() + "." + extension(audio);
        String boundary = "----lumen" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        field(body, boundary, "OSSAccessKeyId", policy.path("oss_access_key_id").asText());
        field(body, boundary, "policy", policy.path("policy").asText());
        field(body, boundary, "Signature", policy.path("signature").asText());
        field(body, boundary, "key", key);
        field(body, boundary, "x-oss-object-acl", policy.path("x_oss_object_acl").asText());
        field(body, boundary, "x-oss-forbid-overwrite", policy.path("x_oss_forbid_overwrite").asText());
        field(body, boundary, "success_action_status", "200");
        // the file must be the last field
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio." + extension(audio)
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(Files.readAllBytes(audio));
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(URI.create(policy.path("upload_host").asText()))
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            log.warn("DashScope temporary upload HTTP {}: {}", res.statusCode(), clip(res.body()));
            throw new ApiException(ErrorCode.ASR_UNAVAILABLE, "音频上传到识别服务失败（" + res.statusCode() + "）");
        }
        return "oss://" + key;
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    // ---------------- 2) submit / 3) poll ----------------

    String submit(String fileUrl) throws IOException, InterruptedException {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model());
        body.putObject("input").putArray("file_urls").add(fileUrl);
        ObjectNode p = body.putObject("parameters");
        p.putArray("language_hints").add("zh").add("en");
        p.put("timestamp_alignment_enabled", true);
        HttpRequest req = authed(base() + "/api/v1/services/audio/asr/transcription")
                .header("Content-Type", "application/json")
                .header("X-DashScope-Async", "enable")
                .header("X-DashScope-OssResourceResolve", "enable")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        JsonNode r = send(req);
        String id = r.path("output").path("task_id").asText("");
        if (id.isEmpty()) throw new ApiException(ErrorCode.ASR_UNAVAILABLE, "语音识别服务未返回任务号");
        return id;
    }

    String poll(String taskId, CancellationToken token) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + MAX_WAIT.toNanos();
        long delay = 1500;
        while (true) {
            check(token);
            JsonNode out = send(authed(base() + "/api/v1/tasks/" + URLEncoder.encode(taskId, StandardCharsets.UTF_8))
                    .POST(HttpRequest.BodyPublishers.noBody()).build()).path("output");
            String status = out.path("task_status").asText("");
            if (status.equals("SUCCEEDED")) {
                // a task "succeeds" if any subtask did: look at ours
                JsonNode first = out.path("results").path(0);
                if (!"SUCCEEDED".equals(first.path("subtask_status").asText("SUCCEEDED"))) {
                    throw new ApiException(ErrorCode.ASR_FAILED, "语音识别失败：" + clip(first.path("message").asText(first.path("code").asText("未知原因"))));
                }
                String url = first.path("transcription_url").asText("");
                if (url.isEmpty()) throw new ApiException(ErrorCode.ASR_FAILED, "语音识别没有返回结果");
                return url;
            }
            if (!status.equals("PENDING") && !status.equals("RUNNING")) {
                throw new ApiException(ErrorCode.ASR_FAILED, "语音识别失败：" + clip(out.path("message").asText(status)));
            }
            if (System.nanoTime() > deadline) throw new ApiException(ErrorCode.ASR_FAILED, "语音识别排队超过 30 分钟，已放弃");
            Thread.sleep(delay);
            delay = Math.min(8000, delay + 1000);
        }
    }

    // ---------------- 4) result ----------------

    List<Sentence> parseResult(JsonNode result) {
        List<Sentence> out = new ArrayList<>();
        for (JsonNode t : result.path("transcripts")) {
            for (JsonNode s : t.path("sentences")) {
                String text = s.path("text").asText("").trim();
                if (!text.isEmpty()) out.add(new Sentence(s.path("begin_time").asLong(), s.path("end_time").asLong(), text));
            }
            break; // channel 0 only
        }
        out.sort(java.util.Comparator.comparingLong(Sentence::beginMs));
        return out;
    }

    // ---------------- http helpers ----------------

    private HttpRequest.Builder authed(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).header("Authorization", "Bearer " + cfg.apiKey());
    }

    private JsonNode getJson(String url, boolean auth) throws IOException, InterruptedException {
        HttpRequest.Builder b = auth ? authed(url) : HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60));
        if (auth) b.header("Content-Type", "application/json");
        return send(b.GET().build());
    }

    private JsonNode send(HttpRequest req) throws IOException, InterruptedException {
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() == 401 || res.statusCode() == 403) {
            throw new ApiException(ErrorCode.ASR_UNAVAILABLE, "阿里云百炼 API Key 无效或没有语音识别权限，请检查 DASHSCOPE_API_KEY");
        }
        if (res.statusCode() == 429) throw new ApiException(ErrorCode.RATE_LIMITED, "语音识别请求过于频繁，请稍后再试");
        if (res.statusCode() / 100 != 2) {
            log.warn("DashScope HTTP {}: {}", res.statusCode(), clip(res.body()));
            throw new ApiException(ErrorCode.ASR_FAILED, "语音识别服务返回错误（" + res.statusCode() + "）");
        }
        return mapper.readTree(res.body());
    }

    private static void check(CancellationToken token) {
        if (token != null && token.isCancelled()) throw new ApiException(ErrorCode.CANCELLED);
    }

    private static String extension(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "mp3" : n.substring(dot + 1);
    }

    private static String clip(String s) {
        if (s == null) return "";
        String t = s.replaceAll("sk-[A-Za-z0-9]{8,}", "sk-***");
        return t.length() > 300 ? t.substring(0, 300) : t;
    }
}
