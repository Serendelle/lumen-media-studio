package com.houqing.tools.video_compressor_backend.lesson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import com.houqing.tools.video_compressor_backend.config.AppProperties;
import com.houqing.tools.video_compressor_backend.media.*;
import com.houqing.tools.video_compressor_backend.media.handler.FfmpegCommands;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * lesson.analyze — listen, look, understand, verify:
 * 1. extract a small mono audio track (FFmpeg), 2. cloud speech recognition with sentence timestamps,
 * 3. sample keyframes (FFmpeg) and OCR them locally, 4. structure outline + code with the language model,
 * 5. validate everything against the measured data and store the lesson (plus the frames used as evidence).
 * The original video is only read; it is kept for 30 days because the player streams it.
 */
@Component
public class LessonAnalyzeHandler implements MediaTaskHandler {
    static final int MAX_FRAMES = 1200;

    private final AppProperties props;
    private final ToolLocator tools;
    private final ProcessRunner runner;
    private final StorageService storage;
    private final AsrClient asr;
    private final OcrRunner ocr;
    private final LessonStructurer structurer;
    private final LessonRepository lessons;
    private final ObjectMapper mapper;

    public LessonAnalyzeHandler(AppProperties props, ToolLocator tools, ProcessRunner runner, StorageService storage,
                                AsrClient asr, OcrRunner ocr, LessonStructurer structurer, LessonRepository lessons, ObjectMapper mapper) {
        this.props = props;
        this.tools = tools;
        this.runner = runner;
        this.storage = storage;
        this.asr = asr;
        this.ocr = ocr;
        this.structurer = structurer;
        this.lessons = lessons;
        this.mapper = mapper;
    }

    @Override public String type() { return "lesson.analyze"; }
    @Override public List<Tool> requiredTools() { return List.of(Tool.FFMPEG, Tool.FFPROBE); }

    @Override
    public void validate(JsonNode p, List<MediaFile> inputs) {
        if (inputs.size() != 1 || inputs.get(0).kind() != MediaKind.VIDEO) throw new ApiException(ErrorCode.VALIDATION_FAILED, "需要一个教学视频");
        if (!asr.configured()) throw new ApiException(ErrorCode.ASR_UNAVAILABLE, "未配置阿里云百炼语音识别（DASHSCOPE_API_KEY），无法分析课程");
        if (!structurer.configured()) throw new ApiException(ErrorCode.AI_UNAVAILABLE, "未配置 DeepSeek，无法整理知识大纲");
        double duration = knownDuration(inputs.get(0));
        int max = props.lesson().maxDurationMin();
        if (duration > max * 60.0) throw new ApiException(ErrorCode.VALIDATION_FAILED, "视频超过 " + max + " 分钟，请先剪成较短的段落");
        if (duration > 0 && duration < 10) throw new ApiException(ErrorCode.VALIDATION_FAILED, "视频太短（不足 10 秒）");
    }

    private double knownDuration(MediaFile f) {
        try {
            return f.getProbeJson() == null ? 0 : mapper.readTree(f.getProbeJson()).path("duration").asDouble(0);
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public HandlerResult execute(TaskContext ctx) throws Exception {
        MediaFile video = ctx.inputs().get(0);
        MediaProbe.MediaInfo info = ctx.probe(video);
        if (!info.hasVideo()) throw new ApiException(ErrorCode.MEDIA_UNREADABLE);
        if (!info.hasAudio()) throw new ApiException(ErrorCode.AUDIO_STREAM_MISSING, "视频没有声音，无法识别讲解内容");
        double duration = info.duration();
        List<String> warnings = new ArrayList<>();

        // ---- 1. audio for ASR: mono 16 kHz MP3 keeps the upload small (≈ 22 MB per hour)
        ctx.progress(2, "提取音轨");
        Path audio = ctx.workDir().resolve("speech.mp3");
        List<String> a = FfmpegCommands.start(tools, props.media().ffmpegThreads());
        FfmpegCommands.input(a, ctx.inputPath(video));
        a.addAll(List.of("-vn", "-map", "a:0", "-ac", "1", "-ar", "16000", "-c:a", "libmp3lame", "-b:a", "48k", audio.toString()));
        runner.runChecked(a, Duration.ofMinutes(20), ctx.token(), null);

        // ---- 2. speech recognition (cloud)
        ctx.progress(8, "语音识别 · 上传音频");
        List<AsrClient.Sentence> sentences = asr.transcribe(audio, ctx.token(), s -> ctx.progress(
                switch (s) { case "上传音频" -> 10; case "提交识别" -> 15; case "识别中" -> 20; default -> 42; }, "语音识别 · " + s));
        if (sentences.isEmpty()) throw new ApiException(ErrorCode.ASR_FAILED, "没有识别到任何讲解语音");

        // ---- 3. keyframes + OCR (local)
        ctx.progress(45, "抽取关键帧");
        double interval = Math.max(props.lesson().keyframeIntervalSec(), duration / MAX_FRAMES);
        Path framesDir = Files.createDirectories(ctx.workDir().resolve("frames"));
        List<String> f = FfmpegCommands.start(tools, props.media().ffmpegThreads());
        FfmpegCommands.input(f, ctx.inputPath(video));
        f.addAll(List.of("-vf", "fps=1/" + String.format(Locale.ROOT, "%.3f", interval) + ",scale='min(1280,iw)':-2", "-q:v", "4",
                framesDir.resolve("f%05d.jpg").toString()));
        runner.runChecked(f, Duration.ofMinutes(30), ctx.token(), null);
        List<String> names;
        try (var s = Files.list(framesDir)) {
            names = s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".jpg")).sorted().toList();
        }
        Map<String, List<LessonSchema.OcrLine>> seen = Map.of();
        if (ocr.available()) {
            ctx.progress(55, "画面文字识别");
            int total = Math.max(1, names.size());
            seen = ocr.run(framesDir, names.size(), ctx.token(), done -> ctx.progress(55 + (int) (20.0 * done / total), "画面文字识别 " + done + "/" + total));
        } else {
            warnings.add("服务器未安装本地 OCR（RapidOCR），本次只根据语音整理，代码片段无法与画面核对");
        }
        List<LessonSchema.OcrFrame> frames = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            frames.add(new LessonSchema.OcrFrame(i * interval, seen.getOrDefault(names.get(i), List.of())));
        }

        // ---- 4. structure with the model, 5. validate against the measurements
        ctx.progress(76, "整理知识大纲");
        LessonStructurer.Outcome o = structurer.structure(sentences, frames, duration);
        warnings.addAll(o.result().warnings());
        ctx.progress(95, "保存课程");

        String id = UUID.randomUUID().toString();
        ObjectNode data = o.result().lesson();
        Path keep = Files.createDirectories(lessonDir(id).resolve("frames"));
        for (JsonNode sn : data.path("snippets")) {
            if (!sn.has("evidenceTime")) continue;
            int idx = (int) Math.round(sn.path("evidenceTime").asDouble() / interval);
            if (idx >= 0 && idx < names.size()) {
                Files.copy(framesDir.resolve(names.get(idx)), keep.resolve(names.get(idx)), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                ((ObjectNode) sn).put("evidenceFrame", names.get(idx));
            }
        }
        ArrayNode transcript = mapper.createArrayNode();
        for (AsrClient.Sentence s : sentences) {
            transcript.addObject().put("begin", s.beginMs() / 1000.0).put("end", s.endMs() / 1000.0).put("text", s.text());
        }
        ArrayNode screen = mapper.createArrayNode();
        String prev = null;
        for (LessonSchema.OcrFrame fr : frames) {
            if (fr.lines().isEmpty()) continue;
            String text = LessonSchema.layout(fr);
            if (text.equals(prev)) continue;
            prev = text;
            screen.addObject().put("t", fr.t()).put("text", text);
        }
        ObjectNode meta = mapper.createObjectNode();
        meta.put("asrModel", asr.model());
        meta.put("llmModel", o.model());
        meta.put("promptVersion", o.promptVersion());
        meta.put("rounds", o.rounds());
        meta.put("keyframeIntervalSec", interval);
        meta.put("frames", names.size());
        meta.put("ocrFrames", screen.size());
        meta.set("warnings", mapper.valueToTree(warnings));

        LessonEntity l = new LessonEntity();
        l.setId(id);
        l.setOwnerId(ctx.ownerId());
        l.setVideoFileId(video.getId());
        l.setTaskId(ctx.taskId());
        l.setTitle(data.path("title").asText("课程"));
        l.setDataJson(data.toString());
        l.setTranscriptJson(transcript.toString());
        l.setOcrJson(screen.toString());
        l.setMetaJson(meta.toString());
        long now = System.currentTimeMillis();
        l.setCreatedAt(now);
        l.setUpdatedAt(now);
        lessons.save(l);
        storage.keepUntil(video, now + Duration.ofDays(30).toMillis()); // the player streams it

        ObjectNode summary = mapper.createObjectNode();
        summary.put("lessonId", id);
        summary.put("title", l.getTitle());
        summary.put("nodes", data.path("nodes").size());
        summary.put("snippets", data.path("snippets").size());
        summary.put("sentences", sentences.size());
        summary.put("ocrFrames", screen.size());
        summary.set("warnings", mapper.valueToTree(warnings));
        return new HandlerResult(null, summary);
    }

    Path lessonDir(String id) {
        return props.dataRoot().resolve("lessons").resolve(id);
    }
}
