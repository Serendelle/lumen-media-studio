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
        String ext = StorageService.extensionOf(originalName == null ? "" : originalName);
        Detected doc = detectDocument(file, mime, ext);
        if (doc != null) return doc;
        Detected d = ALLOWED.get(mime);
        if (d == null) {
            throw new ApiException(ErrorCode.UNSUPPORTED_MEDIA, "不支持的文件类型（检测结果：" + mime + "）");
        }
        if ("svg".equals(d.ext())) checkSvg(file);
        if (d.kind() == MediaKind.VIDEO && "mov".equals(d.ext())) d = refineIsoBmff(file, d);
        return d;
    }

    /**
     * Tika reports every ISO-BMFF file with an unknown brand as QuickTime, so plain MP4s written by FFmpeg
     * ("isom") came out as .mov. The ftyp major brand decides: only "qt  " is QuickTime.
     */
    static Detected refineIsoBmff(Path file, Detected d) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] h = in.readNBytes(12);
            if (h.length < 12 || !new String(h, 4, 4, StandardCharsets.ISO_8859_1).equals("ftyp")) return d;
            String brand = new String(h, 8, 4, StandardCharsets.ISO_8859_1);
            if (brand.equals("qt  ")) return d;
            if (brand.startsWith("M4V")) return ALLOWED.get("video/x-m4v");
            return ALLOWED.get("video/mp4");
        } catch (IOException e) {
            return d;
        }
    }

    private static final java.util.Set<String> ZIP_MIMES = java.util.Set.of("application/zip", "application/x-tika-ooxml",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    /** OLE2 compound files: legacy .doc/.xls, or an encrypted .docx/.xlsx (Office wraps those in OLE2). */
    private static final java.util.Set<String> OLE_MIMES = java.util.Set.of("application/x-tika-msoffice",
            "application/x-tika-ooxml-protected", "application/msword", "application/vnd.ms-excel");
    static final long MAX_OFFICE_BYTES = 30L * 1024 * 1024;
    static final long MAX_HTML_BYTES = 5L * 1024 * 1024;

    /**
     * Office and HTML documents (V2). The real structure decides: a plain ZIP named .docx is refused, an OOXML package
     * is checked entry by entry, and HTML must be a single UTF-8 file. Returns null when the bytes are not a document.
     */
    Detected detectDocument(Path file, String mime, String ext) {
        boolean officeExt = java.util.Set.of("docx", "xlsx", "docm", "xlsm", "dotm", "xltm").contains(ext);
        if (ZIP_MIMES.contains(mime) && officeExt) {
            sizeAtMost(file, MAX_OFFICE_BYTES, "Word / Excel 文档不能超过 30MB");
            com.houqing.tools.video_compressor_backend.document.OoxmlInspector.Format f =
                    com.houqing.tools.video_compressor_backend.document.OoxmlInspector.validate(file, ext);
            return f == com.houqing.tools.video_compressor_backend.document.OoxmlInspector.Format.DOCX
                    ? new Detected(MediaKind.DOCUMENT, "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx")
                    : new Detected(MediaKind.DOCUMENT, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx");
        }
        if (ZIP_MIMES.contains(mime) && !mime.equals("application/zip")) {
            throw new ApiException(ErrorCode.UNSUPPORTED_DOCUMENT, "Office 文档的扩展名应为 .docx 或 .xlsx");
        }
        if (OLE_MIMES.contains(mime)) {
            if (officeExt) throw new ApiException(ErrorCode.DOCUMENT_PASSWORD_REQUIRED);
            if (java.util.Set.of("doc", "xls", "ppt").contains(ext)) {
                throw new ApiException(ErrorCode.UNSUPPORTED_DOCUMENT, "不支持旧版 ." + ext + " 格式，请另存为 .docx / .xlsx 后上传");
            }
            throw new ApiException(ErrorCode.UNSUPPORTED_DOCUMENT);
        }
        boolean htmlExt = ext.equals("html") || ext.equals("htm");
        if (htmlExt && (mime.equals("text/html") || mime.equals("text/plain") || mime.equals("application/xhtml+xml"))) {
            sizeAtMost(file, MAX_HTML_BYTES, "HTML 文件不能超过 5MB");
            checkUtf8Text(file);
            return new Detected(MediaKind.DOCUMENT, "text/html", "html");
        }
        if (officeExt || htmlExt) {
            throw new ApiException(ErrorCode.UNSUPPORTED_DOCUMENT, "文件内容与扩展名 ." + ext + " 不符");
        }
        return null;
    }

    private static void sizeAtMost(Path file, long max, String msg) {
        try {
            if (Files.size(file) > max) throw new ApiException(ErrorCode.FILE_TOO_LARGE, msg);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.DOCUMENT_CORRUPTED);
        }
    }

    /** Strict UTF-8 (a malformed byte sequence is rejected, not replaced) and no NUL bytes. */
    static void checkUtf8Text(Path file) {
        try {
            byte[] b = Files.readAllBytes(file);
            for (byte x : b) if (x == 0) throw new ApiException(ErrorCode.UNSUPPORTED_DOCUMENT, "HTML 文件包含二进制内容");
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(b));
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new ApiException(ErrorCode.UNSUPPORTED_DOCUMENT, "HTML 文件必须为 UTF-8 编码");
        } catch (IOException e) {
            throw new ApiException(ErrorCode.DOCUMENT_CORRUPTED);
        }
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
