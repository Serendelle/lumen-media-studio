package com.houqing.tools.video_compressor_backend.media.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;

/** Overlays an image watermark on a video. Position is a fixed enum; margin/opacity are clamped numbers. */
@Component
public class VideoWatermarkHandler implements MediaTaskHandler {

    private static final Map<String, String> POSITIONS = Map.of(
            "top-left", "%1$d:%1$d",
            "top-right", "W-w-%1$d:%1$d",
            "bottom-left", "%1$d:H-h-%1$d",
            "bottom-right", "W-w-%1$d:H-h-%1$d",
            "center", "(W-w)/2:(H-h)/2",
            "top-center", "(W-w)/2:%1$d",
            "bottom-center", "(W-w)/2:H-h-%1$d",
            "center-left", "%1$d:(H-h)/2",
            "center-right", "W-w-%1$d:(H-h)/2");

    /** Audio codecs that can be stream-copied into an MP4 container. */
    private static final java.util.Set<String> MP4_SAFE_AUDIO = java.util.Set.of("aac", "mp3", "ac3", "eac3", "alac", "opus");

    private final AppProperties props;
    private final ToolLocator tools;
    private final ProcessRunner runner;
    private final StorageService storage;
    private final ObjectMapper mapper;

    public VideoWatermarkHandler(AppProperties props, ToolLocator tools, ProcessRunner runner,
                                 StorageService storage, ObjectMapper mapper) {
        this.props = props; this.tools = tools; this.runner = runner; this.storage = storage; this.mapper = mapper;
    }

    @Override public String type() { return "video.watermark"; }
    @Override public List<Tool> requiredTools() { return List.of(Tool.FFMPEG, Tool.FFPROBE); }

    @Override
    public void validate(JsonNode p, List<MediaFile> inputs) {
        if (inputs.size() != 2)
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "需要两个文件：视频和水印图片");
        if (inputs.get(0).kind() != MediaKind.VIDEO)
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "第一个文件必须是视频");
        if (inputs.get(1).kind() != MediaKind.IMAGE)
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "第二个文件必须是图片");
        String pos = p.path("position").asText("bottom-right");
        if (!POSITIONS.containsKey(pos))
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "无效的水印位置");
        if (p.hasNonNull("margin") && (p.path("margin").asInt(-1) < 0 || p.path("margin").asInt(-1) > 500))
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "边距需在 0-500 像素之间");
        if (p.hasNonNull("opacity") && (p.path("opacity").asDouble(-1) < 0 || p.path("opacity").asDouble(-1) > 1))
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "透明度需在 0-1 之间");
    }

    @Override
    public HandlerResult execute(TaskContext ctx) throws Exception {
        MediaFile video = ctx.inputs().get(0);
        MediaFile mark = ctx.inputs().get(1);
        MediaProbe.MediaInfo info = ctx.probe(video);

        String pos = ctx.params().path("position").asText("bottom-right");
        int margin = Math.max(0, Math.min(500, ctx.params().path("margin").asInt(24)));
        double opacity = Math.max(0, Math.min(1, ctx.params().path("opacity").asDouble(0.75)));
        String overlay = String.format(POSITIONS.get(pos), margin);
        // numbers are formatted by us, not taken from strings, so the filter graph is injection-free
        String filter = String.format(java.util.Locale.ROOT,
                "[1:v]format=rgba,colorchannelmixer=aa=%.2f[wm];[0:v][wm]overlay=%s", opacity, overlay);

        Path out = ctx.workDir().resolve("out.mp4");
        List<String> cmd = FfmpegCommands.start(tools, props.media().ffmpegThreads());
        FfmpegCommands.input(cmd, ctx.inputPath(video));
        FfmpegCommands.input(cmd, ctx.inputPath(mark));
        cmd.add("-filter_complex"); cmd.add(filter);
        cmd.add("-c:v"); cmd.add("libx264"); cmd.add("-crf"); cmd.add("20"); cmd.add("-preset"); cmd.add("medium");
        cmd.add("-pix_fmt"); cmd.add("yuv420p");
        if (!info.hasAudio()) cmd.add("-an");
        else if (MP4_SAFE_AUDIO.contains(String.valueOf(info.audioCodec()))) { cmd.add("-c:a"); cmd.add("copy"); }
        else { cmd.add("-c:a"); cmd.add("aac"); cmd.add("-b:a"); cmd.add("160k"); } // e.g. pcm/vorbis cannot go into mp4
        cmd.add("-movflags"); cmd.add("+faststart");
        cmd.add("-progress"); cmd.add("pipe:1");
        cmd.add(out.toString());

        runner.runChecked(cmd, Duration.ofMinutes(props.media().taskTimeoutMinutes()), ctx.token(),
                FfmpegProgress.lineConsumer(ctx, info.duration(), "watermarking"));

        var summary = mapper.createObjectNode();
        summary.put("position", pos);
        summary.put("outputSize", Files.size(out));
        String name = StorageService.baseName(video.getOriginalName()) + "_watermarked.mp4";
        MediaFile mf = storage.registerOutput(ctx.ownerId(), out, name, MediaKind.VIDEO, "video/mp4", ctx.taskId());
        return new HandlerResult(mf, summary);
    }
}
