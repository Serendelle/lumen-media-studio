package com.houqing.tools.video_compressor_backend.media;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which metadata fields can really be written for each stored format, and with which tool. The UI and the AI
 * planner read this so they never promise an edit the underlying tool cannot perform.
 */
public final class MetadataCapabilities {
    private MetadataCapabilities() {}

    public enum Writer { EXIFTOOL, FFMPEG, NONE }

    public static final List<String> ALL_FIELDS = List.of(
            "title", "artist", "comment", "copyright", "make", "model", "dateTimeOriginal", "gps");

    private static final Set<String> EXIF_IMAGES = Set.of("jpg", "jpeg", "tiff", "tif", "png", "webp", "heic", "heif", "avif");
    private static final Set<String> QUICKTIME = Set.of("mp4", "mov", "m4v", "m4a", "3gp");
    private static final Set<String> FFMPEG_TAGGED = Set.of("mkv", "webm", "mp3", "flac", "ogg", "opus", "wav", "avi");

    public record Capability(String format, Writer writer, List<String> editableFields,
                             boolean canScrubAll, boolean canScrubLocationOnly) {}

    public static Capability of(String ext) {
        String e = ext == null ? "" : ext.toLowerCase(java.util.Locale.ROOT);
        if (EXIF_IMAGES.contains(e)) return new Capability(e, Writer.EXIFTOOL, ALL_FIELDS, true, true);
        if (QUICKTIME.contains(e)) return new Capability(e, Writer.EXIFTOOL, ALL_FIELDS, true, true);
        if (FFMPEG_TAGGED.contains(e)) {
            // container tags only: no camera make/model or GPS convention that players read back
            return new Capability(e, Writer.FFMPEG, List.of("title", "artist", "comment", "copyright", "dateTimeOriginal"),
                    true, false);
        }
        if (e.equals("gif")) return new Capability(e, Writer.EXIFTOOL, List.of("title", "artist", "comment", "copyright"), true, true);
        return new Capability(e, Writer.NONE, List.of(), false, false);
    }

    public static Map<String, Capability> matrix() {
        Map<String, Capability> m = new LinkedHashMap<>();
        for (String e : List.of("jpg", "png", "webp", "heic", "avif", "tiff", "gif", "svg", "bmp",
                "mp4", "mov", "m4a", "mkv", "webm", "mp3", "flac", "wav", "avi")) {
            m.put(e, of(e));
        }
        return m;
    }
}
