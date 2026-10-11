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

/**
 * Removes privacy metadata. mode=all strips everything (keeping image orientation and colour profile so the
 * picture still looks right); mode=location removes only GPS/location tags. Afterwards the output is re-read
 * with ExifTool and any remaining location tags are reported — the result is verified, not assumed.
 */
@Component
public class MetadataScrubHandler implements MediaTaskHandler {

    private final AppProperties props;
    private final ToolLocator tools;
    private final ProcessRunner runner;
    private final ExifToolRunner exif;
    private final StorageService storage;
    private final ObjectMapper mapper;

    public MetadataScrubHandler(AppProperties props, ToolLocator tools, ProcessRunner runner, ExifToolRunner exif,
                                StorageService storage, ObjectMapper mapper) {
        this.props = props; this.tools = tools; this.runner = runner; this.exif = exif;
        this.storage = storage; this.mapper = mapper;
    }

    @Override public String type() { return "metadata.scrub"; }
    @Override public List<Tool> requiredTools() { return List.of(Tool.EXIFTOOL); }

    @Override
    public void validate(JsonNode p, List<MediaFile> inputs) {
        if (inputs.size() != 1) throw new ApiException(ErrorCode.VALIDATION_FAILED, "需要一个文件");
        String mode = p.path("mode").asText("all");
        if (!mode.equals("all") && !mode.equals("location"))
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "mode 仅支持 all 或 location");
        MetadataCapabilities.Capability cap = MetadataCapabilities.of(StorageService.extensionOf(inputs.get(0).getStoredName()));
        if (!cap.canScrubAll()) throw new ApiException(ErrorCode.CAPABILITY_UNSUPPORTED, "该格式不支持元数据清理");
    }

    @Override
    public HandlerResult execute(TaskContext ctx) throws Exception {
        MediaFile in = ctx.inputs().get(0);
        Path src = ctx.inputPath(in);
        String ext = StorageService.extensionOf(in.getStoredName());
        Path out = ctx.workDir().resolve("clean." + ext);
        String mode = ctx.params().path("mode").asText("all");
        MetadataCapabilities.Capability cap = MetadataCapabilities.of(ext);
        Duration timeout = Duration.ofMinutes(Math.min(10, props.media().taskTimeoutMinutes()));
        ctx.progress(15, "scrubbing");

        String method;
        if (cap.writer() == MetadataCapabilities.Writer.EXIFTOOL && mode.equals("location")) {
            exif.run(List.of("-gps:all=", "-xmp:geotag=", "-QuickTime:GPSCoordinates=", "-*Location*=",
                    "-o", out.toString(), src.toString()), ctx.workDir(), timeout, ctx.token());
            method = "exiftool-location";
        } else if (cap.writer() == MetadataCapabilities.Writer.EXIFTOOL && in.kind() == MediaKind.IMAGE) {
            // wipe everything, then copy back only what affects how the image renders
            exif.run(List.of("-all=", "-tagsFromFile", "@", "-Orientation", "-ICC_Profile",
                    "-o", out.toString(), src.toString()), ctx.workDir(), timeout, ctx.token());
            method = "exiftool-all";
        } else {
            // A/V full strip: remux without global/stream metadata or chapters (lossless, stream copy)
            List<String> cmd = FfmpegCommands.start(tools, 0);
            FfmpegCommands.input(cmd, src);
            cmd.add("-map"); cmd.add("0");
            cmd.add("-map_metadata"); cmd.add("-1");
            cmd.add("-map_chapters"); cmd.add("-1");
            cmd.add("-fflags"); cmd.add("+bitexact");
            cmd.add("-c"); cmd.add("copy");
            cmd.add(out.toString());
            runner.runChecked(cmd, timeout, ctx.token(), null);
            method = mode.equals("location") ? "ffmpeg-all(location-only unsupported for this format)" : "ffmpeg-all";
        }
        if (!Files.exists(out)) throw new ApiException(ErrorCode.PROCESS_FAILED);
        ctx.progress(75, "verifying");

        // independent verification of the produced file
        JsonNode after = exif.readJson(out, ctx.workDir(), ctx.token());
        List<String> remaining = ExifToolRunner.locationTags(after);

        var summary = mapper.createObjectNode();
        summary.put("mode", mode);
        summary.put("method", method);
        summary.put("outputSize", Files.size(out));
        summary.put("verified", remaining.isEmpty());
        var arr = summary.putArray("remainingLocationTags");
        new ArrayList<>(remaining).forEach(arr::add);
        String name = StorageService.baseName(in.getOriginalName()) + "_clean." + ext;
        MediaFile mf = storage.registerOutput(ctx.ownerId(), out, name, in.kind(), in.getMimeType(), ctx.taskId());
        return new HandlerResult(mf, summary);
    }
}
