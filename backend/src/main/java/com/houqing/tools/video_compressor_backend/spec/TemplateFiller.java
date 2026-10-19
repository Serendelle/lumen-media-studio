package com.houqing.tools.video_compressor_backend.spec;

import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes values into the organiser's own templates without touching anything else: DOCX placeholders
 * ({@code {{字段}}} or {@code ${字段}}) are replaced in place — also when Word split one across several runs — and XLSX
 * rows are appended under the header. The template file itself is only read; output always goes to a new file.
 */
public final class TemplateFiller {
    private TemplateFiller() {}

    public static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([^{}]{1,40}?)\\s*}}|\\$\\{\\s*([^{}]{1,40}?)\\s*}");

    // ---------------- DOCX ----------------

    /** Placeholder names used anywhere in the template (body, tables, headers, footers). */
    public static Set<String> docxPlaceholders(Path template) {
        Set<String> out = new LinkedHashSet<>();
        try (InputStream in = Files.newInputStream(template); XWPFDocument doc = new XWPFDocument(in)) {
            for (XWPFParagraph p : allParagraphs(doc)) {
                Matcher m = PLACEHOLDER.matcher(p.getText());
                while (m.find()) out.add(name(m));
            }
        } catch (Exception e) {
            throw new ApiException(ErrorCode.DOCUMENT_CORRUPTED, "个人表模板无法解析");
        }
        return out;
    }

    /** Fills one copy; returns the placeholders that had no value (they stay visible in the output). */
    public static Set<String> fillDocx(Path template, Path out, Map<String, String> values) throws Exception {
        Set<String> missing = new LinkedHashSet<>();
        try (InputStream in = Files.newInputStream(template); XWPFDocument doc = new XWPFDocument(in)) {
            for (XWPFParagraph p : allParagraphs(doc)) replaceInParagraph(p, values, missing);
            try (OutputStream os = Files.newOutputStream(out)) {
                doc.write(os);
            }
        }
        return missing;
    }

    /** Re-opens a generated DOCX and lists placeholders still present — the independent check after filling. */
    public static Set<String> remainingPlaceholders(Path docx) {
        return docxPlaceholders(docx);
    }

    static List<XWPFParagraph> allParagraphs(XWPFDocument doc) {
        List<XWPFParagraph> out = new ArrayList<>();
        collect(doc.getBodyElements(), out);
        for (XWPFHeader h : doc.getHeaderList()) collect(h.getBodyElements(), out);
        for (XWPFFooter f : doc.getFooterList()) collect(f.getBodyElements(), out);
        return out;
    }

    private static void collect(List<IBodyElement> els, List<XWPFParagraph> out) {
        for (IBodyElement el : els) {
            if (el instanceof XWPFParagraph p) out.add(p);
            else if (el instanceof XWPFTable t) {
                for (XWPFTableRow r : t.getRows()) {
                    for (XWPFTableCell c : r.getTableCells()) collect(c.getBodyElements(), out);
                }
            }
        }
    }

    /**
     * Replaces placeholders by character offsets across runs: the value goes into the run where the placeholder
     * starts (keeping that run's formatting) and the remainder of the placeholder is cut from the following runs.
     */
    static void replaceInParagraph(XWPFParagraph p, Map<String, String> values, Set<String> missing) {
        List<XWPFRun> runs = p.getRuns();
        if (runs.isEmpty()) return;
        List<StringBuilder> texts = new ArrayList<>();
        StringBuilder full = new StringBuilder();
        for (XWPFRun r : runs) {
            String t = r.text();
            texts.add(new StringBuilder(t == null ? "" : t));
            full.append(t == null ? "" : t);
        }
        Matcher m = PLACEHOLDER.matcher(full);
        List<int[]> spans = new ArrayList<>();
        List<String> repl = new ArrayList<>();
        while (m.find()) {
            String key = name(m);
            String v = values.get(key);
            if (v == null) {
                missing.add(key);
                continue;
            }
            spans.add(new int[]{m.start(), m.end()});
            repl.add(v);
        }
        if (spans.isEmpty()) return;
        for (int i = spans.size() - 1; i >= 0; i--) {
            int start = spans.get(i)[0], end = spans.get(i)[1];
            int[] a = locate(texts, start), b = locate(texts, end - 1);
            if (a[0] == b[0]) {
                texts.get(a[0]).replace(a[1], b[1] + 1, repl.get(i));
            } else {
                texts.get(a[0]).replace(a[1], texts.get(a[0]).length(), repl.get(i));
                for (int k = a[0] + 1; k < b[0]; k++) texts.get(k).setLength(0);
                texts.get(b[0]).delete(0, b[1] + 1);
            }
        }
        for (int i = 0; i < runs.size(); i++) setRunText(runs.get(i), texts.get(i).toString());
    }

    /** [run index, offset in run] of a paragraph-level character offset. */
    private static int[] locate(List<StringBuilder> texts, int offset) {
        int pos = 0;
        for (int i = 0; i < texts.size(); i++) {
            int len = texts.get(i).length();
            if (offset < pos + len) return new int[]{i, offset - pos};
            pos += len;
        }
        return new int[]{texts.size() - 1, Math.max(0, texts.get(texts.size() - 1).length() - 1)};
    }

    private static void setRunText(XWPFRun r, String text) {
        CTR ctr = r.getCTR();
        String current = r.text();
        if (Objects.equals(current, text)) return;
        while (ctr.sizeOfTArray() > 1) ctr.removeT(ctr.sizeOfTArray() - 1);
        if (ctr.sizeOfTArray() == 0) r.setText(text);
        else r.setText(text, 0);
    }

    private static String name(Matcher m) {
        return (m.group(1) != null ? m.group(1) : m.group(2)).trim();
    }

    // ---------------- XLSX ----------------

    public record SheetPlan(int headerRow, Map<Integer, String> columns, String sheetName) {}

    /** Finds the header row (given, or the first of 50 rows naming a known column) and maps columns to fields. */
    public static SheetPlan planXlsx(Path template, Integer headerRow1, Collection<String> known) {
        try (InputStream in = Files.newInputStream(template); Workbook wb = new XSSFWorkbook(in)) {
            Sheet s = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            int from = headerRow1 != null ? headerRow1 - 1 : s.getFirstRowNum();
            int to = headerRow1 != null ? headerRow1 - 1 : Math.min(s.getLastRowNum(), s.getFirstRowNum() + 50);
            for (int r = from; r <= to; r++) {
                Row row = s.getRow(r);
                if (row == null) continue;
                Map<Integer, String> cols = new LinkedHashMap<>();
                for (Cell c : row) {
                    String t = fmt.formatCellValue(c).trim();
                    Matcher m = PLACEHOLDER.matcher(t);
                    if (m.matches()) t = name(m);
                    if (known.contains(t)) cols.put(c.getColumnIndex(), t);
                }
                if (!cols.isEmpty()) return new SheetPlan(r, cols, s.getSheetName());
            }
            throw new ApiException(ErrorCode.VALIDATION_FAILED, headerRow1 != null
                    ? "汇总表模板第 " + headerRow1 + " 行没有可识别的列名"
                    : "汇总表模板前 50 行中没有找到与名单列名一致的表头");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(ErrorCode.DOCUMENT_CORRUPTED, "汇总表模板无法解析");
        }
    }

    /**
     * Appends one row per record under the header. A target cell that already holds a value or a formula is never
     * overwritten: the whole fill is refused and the coordinates are reported. Values are written as text, so a
     * value starting with "=" stays a string and can never become a formula.
     */
    public static void fillXlsx(Path template, Path out, SheetPlan plan, List<Map<String, String>> rows) throws Exception {
        try (InputStream in = Files.newInputStream(template); XSSFWorkbook wb = new XSSFWorkbook(in)) {
            Sheet s = wb.getSheetAt(0);
            List<String> conflicts = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                Row row = s.getRow(plan.headerRow() + 1 + i);
                if (row == null) continue;
                for (int col : plan.columns().keySet()) {
                    Cell c = row.getCell(col);
                    if (c != null && (c.getCellType() == CellType.FORMULA
                            || (c.getCellType() != CellType.BLANK && !new DataFormatter().formatCellValue(c).isBlank()))) {
                        conflicts.add(plan.sheetName() + "!" + new CellReference(row.getRowNum(), col).formatAsString(false));
                    }
                }
            }
            if (!conflicts.isEmpty()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "汇总表模板的写入区域已有内容，未覆盖："
                        + String.join("、", conflicts.subList(0, Math.min(8, conflicts.size()))) + (conflicts.size() > 8 ? " 等 " + conflicts.size() + " 处" : ""));
            }
            for (int i = 0; i < rows.size(); i++) {
                int r = plan.headerRow() + 1 + i;
                Row row = s.getRow(r) != null ? s.getRow(r) : s.createRow(r);
                for (var e : plan.columns().entrySet()) {
                    Cell c = row.getCell(e.getKey()) != null ? row.getCell(e.getKey()) : row.createCell(e.getKey());
                    String v = rows.get(i).getOrDefault(e.getValue(), "");
                    if (e.getValue().equals("序号") && v.matches("\\d{1,9}")) c.setCellValue(Integer.parseInt(v));
                    else c.setCellValue(v);
                }
            }
            wb.setForceFormulaRecalculation(true);
            try (OutputStream os = Files.newOutputStream(out)) {
                wb.write(os);
            }
        }
    }

    /** A plain summary workbook when the organiser has no template. */
    public static void newXlsx(Path out, List<String> columns, List<Map<String, String>> rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("汇总");
            CellStyle bold = wb.createCellStyle();
            Font f = wb.createFont();
            f.setBold(true);
            bold.setFont(f);
            Row h = s.createRow(0);
            for (int c = 0; c < columns.size(); c++) {
                Cell cell = h.createCell(c);
                cell.setCellValue(columns.get(c));
                cell.setCellStyle(bold);
            }
            for (int i = 0; i < rows.size(); i++) {
                Row row = s.createRow(i + 1);
                for (int c = 0; c < columns.size(); c++) {
                    String v = rows.get(i).getOrDefault(columns.get(c), "");
                    if (columns.get(c).equals("序号") && v.matches("\\d{1,9}")) row.createCell(c).setCellValue(Integer.parseInt(v));
                    else row.createCell(c).setCellValue(v);
                }
            }
            for (int c = 0; c < columns.size(); c++) s.setColumnWidth(c, Math.min(60, Math.max(8, columns.get(c).length() * 3 + 6)) * 256);
            s.createFreezePane(0, 1);
            try (OutputStream os = Files.newOutputStream(out)) {
                wb.write(os);
            }
        }
    }

    /** How many of the n rows written under the header really hold a value (the independent read-back). */
    public static int countRows(Path xlsx, int headerRow0, int firstCol, int n) throws Exception {
        try (InputStream in = Files.newInputStream(xlsx); Workbook wb = new XSSFWorkbook(in)) {
            Sheet s = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            int filled = 0;
            for (int r = headerRow0 + 1; r <= headerRow0 + n; r++) {
                Row row = s.getRow(r);
                if (row != null && row.getCell(firstCol) != null && !fmt.formatCellValue(row.getCell(firstCol)).isBlank()) filled++;
            }
            return filled;
        }
    }
}
