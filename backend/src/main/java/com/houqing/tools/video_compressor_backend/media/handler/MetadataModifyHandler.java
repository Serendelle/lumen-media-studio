package com.houqing.tools.video_compressor_backend.media.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.util.regex.Pattern;

/**
 * Edits a whitelisted set of metadata fields. The writer per format comes from {@link MetadataCapabilities}:
 * ExifTool for images and QuickTime-family files (UTF-8 arg file), FFmpeg container tags for the rest.
 * Requesting a field the format cannot hold is rejected up front instead of silently ignored.
 *
 * Params: title, artist, comment, copyright, make, model, dateTimeOriginal ("yyyy:MM:dd HH:mm:ss"),
 * gpsLatitude + gpsLongitude, clearGps (bool).
 */
@Component
public class MetadataModifyHandler implements MediaTaskHandler {

    static final Pattern DATETIME = Pattern.compile("^\\d{4}:\\d{2}:\\d{2} \\d{2}:\\d{2}:\\d{2}$");
    static final List<String> TEXT_FIELDS = List.of("title", "artist", "comment", "copyright", "make", "model");
    static final int MAX_TEXT = 256;

    private final AppProperties props;
    private final ToolLocator tools;
    private final ProcessRunner runner;
    private final ExifToolRunner exif;
    private final StorageService storage;
    private final ObjectMapper mapper;

    public MetadataModifyHandler(AppProperties props, ToolLocator tools, ProcessRunner runner, ExifToolRunner exif,
                                 StorageService storage, ObjectMapper mapper) {
        this.props = props; this.tools = tools; this.runner = runner; this.exif = exif;
        this.storage = storage; this.mapper = mapper;
    }

    @Override public String type() { return "metadata.modify"; }

    @Override
    public void validate(JsonNode p, List<MediaFile> inputs) {
        if (inputs.size() != 1) throw new ApiException(ErrorCode.VALIDATION_FAILED, "需要一个文件");
        MetadataCapabilities.Capability cap = MetadataCapabilities.of(StorageService.extensionOf(inputs.get(0).getStoredName()));
        if (cap.writer() == MetadataCapabilities.Writer.NONE)
            throw new ApiException(ErrorCode.CAPABILITY_UNSUPPORTED, "该格式不支持写入元数据");

        List<String> requested = new ArrayList<>();
        for (String f : TEXT_FIELDS) {
            if (p.hasNonNull(f)) {
                String v = p.path(f).asText();
                if (v.length() > MAX_TEXT) throw new ApiException(ErrorCode.VALIDATION_FAILED, f + " 不能超过 " + MAX_TEXT + " 个字符");
                requested.add(f);
            }
        }
        if (p.hasNonNull("dateTimeOriginal")) {
            if (!DATETIME.matcher(p.path("dateTimeOriginal").asText()).matches())
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "时间格式应为 yyyy:MM:dd HH:mm:ss");
            requested.add("dateTimeOriginal");
        }
        if (p.hasNonNull("gpsLatitude") != p.hasNonNull("gpsLongitude"))
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "GPS 经纬度需同时提供");
        if (p.hasNonNull("gpsLatitude")) {
            double lat = p.path("gpsLatitude").asDouble(), lon = p.path("gpsLongitude").asDouble();
            if (lat < -90 || lat > 90 || lon < -180 || lon > 180)
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "GPS 坐标超出范围");
            if (p.path("clearGps").asBoolean(false))
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "不能同时写入和清除 GPS");
            requested.add("gps");
        }
        if (p.path("clearGps").asBoolean(false)) requested.add("gps");
        if (requested.isEmpty()) throw new ApiException(ErrorCode.VALIDATION_FAILED, "请至少提供一个要修改的字段");

        List<String> unsupported = requested.stream().filter(f -> !cap.editableFields().contains(f)).distinct().toList();
        if (!unsupported.isEmpty()) {
            throw new ApiException(ErrorCode.CAPABILITY_UNSUPPORTED,
                    cap.format().toUpperCase(Locale.ROOT) + " 格式不支持修改: " + String.join(", ", unsupported),
                    java.util.Map.of("unsupportedFields", unsupported, "editableFields", cap.editableFields()));
        }
    }

    @Override
    public HandlerResult execute(TaskContext ctx) throws Exception {
        MediaFile in = ctx.inputs().get(0);
        JsonNode p = ctx.params();
        Path src = ctx.inputPath(in);
        String ext = StorageService.extensionOf(in.getStoredName());
        Path out = ctx.workDir().resolve("modified." + ext);
        MetadataCapabilities.Capability cap = MetadataCapabilities.of(ext);
        ctx.progress(20, "writing-metadata");
        Duration timeout = Duration.ofMinutes(Math.min(10, props.media().taskTimeoutMinutes()));

        ArrayNode written = mapper.createArrayNode();
        if (cap.writer() == MetadataCapabilities.Writer.EXIFTOOL) {
            boolean quicktime = in.kind().isAv();
            List<String> args = new ArrayList<>();
            if (quicktime) { args.add("-api"); args.add("QuickTimeUTC=1"); }
            addText(args, p, "title", quicktime ? "-QuickTime:Title" : "-XMP-dc:Title", written);
            addText(args, p, "artist", quicktime ? "-QuickTime:Artist" : "-EXIF:Artist", written);
            if (!quicktime && p.hasNonNull("artist")) args.add("-XMP-dc:Creator=" + p.path("artist").asText());
            addText(args, p, "comment", quicktime ? "-QuickTime:Comment" : "-EXIF:ImageDescription", written);
            addText(args, p, "copyright", quicktime ? "-QuickTime:Copyright" : "-EXIF:Copyright", written);
            addText(args, p, "make", quicktime ? "-QuickTime:Make" : "-EXIF:Make", written);
            addText(args, p, "model", quicktime ? "-QuickTime:Model" : "-EXIF:Model", written);
            if (p.hasNonNull("dateTimeOriginal")) {
                String dt = p.path("dateTimeOriginal").asText();
                if (quicktime) {
                    args.add("-QuickTime:CreateDate=" + dt);
                    args.add("-QuickTime:ModifyDate=" + dt);
                } else {
                    args.add("-EXIF:DateTimeOriginal=" + dt);
                    args.add("-EXIF:CreateDate=" + dt);
                }
                written.add("dateTimeOriginal");
            }
            if (p.path("clearGps").asBoolean(false)) {
                args.add("-gps:all=");
                if (quicktime) args.add("-QuickTime:GPSCoordinates=");
                written.add("gps(cleared)");
            } else if (p.hasNonNull("gpsLatitude")) {
                double lat = p.path("gpsLatitude").asDouble(), lon = p.path("gpsLongitude").asDouble();
                if (quicktime) {
                    args.add(String.format(Locale.ROOT, "-QuickTime:GPSCoordinates=%.6f, %.6f, 0", lat, lon));
                } else {
                    args.add(String.format(Locale.ROOT, "-GPSLatitude=%.6f", Math.abs(lat)));
                    args.add("-GPSLatitudeRef=" + (lat >= 0 ? "N" : "S"));
                    args.add(String.format(Locale.ROOT, "-GPSLongitude=%.6f", Math.abs(lon)));
                    args.add("-GPSLongitudeRef=" + (lon >= 0 ? "E" : "W"));
                }
                written.add("gps");
            }
            args.add("-o"); args.add(out.toString());
            args.add(src.toString());
            exif.run(args, ctx.workDir(), timeout, ctx.token());
        } else {
            List<String> cmd = FfmpegCommands.start(tools, 0);
            FfmpegCommands.input(cmd, src);
            cmd.add("-map"); cmd.add("0");
            cmd.add("-map_metadata"); cmd.add("0");
            for (String f : List.of("title", "artist", "comment", "copyright")) {
                if (p.hasNonNull(f)) {
                    cmd.add("-metadata"); cmd.add(f + "=" + ExifToolRunnerAccess.clean(p.path(f).asText()));
                    written.add(f);
                }
            }
            if (p.hasNonNull("dateTimeOriginal")) {
                String iso = toIso(p.path("dateTimeOriginal").asText());
                cmd.add("-metadata"); cmd.add("creation_time=" + iso);
                cmd.add("-metadata"); cmd.add("date=" + iso.substring(0, 10));
                written.add("dateTimeOriginal");
            }
            cmd.add("-c"); cmd.add("copy");
            cmd.add(out.toString());
            runner.runChecked(cmd, timeout, ctx.token(), null);
        }
        if (!Files.exists(out)) throw new ApiException(ErrorCode.PROCESS_FAILED);
        ctx.progress(90, "saving");

        var summary = mapper.createObjectNode();
        summary.put("outputSize", Files.size(out));
        summary.put("writer", cap.writer().name());
        summary.set("writtenFields", written);
        String name = StorageService.baseName(in.getOriginalName()) + "_edited." + ext;
        MediaFile mf = storage.registerOutput(ctx.ownerId(), out, name, in.kind(), in.getMimeType(), ctx.taskId());
        return new HandlerResult(mf, summary);
    }

    private static void addText(List<String> args, JsonNode p, String field, String tag, ArrayNode written) {
        if (p.hasNonNull(field)) {
            args.add(tag + "=" + p.path(field).asText());
            written.add(field);
        }
    }

    static String toIso(String exifDate) {
        // "2024:04:06 18:30:00" -> "2024-04-06T18:30:00.000000Z"
        String d = exifDate.substring(0, 10).replace(':', '-');
        return d + "T" + exifDate.substring(11) + ".000000Z";
    }

    /** Small bridge so FFmpeg tag values get the same control-character cleaning as ExifTool ones. */
    static final class ExifToolRunnerAccess {
        private ExifToolRunnerAccess() {}
        static String clean(String s) { return s == null ? "" : s.replaceAll("[\\x00-\\x1f\\x7f]", " "); }
    }
}
