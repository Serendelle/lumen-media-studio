package com.houqing.tools.video_compressor_backend.lesson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import javax.net.ssl.SSLException;

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
    static final int MAX_READ_ATTEMPTS = 3;

    private final AppProperties.Asr cfg;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public DashScopeAsrClient(AppProperties props, ObjectMapper mapper) {
        this.cfg = props.asr();
        this.mapper = mapper;
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15));
        InetSocketAddress proxy = parseProxy(cfg == null ? null : cfg.proxy());
        if (proxy != null) {
            builder.proxy(ProxySelector.of(proxy));
            log.info("DashScope ASR uses the configured outbound proxy");
        }
        this.http = builder.build();
    }

    @Override public boolean configured() { return cfg != null && cfg.configured(); }
    @Override public String model() { return cfg == null ? "" : cfg.model(); }

    private String base() {
        String b = cfg.baseUrl() == null || cfg.baseUrl().isBlank() ? "https://dashscope.aliyuncs.com" : cfg.baseUrl();
        return b.replaceAll("/+$", "");
    }

    @Override
    public List<Sentence> transcribe(Path audio, CancellationToken token, Consumer<String> stage) {
        if (!configured()) throw new ApiException(ErrorCode.ASR_UNAVAILABLE,
                "未配置语音识别服务，请在后端 .env 中设置 DASHSCOPE_API_KEY");
        try {
            check(token);
            stage.accept("上传音频");
            String ossUrl = upload(audio, token);
            check(token);
            stage.accept("提交识别");
            String taskId = submit(ossUrl, token);
            stage.accept("识别中");
            String resultUrl = poll(taskId, token);
            check(token);
            stage.accept("下载结果");
            return parseResult(getJson(resultUrl, false, token, "下载识别结果"));
        } catch (IOException e) {
            log.warn("DashScope ASR audio read failed: {}", e.getClass().getSimpleName());
            throw new ApiException(ErrorCode.ASR_FAILED, "无法读取待识别音频，请重新上传文件");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.CANCELLED);
        }
    }

    // ---------------- 1) temporary upload ----------------

    String upload(Path audio, CancellationToken token) throws IOException, InterruptedException {
        String q = "action=getPolicy&model=" + URLEncoder.encode(model(), StandardCharsets.UTF_8);
        JsonNode policy = getJson(base() + "/api/v1/uploads?" + q, true, token, "获取音频上传凭证").path("data");
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
        HttpResponse<String> res = exchange(req, token, "上传音频");
        if (res.statusCode() / 100 != 2) {
            log.warn("DashScope temporary upload HTTP {}", res.statusCode());
            throw new ApiException(ErrorCode.ASR_UNAVAILABLE, "音频上传到识别服务失败（" + res.statusCode() + "）");
        }
        return "oss://" + key;
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    // ---------------- 2) submit / 3) poll ----------------

    String submit(String fileUrl, CancellationToken token) throws InterruptedException {
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
        JsonNode r = send(req, token, "提交识别任务");
        String id = r.path("output").path("task_id").asText("");
        if (id.isEmpty()) throw new ApiException(ErrorCode.ASR_UNAVAILABLE, "语音识别服务未返回任务号");
        return id;
    }

    String poll(String taskId, CancellationToken token) throws InterruptedException {
        long deadline = System.nanoTime() + MAX_WAIT.toNanos();
        long delay = 1500;
        while (true) {
            check(token);
            JsonNode out = send(authed(base() + "/api/v1/tasks/" + URLEncoder.encode(taskId, StandardCharsets.UTF_8))
                    .GET().build(), token, "查询识别进度").path("output");
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
            pause(delay, token);
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

    private JsonNode getJson(String url, boolean auth, CancellationToken token, String operation) throws InterruptedException {
        HttpRequest.Builder b = auth ? authed(url) : HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60));
        if (auth) b.header("Content-Type", "application/json");
        return send(b.GET().build(), token, operation);
    }

    private JsonNode send(HttpRequest req, CancellationToken token, String operation) throws InterruptedException {
        HttpResponse<String> res = exchange(req, token, operation);
        if (res.statusCode() == 401 || res.statusCode() == 403) {
            if (req.headers().firstValue("Authorization").isPresent()) {
                throw new ApiException(ErrorCode.ASR_UNAVAILABLE, "阿里云百炼 API Key 无效、地域不匹配或没有语音识别权限，请检查 DASHSCOPE_API_KEY 与 DASHSCOPE_BASE_URL");
            }
            throw new ApiException(ErrorCode.ASR_FAILED, operation + "失败，识别结果链接已过期或无权访问");
        }
        if (res.statusCode() == 429) throw new ApiException(ErrorCode.RATE_LIMITED, "语音识别请求过于频繁，请稍后再试");
        if (res.statusCode() / 100 != 2) {
            log.warn("DashScope {} HTTP {}", operation, res.statusCode());
            throw new ApiException(ErrorCode.ASR_FAILED, operation + "失败，语音识别服务返回错误（" + res.statusCode() + "）");
        }
        try {
            JsonNode result = mapper.readTree(res.body());
            if (result == null || !result.isObject()) throw new ApiException(ErrorCode.ASR_FAILED,
                    operation + "失败，语音识别服务返回了无效数据");
            return result;
        } catch (JsonProcessingException e) {
            log.warn("DashScope {} returned invalid JSON", operation);
            throw new ApiException(ErrorCode.ASR_FAILED, operation + "失败，语音识别服务返回了无效数据");
        }
    }

    /** Only read requests may be replayed; submitting an async task can incur charges even if its response is lost. */
    private HttpResponse<String> exchange(HttpRequest req, CancellationToken token, String operation) throws InterruptedException {
        int attempts = req.method().equals("GET") ? MAX_READ_ATTEMPTS : 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            check(token);
            try {
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                check(token);
                if (attempt < attempts && (res.statusCode() == 429 || res.statusCode() >= 500)) {
                    log.warn("DashScope {} HTTP {}, retrying read ({}/{})", operation, res.statusCode(), attempt, attempts);
                    pause(300L * attempt, token);
                    continue;
                }
                return res;
            } catch (IOException e) {
                log.warn("DashScope {} failed ({}/{}): {}", operation, attempt, attempts, e.getClass().getSimpleName());
                if (attempt < attempts && !causedBy(e, SSLException.class)) {
                    pause(300L * attempt, token);
                    continue;
                }
                throw networkFailure(e, operation);
            }
        }
        throw new IllegalStateException("ASR request attempts exhausted");
    }

    private static ApiException networkFailure(IOException e, String operation) {
        String reason;
        if (causedBy(e, HttpTimeoutException.class)) reason = "请求超时，请检查网络或 DASHSCOPE_PROXY 代理设置";
        else if (causedBy(e, UnknownHostException.class)) reason = "无法解析服务域名，请检查 DNS 或 DASHSCOPE_PROXY 代理设置";
        else if (causedBy(e, SSLException.class)) reason = "安全连接失败，请检查代理和系统证书";
        else if (causedBy(e, ConnectException.class)) reason = "无法连接服务，请检查网络或 DASHSCOPE_PROXY 代理设置";
        else reason = "连接中断，请检查网络或 DASHSCOPE_PROXY 代理设置";
        if (operation.equals("提交识别任务")) reason += "；提交结果未确认，为避免重复计费未自动重试，请先在百炼控制台确认任务状态";
        return new ApiException(ErrorCode.ASR_UNAVAILABLE, operation + "时" + reason);
    }

    private static boolean causedBy(Throwable error, Class<? extends Throwable> type) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) return true;
        }
        return false;
    }

    private static void pause(long millis, CancellationToken token) throws InterruptedException {
        while (millis > 0) {
            check(token);
            long slice = Math.min(millis, 100);
            Thread.sleep(slice);
            millis -= slice;
        }
        check(token);
    }

    private static InetSocketAddress parseProxy(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String input = value.trim();
            URI uri = URI.create(input.contains("://") ? input : "http://" + input);
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getPort() <= 0
                    || uri.getPort() > 65535 || uri.getUserInfo() != null) {
                log.warn("Ignoring invalid DASHSCOPE_PROXY; expected http://host:port without credentials");
                return null;
            }
            return InetSocketAddress.createUnresolved(uri.getHost(), uri.getPort());
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring invalid DASHSCOPE_PROXY; expected http://host:port without credentials");
            return null;
        }
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
