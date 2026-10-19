package com.houqing.tools.video_compressor_backend.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.houqing.tools.video_compressor_backend.media.MediaVerifier;

import java.util.List;
import java.util.Locale;

/**
 * The user's hard requirements as one structured object, independent of how the planner chose to meet them:
 *
 * <pre>
 * { "video":    {"container": "mp4", "maxSizeMB": 20, "maxHeight": 1080, "keepAudio": true},
 *   "audio":    {"format": "mp3"},
 *   "image":    {"format": "jpg", "maxSizeKB": 500},
 *   "document": {"format": "pdf"} }
 * </pre>
 *
 * The model proposes it; {@link #normalize} keeps only known, bounded fields (sizes become exact bytes, MiB/KiB
 * like the rest of the platform). The quality reviewer then checks every enabled constraint against the files the
 * user actually receives — a run is VERIFIED only when all of them hold, whatever the individual steps reported.
 */
public final class Constraints {
    private Constraints() {}

    static final List<String> CONTAINERS = List.of("mp4", "mov", "webm", "mkv", "gif");
    static final List<String> AUDIO = List.of("mp3", "aac", "m4a", "wav", "flac");
    static final List<String> IMAGE = List.of("jpg", "png", "webp", "avif");

    public static ObjectNode normalize(JsonNode in, ObjectMapper mapper) {
        ObjectNode out = mapper.createObjectNode();
        if (in == null || !in.isObject()) return out;
        JsonNode v = in.path("video");
        if (v.isObject()) {
            ObjectNode o = mapper.createObjectNode();
            String c = lower(v.path("container"));
            if (CONTAINERS.contains(c)) o.put("container", c);
            double mb = v.path("maxSizeMB").asDouble(0);
            if (mb > 0 && mb <= 100_000) o.put("maxBytes", (long) Math.floor(mb * MediaVerifier.MIB));
            int h = v.path("maxHeight").asInt(0);
            if (h >= 144 && h <= 4320) o.put("maxHeight", h);
            if (v.path("keepAudio").isBoolean()) o.put("keepAudio", v.path("keepAudio").asBoolean());
            if (!o.isEmpty()) out.set("video", o);
        }
        JsonNode a = in.path("audio");
        if (a.isObject() && AUDIO.contains(lower(a.path("format")))) out.putObject("audio").put("format", lower(a.path("format")));
        JsonNode im = in.path("image");
        if (im.isObject()) {
            ObjectNode o = mapper.createObjectNode();
            String f = lower(im.path("format")).replace("jpeg", "jpg");
            if (IMAGE.contains(f)) o.put("format", f);
            double kb = im.path("maxSizeKB").asDouble(0);
            if (kb > 0 && kb <= 500_000) o.put("maxBytes", (long) Math.floor(kb * 1024));
            if (!o.isEmpty()) out.set("image", o);
        }
        JsonNode d = in.path("document");
        if (d.isObject() && "pdf".equals(lower(d.path("format")))) out.putObject("document").put("format", "pdf");
        return out;
    }

    /** Human description of each enabled constraint, for the plan card and the review. */
    public static String describe(JsonNode c) {
        StringBuilder sb = new StringBuilder();
        JsonNode v = c.path("video");
        if (v.isObject()) {
            sb.append("视频");
            if (v.has("container")) sb.append(" ").append(v.path("container").asText().toUpperCase(Locale.ROOT));
            if (v.has("maxBytes")) sb.append(" ≤ ").append(MediaVerifier.mib(v.path("maxBytes").asLong())).append(" MiB");
            if (v.has("maxHeight")) sb.append(" 高度 ≤ ").append(v.path("maxHeight").asInt());
            if (v.has("keepAudio")) sb.append(v.path("keepAudio").asBoolean() ? " 保留音轨" : " 去除音轨");
            sb.append("；");
        }
        if (c.path("audio").isObject()) sb.append("音频 ").append(c.path("audio").path("format").asText().toUpperCase(Locale.ROOT)).append("；");
        JsonNode im = c.path("image");
        if (im.isObject()) {
            sb.append("图片");
            if (im.has("format")) sb.append(" ").append(im.path("format").asText().toUpperCase(Locale.ROOT));
            if (im.has("maxBytes")) sb.append(" ≤ ").append(Math.round(im.path("maxBytes").asLong() / 1024.0)).append(" KB");
            sb.append("；");
        }
        if (c.path("document").isObject()) sb.append("文档 PDF；");
        return sb.toString();
    }

    private static String lower(JsonNode n) {
        return n.isTextual() ? n.asText().trim().toLowerCase(Locale.ROOT).replace(".", "") : "";
    }
}
