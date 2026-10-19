package com.houqing.tools.video_compressor_backend.spec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Reads a participant list from an .xlsx: one header row, one person per following row. Values are taken as the
 * text Excel would display (cached formula results included). The roster never leaves the server: it is not sent
 * to the language model, which only ever sees the public notice.
 */
public final class RosterParser {
    private RosterParser() {}

    static final int MAX_PEOPLE = 1000;
    static final int MAX_FIELDS = 30;
    static final int HEADER_SCAN_ROWS = 20;
    static final List<String> NAME_HINTS = List.of("姓名", "名字", "学生姓名", "选手姓名", "name");
    // not "序号": 1, 2, 3 would match any digit in a file name
    static final List<String> ID_HINTS = List.of("学号", "编号", "工号", "报名号", "准考证号");

    /**
     * @param headerRow 1-based header row, or null to detect (first row with ≥ 2 non-empty text cells)
     * @param nameField column to use as the person's name, or null to detect
     */
    public static ObjectNode parse(Path xlsx, Integer headerRow, String nameField, ObjectMapper mapper) {
        try (InputStream in = Files.newInputStream(xlsx); Workbook wb = new XSSFWorkbook(in)) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter(Locale.SIMPLIFIED_CHINESE);
            FormulaEvaluator eval = wb.getCreationHelper().createFormulaEvaluator();
            int header = headerRow != null ? headerRow - 1 : detectHeader(sheet, fmt, eval);
            if (header < 0) throw new ApiException(ErrorCode.VALIDATION_FAILED, "没有找到表头行：请确认第一张工作表的表头至少有两列文字");
            Row h = sheet.getRow(header);
            if (h == null) throw new ApiException(ErrorCode.VALIDATION_FAILED, "第 " + (header + 1) + " 行是空行，不能作为表头");

            List<String> fields = new ArrayList<>();
            Map<Integer, String> colField = new LinkedHashMap<>();
            Set<String> seen = new HashSet<>();
            for (Cell c : h) {
                String name = fmt.formatCellValue(c, eval).trim().replaceAll("[{}]", "");
                if (name.isEmpty()) continue;
                String unique = name;
                for (int k = 2; !seen.add(unique); k++) unique = name + "(" + k + ")";
                if (fields.size() >= MAX_FIELDS) break;
                fields.add(unique);
                colField.put(c.getColumnIndex(), unique);
            }
            if (fields.size() < 1) throw new ApiException(ErrorCode.VALIDATION_FAILED, "表头为空");

            String name = nameField != null ? nameField : pick(fields, NAME_HINTS);
            if (name == null || !fields.contains(name)) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, nameField != null
                        ? "名单中没有列「" + nameField + "」" : "没有识别出姓名列，请指定姓名所在的列",
                        Map.of("fields", Map.of("nameField", "可选：" + String.join("、", fields))));
            }
            String idField = pick(fields.stream().filter(f -> !f.equals(name)).toList(), ID_HINTS);

            ObjectNode out = mapper.createObjectNode();
            out.put("sheet", sheet.getSheetName());
            out.put("headerRow", header + 1);
            ArrayNode fa = out.putArray("fields");
            fields.forEach(fa::add);
            out.put("nameField", name);
            if (idField != null) out.put("idField", idField);
            ArrayNode people = out.putArray("people");
            ArrayNode warnings = out.putArray("warnings");
            Map<String, Integer> names = new HashMap<>();
            for (int r = header + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                ObjectNode values = mapper.createObjectNode();
                boolean any = false;
                for (var e : colField.entrySet()) {
                    Cell c = row.getCell(e.getKey());
                    String v = c == null ? "" : fmt.formatCellValue(c, eval).trim();
                    values.put(e.getValue(), v);
                    any |= !v.isEmpty();
                }
                if (!any) continue;
                if (values.path(name).asText().isEmpty()) {
                    warnings.add("第 " + (r + 1) + " 行没有姓名，已跳过");
                    continue;
                }
                if (people.size() >= MAX_PEOPLE) {
                    warnings.add("名单超过 " + MAX_PEOPLE + " 人，其余行未导入");
                    break;
                }
                ObjectNode p = people.addObject();
                p.put("key", String.format("p%04d", people.size()));
                p.put("row", r + 1);
                p.set("values", values);
                names.merge(values.path(name).asText(), 1, Integer::sum);
            }
            names.forEach((n, c) -> {
                if (c > 1) warnings.add("名单中有 " + c + " 人叫「" + n + "」：按姓名自动匹配会避开他们，需要按" + (idField == null ? "文件夹手动" : idField) + "区分");
            });
            if (people.isEmpty()) throw new ApiException(ErrorCode.VALIDATION_FAILED, "表头下方没有任何人员数据");
            return out;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(ErrorCode.DOCUMENT_CORRUPTED, "名单文件无法解析");
        }
    }

    private static int detectHeader(Sheet sheet, DataFormatter fmt, FormulaEvaluator eval) {
        for (int r = sheet.getFirstRowNum(); r <= Math.min(sheet.getLastRowNum(), sheet.getFirstRowNum() + HEADER_SCAN_ROWS); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            int texts = 0;
            for (Cell c : row) {
                if (c.getCellType() == CellType.STRING && !fmt.formatCellValue(c, eval).isBlank()) texts++;
            }
            if (texts >= 2) return r;
        }
        return -1;
    }

    private static String pick(List<String> fields, List<String> hints) {
        for (String hint : hints) {
            for (String f : fields) if (f.equalsIgnoreCase(hint)) return f;
        }
        for (String hint : hints) {
            for (String f : fields) if (f.toLowerCase(Locale.ROOT).contains(hint.toLowerCase(Locale.ROOT))) return f;
        }
        return null;
    }
}
