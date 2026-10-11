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
import java.util.Set;

/** AI background removal via the rembg CLI (first run downloads the model, so the timeout is generous). */
@Component
public class RemoveBackgroundHandler implements MediaTaskHandler {

    private final AppProperties props;
    private final ToolLocator tools;
    private final ProcessRunner runner;
    private final StorageService storage;
    private final ObjectMapper mapper;

    public RemoveBackgroundHandler(AppProperties props, ToolLocator tools, ProcessRunner runner,
                                   StorageService storage, ObjectMapper mapper) {
        this.props = props; this.tools = tools; this.runner = runner; this.storage = storage; this.mapper = mapper;
    }

    @Override public String type() { return "image.removebg"; }
    @Override public List<Tool> requiredTools() { return List.of(Tool.REMBG); }

    @Override
    public void validate(JsonNode p, List<MediaFile> inputs) {
        if (inputs.size() != 1 || inputs.get(0).kind() != MediaKind.IMAGE)
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "需要一张图片");
        if (Set.of("svg", "gif").contains(StorageService.extensionOf(inputs.get(0).getStoredName())))
            throw new ApiException(ErrorCode.CAPABILITY_UNSUPPORTED, "抠图仅支持 JPG/PNG/WebP 等位图");
    }

    @Override
    public HandlerResult execute(TaskContext ctx) throws Exception {
        MediaFile in = ctx.inputs().get(0);
        Path out = ctx.workDir().resolve("out.png");
        ctx.progress(5, "loading-model");

        List<String> cmd = new ArrayList<>(tools.command(Tool.REMBG));
        cmd.add("i");
        cmd.add(ctx.inputPath(in).toString());
        cmd.add(out.toString());
        // model download + inference can be slow on first run
        runner.runChecked(cmd, Duration.ofMinutes(Math.max(10, props.media().taskTimeoutMinutes())), ctx.token(), null);
        ctx.progress(95, "saving");

        if (!Files.exists(out)) throw new ApiException(ErrorCode.PROCESS_FAILED, "AI 未能生成结果图像");
        var summary = mapper.createObjectNode();
        summary.put("outputSize", Files.size(out));
        String name = StorageService.baseName(in.getOriginalName()) + "_nobg.png";
        MediaFile mf = storage.registerOutput(ctx.ownerId(), out, name, MediaKind.IMAGE, "image/png", ctx.taskId());
        return new HandlerResult(mf, summary);
    }
}
