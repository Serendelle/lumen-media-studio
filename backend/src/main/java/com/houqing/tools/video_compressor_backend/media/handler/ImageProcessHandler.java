package com.houqing.tools.video_compressor_backend.media.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import com.houqing.tools.video_compressor_backend.config.AppProperties;
import com.houqing.tools.video_compressor_backend.media.*;
import org.springframework.stereotype.Component;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Batch image processing with ImageMagick.
 *
 * tool=convert  : targetFormat, quality
 * tool=resize   : width / height, keepAspectRatio
 * tool=compress : format (keep|jpg|webp|avif|png), quality, targetSizeKB, maxWidth / maxHeight
 *
 * Every file gets its own registered output (so the UI can preview before/after) plus a zip when there are
 * several. A target size is searched by quality; when even the lowest quality cannot reach it the file is
 * still delivered at its smallest and marked targetMet=false with a suggestion — success is never faked.
 */
@Component
public class ImageProcessHandler implements MediaTaskHandler {

    static final Map<String, String> MIME = Map.of(
            "jpg", "image/jpeg", "png", "image/png", "webp", "image/webp",
            "avif", "image/avif", "gif", "image/gif", "bmp", "image/bmp", "tiff", "image/tiff");
    /** Explicit ImageMagick input coders, so content is always decoded as the type detected at upload. */
    static final Map<String, String> INPUT_CODER = Map.ofEntries(
            Map.entry("jpg", "JPEG"), Map.entry("png", "PNG"), Map.entry("webp", "WEBP"), Map.entry("gif", "GIF"),
            Map.entry("bmp", "BMP"), Map.entry("tiff", "TIFF"), Map.entry("avif", "AVIF"), Map.entry("heic", "HEIC"),
            Map.entry("heif", "HEIC"), Map.entry("svg", "MSVG"));
    static final Set<String> LOSSY = Set.of("jpg", "webp", "avif");
    static final int MIN_QUALITY = 5;

    private final AppProperties props;
    private final ToolLocator tools;
    private final ProcessRunner runner;
    private final StorageService storage;
    private final ObjectMapper mapper;

    public ImageProcessHandler(AppProperties props, ToolLocator tools, ProcessRunner runner,
                               StorageService storage, ObjectMapper mapper) {
        this.props = props; this.tools = tools; this.runner = runner; this.storage = storage; this.mapper = mapper;
    }

    @Override public String type() { return "image.process"; }
    @Override public List<Tool> requiredTools() { return List.of(Tool.MAGICK); }

    @Override
    public void validate(JsonNode p, List<MediaFile> inputs) {
        if (inputs.isEmpty()) throw new ApiException(ErrorCode.VALIDATION_FAILED, "请至少选择一张图片");
        if (inputs.size() > 50) throw new ApiException(ErrorCode.VALIDATION_FAILED, "单次最多处理 50 张图片");
        for (MediaFile f : inputs) {
            if (f.kind() != MediaKind.IMAGE) throw new ApiException(ErrorCode.VALIDATION_FAILED, "只能处理图片文件");
        }
        String tool = p.path("tool").asText("");
        if (!Set.of("convert", "resize", "compress").contains(tool))
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "tool 仅支持 convert/resize/compress");
        if (tool.equals("convert") && !MIME.containsKey(p.path("targetFormat").asText("")))
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "不支持的目标格式，可选: " + MIME.keySet());
        if (tool.equals("compress")) {
            String fmt = p.path("format").asText("keep");
            if (!fmt.equals("keep") && !Set.of("jpg", "webp", "avif", "png").contains(fmt))
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "压缩输出格式仅支持 keep/jpg/webp/avif/png");
        }
        if (tool.equals("resize") && p.path("width").asInt(0) <= 0 && p.path("height").asInt(0) <= 0)
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "请提供目标宽度或高度");
        for (String k : List.of("width", "height", "maxWidth", "maxHeight")) {
            int v = p.path(k).asInt(0);
            if (v < 0 || v > 16000) throw new ApiException(ErrorCode.VALIDATION_FAILED, k + " 需在 1-16000 之间");
        }
        int q = p.path("quality").asInt(85);
        if (q < 1 || q > 100) throw new ApiException(ErrorCode.VALIDATION_FAILED, "quality 需在 1-100 之间");
        int kb = p.path("targetSizeKB").asInt(0);
        if (kb < 0 || kb > 500_000) throw new ApiException(ErrorCode.VALIDATION_FAILED, "目标大小超出范围");
    }

    @Override
    public HandlerResult execute(TaskContext ctx) throws Exception {
        JsonNode p = ctx.params();
        String tool = p.path("tool").asText();
        List<MediaFile> inputs = ctx.inputs();
        List<Path> outputs = new ArrayList<>();
        List<String> names = new ArrayList<>();
        ArrayNode items = mapper.createArrayNode();
        long totalIn = 0, totalOut = 0;
        boolean allTargetsMet = true;

        List<ObjectNode> okItems = new ArrayList<>();
        ApiException firstError = null;
        int failed = 0;
        for (int i = 0; i < inputs.size(); i++) {
            if (ctx.token().isCancelled()) throw new ApiException(ErrorCode.CANCELLED);
            MediaFile in = inputs.get(i);
            String outExt = outputExt(tool, p, in);
            Path dst = ctx.workDir().resolve("out_" + i + "." + outExt);
            String name = StorageService.baseName(in.getOriginalName()) + "." + outExt;
            ObjectNode item;
            try {
                item = processOne(ctx, in, dst, tool, p, outExt);
                if (!Files.isRegularFile(dst) || Files.size(dst) == 0) throw new ApiException(ErrorCode.PROCESS_FAILED, "未生成输出图片");
            } catch (ApiException e) {
                if (e.getCode() == ErrorCode.CANCELLED) throw e;
                // one bad image must not sink the batch: record why, keep going, deliver only what succeeded
                if (firstError == null) firstError = e;
                failed++;
                ObjectNode bad = items.addObject();
                bad.put("inputFileId", in.getId());
                bad.put("name", in.getOriginalName());
                bad.put("inputSize", in.getSizeBytes());
                bad.put("ok", false);
                bad.put("errorCode", e.getCode().name());
                // the generic tool failure says nothing useful per image; name the likely cause instead
                bad.put("error", e.getCode() == ErrorCode.PROCESS_FAILED
                        ? "无法解码这张图片，文件可能已损坏或格式不受支持" : e.getMessage());
                totalIn += in.getSizeBytes();
                ctx.progress((int) ((i + 1.0) / inputs.size() * 95), "processing " + (i + 1) + "/" + inputs.size());
                continue;
            }
            outputs.add(dst);
            names.add(name);
            long outSize = Files.size(dst);
            item.put("inputFileId", in.getId());
            item.put("name", name);
            item.put("inputSize", in.getSizeBytes());
            item.put("outputSize", outSize);
            item.put("ok", true);
            if (item.has("targetMet") && !item.path("targetMet").asBoolean()) allTargetsMet = false;
            items.add(item);
            okItems.add(item);
            totalIn += in.getSizeBytes();
            totalOut += outSize;
            ctx.progress((int) ((i + 1.0) / inputs.size() * 95), "processing " + (i + 1) + "/" + inputs.size());
        }
        if (outputs.isEmpty()) {
            throw new ApiException(firstError.getCode(), "全部 " + inputs.size() + " 张图片处理失败：" + items.get(0).path("error").asText(),
                    java.util.Map.of("items", mapper.convertValue(items, Object.class)));
        }

        // zip first (from the work-dir copies), then register each file individually for preview/download
        Path zip = null;
        if (outputs.size() > 1) {
            zip = ctx.workDir().resolve("result.zip");
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip.toFile()))) {
                Set<String> used = new HashSet<>();
                for (int i = 0; i < outputs.size(); i++) {
                    zos.putNextEntry(new ZipEntry(uniqueName(used, names.get(i))));
                    Files.copy(outputs.get(i), zos);
                    zos.closeEntry();
                }
            }
        }
        for (int i = 0; i < outputs.size(); i++) {
            String ext = StorageService.extensionOf(names.get(i));
            MediaFile mf = storage.registerOutput(ctx.ownerId(), outputs.get(i), names.get(i), MediaKind.IMAGE,
                    MIME.getOrDefault(ext, "image/png"), ctx.taskId());
            okItems.get(i).put("outputFileId", mf.getId());
        }

        ObjectNode summary = mapper.createObjectNode();
        summary.put("tool", tool);
        summary.put("count", inputs.size());
        summary.put("succeeded", outputs.size());
        summary.put("failed", failed);
        // presentation-level aggregate; the task status itself stays SUCCEEDED because usable output exists
        summary.put("overall", failed > 0 ? "PARTIAL_SUCCESS" : "SUCCEEDED");
        summary.put("inputSize", totalIn);
        summary.put("outputSize", totalOut);
        if (p.path("targetSizeKB").asInt(0) > 0) summary.put("allTargetsMet", allTargetsMet);
        summary.set("items", items);

        if (zip == null) {
            MediaFile single = storage.require(ctx.ownerId(), okItems.get(0).path("outputFileId").asText());
            return new HandlerResult(single, summary);
        }
        MediaFile mf = storage.registerOutput(ctx.ownerId(), zip, failed > 0 ? "images_processed_partial.zip" : "images_processed.zip",
                MediaKind.ARCHIVE, "application/zip", ctx.taskId());
        return new HandlerResult(mf, summary);
    }

    private ObjectNode processOne(TaskContext ctx, MediaFile in, Path dst, String tool, JsonNode p, String outExt) {
        ObjectNode item = mapper.createObjectNode();
        int targetKB = tool.equals("compress") ? p.path("targetSizeKB").asInt(0) : 0;
        int quality = p.path("quality").asInt(85);

        if (targetKB > 0 && LOSSY.contains(outExt)) {
            long target = targetKB * 1024L;
            // binary search on quality: highest quality whose output fits the target
            int lo = MIN_QUALITY, hi = Math.min(95, quality), best = -1;
            int attempts = 0;
            while (lo <= hi && attempts < 8) {
                attempts++;
                int mid = (lo + hi) / 2;
                runMagick(ctx, in, dst, tool, p, outExt, mid);
                if (size(dst) <= target) { best = mid; lo = mid + 1; } else { hi = mid - 1; }
            }
            int finalQ = best > 0 ? best : MIN_QUALITY;
            runMagick(ctx, in, dst, tool, p, outExt, finalQ);
            boolean met = size(dst) <= target;
            item.put("quality", finalQ);
            item.put("targetSizeKB", targetKB);
            item.put("targetMet", met);
            if (!met) item.put("suggestion", "最低画质下仍超出目标，可同时设置最大宽度/高度来缩小尺寸");
        } else {
            runMagick(ctx, in, dst, tool, p, outExt, quality);
            if (targetKB > 0) {
                item.put("targetSizeKB", targetKB);
                item.put("targetMet", size(dst) <= targetKB * 1024L);
                item.put("suggestion", "PNG 为无损格式，按体积压缩请选择 JPG/WebP/AVIF");
            }
            if (LOSSY.contains(outExt)) item.put("quality", quality);
        }
        return item;
    }

    private void runMagick(TaskContext ctx, MediaFile in, Path dst, String tool, JsonNode p, String outExt, int quality) {
        String inExt = StorageService.extensionOf(in.getStoredName());
        List<String> cmd = new ArrayList<>(tools.command(Tool.MAGICK));
        if (inExt.equals("svg")) { cmd.add("-density"); cmd.add("300"); cmd.add("-background"); cmd.add("none"); }
        String coder = INPUT_CODER.getOrDefault(inExt, "");
        // first frame only (guards against multi-frame decompression bombs); explicit coder prevents type confusion
        cmd.add((coder.isEmpty() ? "" : coder + ":") + ctx.inputPath(in) + "[0]");
        cmd.add("-auto-orient");

        int w = p.path("width").asInt(0), h = p.path("height").asInt(0);
        if (tool.equals("resize")) {
            boolean keepRatio = p.path("keepAspectRatio").asBoolean(true);
            cmd.add("-resize"); cmd.add((w > 0 ? w : "") + "x" + (h > 0 ? h : "") + (keepRatio ? "" : "!"));
        }
        int mw = p.path("maxWidth").asInt(0), mh = p.path("maxHeight").asInt(0);
        if (mw > 0 || mh > 0) {
            cmd.add("-resize"); cmd.add((mw > 0 ? mw : "") + "x" + (mh > 0 ? mh : "") + ">"); // shrink only
        }
        if (LOSSY.contains(outExt) || outExt.equals("png")) {
            cmd.add("-quality"); cmd.add(String.valueOf(quality));
        }
        cmd.add("-strip");
        String outStr = dst.toString();
        cmd.add(outExt.equals("png") ? "PNG32:" + outStr : outStr);
        runner.runChecked(cmd, Duration.ofMinutes(Math.min(5, props.media().taskTimeoutMinutes())), ctx.token(), null);
    }

    static String outputExt(String tool, JsonNode p, MediaFile in) {
        if (tool.equals("convert")) return p.path("targetFormat").asText("png");
        if (tool.equals("compress")) {
            String fmt = p.path("format").asText("keep");
            if (!fmt.equals("keep")) return fmt;
        }
        String ext = StorageService.extensionOf(in.getStoredName());
        if (ext.equals("heic") || ext.equals("heif")) return "jpg";
        return MIME.containsKey(ext) ? ext : "png";
    }

    private static long size(Path p) {
        try { return Files.size(p); } catch (Exception e) { return Long.MAX_VALUE; }
    }

    private static String uniqueName(Set<String> used, String name) {
        String candidate = name;
        int n = 1;
        while (!used.add(candidate)) {
            String ext = StorageService.extensionOf(name);
            candidate = StorageService.baseName(name) + "(" + (n++) + ")" + (ext.isEmpty() ? "" : "." + ext);
        }
        return candidate;
    }
}
