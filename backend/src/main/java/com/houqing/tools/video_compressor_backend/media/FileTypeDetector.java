package com.houqing.tools.video_compressor_backend.media;

import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Determines the real type of an uploaded file from its content (magic bytes), never from the client's
 * file name or Content-Type. Only an allow-list of types is accepted; the stored extension comes from here.
 */
@Component
public class FileTypeDetector {

    public record Detected(MediaKind kind, String mime, String ext) {}

    /** mime -> (kind, canonical extension) */
    private static final Map<String, Detected> ALLOWED = Map.ofEntries(
            entry("video/mp4", MediaKind.VIDEO, "mp4"),
            entry("video/quicktime", MediaKind.VIDEO, "mov"),
            entry("video/x-m4v", MediaKind.VIDEO, "m4v"),
            entry("video/x-matroska", MediaKind.VIDEO, "mkv"),
            entry("video/webm", MediaKind.VIDEO, "webm"),
            entry("video/x-msvideo", MediaKind.VIDEO, "avi"),
            entry("video/x-flv", MediaKind.VIDEO, "flv"),
            entry("video/mp2t", MediaKind.VIDEO, "ts"),
            entry("video/3gpp", MediaKind.VIDEO, "3gp"),
            entry("video/mpeg", MediaKind.VIDEO, "mpg"),
            entry("audio/mpeg", MediaKind.AUDIO, "mp3"),
            entry("audio/vnd.wave", MediaKind.AUDIO, "wav"),
            entry("audio/x-wav", MediaKind.AUDIO, "wav"),
            entry("audio/wav", MediaKind.AUDIO, "wav"),
            entry("audio/x-flac", MediaKind.AUDIO, "flac"),
            entry("audio/flac", MediaKind.AUDIO, "flac"),
            entry("audio/mp4", MediaKind.AUDIO, "m4a"),
            entry("audio/x-aac", MediaKind.AUDIO, "aac"),
            entry("audio/aac", MediaKind.AUDIO, "aac"),
            entry("audio/ogg", MediaKind.AUDIO, "ogg"),
            entry("audio/opus", MediaKind.AUDIO, "opus"),
            entry("audio/vorbis", MediaKind.AUDIO, "ogg"),
            entry("image/jpeg", MediaKind.IMAGE, "jpg"),
            entry("image/png", MediaKind.IMAGE, "png"),
            entry("image/webp", MediaKind.IMAGE, "webp"),
            entry("image/gif", MediaKind.IMAGE, "gif"),
            entry("image/bmp", MediaKind.IMAGE, "bmp"),
            entry("image/tiff", MediaKind.IMAGE, "tiff"),
            entry("image/avif", MediaKind.IMAGE, "avif"),
            entry("image/heic", MediaKind.IMAGE, "heic"),
            entry("image/heif", MediaKind.IMAGE, "heif"),
            entry("image/svg+xml", MediaKind.IMAGE, "svg"),
            entry("application/pdf", MediaKind.PDF, "pdf"));

    private static final long MAX_SVG_BYTES = 5L * 1024 * 1024;
    private static final Pattern HREF = Pattern.compile("(?i)(?:xlink:)?href\\s*=\\s*[\"']\\s*([^\"']*)[\"']");
    private static final Pattern CSS_URL = Pattern.compile("(?i)url\\s*\\(\\s*[\"']?\\s*([^\"')]*)");

    private final Tika tika = new Tika();

    private static Map.Entry<String, Detected> entry(String mime, MediaKind kind, String ext) {
        return Map.entry(mime, new Detected(kind, mime, ext));
    }

    public Detected detect(Path file, String originalName) {
        String mime;
        try (InputStream in = Files.newInputStream(file)) {
            if (Files.size(file) == 0) throw new ApiException(ErrorCode.UNSUPPORTED_MEDIA, "文件为空");
            // content only: Tika falls back to the file-name extension when the bytes are unknown, which would let
            // a text file named "x.png" pass as an image. Audio-only MP4s are reclassified after ffprobe.
            mime = tika.detect(in);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.MEDIA_UNREADABLE);
        }
        Detected d = ALLOWED.get(mime);
        if (d == null) {
            throw new ApiException(ErrorCode.UNSUPPORTED_MEDIA, "不支持的文件类型（检测结果：" + mime + "）");
        }
        if ("svg".equals(d.ext())) checkSvg(file);
        return d;
    }

    /** SVGs are only accepted when self-contained: no DTD/entities, scripts, or references to other resources. */
    void checkSvg(Path file) {
        try {
            if (Files.size(file) > MAX_SVG_BYTES) throw new ApiException(ErrorCode.FILE_TOO_LARGE, "SVG 文件不能超过 5MB");
            String text = Files.readString(file, StandardCharsets.UTF_8);
            String lower = text.toLowerCase(Locale.ROOT);
            if (lower.contains("<!doctype") || lower.contains("<!entity") || lower.contains("<script")
                    || lower.contains("<foreignobject")) {
                throw new ApiException(ErrorCode.UNSUPPORTED_MEDIA, "SVG 包含不支持的内容（DTD、脚本或外部对象）");
            }
            if (!onlyLocalRefs(HREF.matcher(text)) || !onlyLocalRefs(CSS_URL.matcher(text))) {
                throw new ApiException(ErrorCode.UNSUPPORTED_MEDIA, "SVG 引用了外部资源，请先内嵌后再上传");
            }
        } catch (IOException e) {
            throw new ApiException(ErrorCode.MEDIA_UNREADABLE);
        }
    }

    private static boolean onlyLocalRefs(Matcher m) {
        while (m.find()) {
            String ref = m.group(1).trim().toLowerCase(Locale.ROOT);
            if (ref.isEmpty() || ref.startsWith("#") || ref.startsWith("data:image/")) continue;
            return false;
        }
        return true;
    }

    public static Map<String, Detected> allowed() { return ALLOWED; }
}
