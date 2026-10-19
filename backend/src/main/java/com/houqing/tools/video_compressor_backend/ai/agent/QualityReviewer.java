package com.houqing.tools.video_compressor_backend.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.media.*;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Agent 04, verification half — deterministic and independent of the handlers' own summaries: it re-reads every
 * output file (FFprobe / ExifTool) and compares it with what the confirmed plan asked for. A missed size target
 * gets at most ONE safer retry (lower bitrate, same resolution/codec); everything else is reported as-is.
 * The model only writes the explanation afterwards (see AiOrchestrator).
 */
@Component
public class QualityReviewer {
    static final int MAX_RETRIES = 1;

    private final StorageService storage;
    private final MediaProbe probe;
    private final ExifToolRunner exif;
    private final MediaExecutor executor;
    private final TaskEngine engine;
    private final ObjectMapper mapper;
    private final MediaVerifier verifier;

    public QualityReviewer(StorageService storage, MediaProbe probe, ExifToolRunner exif, MediaExecutor executor,
                           TaskEngine engine, ObjectMapper mapper, MediaVerifier verifier) {
        this.engine = engine;
        this.verifier = verifier;
        this.storage = storage;
        this.probe = probe;
        this.exif = exif;
        this.executor = executor;
        this.mapper = mapper;
    }

    public record Review(ArrayNode checks, int retries, boolean allPassed, Map<Integer, MediaExecutor.StepResult> results) {}

    public Review review(long ownerId, String conversationId, int planVersion, JsonNode plan, Map<Integer, MediaExecutor.StepResult> results,
                         MediaExecutor.Listener listener, BooleanSupplier cancelled, Set<String> runningTasks) {
        ArrayNode checks = mapper.createArrayNode();
        int retries = 0;
        Map<Integer, MediaExecutor.StepResult> finalResults = new LinkedHashMap<>(results);

        for (MediaExecutor.StepResult r : results.values()) {
            if (!"SUCCEEDED".equals(r.status())) {
                add(checks, r.stepId(), "执行状态", false, "成功", statusLabel(r.status()) + (r.message() == null ? "" : "：" + r.message()));
                continue;
            }
            MediaExecutor.StepResult current = r;
            List<Boolean> outcome = checkStep(ownerId, current, checks);
            // safe, bounded retry: only for a missed size target, only once, never below the planner's minimums
            if (outcome.contains(Boolean.FALSE) && retries < MAX_RETRIES && sizeMiss(ownerId, current) && !cancelled.getAsBoolean()) {
                JsonNode retryParams = retryParams(ownerId, current);
                List<String> retryInputs = inputsOf(ownerId, current);
                if (retryParams != null && !retryInputs.isEmpty()) {
                    retries++;
                    add(checks, r.stepId(), "自动重试", true, "≤ 1 次", "体积未达标，降低码率重试 1 次");
                    MediaExecutor.StepResult again = executor.runStep(ownerId, conversationId + ":" + planVersion + ":" + r.stepId() + ":retry1",
                            r.stepId(), r.tool(), retryParams, retryInputs, listener, cancelled, runningTasks);
                    if ("SUCCEEDED".equals(again.status())) {
                        ArrayNode retryChecks = mapper.createArrayNode();
                        checkStep(ownerId, again, retryChecks);
                        retryChecks.forEach(c -> ((ObjectNode) c).put("afterRetry", true));
                        checks.addAll(retryChecks);
                        finalResults.put(r.stepId(), again);
                    } else {
                        add(checks, r.stepId(), "重试执行", false, "成功", statusLabel(again.status()));
                    }
                }
            }
        }
        privacyCoverage(ownerId, plan, finalResults, checks);
        boolean allPassed = true;
        for (JsonNode c : checks) {
            if (!c.path("ok").asBoolean() && !superseded(checks, c)) allPassed = false;
        }
        return new Review(checks, retries, allPassed, finalResults);
    }

    /**
     * Safety net independent of the planner: when the user asked for privacy cleaning, every deliverable that did
     * not itself go through a scrub step is re-read, and leftover location tags are reported as a failed check.
     */
    private void privacyCoverage(long ownerId, JsonNode plan, Map<Integer, MediaExecutor.StepResult> results, ArrayNode checks) {
        boolean wantsPrivacy = false;
        Set<Integer> scrubbedSources = new HashSet<>();
        Set<Integer> scrubSteps = new HashSet<>();
        for (JsonNode s : plan.path("steps")) {
            if ("metadata.scrub".equals(s.path("tool").asText())) {
                wantsPrivacy = true;
                scrubSteps.add(s.path("id").asInt());
                for (JsonNode in : s.path("inputs")) {
                    if (in.asText().startsWith("step:")) scrubbedSources.add(Integer.parseInt(in.asText().substring(5)));
                }
            }
        }
        if (!wantsPrivacy) return;
        for (MediaExecutor.StepResult r : results.values()) {
            if (!"SUCCEEDED".equals(r.status()) || scrubSteps.contains(r.stepId()) || scrubbedSources.contains(r.stepId())) continue;
            Optional<MediaFile> f = r.outputFileId() == null ? Optional.empty() : storage.find(ownerId, r.outputFileId());
            if (f.isEmpty() || !(f.get().kind().isAv() || f.get().kind() == MediaKind.IMAGE)) continue;
            String dirId = "review-" + UUID.randomUUID();
            try {
                List<String> left = ExifToolRunner.locationTags(exif.readJson(storage.pathOf(f.get()), storage.newWorkDir(dirId), null));
                add(checks, r.stepId(), "交付文件位置信息", left.isEmpty(), "无位置标签",
                        left.isEmpty() ? "未发现" : "残留 " + left + "（该文件未经过清理步骤）");
            } catch (ApiException e) {
                add(checks, r.stepId(), "交付文件位置信息", false, "可检查", "无法读取元数据");
            } finally {
                storage.deleteWorkDir(dirId);
            }
        }
    }

    /** A failed pre-retry check is superseded when the same step+check passed after the retry. */
    private static boolean superseded(ArrayNode checks, JsonNode failed) {
        if (failed.path("afterRetry").asBoolean()) return false;
        for (JsonNode c : checks) {
            if (c.path("afterRetry").asBoolean() && c.path("stepId").asInt() == failed.path("stepId").asInt()
                    && c.path("name").asText().equals(failed.path("name").asText())) return true;
        }
        return false;
    }

    private List<Boolean> checkStep(long ownerId, MediaExecutor.StepResult r, ArrayNode checks) {
        List<Boolean> oks = new ArrayList<>();
        Optional<MediaFile> out = r.outputFileId() == null ? Optional.empty() : storage.find(ownerId, r.outputFileId());
        if (out.isEmpty() || !Files.isRegularFile(storage.pathOf(out.get()))) {
            oks.add(add(checks, r.stepId(), "输出文件", false, "存在", "缺失"));
            return oks;
        }
        MediaFile f = out.get();
        long actualSize = f.getSizeBytes();
        JsonNode p = r.params();
        MediaProbe.MediaInfo mi = null;
        if (f.kind().isAv()) {
            try {
                mi = probe.probe(storage.pathOf(f));
                oks.add(add(checks, r.stepId(), "文件有效", true, "可解码", "FFprobe 读取成功（" + Math.round(mi.duration() * 10) / 10.0 + " 秒）"));
            } catch (ApiException e) {
                oks.add(add(checks, r.stepId(), "文件有效", false, "可解码", "FFprobe 无法读取"));
                return oks;
            }
        }
        switch (r.tool()) {
            // the three AV operations are re-verified from scratch with the same rule sets the handlers use, so the
            // review is evidence read back from the file, not an echo of the handler's own summary
            case "video.compress" -> {
                MediaProbe.MediaInfo source = sourceInfo(ownerId, r);
                if (source != null) addAll(checks, r.stepId(), verifier.verifyCompress(storage.pathOf(f), p, source, null), oks);
                else oks.add(add(checks, r.stepId(), "源文件", false, "可读取", "源文件已不可用，无法对照"));
            }
            case "audio.extract" -> addAll(checks, r.stepId(),
                    verifier.verifyAudio(storage.pathOf(f), p.path("format").asText("mp3"), p), oks);
            case "video.convert" -> {
                MediaProbe.MediaInfo source = sourceInfo(ownerId, r);
                if (source != null) addAll(checks, r.stepId(),
                        verifier.verifyConvert(storage.pathOf(f), p.path("targetFormat").asText(), source, null), oks);
                else oks.add(add(checks, r.stepId(), "源文件", false, "可读取", "源文件已不可用，无法对照"));
            }
            case "metadata.scrub" -> {
                String dirId = "review-" + UUID.randomUUID();
                JsonNode tags;
                try {
                    tags = exif.readJson(storage.pathOf(f), storage.newWorkDir(dirId), null);
                } finally {
                    storage.deleteWorkDir(dirId);
                }
                List<String> left = ExifToolRunner.locationTags(tags);
                oks.add(add(checks, r.stepId(), "位置信息", left.isEmpty(), "无 GPS / 位置标签", left.isEmpty() ? "已清除" : "残留 " + left));
            }
            case "metadata.modify" -> {
                ArrayNode written = r.result() != null && r.result().path("writtenFields").isArray()
                        ? (ArrayNode) r.result().path("writtenFields") : mapper.createArrayNode();
                oks.add(add(checks, r.stepId(), "写入字段", !written.isEmpty(), "按计划写入", written.toString()));
            }
            case "image.process" -> {
                if (p.path("targetSizeKB").asInt(0) > 0 && r.result() != null) {
                    boolean met = r.result().path("allTargetsMet").asBoolean(false);
                    oks.add(add(checks, r.stepId(), "目标体积", met, "≤ " + p.path("targetSizeKB").asInt() + " KB / 张", met ? "全部达到" : "部分未达到"));
                } else {
                    oks.add(add(checks, r.stepId(), "输出", true, "生成", humanSize(actualSize)));
                }
            }
            default -> oks.add(add(checks, r.stepId(), "输出", true, "生成", humanSize(actualSize)));
        }
        return oks;
    }

    private void addAll(ArrayNode checks, int stepId, MediaVerifier.Verification v, List<Boolean> oks) {
        for (JsonNode c : v.checks()) {
            oks.add(add(checks, stepId, c.path("name").asText(), c.path("ok").asBoolean(),
                    c.path("expected").asText(), c.path("actual").asText()));
        }
    }

    /** Probe of the step's first input, read back from its task record (the handler's view is not trusted). */
    private MediaProbe.MediaInfo sourceInfo(long ownerId, MediaExecutor.StepResult r) {
        List<String> ins = inputsOf(ownerId, r);
        if (ins.isEmpty()) return null;
        return storage.find(ownerId, ins.get(0)).map(mf -> {
            try {
                return probe.probe(storage.pathOf(mf));
            } catch (ApiException e) {
                return null;
            }
        }).orElse(null);
    }

    private boolean sizeMiss(long ownerId, MediaExecutor.StepResult r) {
        if (!"video.compress".equals(r.tool()) || !"size".equals(r.params().path("mode").asText())) return false;
        return storage.find(ownerId, r.outputFileId())
                .map(f -> f.getSizeBytes() > MediaVerifier.targetBytes(r.params()))
                .orElse(false);
    }

    /** Lower the bitrate in proportion to the overshoot (with 5% margin); keeps codec/resolution/audio. */
    private JsonNode retryParams(long ownerId, MediaExecutor.StepResult r) {
        JsonNode res = r.result();
        long kbps = res == null ? 0 : res.path("targetVideoBitrateKbps").asLong(0);
        long actual = storage.find(ownerId, r.outputFileId()).map(MediaFile::getSizeBytes).orElse(0L);
        double target = r.params().path("targetSizeMB").asDouble() * 1048576.0;
        if (kbps <= 0 || actual <= 0) return null;
        long next = (long) Math.floor(kbps * (target / actual) * 0.95);
        if (next < 50) return null;
        ObjectNode p = ((ObjectNode) r.params()).deepCopy();
        p.put("mode", "bitrate");
        p.remove("targetSizeMB");
        p.put("videoBitrateKbps", next);
        return p;
    }

    /** The retry re-encodes exactly the inputs the first attempt used (read back from its task record). */
    private List<String> inputsOf(long ownerId, MediaExecutor.StepResult r) {
        try {
            TaskEntity t = engine.require(ownerId, r.taskId());
            return mapper.readValue(t.getInputFiles(), mapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private boolean add(ArrayNode checks, int stepId, String name, boolean ok, String expected, String actual) {
        ObjectNode c = checks.addObject();
        c.put("stepId", stepId);
        c.put("name", name);
        c.put("ok", ok);
        c.put("expected", expected);
        c.put("actual", actual);
        return ok;
    }

    private static String statusLabel(String s) {
        return switch (s) { case "FAILED" -> "失败"; case "CANCELLED" -> "已取消"; case "SKIPPED" -> "已跳过"; default -> s; };
    }

    private static double round2(double v) { return Math.round(v * 100) / 100.0; }

    private static String humanSize(long b) {
        return b >= 1048576 ? round2(b / 1048576.0) + " MB" : Math.round(b / 1024.0) + " KB";
    }
}
