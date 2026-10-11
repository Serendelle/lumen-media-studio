package com.houqing.tools.video_compressor_backend.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads authoritative media facts with ffprobe. Everything the processing code needs (duration, bitrate,
 * resolution, codecs) comes from here — never from numbers supplied by the client.
 */
@Component
public class MediaProbe {
    private static final Logger log = LoggerFactory.getLogger(MediaProbe.class);
    private static final List<String> FORMAT_WHITELIST = List.of(
            "mov", "mp4", "m4a", "3gp", "3g2", "mj2", "matroska", "webm", "avi", "flv",
            "mpegts", "mpeg", "mp3", "wav", "flac", "aac", "ogg", "image2",
            "png_pipe", "jpeg_pipe", "webp_pipe", "gif");

    private final ToolLocator tools;
    private final ProcessRunner runner;
    private final ObjectMapper mapper = new ObjectMapper();

    public MediaProbe(ToolLocator tools, ProcessRunner runner) {
        this.tools = tools;
        this.runner = runner;
    }

    public record MediaInfo(String formatName, double duration, long size, long overallBitrate,
                            boolean hasVideo, boolean hasAudio,
                            int width, int height, double fps,
                            String videoCodec, long videoBitrate,
                            String audioCodec, long audioBitrate, int audioChannels, int audioSampleRate,
                            String rawJson) {}

    public MediaInfo probe(Path file) {
        List<String> cmd = new ArrayList<>(tools.command(Tool.FFPROBE));
        cmd.addAll(List.of(
                "-hide_banner", "-loglevel", "error",
                "-protocol_whitelist", "file",
                "-f_strict", "experimental",
                "-print_format", "json", "-show_format", "-show_streams",
                "-i", file.toString()));
        StringBuilder out = new StringBuilder();
        ProcessRunner.Result r = runner.run(cmd, Duration.ofSeconds(60), null, line -> out.append(line).append('\n'));
        if (!r.ok()) {
            log.warn("ffprobe failed ({}): {}", r.exitCode(), r.tail());
            throw new ApiException(ErrorCode.MEDIA_UNREADABLE);
        }
        try {
            JsonNode root = mapper.readTree(out.toString());
            return parse(root);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.MEDIA_UNREADABLE);
        }
    }

    private MediaInfo parse(JsonNode root) {
        JsonNode format = root.path("format");
        String formatName = format.path("format_name").asText("");
        if (!formatWhitelisted(formatName)) {
            log.warn("ffprobe reported non-whitelisted format: {}", formatName);
            throw new ApiException(ErrorCode.UNSUPPORTED_MEDIA, "不支持的媒体封装格式");
        }
        double duration = format.path("duration").asDouble(0);
        long size = format.path("size").asLong(0);
        long overall = format.path("bit_rate").asLong(0);

        boolean hasVideo = false, hasAudio = false;
        int width = 0, height = 0, audioChannels = 0, audioSampleRate = 0;
        double fps = 0;
        String vCodec = null, aCodec = null;
        long vBitrate = 0, aBitrate = 0;

        for (JsonNode s : root.path("streams")) {
            String type = s.path("codec_type").asText();
            if ("video".equals(type) && !isCoverArt(s)) {
                hasVideo = true;
                width = s.path("width").asInt(0);
                height = s.path("height").asInt(0);
                fps = parseRational(s.path("avg_frame_rate").asText("0/0"));
                vCodec = s.path("codec_name").asText(null);
                vBitrate = s.path("bit_rate").asLong(0);
            } else if ("audio".equals(type)) {
                hasAudio = true;
                aCodec = s.path("codec_name").asText(null);
                aBitrate = s.path("bit_rate").asLong(0);
                audioChannels = s.path("channels").asInt(0);
                audioSampleRate = s.path("sample_rate").asInt(0);
            }
        }
        if (overall <= 0 && duration > 0 && size > 0) overall = (long) (size * 8 / duration);
        return new MediaInfo(formatName, duration, size, overall, hasVideo, hasAudio,
                width, height, fps, vCodec, vBitrate, aCodec, aBitrate, audioChannels, audioSampleRate,
                root.toString());
    }

    private static boolean isCoverArt(JsonNode stream) {
        return stream.path("disposition").path("attached_pic").asInt(0) == 1;
    }

    private static boolean formatWhitelisted(String formatName) {
        if (formatName == null || formatName.isBlank()) return false;
        for (String f : formatName.split(",")) {
            if (FORMAT_WHITELIST.contains(f.trim())) return true;
        }
        return false;
    }

    static double parseRational(String r) {
        try {
            String[] p = r.split("/");
            if (p.length == 2) {
                double den = Double.parseDouble(p[1]);
                return den == 0 ? 0 : Double.parseDouble(p[0]) / den;
            }
            return Double.parseDouble(r);
        } catch (Exception e) {
            return 0;
        }
    }
}
