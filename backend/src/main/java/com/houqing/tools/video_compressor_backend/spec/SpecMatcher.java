package com.houqing.tools.video_compressor_backend.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.houqing.tools.video_compressor_backend.media.MediaKind;

import java.util.*;

/**
 * Assigns a dropped file to a person and a material slot using only what is visible in its path — deterministic,
 * explainable, and without sending anyone's name to a model. Anything ambiguous is left unmatched with the reason,
 * for the user to resolve; a wrong automatic guess would be worse than none.
 */
public final class SpecMatcher {
    private SpecMatcher() {}

    public record Match(String personKey, String slotKey, String note) {}

    static final Map<String, List<String>> SLOT_HINTS = Map.of(
            "VIDEO", List.of("视频", "video", "作品", "录像"),
            "AUDIO", List.of("音频", "音乐", "audio", "录音", "配乐"),
            "IMAGE", List.of("照片", "图片", "证件照", "photo", "头像", "海报", "截图"),
            "DOCUMENT", List.of("报名表", "表", "文档", "说明", "doc", "pdf", "证明", "承诺书"));

    public static Match match(String relativePath, MediaKind fileKind, JsonNode spec, JsonNode roster) {
        List<String> notes = new ArrayList<>();
        String person = matchPerson(relativePath, roster, notes);
        String slot = matchSlot(relativePath, fileKind, spec, notes);
        return new Match(person, slot, notes.isEmpty() ? null : String.join("；", notes));
    }

    static String matchPerson(String path, JsonNode roster, List<String> notes) {
        if (roster == null || !roster.path("people").isArray()) {
            notes.add("尚未导入名单");
            return null;
        }
        String hay = norm(path);
        String nameField = roster.path("nameField").asText();
        String idField = roster.path("idField").asText(null);
        Map<String, Integer> nameCount = new HashMap<>();
        for (JsonNode p : roster.path("people")) nameCount.merge(p.path("values").path(nameField).asText(), 1, Integer::sum);

        // an id (学号 / 编号, ≥ 4 chars) is the strongest evidence: unique by construction
        if (idField != null) {
            List<JsonNode> byId = new ArrayList<>();
            for (JsonNode p : roster.path("people")) {
                String id = norm(p.path("values").path(idField).asText());
                if (id.length() >= 4 && hay.contains(id)) byId.add(p);
            }
            if (byId.size() == 1) {
                notes.add("按" + idField + "匹配");
                return byId.get(0).path("key").asText();
            }
        }
        List<JsonNode> hits = new ArrayList<>();
        int longest = 0;
        for (JsonNode p : roster.path("people")) {
            String name = norm(p.path("values").path(nameField).asText());
            if (name.length() < 2 || !hay.contains(name)) continue;
            if (name.length() > longest) {
                hits.clear();
                longest = name.length();
            }
            if (name.length() == longest) hits.add(p);
        }
        if (hits.isEmpty()) {
            notes.add("路径中没有出现名单里的姓名");
            return null;
        }
        // "张三丰" in the path also contains "张三": the longest name wins, but only if it is unique
        if (hits.size() > 1) {
            notes.add("路径同时匹配多人（" + String.join("、", hits.stream().map(p -> p.path("values").path(nameField).asText()).toList()) + "），请手动指定");
            return null;
        }
        JsonNode p = hits.get(0);
        String name = p.path("values").path(nameField).asText();
        if (nameCount.getOrDefault(name, 0) > 1) {
            notes.add("名单中有多人叫「" + name + "」，请手动指定");
            return null;
        }
        notes.add("按姓名「" + name + "」匹配");
        return p.path("key").asText();
    }

    static String matchSlot(String path, MediaKind fileKind, JsonNode spec, List<String> notes) {
        List<JsonNode> compatible = new ArrayList<>();
        for (JsonNode s : spec.path("slots")) if (compatible(s.path("kind").asText(), fileKind)) compatible.add(s);
        if (compatible.isEmpty()) {
            notes.add("没有接收" + kindLabel(fileKind) + "的材料项");
            return null;
        }
        if (compatible.size() == 1) return compatible.get(0).path("key").asText();
        String hay = norm(path);
        List<JsonNode> named = new ArrayList<>();
        for (JsonNode s : compatible) {
            String label = norm(s.path("label").asText());
            if (label.length() >= 2 && hay.contains(label)) named.add(s);
        }
        if (named.size() == 1) return named.get(0).path("key").asText();
        // same kind for several slots: same-kind slots are told apart only by their labels; otherwise ask
        Set<String> kinds = new HashSet<>();
        compatible.forEach(s -> kinds.add(s.path("kind").asText()));
        if (kinds.size() > 1) {
            List<JsonNode> exact = compatible.stream().filter(s -> s.path("kind").asText().equals(exactKind(fileKind))).toList();
            if (exact.size() == 1) return exact.get(0).path("key").asText();
        }
        for (JsonNode s : compatible) {
            for (String hint : SLOT_HINTS.getOrDefault(s.path("kind").asText(), List.of())) {
                if (hay.contains(hint)) {
                    List<JsonNode> sameKind = compatible.stream().filter(x -> x.path("kind").asText().equals(s.path("kind").asText())).toList();
                    if (sameKind.size() == 1) return s.path("key").asText();
                }
            }
        }
        notes.add("可放入多个材料项，请手动指定");
        return null;
    }

    /** Which file kinds a slot can take (a video can feed an audio slot by extracting its track). */
    public static boolean compatible(String slotKind, MediaKind fileKind) {
        return switch (slotKind) {
            case "VIDEO" -> fileKind == MediaKind.VIDEO;
            case "AUDIO" -> fileKind == MediaKind.AUDIO || fileKind == MediaKind.VIDEO;
            case "IMAGE" -> fileKind == MediaKind.IMAGE;
            case "DOCUMENT" -> fileKind == MediaKind.DOCUMENT || fileKind == MediaKind.PDF;
            default -> false;
        };
    }

    private static String exactKind(MediaKind k) {
        return k == MediaKind.PDF ? "DOCUMENT" : k.name();
    }

    static String kindLabel(MediaKind k) {
        return switch (k) {
            case VIDEO -> "视频";
            case AUDIO -> "音频";
            case IMAGE -> "图片";
            case DOCUMENT, PDF -> "文档";
            default -> "此类文件";
        };
    }

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-·.、，,（）()\\[\\]【】]+", "");
    }
}
