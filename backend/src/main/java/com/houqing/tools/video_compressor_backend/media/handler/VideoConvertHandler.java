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

/** Transcodes a video to another container/codec from a fixed whitelist of target formats. */
@Component
public class VideoConvertHandler implements MediaTaskHandler {

    /** target -> [container ext, video codec, audio codec, mime] */
    private static final Map<String, String[]> TARGETS = Map.of(
            "mp4", new String[]{"mp4", "libx264", "aac", "video/mp4"},
            "mov", new String[]{"mov", "libx264", "aac", "video/quicktime"},
            "webm", new String[]{"webm", "libvpx-vp9", "libopus", "video/webm"},
            "mkv", new String[]{"matroska", "libx264", "aac", "video/x-matroska"},
            "gif", new String[]{"gif", "gif", "none", "image/gif"});

    private final AppProperties props;
    private final ToolLocator tools;
    private final ProcessRunner runner;
    private final StorageService storage;
    private final ObjectMapper mapper;

    public VideoConvertHandler(AppProperties props, ToolLocator tools, ProcessRunner runner,
                               StorageService storage, ObjectMapper mapper) {
        this.props = props; this.tools = tools; this.runner = runner; this.storage = storage; this.mapper = mapper;
    }

    @Override public String type() { return "video.convert"; }
    @Override public List<Tool> requiredTools() { return List.of(Tool.FFMPEG, Tool.FFPROBE); }

    @Override
    public void validate(JsonNode p, List<MediaFile> inputs) {
        if (inputs.size() != 1 || inputs.get(0).kind() != MediaKind.VIDEO)
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "需要一个视频文件");
        String target = p.path("targetFormat").asText("");
        if (!TARGETS.containsKey(target))
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "不支持的目标格式，可选: " + TARGETS.keySet());
        if (p.hasNonNull("fps") && (p.path("fps").asInt(0) < 1 || p.path("fps").asInt(0) > 30))
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "GIF 帧率需在 1-30 之间");
    }

    @Override
    public HandlerResult execute(TaskContext ctx) throws Exception {
        MediaFile input = ctx.inputs().get(0);
        MediaProbe.MediaInfo info = ctx.probe(input);
        String target = ctx.params().path("targetFormat").asText();
        String[] spec = TARGETS.get(target);
        Path out = ctx.workDir().resolve("out." + spec[0]);

        List<String> cmd = FfmpegCommands.start(tools, props.media().ffmpegThreads());
        FfmpegCommands.input(cmd, ctx.inputPath(input));
        if (target.equals("gif")) {
            int fps = Math.max(1, Math.min(30, ctx.params().path("fps").asInt(12)));
            cmd.add("-vf"); cmd.add("fps=" + fps + ",scale=480:-2:flags=lanczos,split[s0][s1];[s0]palettegen[p];[s1][p]paletteuse");
            cmd.add("-loop"); cmd.add("0");
        } else {
            cmd.add("-c:v"); cmd.add(spec[1]);
            cmd.add("-pix_fmt"); cmd.add("yuv420p");
            if (spec[1].equals("libx264")) { cmd.add("-crf"); cmd.add("23"); cmd.add("-preset"); cmd.add("medium"); }
            if (spec[1].equals("libvpx-vp9")) { cmd.add("-crf"); cmd.add("32"); cmd.add("-b:v"); cmd.add("0"); }
            if (info.hasAudio() && !"none".equals(spec[2])) { cmd.add("-c:a"); cmd.add(spec[2]); }
            else cmd.add("-an");
            if (spec[0].equals("mp4") || spec[0].equals("mov")) { cmd.add("-movflags"); cmd.add("+faststart"); }
        }
        cmd.add("-progress"); cmd.add("pipe:1");
        cmd.add(out.toString());

        runner.runChecked(cmd, Duration.ofMinutes(props.media().taskTimeoutMinutes()), ctx.token(),
                FfmpegProgress.lineConsumer(ctx, info.duration(), "converting"));

        var summary = mapper.createObjectNode();
        summary.put("targetFormat", target);
        summary.put("outputSize", Files.size(out));
        MediaKind kind = target.equals("gif") ? MediaKind.IMAGE : MediaKind.VIDEO;
        String name = StorageService.baseName(input.getOriginalName()) + "." + spec[0];
        MediaFile mf = storage.registerOutput(ctx.ownerId(), out, name, kind, spec[3], ctx.taskId());
        return new HandlerResult(mf, summary);
    }
}
