package com.houqing.tools.video_compressor_backend.media.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import com.houqing.tools.video_compressor_backend.config.AppProperties;
import com.houqing.tools.video_compressor_backend.media.*;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** PDF ↔ image conversion via ImageMagick (Ghostscript renders PDF). */
@Component
public class PdfHandler implements MediaTaskHandler {

    private final AppProperties props;
    private final ToolLocator tools;
    private final ProcessRunner runner;
    private final StorageService storage;
    private final ObjectMapper mapper;

    public PdfHandler(AppProperties props, ToolLocator tools, ProcessRunner runner,
                      StorageService storage, ObjectMapper mapper) {
        this.props = props; this.tools = tools; this.runner = runner; this.storage = storage; this.mapper = mapper;
    }

    @Override public String type() { return "pdf.process"; }
    @Override public List<Tool> requiredTools() { return List.of(Tool.MAGICK, Tool.GHOSTSCRIPT); }

    @Override
    public void validate(JsonNode p, List<MediaFile> inputs) {
        String tool = p.path("tool").asText("");
        if (tool.equals("pdf-to-image")) {
            if (inputs.size() != 1 || inputs.get(0).kind() != MediaKind.PDF)
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "需要一个 PDF 文件");
        } else if (tool.equals("images-to-pdf")) {
            if (inputs.isEmpty()) throw new ApiException(ErrorCode.VALIDATION_FAILED, "请至少选择一张图片");
            if (inputs.size() > 200) throw new ApiException(ErrorCode.VALIDATION_FAILED, "单次最多合并 200 张图片");
            for (MediaFile f : inputs)
                if (f.kind() != MediaKind.IMAGE) throw new ApiException(ErrorCode.VALIDATION_FAILED, "只能合并图片为 PDF");
        } else {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "tool 仅支持 pdf-to-image / images-to-pdf");
        }
    }

    @Override
    public HandlerResult execute(TaskContext ctx) throws Exception {
        String tool = ctx.params().path("tool").asText();
        return tool.equals("pdf-to-image") ? pdfToImages(ctx) : imagesToPdf(ctx);
    }

    private HandlerResult pdfToImages(TaskContext ctx) throws Exception {
        MediaFile pdf = ctx.inputs().get(0);
        int density = Math.max(72, Math.min(600, ctx.params().path("density").asInt(150)));
        String fmt = ctx.params().path("format").asText("png");
        if (!Set.of("png", "jpg").contains(fmt)) fmt = "png";
        int quality = Math.max(1, Math.min(100, ctx.params().path("quality").asInt(90)));

        Path dir = ctx.workDir().resolve("pages");
        Files.createDirectories(dir);
        List<String> cmd = new ArrayList<>(tools.command(Tool.MAGICK));
        cmd.add("-density"); cmd.add(String.valueOf(density));
        cmd.add("-limit"); cmd.add("disk"); cmd.add("2GiB");
        cmd.add("PDF:" + ctx.inputPath(pdf));
        cmd.add("-quality"); cmd.add(String.valueOf(quality));
        cmd.add(dir.resolve("page-%03d." + fmt).toString());
        runner.runChecked(cmd, Duration.ofMinutes(props.media().taskTimeoutMinutes()), ctx.token(), null);
        ctx.progress(80, "packaging");

        File[] pages = dir.toFile().listFiles();
        if (pages == null || pages.length == 0) throw new ApiException(ErrorCode.PROCESS_FAILED, "PDF 渲染结果为空");
        Arrays.sort(pages, Comparator.comparing(File::getName));
        Path zip = ctx.workDir().resolve("pages.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip.toFile()))) {
            for (File page : pages) {
                zos.putNextEntry(new ZipEntry(page.getName()));
                Files.copy(page.toPath(), zos);
                zos.closeEntry();
            }
        }
        var summary = mapper.createObjectNode();
        summary.put("pages", pages.length);
        summary.put("outputSize", Files.size(zip));
        String name = StorageService.baseName(pdf.getOriginalName()) + "_pages.zip";
        MediaFile mf = storage.registerOutput(ctx.ownerId(), zip, name, MediaKind.ARCHIVE, "application/zip", ctx.taskId());
        return new HandlerResult(mf, summary);
    }

    private HandlerResult imagesToPdf(TaskContext ctx) throws Exception {
        Path out = ctx.workDir().resolve("merged.pdf");
        List<String> cmd = new ArrayList<>(tools.command(Tool.MAGICK));
        for (MediaFile img : ctx.inputs()) {
            String coder = ImageProcessHandler.INPUT_CODER.getOrDefault(StorageService.extensionOf(img.getStoredName()), "");
            cmd.add((coder.isEmpty() ? "" : coder + ":") + ctx.inputPath(img) + "[0]");
        }
        cmd.add("-auto-orient");
        // Normalise page size: many JPEGs carry a 1:1 "aspect only" JFIF density, which ImageMagick reads as 1 DPI
        // and would turn a 1600px photo into a 1600-inch page (Ghostscript then renders a gigantic bitmap).
        cmd.add("-units"); cmd.add("PixelsPerInch");
        cmd.add("-density"); cmd.add("150");
        cmd.add(out.toString());
        runner.runChecked(cmd, Duration.ofMinutes(props.media().taskTimeoutMinutes()), ctx.token(), null);

        var summary = mapper.createObjectNode();
        summary.put("count", ctx.inputs().size());
        summary.put("outputSize", Files.size(out));
        MediaFile mf = storage.registerOutput(ctx.ownerId(), out, "merged.pdf", MediaKind.PDF, "application/pdf", ctx.taskId());
        return new HandlerResult(mf, summary);
    }
}
