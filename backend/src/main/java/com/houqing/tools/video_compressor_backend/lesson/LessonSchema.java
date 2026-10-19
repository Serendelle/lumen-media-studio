package com.houqing.tools.video_compressor_backend.lesson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Deterministic side of the learning player. Builds the model's input from real measurements (transcript sentences,
 * OCR lines with positions) and validates its answer: timestamps inside the video, a proper tree, bounded sizes.
 * Every code snippet is compared line by line with what OCR actually saw on screen in that time window, and is
 * labelled accordingly — the UI never presents model-written code as "recognised from the video".
 */
public final class LessonSchema {
    private LessonSchema() {}

    public record OcrLine(String text, int x0, int y0, int x1, int y1) {}

    public record OcrFrame(double t, List<OcrLine> lines) {}

    public record Result(ObjectNode lesson, List<String> errors, List<String> warnings) {
        public boolean valid() { return errors.isEmpty(); }
    }

    static final int MAX_NODES = 80, MAX_DEPTH = 3, MAX_SNIPPETS = 30, MAX_CODE = 6000;
    static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,20}");
    static final Set<String> LANGS = Set.of("python", "javascript", "text");
    static final double EVIDENCE_WINDOW = 30;

    // ---------------- model input ----------------

    public static String transcript(List<AsrClient.Sentence> sentences, int maxChars) {
        StringBuilder sb = new StringBuilder();
        for (AsrClient.Sentence s : sentences) {
            String line = "[" + clock(s.beginMs() / 1000.0) + "] " + s.text() + "\n";
            if (sb.length() + line.length() > maxChars) {
                sb.append("……（后续转写因长度限制省略）\n");
                break;
            }
            sb.append(line);
        }
        return sb.toString();
    }

    /** Screen text per changed frame, code laid out with the indentation implied by the boxes. */
    public static String screenText(List<OcrFrame> frames, int maxChars) {
        StringBuilder sb = new StringBuilder();
        String prev = null;
        for (OcrFrame f : frames) {
            if (f.lines().isEmpty()) continue;
            String block = layout(f);
            if (block.equals(prev)) continue;
            prev = block;
            String entry = "[" + clock(f.t()) + "]\n" + block + "\n";
            if (sb.length() + entry.length() > maxChars) {
                sb.append("……（后续画面文字因长度限制省略）\n");
                break;
            }
            sb.append(entry);
        }
        return sb.toString();
    }

    /**
     * Rebuilds a frame's text top-to-bottom, indenting each line by its left edge measured in character widths
     * (rounded to steps of 2), so code keeps its block structure without the model having to guess it.
     */
    public static String layout(OcrFrame f) {
        List<OcrLine> lines = new ArrayList<>(f.lines());
        lines.sort(Comparator.comparingInt(OcrLine::y0).thenComparingInt(OcrLine::x0));
        List<Double> widths = new ArrayList<>();
        int minX = Integer.MAX_VALUE;
        for (OcrLine l : lines) {
            int len = l.text().length();
            if (len >= 3 && l.x1() > l.x0()) widths.add((l.x1() - l.x0()) / (double) len);
            minX = Math.min(minX, l.x0());
        }
        Collections.sort(widths);
        double cw = widths.isEmpty() ? 0 : widths.get(widths.size() / 2);
        StringBuilder sb = new StringBuilder();
        for (OcrLine l : lines) {
            int indent = cw <= 0 ? 0 : (int) Math.round((l.x0() - minX) / cw / 2.0) * 2;
            sb.append(" ".repeat(Math.max(0, Math.min(40, indent)))).append(l.text()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    // ---------------- validation ----------------

    public static Result validate(JsonNode in, double duration, List<OcrFrame> frames, ObjectMapper mapper) {
        List<String> errors = new ArrayList<>(), warnings = new ArrayList<>();
        ObjectNode out = mapper.createObjectNode();
        out.put("title", clip(text(in.path("title"), "课程"), 80));
        out.put("summary", clip(text(in.path("summary"), ""), 500));
        out.put("duration", Math.round(duration * 10) / 10.0);

        // ---- nodes: a proper tree inside the video's time range
        ArrayNode nodes = out.putArray("nodes");
        JsonNode inNodes = in.path("nodes");
        if (!inNodes.isArray() || inNodes.isEmpty()) errors.add("至少需要一个大纲节点");
        else if (inNodes.size() > MAX_NODES) errors.add("大纲节点不能超过 " + MAX_NODES + " 个");
        Map<String, ObjectNode> byId = new LinkedHashMap<>();
        for (JsonNode n : inNodes) {
            String id = n.path("id").asText("");
            String where = "节点「" + clip(n.path("title").asText(id), 20) + "」";
            if (!ID.matcher(id).matches()) {
                errors.add(where + "：id 不合法");
                continue;
            }
            if (byId.containsKey(id)) {
                errors.add(where + "：id「" + id + "」重复");
                continue;
            }
            double s = n.path("start").asDouble(-1), e = n.path("end").asDouble(-1);
            if (s < 0 || s > duration + 1) errors.add(where + "：开始时间 " + s + " 不在视频范围（0–" + Math.round(duration) + " 秒）");
            if (e <= s || e > duration + 1) errors.add(where + "：结束时间需大于开始时间且不超过视频时长");
            ObjectNode o = mapper.createObjectNode();
            o.put("id", id);
            String parent = n.path("parentId").isTextual() && !n.path("parentId").asText().isBlank() ? n.path("parentId").asText() : null;
            if (parent != null) o.put("parentId", parent);
            else o.putNull("parentId");
            o.put("title", clip(text(n.path("title"), "未命名"), 40));
            o.put("summary", clip(text(n.path("summary"), ""), 300));
            o.put("start", round(Math.max(0, s)));
            o.put("end", round(Math.min(duration, Math.max(e, s))));
            ArrayNode kw = o.putArray("keywords");
            for (JsonNode k : n.path("keywords")) if (kw.size() < 5 && k.isTextual()) kw.add(clip(k.asText(), 20));
            byId.put(id, o);
        }
        for (ObjectNode o : byId.values()) {
            String parent = o.path("parentId").isNull() ? null : o.path("parentId").asText();
            if (parent != null && !byId.containsKey(parent)) {
                errors.add("节点「" + o.path("title").asText() + "」的父节点「" + parent + "」不存在");
                continue;
            }
            int depth = 1;
            Set<String> seen = new HashSet<>();
            for (String p = parent; p != null; p = byId.get(p).path("parentId").isNull() ? null : byId.get(p).path("parentId").asText()) {
                if (!seen.add(p) || !byId.containsKey(p)) {
                    errors.add("节点「" + o.path("title").asText() + "」的层级关系形成了环");
                    break;
                }
                depth++;
            }
            if (depth > MAX_DEPTH) errors.add("节点「" + o.path("title").asText() + "」层级超过 " + MAX_DEPTH + " 层");
            if (parent != null && byId.containsKey(parent)) {
                ObjectNode p = byId.get(parent);
                if (o.path("start").asDouble() < p.path("start").asDouble() - 1 || o.path("end").asDouble() > p.path("end").asDouble() + 1) {
                    warnings.add("「" + o.path("title").asText() + "」的时间超出了所属章节，已收拢到章节范围内");
                    o.put("start", Math.max(o.path("start").asDouble(), p.path("start").asDouble()));
                    o.put("end", Math.min(o.path("end").asDouble(), p.path("end").asDouble()));
                }
            }
        }
        byId.values().stream().sorted(Comparator.comparingDouble(o -> o.path("start").asDouble())).forEach(nodes::add);

        // ---- snippets: compared with what OCR really saw
        ArrayNode snippets = out.putArray("snippets");
        JsonNode inSn = in.path("snippets");
        if (inSn.isArray() && inSn.size() > MAX_SNIPPETS) errors.add("代码片段不能超过 " + MAX_SNIPPETS + " 个");
        int i = 0;
        for (JsonNode c : inSn) {
            i++;
            String where = "代码片段 " + i;
            String code = c.path("code").asText("");
            if (code.isBlank()) {
                errors.add(where + "：代码为空");
                continue;
            }
            if (code.length() > MAX_CODE) errors.add(where + "：代码超过 " + MAX_CODE + " 字符");
            String lang = c.path("language").asText("text").toLowerCase(Locale.ROOT);
            if (lang.equals("js")) lang = "javascript";
            if (!LANGS.contains(lang)) lang = "text";
            double s = c.path("start").asDouble(-1), e = c.path("end").asDouble(-1);
            if (s < 0 || s > duration + 1) errors.add(where + "：开始时间不在视频范围内");
            if (e < s) e = Math.min(duration, s + 30);
            ObjectNode o = snippets.addObject();
            o.put("id", "c" + i);
            o.put("title", clip(text(c.path("title"), "代码片段 " + i), 40));
            o.put("language", lang);
            o.put("start", round(Math.max(0, s)));
            o.put("end", round(Math.min(duration, e)));
            o.put("code", code.length() > MAX_CODE ? code.substring(0, MAX_CODE) : code);
            o.put("explanation", clip(text(c.path("explanation"), ""), 500));
            o.put("runnable", c.path("runnable").asBoolean(false) && !lang.equals("text"));
            evidence(o, frames, warnings);
        }
        return new Result(out, errors, warnings);
    }

    /**
     * Share of the snippet's code lines that OCR saw on screen within ±30 s of its time range, the best frame as
     * evidence, and the resulting source label.
     */
    static void evidence(ObjectNode snippet, List<OcrFrame> frames, List<String> warnings) {
        double from = snippet.path("start").asDouble() - EVIDENCE_WINDOW, to = snippet.path("end").asDouble() + EVIDENCE_WINDOW;
        List<String> codeLines = new ArrayList<>();
        for (String l : snippet.path("code").asText().split("\n")) {
            String n = norm(l);
            if (n.length() >= 3) codeLines.add(n);
        }
        int bestHits = 0;
        double bestT = -1;
        Set<Integer> covered = new HashSet<>();
        for (OcrFrame f : frames) {
            if (f.t() < from || f.t() > to || f.lines().isEmpty()) continue;
            List<String> seen = f.lines().stream().map(l -> norm(l.text())).filter(x -> x.length() >= 3).toList();
            int hits = 0;
            for (int k = 0; k < codeLines.size(); k++) {
                if (matches(codeLines.get(k), seen)) {
                    hits++;
                    covered.add(k);
                }
            }
            if (hits > bestHits) {
                bestHits = hits;
                bestT = f.t();
            }
        }
        double support = codeLines.isEmpty() ? 0 : covered.size() / (double) codeLines.size();
        snippet.put("ocrSupport", Math.round(support * 100) / 100.0);
        if (bestT >= 0) snippet.put("evidenceTime", round(bestT));
        String source = support >= 0.7 ? "SCREEN" : support >= 0.3 ? "SCREEN_COMPLETED" : "MODEL";
        snippet.put("source", source);
        if (source.equals("MODEL")) {
            warnings.add("代码片段「" + snippet.path("title").asText() + "」在对应时间的画面上几乎没有识别到，是 AI 根据讲解整理的，请谨慎参考");
        }
    }

    static boolean matches(String line, List<String> seen) {
        for (String s : seen) {
            if (s.equals(line) || (line.length() >= 6 && (s.contains(line) || line.contains(s) && s.length() >= 6))) return true;
            if (Math.abs(s.length() - line.length()) <= Math.max(2, line.length() / 5) && similarity(s, line) >= 0.8) return true;
        }
        return false;
    }

    static double similarity(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        int max = Math.max(a.length(), b.length());
        return max == 0 ? 1 : 1 - prev[b.length()] / (double) max;
    }

    static String norm(String s) { return s.replaceAll("\\s+", "").toLowerCase(Locale.ROOT); }

    public static String clock(double sec) {
        int s = (int) Math.floor(sec);
        return String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60);
    }

    private static double round(double v) { return Math.round(v * 10) / 10.0; }

    private static String text(JsonNode n, String def) {
        return n.isTextual() && !n.asText().isBlank() ? n.asText().trim() : def;
    }

    private static String clip(String s, int max) { return s.length() <= max ? s : s.substring(0, max); }
}
