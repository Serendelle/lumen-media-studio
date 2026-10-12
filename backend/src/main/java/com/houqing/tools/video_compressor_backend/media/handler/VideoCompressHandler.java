package com.houqing.tools.video_compressor_backend.media.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import com.houqing.tools.video_compressor_backend.config.AppProperties;
import com.houqing.tools.video_compressor_backend.media.*;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Compresses a video. Modes: crf (constant quality), bitrate (explicit kbps, two-pass) and size (target MB,
 * two-pass, feasibility-checked first). Resolution / codec / fps / audio are optional; every fact about the
 * source comes from a server-side ffprobe, never from the client.
 *
 * Params: mode, crf, videoBitrateKbps, targetSizeMB, codec(h264|h265), resolution("original"|"1080p"…|{width,height}),
 * fps, audioBitrateKbps (0 = drop audio), preset.
 */
@Component
public class VideoCompressHandler implements MediaTaskHandler {

    static final Map<String, Integer> RESOLUTION_PRESETS = Map.of(
            "2160p", 2160, "1440p", 1440, "1080p", 1080, "720p", 720, "540p", 540, "480p", 480, "360p", 360);
    static final List<String> PRESETS = List.of("ultrafast", "superfast", "veryfast", "faster", "fast", "medium", "slow");
    static final int MAX_DIMENSION = 7680;

    private final AppProperties props;
    private final ToolLocator tools;
    private final ProcessRunner runner;
    private final StorageService storage;
    private final ObjectMapper mapper;

    public VideoCompressHandler(AppProperties props, ToolLocator tools, ProcessRunner runner,
                                StorageService storage, ObjectMapper mapper) {
        this.props = props;
        this.tools = tools;
        this.runner = runner;
        this.storage = storage;
        this.mapper = mapper;
    }

    @Override public String type() { return "video.compress"; }
    @Override public List<Tool> requiredTools() { return List.of(Tool.FFMPEG, Tool.FFPROBE); }

    @Override
    public void validate(JsonNode p, List<MediaFile> inputs) {
        if (inputs.size() != 1 || inputs.get(0).kind() != MediaKind.VIDEO) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "需要且仅需要一个视频文件");
        }
        String mode = p.path("mode").asText("crf");
        switch (mode) {
            case "crf" -> {
                int crf = p.path("crf").asInt(23);
                if (crf < 0 || crf > 51) throw new ApiException(ErrorCode.VALIDATION_FAILED, "CRF 需在 0-51 之间");
            }
            case "bitrate" -> {
                int kbps = p.path("videoBitrateKbps").asInt(0);
                if (kbps < 50 || kbps > 200_000)
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "视频码率需在 50-200000 kbps 之间");
            }
            case "size" -> {
                double mb = p.path("targetSizeMB").asDouble(0);
                if (mb <= 0 || mb > 100_000) throw new ApiException(ErrorCode.VALIDATION_FAILED, "请提供有效的目标大小 (MB)");
            }
            default -> throw new ApiException(ErrorCode.VALIDATION_FAILED, "未知的压缩模式: " + mode);
        }
        String codec = p.path("codec").asText("h264");
        if (!codec.equals("h264") && !codec.equals("h265"))
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "codec 仅支持 h264 或 h265");
        if (p.hasNonNull("preset") && !PRESETS.contains(p.path("preset").asText()))
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "无效的编码速度预设");
        if (p.hasNonNull("fps")) {
            int fps = p.path("fps").asInt(0);
            if (fps < 1 || fps > 120) throw new ApiException(ErrorCode.VALIDATION_FAILED, "帧率需在 1-120 之间");
        }
        if (p.hasNonNull("audioBitrateKbps")) {
            int a = p.path("audioBitrateKbps").asInt(-1);
            if (a != 0 && (a < 32 || a > 512))
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "音频码率需为 0（去除音频）或 32-512 kbps");
        }
        JsonNode res = p.path("resolution");
        if (res.isObject()) {
            int w = res.path("width").asInt(0), h = res.path("height").asInt(0);
            if ((w <= 0 && h <= 0) || w < 0 || h < 0 || w > MAX_DIMENSION || h > MAX_DIMENSION
                    || (w > 0 && w < 16) || (h > 0 && h < 16))
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "自定义分辨率需在 16-" + MAX_DIMENSION + " 之间");
        } else if (!res.isMissingNode() && !res.isNull()) {
            String preset = res.asText("original");
            if (!preset.equals("original") && !RESOLUTION_PRESETS.containsKey(preset))
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "无效的分辨率预设");
        }
    }

    /** Feasibility check for size mode, shared with the estimate endpoint and the AI planner. */
    public static CompressionPlanner.Plan estimate(MediaProbe.MediaInfo info, JsonNode p) {
        int audioKbps = audioKbps(p, info);
        int[] dims = targetDimensions(p, info);
        int targetH = dims == null ? 0 : (dims[1] > 0 ? dims[1] : 0);
        double fps = p.hasNonNull("fps") ? p.path("fps").asInt() : info.fps();
        long targetBytes = (long) (p.path("targetSizeMB").asDouble(0) * 1024 * 1024);
        return CompressionPlanner.plan(info.duration(), info.width(), info.height(), fps, targetBytes,
                audioKbps, targetH, "h265".equals(p.path("codec").asText("h264")));
    }

    @Override
    public HandlerResult execute(TaskContext ctx) throws Exception {
        MediaFile input = ctx.inputs().get(0);
        MediaProbe.MediaInfo info = ctx.probe(input);
        if (!info.hasVideo() || info.duration() <= 0) throw new ApiException(ErrorCode.MEDIA_UNREADABLE);
        JsonNode p = ctx.params();

        boolean h265 = "h265".equals(p.path("codec").asText("h264"));
        String vEnc = h265 ? "libx265" : "libx264";
        int[] dims = targetDimensions(p, info);
        Integer fps = p.hasNonNull("fps") ? p.path("fps").asInt() : null;
        String preset = p.path("preset").asText("medium");
        int audioKbps = audioKbps(p, info);
        Encode enc = new Encode(vEnc, dims, fps, preset, audioKbps, props.media().ffmpegThreads(), info.duration());

        String mode = p.path("mode").asText("crf");
        Path inputPath = ctx.inputPath(input);
        Path output = ctx.workDir().resolve("out.mp4");

        ObjectNode summary = mapper.createObjectNode();
        summary.put("mode", mode);
        summary.put("codec", h265 ? "h265" : "h264");
        summary.put("originalSize", info.size());
        summary.put("originalWidth", info.width());
        summary.put("originalHeight", info.height());
        summary.put("originalBitrateKbps", info.overallBitrate() / 1000);

        switch (mode) {
            case "size" -> {
                CompressionPlanner.Plan plan = estimate(info, p);
                if (!plan.feasible()) {
                    throw new ApiException(ErrorCode.TARGET_UNREACHABLE, plan.reason(),
                            Map.of("minFeasibleMB", Math.round(plan.minFeasibleBytes() / 1048576.0 * 10) / 10.0,
                                    "suggestions", plan.suggestions()));
                }
                long kbps = plan.videoKbps();
                // a target larger than the source must not inflate the video: never exceed the source bitrate
                long sourceKbps = info.videoBitrate() / 1000;
                if (sourceKbps > 0 && kbps > sourceKbps && enc.dims() == null) {
                    kbps = sourceKbps;
                    summary.put("cappedToSourceBitrate", true);
                }
                summary.put("targetSizeMB", p.path("targetSizeMB").asDouble());
                summary.put("targetVideoBitrateKbps", kbps);
                twoPass(ctx, inputPath, output, enc, kbps);
            }
            case "bitrate" -> {
                long kbps = p.path("videoBitrateKbps").asInt();
                summary.put("targetVideoBitrateKbps", kbps);
                twoPass(ctx, inputPath, output, enc, kbps);
            }
            default -> {
                int crf = p.path("crf").asInt(23);
                summary.put("crf", crf);
                singlePassCrf(ctx, inputPath, output, enc, crf);
            }
        }

        long outSize = Files.size(output);
        summary.put("outputSize", outSize);
        summary.put("savedPercent", info.size() > 0 ? Math.round((1 - (double) outSize / info.size()) * 1000) / 10.0 : 0);

        String outName = StorageService.baseName(input.getOriginalName()) + "_compressed.mp4";
        MediaFile out = storage.registerOutput(ctx.ownerId(), output, outName, MediaKind.VIDEO, "video/mp4", ctx.taskId());
        return new HandlerResult(out, summary);
    }

    private record Encode(String vEnc, int[] dims, Integer fps, String preset, int audioKbps, int threads,
                          double duration) {}

    private void singlePassCrf(TaskContext ctx, Path in, Path out, Encode e, int crf) {
        List<String> cmd = FfmpegCommands.start(tools, e.threads());
        FfmpegCommands.input(cmd, in);
        addVideo(cmd, e);
        cmd.add("-crf"); cmd.add(String.valueOf(crf));
        addAudio(cmd, e.audioKbps());
        addOutput(cmd, out);
        runner.runChecked(cmd, timeout(), ctx.token(), FfmpegProgress.lineConsumer(ctx, e.duration(), 0, 100, "encoding"));
    }

    /**
     * Two-pass ABR so the bitrate (and thus the file size) is actually met. x264 uses -pass/-passlogfile;
     * x265 needs its own x265-params, and Windows drive colons cannot appear there, so it runs in the work dir
     * with a relative stats file.
     */
    private void twoPass(TaskContext ctx, Path in, Path out, Encode e, long videoKbps) {
        String nullSink = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "NUL" : "/dev/null";
        boolean x265 = e.vEnc().equals("libx265");

        List<String> pass1 = FfmpegCommands.start(tools, e.threads());
        FfmpegCommands.input(pass1, in);
        addVideo(pass1, e);
        pass1.add("-b:v"); pass1.add(videoKbps + "k");
        addPass(pass1, 1, x265, ctx.workDir());
        pass1.add("-an"); pass1.add("-f"); pass1.add("mp4");
        pass1.add(nullSink);
        runner.runChecked(pass1, timeout(), ctx.token(),
                FfmpegProgress.lineConsumer(ctx, e.duration(), 0, 45, "analyzing"), ctx.workDir());

        List<String> pass2 = FfmpegCommands.start(tools, e.threads());
        FfmpegCommands.input(pass2, in);
        addVideo(pass2, e);
        pass2.add("-b:v"); pass2.add(videoKbps + "k");
        addPass(pass2, 2, x265, ctx.workDir());
        addAudio(pass2, e.audioKbps());
        addOutput(pass2, out);
        runner.runChecked(pass2, timeout(), ctx.token(),
                FfmpegProgress.lineConsumer(ctx, e.duration(), 45, 100, "encoding"), ctx.workDir());
    }

    private static void addPass(List<String> cmd, int pass, boolean x265, Path workDir) {
        if (x265) {
            cmd.add("-x265-params"); cmd.add("pass=" + pass + ":stats=x265_2pass.log:log-level=error");
        } else {
            cmd.add("-pass"); cmd.add(String.valueOf(pass));
            cmd.add("-passlogfile"); cmd.add(workDir.resolve("x264_2pass").toString());
        }
    }

    private static void addVideo(List<String> cmd, Encode e) {
        List<String> vf = new ArrayList<>();
        if (e.dims() != null) vf.add("scale=" + e.dims()[0] + ":" + e.dims()[1] + ":flags=lanczos");
        if (e.fps() != null) vf.add("fps=" + e.fps());
        if (!vf.isEmpty()) { cmd.add("-vf"); cmd.add(String.join(",", vf)); }
        cmd.add("-c:v"); cmd.add(e.vEnc());
        cmd.add("-preset"); cmd.add(e.preset());
        cmd.add("-pix_fmt"); cmd.add("yuv420p");
        if (e.vEnc().equals("libx265")) { cmd.add("-tag:v"); cmd.add("hvc1"); }
    }

    private static void addAudio(List<String> cmd, int audioKbps) {
        if (audioKbps > 0) {
            cmd.add("-c:a"); cmd.add("aac");
            cmd.add("-b:a"); cmd.add(audioKbps + "k");
        } else {
            cmd.add("-an");
        }
    }

    private static void addOutput(List<String> cmd, Path out) {
        cmd.add("-movflags"); cmd.add("+faststart");
        cmd.add("-progress"); cmd.add("pipe:1");
        cmd.add(out.toString());
    }

    private Duration timeout() { return Duration.ofMinutes(props.media().taskTimeoutMinutes()); }

    static int audioKbps(JsonNode p, MediaProbe.MediaInfo info) {
        if (!info.hasAudio()) return 0;
        return p.hasNonNull("audioBitrateKbps") ? p.path("audioBitrateKbps").asInt(128) : 128;
    }

    /** Target [w,h] for the scale filter (even, downscale-only; -2 keeps aspect) or null to keep source size. */
    static int[] targetDimensions(JsonNode p, MediaProbe.MediaInfo info) {
        JsonNode res = p.path("resolution");
        if (res.isObject()) {
            int w = res.path("width").asInt(0);
            int h = res.path("height").asInt(0);
            if (w <= 0 && h <= 0) return null;
            if (info.width() > 0 && info.height() > 0 && (w >= info.width() || w <= 0) && (h >= info.height() || h <= 0)) {
                return null; // never upscale
            }
            return new int[]{w > 0 ? even(Math.min(w, info.width() > 0 ? info.width() : w)) : -2,
                    h > 0 ? even(Math.min(h, info.height() > 0 ? info.height() : h)) : -2};
        }
        Integer targetH = RESOLUTION_PRESETS.get(res.asText("original"));
        if (targetH == null || info.height() <= 0 || targetH >= info.height()) return null;
        return new int[]{-2, targetH};
    }

    private static int even(int v) { return v % 2 == 0 ? v : v - 1; }
}
