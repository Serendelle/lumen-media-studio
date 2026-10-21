package com.houqing.tools.video_compressor_backend.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Explicit allowlists and bounded values, shared by settings, row writes and exports. */
final class FormSchema {
    static final Set<String> TYPES = Set.of("TEXT", "LONG_TEXT", "NUMBER", "SELECT", "DATE", "CHECKBOX");
    private FormSchema() {}

    static ObjectNode defaults(ObjectMapper mapper) {
        ObjectNode n = mapper.createObjectNode().put("viewMode", "OWN").put("editMode", "OWN")
                .put("accepting", true).put("allowMultiple", false).put("allowComments", true);
        n.putArray("fields"); return n;
    }

    static ObjectNode config(JsonNode input, ObjectMapper mapper) {
        if (input == null || !input.isObject()) throw invalid("表格设置无效");
        String view = input.path("viewMode").asText(), edit = input.path("editMode").asText();
        if (!Set.of("OWN", "ALL").contains(view) || !Set.of("OWN", "ALL", "DESIGNATED", "LOCKED").contains(edit))
            throw invalid("请选择有效的查看和编辑范围");
        if ("ALL".equals(edit) && !"ALL".equals(view)) throw invalid("所有人编辑时，需要允许参与者查看全表");
        ObjectNode out = defaults(mapper).put("viewMode", view).put("editMode", edit);
        for (String flag : List.of("accepting", "allowMultiple", "allowComments")) {
            if (!input.path(flag).isBoolean()) throw invalid("表格开关设置无效");
            out.put(flag, input.path(flag).asBoolean());
        }
        JsonNode fields = input.path("fields");
        if (!fields.isArray() || fields.size() > 20) throw invalid("最多设置 20 个信息字段");
        Set<String> keys = new HashSet<>(), labels = new HashSet<>();
        var arr = out.putArray("fields");
        for (JsonNode f : fields) {
            String key = f.path("key").asText(), label = text(f.path("label").asText(), 40, "字段名称", true);
            String type = f.path("type").asText();
            if (!key.matches("[a-z][a-z0-9_]{0,39}") || !keys.add(key) || !labels.add(label)) throw invalid("字段标识或名称重复 / 无效");
            if (!TYPES.contains(type) || !f.path("required").isBoolean()) throw invalid("字段类型或必填设置无效");
            ObjectNode field = arr.addObject().put("key", key).put("label", label).put("type", type)
                    .put("required", f.path("required").asBoolean());
            if ("SELECT".equals(type)) {
                if (!f.path("options").isArray() || f.path("options").isEmpty() || f.path("options").size() > 30) throw invalid("选择字段需设置 1-30 个选项");
                Set<String> options = new LinkedHashSet<>();
                for (JsonNode opt : f.path("options")) {
                    if (!opt.isTextual() || !options.add(text(opt.asText(), 80, "选项", true))) throw invalid("选项不可重复");
                }
                field.set("options", mapper.valueToTree(options));
            }
        }
        return out;
    }

    static ObjectNode values(JsonNode input, JsonNode config, ObjectMapper mapper, boolean enforceRequired) {
        if (input == null || !input.isObject() || input.toString().length() > 16000) throw invalid("信息格式无效或内容过长");
        Map<String, JsonNode> fields = new LinkedHashMap<>(); config.path("fields").forEach(f -> fields.put(f.path("key").asText(), f));
        input.fieldNames().forEachRemaining(k -> { if (!fields.containsKey(k)) throw invalid("存在未定义的字段：" + k); });
        ObjectNode out = mapper.createObjectNode();
        for (var entry : fields.entrySet()) {
            String key = entry.getKey(), label = entry.getValue().path("label").asText(), type = entry.getValue().path("type").asText();
            JsonNode value = input.get(key);
            boolean empty = value == null || value.isNull() || (value.isTextual() && value.asText().isBlank());
            if (empty) {
                if (enforceRequired && entry.getValue().path("required").asBoolean()) throw invalid("请填写「" + label + "」");
                out.putNull(key); continue;
            }
            if ("CHECKBOX".equals(type)) {
                if (!value.isBoolean()) throw invalid(label + "需为勾选值");
                if (enforceRequired && entry.getValue().path("required").asBoolean() && !value.asBoolean()) throw invalid("请勾选「" + label + "」");
                out.put(key, value.asBoolean()); continue;
            }
            if (!value.isTextual() && !("NUMBER".equals(type) && value.isNumber())) throw invalid(label + "格式不正确");
            String s = text(value.asText(), "LONG_TEXT".equals(type) ? 2000 : 500, label, true);
            switch (type) {
                case "NUMBER" -> {
                    try {
                        if (!s.matches("-?\\d{1,12}(\\.\\d{1,6})?")) throw new NumberFormatException();
                        BigDecimal n = new BigDecimal(s).stripTrailingZeros();
                        if (n.precision() > 15) throw new NumberFormatException();
                        out.put(key, n.toPlainString());
                    } catch (NumberFormatException e) { throw invalid(label + "需为数字，最多 12 位整数、6 位小数及 15 位有效数字"); }
                }
                case "SELECT" -> {
                    if (java.util.stream.StreamSupport.stream(entry.getValue().path("options").spliterator(), false).noneMatch(o -> o.asText().equals(s)))
                        throw invalid(label + "不在可选范围内");
                    out.put(key, s);
                }
                case "DATE" -> {
                    try { if (!s.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException(); LocalDate.parse(s); }
                    catch (RuntimeException e) { throw invalid(label + "需为有效的 YYYY-MM-DD 日期"); }
                    out.put(key, s);
                }
                default -> out.put(key, s);
            }
        }
        return out;
    }

    static String text(String value, int max, String label, boolean required) {
        String s = value == null ? "" : value.trim();
        if ((required && s.isBlank()) || s.length() > max || s.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'))
            throw invalid(label + "为空、过长或包含无效字符");
        return s;
    }
    static ApiException invalid(String message) { return new ApiException(ErrorCode.VALIDATION_FAILED, message); }
}
