package com.houqing.tools.video_compressor_backend.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import com.houqing.tools.video_compressor_backend.media.CancellationToken;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Editable content conversion. It extracts real local text/table values rather than disguising page images
 * as editable documents. It does not run OCR, macros, formulas, JavaScript, or retrieve external resources.
 * Complex layout and PDF table semantics cannot be recovered reliably; the report states those limitations.
 */
@Service
public class EditableDocumentService {
    private static final int MAX_PAGES = 200;
    private static final int MAX_CELLS = 100_000;
    private static final int MAX_TEXT = 1_000_000;
    private static final int MAX_SECTIONS = 200;
    private static final Set<String> SOURCES = Set.of("pdf", "html", "docx", "xlsx");
    private static final Set<String> TARGETS = Set.of("html", "docx", "xlsx");
    private final ObjectMapper mapper;

    public EditableDocumentService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    private record Block(String text, List<List<String>> rows) {
        static Block paragraph(String value) { return new Block(value, List.of()); }
        static Block table(List<List<String>> rows) { return new Block(null, rows); }
        boolean isTable() { return text == null; }
    }

    private record Section(String title, List<Block> blocks, int pdfPage) {}
    private record Content(List<Section> sections, int pages, Budget budget, List<String> warnings) {}

    private static final class Budget {
        int characters;
        int cells;
        int scannedCells;
        int tables;

        String text(String value) {
            String actual = value == null ? "" : value;
            if (actual.length() > MAX_TEXT - characters) throw limit("可编辑转换最多提取100万字符，请拆分文档");
            characters += actual.length();
            return actual;
        }

        void cell() {
            if (++cells > MAX_CELLS) throw limit("可编辑转换最多处理10万个单元格，请拆分表格");
        }

        void scanCell() {
            if (++scannedCells > MAX_CELLS) throw limit("可编辑转换最多扫描10万个单元格（含隐藏范围），请拆分表格");
        }
    }

    public ObjectNode convert(Path source, String sourceFormat, String targetFormat, Path output,
                              CancellationToken token) {
        String from = normalize(sourceFormat), to = normalize(targetFormat);
        if (!SOURCES.contains(from) || !TARGETS.contains(to) || from.equals(to)) {
            throw new ApiException(ErrorCode.CAPABILITY_UNSUPPORTED, "该方向不支持可编辑内容转换");
        }
        if (source.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "转换结果不能覆盖原始文件");
        }
        check(token);
        try {
            long size = Files.size(source);
            long max = from.equals("pdf") ? 200L * 1024 * 1024 : from.equals("html") ? 8L * 1024 * 1024 : 64L * 1024 * 1024;
            if (size > max) throw limit("该文件超过可编辑内容转换的大小限制，请拆分或使用保真模式");
            if (from.equals("docx") || from.equals("xlsx")) OoxmlInspector.validate(source, from);
            Content content = switch (from) {
                case "pdf" -> readPdf(source, token);
                case "docx" -> readDocx(source, token);
                case "xlsx" -> readXlsx(source, token);
                case "html" -> readHtml(source, token);
                default -> throw new ApiException(ErrorCode.CAPABILITY_UNSUPPORTED);
            };
            if (!hasContent(content)) {
                throw new ApiException(ErrorCode.CAPABILITY_UNSUPPORTED,
                        "未找到可提取的文字或表格；扫描件、纯图片文档请使用版式保真模式，本次未执行OCR");
            }
            if (output.getParent() != null) Files.createDirectories(output.getParent());
            switch (to) {
                case "docx" -> writeDocx(content, output, token);
                case "xlsx" -> writeXlsx(content, output, token);
                case "html" -> writeHtml(content, output, token);
                default -> throw new ApiException(ErrorCode.CAPABILITY_UNSUPPORTED);
            }
            verify(output, to, token);
            ObjectNode report = mapper.createObjectNode();
            report.put("mode", "editable");
            report.put("sourceFormat", from);
            report.put("targetFormat", to);
            report.put("textCharacters", content.budget.characters);
            report.put("sourceCells", content.budget.cells);
            report.put("tables", content.budget.tables);
            report.put("sections", content.sections.size());
            if (content.pages > 0) report.put("sourcePageCount", content.pages);
            report.put("ocrPerformed", false);
            report.put("verified", true);
            var warnings = report.putArray("warnings");
            warnings.add("可编辑模式保留实际文字和表格值，不保证字体、分页、合并单元格、图表或复杂版式与原件一致；需要外观一致请选保真模式。");
            content.warnings.forEach(warnings::add);
            return report;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(ErrorCode.DOCUMENT_CONVERSION_FAILED, "可编辑内容转换失败，请检查文档结构或改用版式保真模式");
        }
    }

    private Content readPdf(Path source, CancellationToken token) throws IOException {
        Budget budget = new Budget();
        List<Section> sections = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        try (var doc = PdfFacts.open(source)) {
            int pages = doc.getNumberOfPages();
            if (pages < 1) throw new ApiException(ErrorCode.DOCUMENT_CORRUPTED, "PDF没有可读取页面");
            if (pages > MAX_PAGES) throw limit("可编辑PDF转换最多200页，请先拆分文档");
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            int emptyPages = 0;
            for (int page = 1; page <= pages; page++) {
                check(token);
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                BoundedTextWriter writer = new BoundedTextWriter(MAX_TEXT - budget.characters, token);
                stripper.writeText(doc, writer);
                String text = budget.text(writer.toString());
                List<Block> blocks = new ArrayList<>();
                for (String line : text.split("\\R", -1)) {
                    if (!line.isBlank()) blocks.add(Block.paragraph(line.strip()));
                }
                if (blocks.isEmpty()) emptyPages++;
                sections.add(new Section("第" + page + "页", blocks, page));
            }
            if (emptyPages > 0) warnings.add(emptyPages + "页没有可提取的文字，可能为扫描页或空白页；这些页面未进行OCR。");
            warnings.add("PDF内容按实际文字位置和行读取；表格拆列仅依据制表符或连续空格，不代表准确恢复原表格结构。Excel结果保留实际页码和原文行号。");
            return new Content(sections, pages, budget, warnings);
        }
    }

    private Content readDocx(Path source, CancellationToken token) throws Exception {
        Budget budget = new Budget();
        List<Block> blocks = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        try (OPCPackage pkg = OPCPackage.open(source.toFile(), PackageAccess.READ); XWPFDocument doc = new XWPFDocument(pkg)) {
            for (IBodyElement element : doc.getBodyElements()) {
                check(token);
                if (element instanceof XWPFParagraph paragraph) {
                    addParagraph(blocks, budget, paragraph.getText());
                } else if (element instanceof XWPFTable table) {
                    List<List<String>> rows = new ArrayList<>();
                    for (XWPFTableRow row : table.getRows()) {
                        check(token);
                        List<String> values = new ArrayList<>();
                        for (XWPFTableCell cell : row.getTableCells()) {
                            budget.cell();
                            values.add(budget.text(cell.getText()));
                        }
                        rows.add(values);
                    }
                    budget.tables++;
                    blocks.add(Block.table(rows));
                }
            }
            if (!doc.getAllPictures().isEmpty()) warnings.add("可编辑模式未保留Word中的图片和图形；可使用保真模式保留页面外观。");
            if (!doc.getHeaderList().isEmpty() || !doc.getFooterList().isEmpty()) warnings.add("本次读取正文和正文表格，页眉、页脚及批注未纳入内容转换。");
        }
        return new Content(List.of(new Section("正文", blocks, 0)), 0, budget, warnings);
    }

    private Content readXlsx(Path source, CancellationToken token) throws Exception {
        Budget budget = new Budget();
        List<Section> sections = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        int formulas = 0;
        try (Workbook workbook = WorkbookFactory.create(source.toFile(), null, true)) {
            for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
                check(token);
                if (workbook.isSheetHidden(index) || workbook.isSheetVeryHidden(index)) continue;
                if (sections.size() >= MAX_SECTIONS) throw limit("可编辑转换最多200个可见工作表");
                Sheet sheet = workbook.getSheetAt(index);
                List<List<String>> rows = new ArrayList<>();
                for (Row row : sheet) {
                    check(token);
                    if (row.getZeroHeight()) continue;
                    List<String> values = new ArrayList<>();
                    for (int col = 0; col < Math.max(0, row.getLastCellNum()); col++) {
                        budget.scanCell();
                        if (sheet.isColumnHidden(col)) continue;
                        budget.cell();
                        Cell cell = row.getCell(col);
                        if (cell != null && cell.getCellType() == CellType.FORMULA) formulas++;
                        values.add(budget.text(cellValue(cell, formatter)));
                    }
                    if (values.stream().anyMatch(value -> !value.isBlank())) rows.add(values);
                }
                budget.tables++;
                sections.add(new Section(sheet.getSheetName(), List.of(Block.table(rows)), 0));
            }
        }
        if (formulas > 0) warnings.add("读取了" + formulas + "个公式单元格的文件内缓存值，未执行公式、外部链接或宏；需要最新结果请先在Excel重算并保存。");
        warnings.add("只读取可见工作表、行和列中的单元格值；图表、图片、条件格式及合并样式不重建。");
        return new Content(sections, 0, budget, warnings);
    }

    private Content readHtml(Path source, CancellationToken token) throws IOException {
        String input = Files.readString(source, StandardCharsets.UTF_8);
        if (input.chars().filter(ch -> ch == '<').count() > 50_000) throw limit("HTML标记数量过多，请拆分文档或使用保真模式");
        HtmlSanitizer.Result safe = HtmlSanitizer.sanitize(input, null);
        var html = Jsoup.parse(safe.html());
        html.select("[hidden], [aria-hidden=true]").remove();
        Budget budget = new Budget();
        List<Block> blocks = new ArrayList<>();
        htmlBlocks(html.body(), blocks, budget, token, 0);
        List<String> warnings = new ArrayList<>();
        if (safe.totalRemoved() > 0) warnings.add("已移除" + safe.totalRemoved() + "项脚本、嵌入对象或外部/本地资源引用，转换不访问网络。");
        if (!html.select("img,svg,canvas").isEmpty()) warnings.add("可编辑模式读取静态HTML正文与表格，不保留图片、绘图及动态内容；需要页面外观请选保真模式。");
        return new Content(List.of(new Section(html.title().isBlank() ? "正文" : html.title(), blocks, 0)), 0, budget, warnings);
    }

    private void htmlBlocks(Element parent, List<Block> blocks, Budget budget, CancellationToken token, int depth) {
        if (depth > 100) throw limit("HTML嵌套层级过深");
        for (Node child : parent.childNodes()) {
            check(token);
            if (child instanceof TextNode text) {
                addParagraph(blocks, budget, text.text());
                continue;
            }
            if (!(child instanceof Element element)) continue;
            String tag = element.normalName();
            if (Set.of("style", "head", "script", "noscript", "svg", "canvas", "img").contains(tag)) continue;
            if (element.attr("style").replaceAll("\\s", "").toLowerCase(Locale.ROOT).matches(".*(?:display:none|visibility:hidden).*")) continue;
            if (tag.equals("table")) {
                List<List<String>> rows = new ArrayList<>();
                for (Element row : element.select("tr")) {
                    check(token);
                    if (row.closest("table") != element) continue;
                    List<String> values = new ArrayList<>();
                    for (Element cell : row.children()) {
                        if (!Set.of("th", "td").contains(cell.normalName())) continue;
                        budget.cell();
                        values.add(budget.text(cell.text()));
                    }
                    if (!values.isEmpty()) rows.add(values);
                }
                budget.tables++;
                blocks.add(Block.table(rows));
            } else if (Set.of("p", "pre", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote").contains(tag)
                    && element.select("table").isEmpty()) {
                addParagraph(blocks, budget, element.wholeText());
            } else {
                htmlBlocks(element, blocks, budget, token, depth + 1);
            }
        }
    }

    private void writeDocx(Content content, Path output, CancellationToken token) throws IOException {
        try (XWPFDocument doc = new XWPFDocument()) {
            int gridCells = 0;
            for (int sectionIndex = 0; sectionIndex < content.sections.size(); sectionIndex++) {
                Section section = content.sections.get(sectionIndex);
                check(token);
                if (content.sections.size() > 1 || section.pdfPage > 0) {
                    XWPFParagraph heading = doc.createParagraph();
                    if (sectionIndex > 0 && section.pdfPage > 0) heading.setPageBreak(true);
                    XWPFRun run = heading.createRun();
                    run.setBold(true);
                    run.setText(section.title);
                }
                for (Block block : section.blocks) {
                    check(token);
                    if (!block.isTable()) {
                        XWPFRun run = doc.createParagraph().createRun();
                        run.setFontFamily("Microsoft YaHei");
                        String[] lines = block.text.split("\\R", -1);
                        for (int line = 0; line < lines.length; line++) {
                            if (line > 0) run.addBreak();
                            run.setText(lines[line]);
                        }
                    } else if (!block.rows.isEmpty()) {
                        int columns = block.rows.stream().mapToInt(List::size).max().orElse(1);
                        if ((long) block.rows.size() * columns > MAX_CELLS - gridCells) throw limit("转换后的Word表格网格超过10万个单元格，请拆分文档");
                        gridCells += block.rows.size() * columns;
                        XWPFTable table = doc.createTable(block.rows.size(), Math.max(1, columns));
                        for (int row = 0; row < block.rows.size(); row++) {
                            check(token);
                            for (int col = 0; col < block.rows.get(row).size(); col++) table.getRow(row).getCell(col).setText(block.rows.get(row).get(col));
                        }
                    }
                }
            }
            try (var out = Files.newOutputStream(output)) { doc.write(out); }
        }
    }

    private void writeXlsx(Content content, Path output, CancellationToken token) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Set<String> usedNames = new HashSet<>();
            int totalCells = 0;
            for (Section section : content.sections) {
                check(token);
                Sheet sheet = workbook.createSheet(sheetName(section.title, usedNames));
                int rowIndex = 0, sourceLine = 0;
                if (section.pdfPage > 0) {
                    Row header = sheet.createRow(rowIndex++);
                    for (int col = 0; col < 3; col++) header.createCell(col, CellType.STRING).setCellValue(List.of("页码", "原文行号", "文本/拆分列").get(col));
                    totalCells += 3;
                }
                for (Block block : section.blocks) {
                    List<List<String>> rows;
                    if (block.isTable()) rows = block.rows;
                    else if (section.pdfPage > 0) rows = List.of(Arrays.asList(block.text.split("\\t+| {2,}", -1)));
                    else rows = List.of(List.of(block.text));
                    for (List<String> values : rows) {
                        check(token);
                        Row row = sheet.createRow(rowIndex++);
                        int offset = section.pdfPage > 0 ? 2 : 0;
                        if (values.size() + offset > 16384) throw limit("目标表格超过Excel列数限制");
                        if (section.pdfPage > 0) {
                            row.createCell(0, CellType.NUMERIC).setCellValue(section.pdfPage);
                            row.createCell(1, CellType.NUMERIC).setCellValue(++sourceLine);
                            totalCells += 2;
                        }
                        for (int col = 0; col < values.size(); col++) {
                            if (++totalCells > MAX_CELLS) throw limit("转换后的表格超过10万个单元格，请拆分文档");
                            String value = values.get(col);
                            if (value.length() > 32767) throw limit("某段文字超过Excel单元格32767字符限制，请改用Word或HTML");
                            // Always STRING: a source value starting with =, +, -, or @ never becomes a formula.
                            row.createCell(col + offset, CellType.STRING).setCellValue(value);
                        }
                    }
                }
                if (section.pdfPage > 0) sheet.createFreezePane(0, 1);
                sheet.setColumnWidth(0, section.pdfPage > 0 ? 12 * 256 : 28 * 256);
                if (section.pdfPage > 0) sheet.setColumnWidth(2, 60 * 256);
            }
            try (var out = Files.newOutputStream(output)) { workbook.write(out); }
        }
    }

    private void writeHtml(Content content, Path output, CancellationToken token) throws IOException {
        var document = org.jsoup.nodes.Document.createShell("");
        document.outputSettings().charset(StandardCharsets.UTF_8);
        document.head().appendElement("meta").attr("charset", "utf-8");
        document.head().appendElement("meta").attr("http-equiv", "Content-Security-Policy").attr("content", HtmlSanitizer.CSP);
        document.title("文档转换结果");
        document.head().appendElement("style").text("body{font-family:sans-serif;line-height:1.6;max-width:1100px;margin:2em auto;padding:0 1em}table{border-collapse:collapse;width:100%;margin:1em 0}td{border:1px solid #bbb;padding:.4em;white-space:pre-wrap}p{white-space:pre-wrap}section+section{margin-top:2em}");
        for (Section section : content.sections) {
            check(token);
            Element wrapper = document.body().appendElement("section");
            if (content.sections.size() > 1 || section.pdfPage > 0) wrapper.appendElement("h2").text(section.title);
            if (section.pdfPage > 0) wrapper.attr("data-source-page", String.valueOf(section.pdfPage));
            for (Block block : section.blocks) {
                check(token);
                if (!block.isTable()) wrapper.appendElement("p").text(block.text);
                else {
                    Element table = wrapper.appendElement("table");
                    for (List<String> row : block.rows) {
                        Element tr = table.appendElement("tr");
                        for (String value : row) tr.appendElement("td").text(value);
                    }
                }
            }
        }
        Files.writeString(output, document.outerHtml(), StandardCharsets.UTF_8);
    }

    private void verify(Path output, String format, CancellationToken token) throws Exception {
        check(token);
        if (!Files.isRegularFile(output) || Files.size(output) == 0) throw new ApiException(ErrorCode.OUTPUT_INVALID);
        boolean nonEmpty = false;
        if (format.equals("docx")) {
            try (var pkg = OPCPackage.open(output.toFile(), PackageAccess.READ); var doc = new XWPFDocument(pkg)) {
                nonEmpty = doc.getParagraphs().stream().anyMatch(p -> !p.getText().isBlank())
                        || doc.getTables().stream().anyMatch(t -> !t.getText().isBlank());
            }
        } else if (format.equals("xlsx")) {
            try (Workbook workbook = WorkbookFactory.create(output.toFile(), null, true)) {
                for (Sheet sheet : workbook) for (Row row : sheet) for (Cell cell : row) {
                    check(token);
                    if (cell.getCellType() == CellType.FORMULA) throw new ApiException(ErrorCode.OUTPUT_INVALID, "转换结果不能包含执行型公式");
                    if (cell.getCellType() == CellType.STRING && !cell.getStringCellValue().isBlank()) nonEmpty = true;
                }
            }
        } else {
            var html = Jsoup.parse(Files.readString(output, StandardCharsets.UTF_8));
            nonEmpty = !html.body().text().isBlank();
            if (!html.select("script,iframe,object,embed,link,[src],[href]").isEmpty()) throw new ApiException(ErrorCode.OUTPUT_INVALID);
        }
        if (!nonEmpty) throw new ApiException(ErrorCode.OUTPUT_INVALID, "转换结果缺少可读取内容");
    }

    private static String cellValue(Cell cell, DataFormatter formatter) {
        if (cell == null) return "";
        if (cell.getCellType() != CellType.FORMULA) return formatter.formatCellValue(cell);
        return switch (cell.getCachedFormulaResultType()) {
            case STRING -> cell.getStringCellValue();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case NUMERIC -> formatter.formatRawCellContents(cell.getNumericCellValue(), cell.getCellStyle().getDataFormat(), cell.getCellStyle().getDataFormatString());
            case ERROR -> FormulaError.forInt(cell.getErrorCellValue()).getString();
            default -> "";
        };
    }

    private static boolean hasContent(Content content) {
        return content.sections.stream().flatMap(section -> section.blocks.stream()).anyMatch(block ->
                block.isTable() ? block.rows.stream().flatMap(List::stream).anyMatch(value -> !value.isBlank()) : !block.text.isBlank());
    }

    private static void addParagraph(List<Block> blocks, Budget budget, String text) {
        if (text != null && !text.isBlank()) blocks.add(Block.paragraph(budget.text(text.strip())));
    }

    private static String sheetName(String title, Set<String> used) {
        String base = WorkbookUtil.createSafeSheetName(title == null || title.isBlank() ? "正文" : title);
        if (base.isBlank()) base = "正文";
        String name = base;
        int suffix = 2;
        while (!used.add(name.toLowerCase(Locale.ROOT))) {
            String ending = "(" + suffix++ + ")";
            name = base.substring(0, Math.min(base.length(), 31 - ending.length())) + ending;
        }
        return name;
    }

    private static String normalize(String format) { return format == null ? "" : format.toLowerCase(Locale.ROOT); }
    private static ApiException limit(String message) { return new ApiException(ErrorCode.VALIDATION_FAILED, message); }
    private static void check(CancellationToken token) { if (token != null) token.throwIfCancelled(); }

    private static final class BoundedTextWriter extends Writer {
        private final StringBuilder text = new StringBuilder();
        private final int max;
        private final CancellationToken token;
        BoundedTextWriter(int max, CancellationToken token) { this.max = max; this.token = token; }
        @Override public void write(char[] buffer, int offset, int length) {
            check(token);
            if (length > max - text.length()) throw limit("PDF提取文字超过100万字符，请拆分文档");
            text.append(buffer, offset, length);
        }
        @Override public void flush() {}
        @Override public void close() {}
        @Override public String toString() { return text.toString(); }
    }
}
