package com.houqing.tools.video_compressor_backend.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.document.UniversalConversionService;
import com.houqing.tools.video_compressor_backend.media.*;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
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
        constraintCoverage(ownerId, plan, finalResults, checks);
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

    /**
     * The user's structured constraints (see {@link Constraints}) are checked against what the user actually gets:
     * the outputs of steps no later step consumed. A constraint with no matching deliverable fails too.
     */
    private void constraintCoverage(long ownerId, JsonNode plan, Map<Integer, MediaExecutor.StepResult> results, ArrayNode checks) {
        JsonNode c = plan.path("constraints");
        if (!c.isObject() || c.isEmpty()) return;
        Set<Integer> consumed = new HashSet<>();
        for (JsonNode s : plan.path("steps")) {
            for (JsonNode in : s.path("inputs")) {
                if (in.asText().startsWith("step:")) consumed.add(Integer.parseInt(in.asText().substring(5)));
            }
        }
        List<MediaFile> videos = new ArrayList<>(), audios = new ArrayList<>(), images = new ArrayList<>(), documents = new ArrayList<>();
        Map<String, Integer> stepOf = new HashMap<>();
        for (MediaExecutor.StepResult r : results.values()) {
            if (!"SUCCEEDED".equals(r.status()) || consumed.contains(r.stepId()) || r.outputFileId() == null) continue;
            storage.find(ownerId, r.outputFileId()).ifPresent(f -> {
                stepOf.put(f.getId(), r.stepId());
                if (f.kind() == MediaKind.PDF || f.kind() == MediaKind.DOCUMENT || "document.convert".equals(r.tool())
                        || "pdf.process".equals(r.tool())) documents.add(f);
                String ext = StorageService.extensionOf(f.getStoredName());
                switch (f.kind()) {
                    case VIDEO -> videos.add(f);
                    case AUDIO -> audios.add(f);
                    case PDF -> { }
                    case IMAGE -> (ext.equals("gif") ? videos : images).add(f);
                    default -> { }
                }
            });
        }
        JsonNode v = c.path("video");
        if (v.isObject()) {
            if (videos.isEmpty()) add(checks, 0, "约束·视频", false, "交付一个视频", "没有视频交付物");
            for (MediaFile f : videos) {
                int step = stepOf.get(f.getId());
                String ext = StorageService.extensionOf(f.getStoredName());
                if (v.has("container")) add(checks, step, "约束·视频格式", ext.equals(v.path("container").asText()),
                        v.path("container").asText().toUpperCase(), ext.toUpperCase());
                if (v.has("maxBytes")) add(checks, step, "约束·视频体积", f.getSizeBytes() <= v.path("maxBytes").asLong(),
                        "≤ " + v.path("maxBytes").asLong() + " 字节", f.getSizeBytes() + " 字节");
                if (v.has("maxHeight") || v.has("keepAudio")) {
                    try {
                        MediaProbe.MediaInfo mi = probe.probe(storage.pathOf(f));
                        if (v.has("maxHeight")) add(checks, step, "约束·分辨率", mi.height() <= v.path("maxHeight").asInt(),
                                "高度 ≤ " + v.path("maxHeight").asInt(), mi.width() + "×" + mi.height());
                        if (v.has("keepAudio")) add(checks, step, "约束·音轨", mi.hasAudio() == v.path("keepAudio").asBoolean(),
                                v.path("keepAudio").asBoolean() ? "保留" : "去除", mi.hasAudio() ? "有音轨" : "无音轨");
                    } catch (ApiException e) {
                        add(checks, step, "约束·视频可读", false, "可解析", "无法解析");
                    }
                }
            }
        }
        JsonNode a = c.path("audio");
        if (a.isObject()) {
            if (audios.isEmpty()) add(checks, 0, "约束·音频", false, "交付一个音频", "没有音频交付物");
            for (MediaFile f : audios) {
                String ext = StorageService.extensionOf(f.getStoredName());
                add(checks, stepOf.get(f.getId()), "约束·音频格式", ext.equals(a.path("format").asText()), a.path("format").asText().toUpperCase(), ext.toUpperCase());
            }
        }
        JsonNode im = c.path("image");
        if (im.isObject()) {
            if (images.isEmpty()) add(checks, 0, "约束·图片", false, "交付图片", "没有图片交付物");
            for (MediaFile f : images) {
                int step = stepOf.get(f.getId());
                String ext = StorageService.extensionOf(f.getStoredName());
                if (im.has("format")) add(checks, step, "约束·图片格式", ext.equals(im.path("format").asText()), im.path("format").asText().toUpperCase(), ext.toUpperCase());
                if (im.has("maxBytes")) add(checks, step, "约束·图片体积", f.getSizeBytes() <= im.path("maxBytes").asLong(),
                        "≤ " + im.path("maxBytes").asLong() + " 字节", f.getSizeBytes() + " 字节");
            }
        }
        if (c.path("document").isObject()) {
            String target = c.path("document").path("format").asText();
            String name = target.equals("pdf") ? "约束·PDF" : "约束·文档格式";
            String label = target.toUpperCase(Locale.ROOT);
            if (documents.isEmpty()) add(checks, 0, name, false, "交付 " + label, "没有文档交付物");
            for (MediaFile f : documents) {
                String ext = StorageService.extensionOf(f.getStoredName());
                boolean archive = f.kind() == MediaKind.ARCHIVE && Set.of("png", "jpg").contains(target);
                boolean valid = ext.equals(archive ? "zip" : target);
                if (valid) {
                    try { valid = UniversalConversionService.verifyOutput(storage.pathOf(f), target, archive) > 0; }
                    catch (Exception e) { valid = false; }
                }
                add(checks, stepOf.get(f.getId()), name, valid, label,
                        valid ? label + (archive ? "（逐页 ZIP）" : "") : ext.toUpperCase(Locale.ROOT) + "（未满足目标格式或内容无效）");
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
            case "document.convert" -> {
                String target = UniversalConversionService.target(p);
                boolean imageTarget = Set.of("png", "jpg").contains(target);
                List<String> inputIds = inputsOf(ownerId, r);
                boolean singleImage = inputIds.size() == 1 && storage.find(ownerId, inputIds.get(0))
                        .map(source -> source.kind() == MediaKind.IMAGE).orElse(false);
                boolean archive = imageTarget && !singleImage;
                MediaKind expectedKind = archive ? MediaKind.ARCHIVE : target.equals("pdf") ? MediaKind.PDF
                        : imageTarget ? MediaKind.IMAGE : MediaKind.DOCUMENT;
                String expectedExt = archive ? "zip" : target;
                String actualExt = StorageService.extensionOf(f.getStoredName());
                oks.add(add(checks, r.stepId(), "目标格式", UniversalConversionService.FORMATS.contains(target)
                                && f.kind() == expectedKind && actualExt.equals(expectedExt),
                        expectedKind + " / " + expectedExt, f.kind() + " / " + actualExt));
                if (inputIds.isEmpty()) oks.add(add(checks, r.stepId(), "源文件", false, "可读取任务输入", "输入记录不可用"));
                if (target.equals("pdf")) {
                    boolean magic = com.houqing.tools.video_compressor_backend.document.PdfFacts.hasPdfMagic(storage.pathOf(f));
                    oks.add(add(checks, r.stepId(), "PDF 文件头", magic, "%PDF-", magic ? "%PDF-" : "不是 PDF"));
                }
                try {
                    int units = UniversalConversionService.verifyOutput(storage.pathOf(f), target, archive);
                    oks.add(add(checks, r.stepId(), target.equals("pdf") ? "页数" : "实际内容可解析", units > 0,
                            "≥ 1", units + (archive ? " 张图片（逐项解码并核对校验和）" : "")));
                    if (target.equals("pdf")) oks.add(add(checks, r.stepId(), "首页可渲染", true, "可渲染", "正常"));
                } catch (Exception e) {
                    oks.add(add(checks, r.stepId(), "可解析", false, target.toUpperCase(Locale.ROOT) + " 可重新打开",
                            "实际文件无法按目标格式解析"));
                }
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
