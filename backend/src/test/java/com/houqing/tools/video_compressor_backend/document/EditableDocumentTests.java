package com.houqing.tools.video_compressor_backend.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import com.houqing.tools.video_compressor_backend.media.CancellationToken;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real PDFBox/POI/jsoup fixture conversions; no model, network, paid converter or external tool. */
class EditableDocumentTests {
    @TempDir Path directory;
    private final EditableDocumentService service = new EditableDocumentService(new ObjectMapper());

    private Path pdf() throws Exception {
        Path file = directory.resolve("source.pdf");
        try (var document = new PDDocument()) {
            for (int page = 1; page <= 2; page++) {
                var actual = new PDPage(); document.addPage(actual);
                try (var content = new PDPageContentStream(document, actual)) {
                    content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(60, 740); content.showText("Actual page " + page);
                    content.newLineAtOffset(0, -24); content.showText("Item  Quantity");
                    content.newLineAtOffset(0, -24); content.showText("Alpha  " + page);
                    content.endText();
                }
            }
            document.save(file.toFile());
        }
        return file;
    }

    private Path word() throws Exception {
        Path file = directory.resolve("source.docx");
        try (var document = new XWPFDocument(); var out = Files.newOutputStream(file)) {
            document.createParagraph().createRun().setText("实际正文 <script>不是脚本</script>");
            var table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("材料"); table.getRow(0).getCell(1).setText("数量");
            table.getRow(1).getCell(0).setText("报名表"); table.getRow(1).getCell(1).setText("=2+3");
            document.write(out);
        }
        return file;
    }

    private Path excel() throws Exception {
        Path file = directory.resolve("source.xlsx");
        try (var workbook = new XSSFWorkbook(); var out = Files.newOutputStream(file)) {
            var sheet = workbook.createSheet("可见资料");
            var header = sheet.createRow(0); header.createCell(0).setCellValue("项目"); header.createCell(1).setCellValue("数量");
            var row = sheet.createRow(1); row.createCell(0).setCellValue("实际数据"); row.createCell(1).setCellFormula("1+1");
            workbook.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(row.getCell(1));
            sheet.createRow(2).createCell(0).setCellValue("=HYPERLINK(\"https://example.invalid\",\"literal\")");
            var hidden = workbook.createSheet("隐藏资料"); hidden.createRow(0).createCell(0).setCellValue("HIDDEN_SECRET");
            workbook.setSheetHidden(1, true);
            var hiddenRow = sheet.createRow(3); hiddenRow.setZeroHeight(true); hiddenRow.createCell(0).setCellValue("HIDDEN_ROW");
            workbook.write(out);
        }
        return file;
    }

    private String digest(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    @Test void pdfToWordContainsActualExtractedTextAndLeavesTheOriginalUntouched() throws Exception {
        Path input = pdf(), output = directory.resolve("converted.docx"); String original = digest(input);
        var result = service.convert(input, "pdf", "docx", output, new CancellationToken());
        assertThat(result.path("verified").asBoolean()).isTrue();
        assertThat(result.path("sourcePageCount").asInt()).isEqualTo(2);
        assertThat(result.path("ocrPerformed").asBoolean()).isFalse();
        try (var in = Files.newInputStream(output); var word = new XWPFDocument(in)) {
            String text = word.getParagraphs().stream().map(p -> p.getText()).reduce("", (a, b) -> a + "\n" + b);
            assertThat(text).contains("Actual page 1", "Actual page 2", "Alpha");
        }
        assertThat(digest(input)).isEqualTo(original);
        try (var reopened = Loader.loadPDF(input.toFile())) { assertThat(reopened.getNumberOfPages()).isEqualTo(2); }
    }

    @Test void pdfToExcelRetainsActualPageAndLineProvenanceWithoutPretendingToKnowTableSemantics() throws Exception {
        Path output = directory.resolve("converted.xlsx");
        var result = service.convert(pdf(), "pdf", "xlsx", output, null);
        assertThat(result.path("warnings").toString()).contains("拆列", "不代表准确恢复");
        try (var workbook = WorkbookFactory.create(output.toFile())) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            for (int page = 0; page < 2; page++) {
                var sheet = workbook.getSheetAt(page);
                assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("页码");
                assertThat(sheet.getRow(1).getCell(0).getNumericCellValue()).isEqualTo(page + 1);
                assertThat(sheet.getRow(1).getCell(1).getNumericCellValue()).isEqualTo(1);
                assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).contains("Actual page " + (page + 1));
            }
        }
    }

    @Test void wordToHtmlAndExcelPreserveTextAndTablesButDoNotTurnValuesIntoScriptsOrFormulas() throws Exception {
        Path source = word(), html = directory.resolve("word.html"), xlsx = directory.resolve("word.xlsx");
        service.convert(source, "docx", "html", html, null);
        var parsed = Jsoup.parse(Files.readString(html));
        assertThat(parsed.body().text()).contains("实际正文", "<script>不是脚本</script>", "报名表", "=2+3");
        assertThat(parsed.select("script,iframe,object,[href],[src]")).isEmpty();
        assertThat(parsed.select("table tr")).hasSize(2);
        service.convert(source, "docx", "xlsx", xlsx, null);
        try (var workbook = WorkbookFactory.create(xlsx.toFile())) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).contains("实际正文");
            assertThat(sheet.getRow(2).getCell(1).getCellType()).isEqualTo(CellType.STRING);
            assertThat(sheet.getRow(2).getCell(1).getStringCellValue()).isEqualTo("=2+3");
        }
    }

    @Test void excelExportsOnlyVisibleValuesAndUsesFormulaCachesWithoutExecutingExternalLinks() throws Exception {
        Path source = excel(), html = directory.resolve("excel.html"), docx = directory.resolve("excel.docx");
        var result = service.convert(source, "xlsx", "html", html, null);
        assertThat(result.path("warnings").toString()).contains("缓存值", "未执行公式");
        var parsed = Jsoup.parse(Files.readString(html));
        assertThat(parsed.body().text()).contains("实际数据", "2", "=HYPERLINK").doesNotContain("HIDDEN_SECRET", "HIDDEN_ROW");
        assertThat(parsed.select("a,script,[src],[href]")).isEmpty();
        service.convert(source, "xlsx", "docx", docx, null);
        try (var in = Files.newInputStream(docx); var document = new XWPFDocument(in)) {
            String text = document.getTables().get(0).getText();
            assertThat(text).contains("实际数据", "2", "=HYPERLINK").doesNotContain("HIDDEN_SECRET", "HIDDEN_ROW");
        }
    }

    @Test void staticHtmlExportsOnlySanitizedParagraphsAndTableValues() throws Exception {
        Path source = directory.resolve("source.html"), docx = directory.resolve("html.docx"), xlsx = directory.resolve("html.xlsx");
        Files.writeString(source, "<!doctype html><title>真实网页</title><p>实际内容</p><script>EVIL_SCRIPT</script>"
                + "<iframe src='http://127.0.0.1/private'></iframe><p hidden>HIDDEN_NODE</p>"
                + "<table><tr><th>名称</th><th>值</th></tr><tr><td>项目甲</td><td>=1+2</td></tr></table>");
        String original = digest(source);
        var result = service.convert(source, "html", "docx", docx, null);
        assertThat(result.path("warnings").toString()).contains("不访问网络");
        try (var in = Files.newInputStream(docx); var document = new XWPFDocument(in)) {
            String paragraphs = document.getParagraphs().stream().map(p -> p.getText()).reduce("", (a, b) -> a + "\n" + b);
            assertThat(paragraphs).contains("实际内容").doesNotContain("EVIL_SCRIPT", "HIDDEN_NODE");
            assertThat(document.getTables().get(0).getText()).contains("项目甲", "=1+2");
        }
        service.convert(source, "html", "xlsx", xlsx, null);
        try (var workbook = WorkbookFactory.create(xlsx.toFile())) {
            var cell = workbook.getSheetAt(0).getRow(2).getCell(1);
            assertThat(cell.getCellType()).isEqualTo(CellType.STRING);
            assertThat(cell.getStringCellValue()).isEqualTo("=1+2");
        }
        assertThat(digest(source)).isEqualTo(original);
    }

    @Test void pdfToEditableHtmlUsesRealTextAndSafeLocalMarkup() throws Exception {
        Path html = directory.resolve("pdf.html");
        service.convert(pdf(), "pdf", "html", html, null);
        var document = Jsoup.parse(Files.readString(html));
        assertThat(document.select("section[data-source-page]")).hasSize(2);
        assertThat(document.body().text()).contains("Actual page 1", "Actual page 2");
        assertThat(document.select("script,iframe,[src],[href]")).isEmpty();
    }

    @Test void aPdfWithoutExtractableTextCannotBecomeAnApparentlySuccessfulEmptyOfficeFile() throws Exception {
        Path source = directory.resolve("blank.pdf"), output = directory.resolve("blank.docx");
        try (var document = new PDDocument()) { document.addPage(new PDPage()); document.save(source.toFile()); }
        assertThatThrownBy(() -> service.convert(source, "pdf", "docx", output, null))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.CAPABILITY_UNSUPPORTED);
                    assertThat(exception.getMessage()).contains("未找到可提取", "保真", "未执行OCR");
                });
        assertThat(Files.exists(output)).isFalse();
    }

    @Test void cancelledTasksAndOutputPathsEqualToTheSourceNeverModifyAnOriginal() throws Exception {
        Path source = word(); String original = digest(source);
        var token = new CancellationToken(); token.cancel();
        assertThatThrownBy(() -> service.convert(source, "docx", "html", directory.resolve("cancelled.html"), token))
                .isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.getCode()).isEqualTo(ErrorCode.CANCELLED));
        assertThatThrownBy(() -> service.convert(source, "docx", "html", source, null))
                .isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThat(digest(source)).isEqualTo(original);
    }

    @Test void scriptOnlyHtmlAndCorruptOfficeFilesDoNotProduceFalseSuccess() throws Exception {
        Path html = directory.resolve("only-script.html"), corrupt = directory.resolve("corrupt.docx");
        Files.writeString(html, "<script>EVIL()</script><iframe src='https://example.invalid'></iframe>");
        Files.writeString(corrupt, "not an Office package", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> service.convert(html, "html", "docx", directory.resolve("empty.docx"), null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.convert(corrupt, "docx", "html", directory.resolve("corrupt.html"), null))
                .isInstanceOf(ApiException.class);
        assertThat(Files.exists(directory.resolve("empty.docx"))).isFalse();
        assertThat(Files.exists(directory.resolve("corrupt.html"))).isFalse();
    }
}
