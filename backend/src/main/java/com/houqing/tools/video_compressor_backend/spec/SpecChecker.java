package com.houqing.tools.video_compressor_backend.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.media.*;
import com.houqing.tools.video_compressor_backend.media.handler.VideoCompressHandler;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Compares one file with one material slot's rules using measured facts (real type from the content, FFprobe for
 * audio/video, pixel size for images). Every violation says whether the platform can fix it with an existing
 * operation, and the fix is expressed as an ordinary task (compress / convert / extract / document.convert), so it
 * runs on the same engine and gets the same output verification as anything else.
 */
@Component
public class SpecChecker {
    static final Map<Integer, String> HEIGHT_PRESETS = Map.of(2160, "2160p", 1440, "1440p", 1080, "1080p", 720, "720p",
            540, "540p", 480, "480p", 360, "360p");

    private final MediaProbe probe;
    private final StorageService storage;
    private final ObjectMapper mapper;

    public SpecChecker(MediaProbe probe, StorageService storage, ObjectMapper mapper) {
        this.probe = probe;
        this.storage = storage;
        this.mapper = mapper;
    }

    /** {status: OK|FIXABLE|INVALID, violations[{rule, label, expected, actual, fixable}], facts{}, fix?{type, params, reason}} */
    public ObjectNode check(MediaFile f, JsonNode slot) {
        ObjectNode out = mapper.createObjectNode();
        ArrayNode v = out.putArray("violations");
        ObjectNode facts = out.putObject("facts");
        String kind = slot.path("kind").asText();
        JsonNode r = slot.path("rules");
        String ext = StorageService.extensionOf(f.getStoredName());
        facts.put("format", ext);
        facts.put("sizeBytes", f.getSizeBytes());

        if (!SpecMatcher.compatible(kind, f.kind())) {
            add(v, "kind", "类型", kindLabel(kind), SpecMatcher.kindLabel(f.kind()), false);
            return finish(out, null);
        }
        MediaProbe.MediaInfo mi = null;
        if (f.kind().isAv()) {
            try {
                mi = probe.probe(storage.pathOf(f));
            } catch (ApiException e) {
                add(v, "readable", "可读取", "可解码的音视频", "无法解析", false);
                return finish(out, null);
            }
            facts.put("duration", Math.round(mi.duration() * 10) / 10.0);
            if (mi.hasVideo()) {
                facts.put("width", mi.width());
                facts.put("height", mi.height());
            }
            facts.put("hasAudio", mi.hasAudio());
        } else if (f.kind() == MediaKind.IMAGE) {
            try {
                MediaProbe.MediaInfo img = probe.probe(storage.pathOf(f));
                facts.put("width", img.width());
                facts.put("height", img.height());
            } catch (ApiException ignored) {
                // pixel size unknown: size rules on dimensions are reported as unverifiable below
            }
        }

        List<String> exts = strings(r.path("extensions"));
        long maxBytes = r.path("maxBytes").asLong(0);
        return switch (kind) {
            case "VIDEO" -> video(out, v, f, mi, ext, exts, maxBytes, r);
            case "AUDIO" -> audio(out, v, f, mi, ext, exts, maxBytes, r);
            case "IMAGE" -> image(out, v, f, facts, ext, exts, maxBytes, r);
            default -> document(out, v, f, ext, exts, maxBytes);
        };
    }

    private ObjectNode video(ObjectNode out, ArrayNode v, MediaFile f, MediaProbe.MediaInfo mi, String ext, List<String> exts,
                             long maxBytes, JsonNode r) {
        boolean extBad = !exts.isEmpty() && !exts.contains(ext);
        boolean canMp4 = exts.isEmpty() || exts.contains("mp4");
        if (extBad) add(v, "extensions", "格式", String.join(" / ", exts).toUpperCase(Locale.ROOT), ext.toUpperCase(Locale.ROOT), canMp4);
        boolean big = maxBytes > 0 && f.getSizeBytes() > maxBytes;
        if (big) add(v, "maxBytes", "体积", "≤ " + human(maxBytes), human(f.getSizeBytes()), true);
        int maxH = r.path("maxHeight").asInt(0), maxW = r.path("maxWidth").asInt(0);
        boolean tall = maxH > 0 && mi.height() > maxH, wide = maxW > 0 && mi.width() > maxW;
        if (tall || wide) add(v, "resolution", "分辨率", (maxW > 0 ? "宽 ≤ " + maxW + " " : "") + (maxH > 0 ? "高 ≤ " + maxH : ""),
                mi.width() + "×" + mi.height(), true);
        duration(v, mi, r);
        if (v.isEmpty()) return finish(out, null);
        if (!onlyFixable(v)) return finish(out, null);

        // one compress task fixes container (always MP4/H.264), size and resolution together
        ObjectNode p = mapper.createObjectNode();
        p.put("codec", "h264");
        p.put("preset", "medium");
        String res = "original";
        if (tall || wide) {
            int targetH = maxH > 0 ? maxH : (int) Math.floor(mi.height() * (maxW / (double) mi.width()));
            res = HEIGHT_PRESETS.keySet().stream().filter(h -> h <= targetH).max(Integer::compare).map(HEIGHT_PRESETS::get).orElse(null);
            if (res == null) {
                markUnfixable(v, "resolution", "分辨率上限低于 360p，无法自动处理");
                return finish(out, null);
            }
        }
        p.put("resolution", res);
        if (maxBytes > 0) {
            // aim 3% under the limit; the handler then proves the result against the real byte count
            double mb = Math.floor(maxBytes * 0.97 / MediaVerifier.MIB * 100) / 100.0;
            p.put("mode", "size");
            p.put("targetSizeMB", mb);
            p.put("audioBitrateKbps", mi.hasAudio() ? 128 : 0);
            CompressionPlanner.Plan plan = VideoCompressHandler.estimate(mi, p);
            if (!plan.feasible()) {
                markUnfixable(v, "maxBytes", "按体积上限压缩会严重损失画质：" + plan.reason());
                return finish(out, null);
            }
        } else {
            p.put("mode", "crf");
            p.put("crf", 23);
        }
        return finish(out, fix("video.compress", p, "压缩为 MP4（H.264）" + (maxBytes > 0 ? "，体积 ≤ " + human(maxBytes) : "")
                + (!"original".equals(res) ? "，分辨率 " + res : "")));
    }

    private ObjectNode audio(ObjectNode out, ArrayNode v, MediaFile f, MediaProbe.MediaInfo mi, String ext, List<String> exts,
                             long maxBytes, JsonNode r) {
        if (!mi.hasAudio()) {
            add(v, "audio", "音轨", "包含声音", "没有音轨", false);
            return finish(out, null);
        }
        boolean fromVideo = f.kind() == MediaKind.VIDEO;
        boolean extBad = fromVideo || (!exts.isEmpty() && !exts.contains(ext));
        String target = exts.isEmpty() ? "mp3" : exts.stream().filter(List.of("mp3", "m4a", "aac", "wav", "flac")::contains).findFirst().orElse(null);
        if (extBad) add(v, "extensions", "格式", exts.isEmpty() ? "音频文件" : String.join(" / ", exts).toUpperCase(Locale.ROOT),
                fromVideo ? "视频文件（含音轨）" : ext.toUpperCase(Locale.ROOT), target != null);
        boolean big = maxBytes > 0 && f.getSizeBytes() > maxBytes;
        boolean lossyTarget = target != null && List.of("mp3", "aac", "m4a").contains(target);
        Integer kbps = null;
        if (lossyTarget && maxBytes > 0 && (big || extBad)) {
            // constant bitrate whose size fits: bytes*8/duration with a 5% margin, capped at 192 kbps
            kbps = (int) Math.min(192, Math.floor(maxBytes * 8.0 / Math.max(1, mi.duration()) / 1000 * 0.95));
        }
        // lossless targets (WAV / FLAC) cannot be made smaller, so an oversize there is not fixable
        if (big) add(v, "maxBytes", "体积", "≤ " + human(maxBytes), human(f.getSizeBytes()), kbps != null && kbps >= 32);
        duration(v, mi, r);
        if (v.isEmpty()) return finish(out, null);
        if (!onlyFixable(v) || target == null || (kbps != null && kbps < 32)) return finish(out, null);
        ObjectNode p = mapper.createObjectNode();
        p.put("format", target);
        if (kbps != null) p.put("bitrateKbps", kbps);
        return finish(out, fix("audio.extract", p, (fromVideo ? "从视频提取音轨" : "转换音频") + "为 " + p.path("format").asText().toUpperCase(Locale.ROOT)
                + (kbps != null ? "，" + kbps + " kbps" : "")));
    }

    private ObjectNode image(ObjectNode out, ArrayNode v, MediaFile f, ObjectNode facts, String ext, List<String> exts,
                             long maxBytes, JsonNode r) {
        List<String> lossy = List.of("jpg", "webp");
        String target = exts.isEmpty() ? ext : (exts.contains(ext) ? ext : exts.stream().filter(List.of("jpg", "png", "webp")::contains).findFirst().orElse(null));
        if (!exts.isEmpty() && !exts.contains(ext)) add(v, "extensions", "格式", String.join(" / ", exts).toUpperCase(Locale.ROOT), ext.toUpperCase(Locale.ROOT), target != null);
        boolean big = maxBytes > 0 && f.getSizeBytes() > maxBytes;
        if (big) add(v, "maxBytes", "体积", "≤ " + human(maxBytes), human(f.getSizeBytes()), target != null && lossy.contains(target));
        int minW = r.path("minWidth").asInt(0), minH = r.path("minHeight").asInt(0);
        if (minW > 0 || minH > 0) {
            if (!facts.has("width")) add(v, "dimensions", "像素尺寸", "宽 ≥ " + minW + " 高 ≥ " + minH, "无法读取", false);
            else if (facts.path("width").asInt() < minW || facts.path("height").asInt() < minH) {
                add(v, "dimensions", "像素尺寸", "宽 ≥ " + minW + " 高 ≥ " + minH, facts.path("width").asInt() + "×" + facts.path("height").asInt(), false);
            }
        }
        if (v.isEmpty()) return finish(out, null);
        if (!onlyFixable(v)) return finish(out, null);
        ObjectNode p = mapper.createObjectNode();
        p.put("tool", "compress");
        p.put("format", target);
        if (big) p.put("targetSizeKB", (int) Math.max(1, Math.floor(maxBytes * 0.97 / 1024)));
        else p.put("quality", 90);
        return finish(out, fix("image.process", p, "转换为 " + target.toUpperCase(Locale.ROOT) + (big ? "，体积 ≤ " + human(maxBytes) : "")));
    }

    private ObjectNode document(ObjectNode out, ArrayNode v, MediaFile f, String ext, List<String> exts, long maxBytes) {
        boolean extBad = !exts.isEmpty() && !exts.contains(ext);
        boolean toPdf = extBad && exts.contains("pdf") && List.of("docx", "xlsx", "html").contains(ext);
        if (extBad) add(v, "extensions", "格式", String.join(" / ", exts).toUpperCase(Locale.ROOT), ext.toUpperCase(Locale.ROOT), toPdf);
        if (maxBytes > 0 && f.getSizeBytes() > maxBytes) add(v, "maxBytes", "体积", "≤ " + human(maxBytes), human(f.getSizeBytes()), false);
        if (v.isEmpty()) return finish(out, null);
        if (!onlyFixable(v)) return finish(out, null);
        ObjectNode p = mapper.createObjectNode();
        p.put("targetFormat", "pdf");
        p.putObject("options");
        return finish(out, fix("document.convert", p, "转换为 PDF"));
    }

    private void duration(ArrayNode v, MediaProbe.MediaInfo mi, JsonNode r) {
        long min = r.path("minDurationSec").asLong(0), max = r.path("maxDurationSec").asLong(0);
        if (min > 0 && mi.duration() < min) add(v, "minDurationSec", "时长", "≥ " + min + " 秒", Math.round(mi.duration()) + " 秒", false);
        if (max > 0 && mi.duration() > max + 0.5) add(v, "maxDurationSec", "时长", "≤ " + max + " 秒", Math.round(mi.duration()) + " 秒", false);
    }

    private ObjectNode fix(String type, ObjectNode params, String reason) {
        ObjectNode fx = mapper.createObjectNode();
        fx.put("type", type);
        fx.set("params", params);
        fx.put("reason", reason);
        return fx;
    }

    private static ObjectNode finish(ObjectNode out, ObjectNode fix) {
        ArrayNode v = (ArrayNode) out.path("violations");
        if (v.isEmpty()) out.put("status", "OK");
        else if (fix != null) {
            out.put("status", "FIXABLE");
            out.set("fix", fix);
        } else out.put("status", "INVALID");
        return out;
    }

    private static boolean onlyFixable(ArrayNode v) {
        for (JsonNode x : v) if (!x.path("fixable").asBoolean()) return false;
        return true;
    }

    private static void markUnfixable(ArrayNode v, String rule, String why) {
        for (JsonNode x : v) {
            if (x.path("rule").asText().equals(rule)) {
                ((ObjectNode) x).put("fixable", false);
                ((ObjectNode) x).put("why", why);
            }
        }
    }

    private static void add(ArrayNode v, String rule, String label, String expected, String actual, boolean fixable) {
        ObjectNode x = v.addObject();
        x.put("rule", rule);
        x.put("label", label);
        x.put("expected", expected);
        x.put("actual", actual);
        x.put("fixable", fixable);
    }

    private static List<String> strings(JsonNode a) {
        return a.isArray() ? java.util.stream.StreamSupport.stream(a.spliterator(), false).map(JsonNode::asText).toList() : List.of();
    }

    static String kindLabel(String k) {
        return switch (k) { case "VIDEO" -> "视频"; case "AUDIO" -> "音频"; case "IMAGE" -> "图片"; default -> "文档"; };
    }

    /** Decimal units, as notices write them ("100MB" = 100,000,000 bytes). */
    static String human(long b) {
        if (b >= 1_000_000_000) return String.format(Locale.ROOT, "%.2f GB", b / 1e9);
        if (b >= 1_000_000) return String.format(Locale.ROOT, "%.1f MB", b / 1e6);
        if (b >= 1_000) return String.format(Locale.ROOT, "%.0f KB", b / 1e3);
        return b + " B";
    }
}
