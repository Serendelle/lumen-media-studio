package com.houqing.tools.video_compressor_backend.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one definition of a valid material spec. Both the user's edits and the model's draft pass through here, so a
 * spec can only ever contain rules the checker knows how to verify. For a model draft, every rule carries the
 * sentence it came from, and that sentence must literally appear in the notice — otherwise it is flagged.
 *
 * <pre>
 * { "title": "...", "sizeUnit": "DECIMAL"|"BINARY",
 *   "slots": [{ "key": "video", "label": "讲解视频", "kind": "VIDEO", "required": true,
 *               "rules": { "extensions": ["mp4"], "maxBytes": 100000000, "maxHeight": 1080, "maxDurationSec": 300 },
 *               "evidence": "视频格式为 MP4，不超过 100MB" }],
 *   "naming": { "pattern": "{学校}_{姓名}_{材料}", "evidence": "..." },
 *   "notes": ["通知里提到但无法自动检查的要求"] }
 * </pre>
 */
public final class SpecSchema {
    private SpecSchema() {}

    public static final List<String> KINDS = List.of("VIDEO", "AUDIO", "IMAGE", "DOCUMENT");
    static final Map<String, List<String>> EXTENSIONS = Map.of(
            "VIDEO", List.of("mp4", "mov", "mkv", "webm", "avi"),
            "AUDIO", List.of("mp3", "wav", "m4a", "aac", "flac"),
            "IMAGE", List.of("jpg", "png", "webp", "gif", "bmp", "tiff", "heic"),
            "DOCUMENT", List.of("pdf", "docx", "xlsx", "html"));
    static final Map<String, Set<String>> RULES = Map.of(
            "VIDEO", Set.of("extensions", "maxBytes", "maxWidth", "maxHeight", "minDurationSec", "maxDurationSec"),
            "AUDIO", Set.of("extensions", "maxBytes", "minDurationSec", "maxDurationSec"),
            "IMAGE", Set.of("extensions", "maxBytes", "minWidth", "minHeight"),
            "DOCUMENT", Set.of("extensions", "maxBytes"));
    /** Tokens every naming pattern may use besides the roster's own column names. */
    public static final List<String> BUILTIN_TOKENS = List.of("序号", "材料", "原文件名");
    static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]{0,31}");
    static final Pattern TOKEN = Pattern.compile("\\{([^{}]{1,30})}");
    static final long MAX_BYTES = 50L * 1024 * 1024 * 1024;

    public record Result(ObjectNode spec, List<String> errors, List<String> warnings) {
        public boolean valid() { return errors.isEmpty(); }
    }

    /**
     * @param notice     the notice text the evidence must come from (null: evidence is not checked)
     * @param rosterCols roster column names known so far (null: naming tokens are not checked yet)
     */
    public static Result validate(JsonNode in, ObjectMapper mapper, String notice, List<String> rosterCols) {
        List<String> errors = new ArrayList<>(), warnings = new ArrayList<>();
        ObjectNode out = mapper.createObjectNode();
        if (in == null || !in.isObject()) {
            errors.add("规范必须是一个对象");
            return new Result(out, errors, warnings);
        }
        String title = text(in.path("title"));
        out.put("title", title == null ? "" : clip(title, 80));
        String unit = in.path("sizeUnit").asText("DECIMAL");
        out.put("sizeUnit", unit.equals("BINARY") ? "BINARY" : "DECIMAL");
        String normNotice = notice == null ? null : squash(notice);

        ArrayNode slots = out.putArray("slots");
        JsonNode inSlots = in.path("slots");
        if (!inSlots.isArray() || inSlots.isEmpty()) errors.add("至少需要一个材料项");
        else if (inSlots.size() > 20) errors.add("材料项不能超过 20 个");
        Set<String> keys = new HashSet<>();
        int i = 0;
        for (JsonNode s : inSlots) {
            i++;
            String where = "第 " + i + " 个材料项";
            ObjectNode o = slots.addObject();
            String key = text(s.path("key"));
            if (key == null || !KEY.matcher(key).matches()) errors.add(where + "：key 需为小写字母开头的英文、数字或下划线（≤32）");
            else if (!keys.add(key)) errors.add(where + "：key「" + key + "」重复");
            o.put("key", key == null ? "" : key);
            String label = text(s.path("label"));
            if (label == null || label.length() > 40) errors.add(where + "：名称必填且不超过 40 字");
            o.put("label", label == null ? "" : label);
            where = "材料「" + (label == null ? key : label) + "」";
            String kind = text(s.path("kind"));
            if (kind == null || !KINDS.contains(kind)) {
                errors.add(where + "：类型需为 " + KINDS);
                kind = null;
            }
            o.put("kind", kind == null ? "" : kind);
            o.put("required", s.path("required").asBoolean(true));

            ObjectNode rules = o.putObject("rules");
            JsonNode r = s.path("rules");
            if (!r.isMissingNode() && !r.isNull() && !r.isObject()) errors.add(where + "：rules 必须是对象");
            if (kind != null && r.isObject()) {
                Set<String> allowed = RULES.get(kind);
                for (Iterator<String> it = r.fieldNames(); it.hasNext(); ) {
                    String rk = it.next();
                    JsonNode v = r.get(rk);
                    if (v == null || v.isNull()) continue;
                    if (!allowed.contains(rk)) {
                        errors.add(where + "：" + kind + " 不支持规则 " + rk);
                        continue;
                    }
                    if (rk.equals("extensions")) {
                        ArrayNode exts = rules.putArray("extensions");
                        if (!v.isArray() || v.isEmpty()) errors.add(where + "：extensions 需为非空数组");
                        for (JsonNode e : v) {
                            String ext = e.asText("").toLowerCase(Locale.ROOT).replace(".", "").replace("jpeg", "jpg");
                            if (!EXTENSIONS.get(kind).contains(ext)) errors.add(where + "：不支持的格式 " + e.asText() + "（可选 " + EXTENSIONS.get(kind) + "）");
                            else if (!contains(exts, ext)) exts.add(ext);
                        }
                    } else {
                        if (!v.isNumber() || v.asDouble() <= 0 || v.asDouble() != Math.floor(v.asDouble())) {
                            errors.add(where + "：" + rk + " 需为正整数");
                            continue;
                        }
                        long n = v.asLong();
                        if (rk.equals("maxBytes") && n > MAX_BYTES) errors.add(where + "：maxBytes 过大");
                        if ((rk.endsWith("Width") || rk.endsWith("Height")) && n > 7680) errors.add(where + "：" + rk + " 不能超过 7680");
                        if (rk.endsWith("DurationSec") && n > 24 * 3600) errors.add(where + "：" + rk + " 不能超过 24 小时");
                        rules.put(rk, n);
                    }
                }
                if (rules.has("minDurationSec") && rules.has("maxDurationSec")
                        && rules.path("minDurationSec").asLong() > rules.path("maxDurationSec").asLong()) {
                    errors.add(where + "：最短时长不能大于最长时长");
                }
            }
            evidence(s.path("evidence"), o, normNotice, where, warnings);
        }

        ObjectNode naming = out.putObject("naming");
        JsonNode n = in.path("naming");
        String pattern = text(n.path("pattern"));
        if (pattern != null) pattern = normalizeTokens(pattern);
        if (pattern != null) {
            if (pattern.length() > 120) errors.add("命名规则不能超过 120 字");
            String outside = TOKEN.matcher(pattern).replaceAll("");
            if (outside.matches(".*[\\\\/:*?\"<>|].*")) errors.add("命名规则包含文件名不允许的字符 \\ / : * ? \" < > |");
            Matcher m = TOKEN.matcher(pattern);
            while (m.find()) {
                String tok = m.group(1);
                if (BUILTIN_TOKENS.contains(tok)) continue;
                if (rosterCols == null) continue;
                if (!rosterCols.contains(tok)) errors.add("命名规则中的 {" + tok + "} 不是名单里的列，可用：" + String.join("、", rosterCols) + "、" + String.join("、", BUILTIN_TOKENS));
            }
            if (!pattern.contains("{")) warnings.add("命名规则没有任何 {字段}，所有人的文件会同名（会自动加序号区分）");
            naming.put("pattern", pattern);
        } else {
            naming.put("pattern", "{序号}_{姓名}_{材料}");
            if (rosterCols != null && !rosterCols.contains("姓名")) naming.put("pattern", "{序号}_{材料}");
        }
        evidence(n.path("evidence"), naming, normNotice, "命名规则", warnings);

        ArrayNode notes = out.putArray("notes");
        for (JsonNode note : in.path("notes")) {
            String t = text(note);
            if (t != null && notes.size() < 20) notes.add(clip(t, 200));
        }
        return new Result(out, errors, warnings);
    }

    /** A rule's evidence must be a literal excerpt of the notice (whitespace-insensitive) to count as verified. */
    private static void evidence(JsonNode ev, ObjectNode target, String normNotice, String where, List<String> warnings) {
        String e = text(ev);
        if (e == null) return;
        target.put("evidence", clip(e, 300));
        if (normNotice == null) return;
        boolean ok = normNotice.contains(squash(e));
        target.put("evidenceVerified", ok);
        if (!ok) warnings.add(where + "：引用的依据在通知原文中找不到，请人工核对这条规则");
    }

    /** Notices say "材料名称" / "作品类型" where the built-in {材料} is meant; map those words onto it. */
    static final List<String> MATERIAL_ALIASES = List.of("材料名称", "材料名", "材料类型", "文件类型", "材料项");

    static String normalizeTokens(String pattern) {
        String out = pattern;
        for (String a : MATERIAL_ALIASES) out = out.replace("{" + a + "}", "{材料}");
        return out;
    }

    /** Naming tokens that neither the roster nor the built-ins can fill (they would come out empty). */
    public static List<String> unknownTokens(String pattern, List<String> rosterCols) {
        List<String> out = new ArrayList<>();
        if (pattern == null) return out;
        Matcher m = TOKEN.matcher(pattern);
        while (m.find()) {
            String tok = m.group(1);
            if (!BUILTIN_TOKENS.contains(tok) && (rosterCols == null || !rosterCols.contains(tok)) && !out.contains(tok)) out.add(tok);
        }
        return out;
    }

    public static Map<String, JsonNode> slotsByKey(JsonNode spec) {
        Map<String, JsonNode> m = new LinkedHashMap<>();
        for (JsonNode s : spec.path("slots")) m.put(s.path("key").asText(), s);
        return m;
    }

    static String squash(String s) { return s.replaceAll("[\\s\\u3000]+", ""); }

    private static boolean contains(ArrayNode a, String v) {
        for (JsonNode x : a) if (x.asText().equals(v)) return true;
        return false;
    }

    private static String text(JsonNode n) {
        if (n == null || !n.isTextual()) return null;
        String t = n.asText().trim();
        return t.isEmpty() ? null : t;
    }

    private static String clip(String s, int max) { return s.length() <= max ? s : s.substring(0, max); }
}
