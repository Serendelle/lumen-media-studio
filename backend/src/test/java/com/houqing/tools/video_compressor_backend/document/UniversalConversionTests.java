package com.houqing.tools.video_compressor_backend.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.houqing.tools.video_compressor_backend.ai.agent.Constraints;
import com.houqing.tools.video_compressor_backend.ai.agent.MediaExecutor;
import com.houqing.tools.video_compressor_backend.ai.agent.PlanValidator;
import com.houqing.tools.video_compressor_backend.ai.agent.QualityReviewer;
import com.houqing.tools.video_compressor_backend.media.CancellationToken;
import com.houqing.tools.video_compressor_backend.media.MediaFile;
import com.houqing.tools.video_compressor_backend.media.StorageService;
import com.houqing.tools.video_compressor_backend.media.Tool;
import com.houqing.tools.video_compressor_backend.support.ApiTestBase;
import com.houqing.tools.video_compressor_backend.support.TestMedia;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real HTTP/task/storage conversions, with independently opened generated fixtures and outputs. */
class UniversalConversionTests extends ApiTestBase {
    @Autowired StorageService storage;
    @Autowired OfdConversionService ofd;
    @Autowired PlanValidator plans;
    @Autowired QualityReviewer reviewer;
    @TempDir Path fixtures;

    @Test
    void pdfToWordDefaultsToPageImagesAndEditableModeContainsActualText() throws Exception {
        User owner = register(), other = register();
        byte[] original = pdf(2, false);
        String source = uploadOk(original, "source.pdf", owner);
        String before = checksum(path(source, owner));

        JsonNode layout = convert(source, "docx", Map.of("density", 96), owner);
        assertThat(layout.path("result").path("mode").asText()).isEqualTo("layout");
        assertThat(layout.path("result").path("warnings").toString()).contains("不可直接编辑");
        assertThat(layout.path("output").path("mediaType").asText()).isEqualTo("DOCUMENT");
        try (var in = Files.newInputStream(outputPath(layout, owner)); var word = new XWPFDocument(in)) {
            assertThat(word.getAllPictures()).hasSize(2);
            for (var picture : word.getAllPictures()) {
                assertThat(ImageIO.read(new ByteArrayInputStream(picture.getData()))).isNotNull();
            }
        }

        JsonNode editable = convert(source, "docx", Map.of("mode", "editable"), owner);
        assertThat(editable.path("result").path("mode").asText()).isEqualTo("editable");
        assertThat(editable.path("result").path("ocrPerformed").asBoolean()).isFalse();
        try (var in = Files.newInputStream(outputPath(editable, owner)); var word = new XWPFDocument(in)) {
            assertThat(word.getParagraphs().stream().map(p -> p.getText()).toList().toString())
                    .contains("Actual source page 1", "Actual source page 2", "Alpha 7");
            assertThat(word.getAllPictures()).isEmpty();
        }
        assertThat(checksum(path(source, owner))).isEqualTo(before);
        assertThat(get("/api/files/" + source + "/content", owner).getResponse().getContentAsByteArray()).isEqualTo(original);
        String outputId = editable.path("output").path("fileId").asText();
        assertThat(get("/api/files/" + source, other).getResponse().getStatus()).isEqualTo(404);
        assertThat(get("/api/files/" + outputId + "/content", other).getResponse().getStatus()).isEqualTo(404);
        assertThat(get("/api/tasks/" + editable.path("taskId").asText(), other).getResponse().getStatus()).isEqualTo(404);
        assertThat(postJson("/api/v2/documents/convert", Map.of("sourceFileId", source, "targetFormat", "html"), other)
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void narrowStripImageKeepsItsAspectRatioInWordAndFitsOnePhysicalPage() throws Exception {
        User owner = register();
        String source = uploadOk(image("png", 1000, 100), "narrow-strip.png", owner);
        JsonNode converted = convert(source, "docx", Map.of(), owner);
        try (var in = Files.newInputStream(outputPath(converted, owner)); var word = new XWPFDocument(in)) {
            var pictures = word.getParagraphs().stream().flatMap(p -> p.getRuns().stream())
                    .flatMap(run -> run.getEmbeddedPictures().stream()).toList();
            assertThat(pictures).hasSize(1);
            var extent = pictures.get(0).getCTPicture().getSpPr().getXfrm().getExt();
            assertThat(extent.getCx()).isPositive();
            assertThat(extent.getCy()).isPositive();
            assertThat((double) extent.getCx() / extent.getCy()).isCloseTo(10, offset(0.01));
            BufferedImage embedded = ImageIO.read(new ByteArrayInputStream(pictures.get(0).getPictureData().getData()));
            assertThat(embedded.getWidth()).isEqualTo(1000);
            assertThat(embedded.getHeight()).isEqualTo(100);
            embedded.flush();
        }
        if (TestMedia.available(Tool.SOFFICE)) {
            JsonNode back = convert(converted.path("output").path("fileId").asText(), "pdf", Map.of(), owner);
            try (var pdf = PdfFacts.open(outputPath(back, owner))) {
                assertThat(pdf.getNumberOfPages()).as("a narrow picture must not overflow onto a blank page").isEqualTo(1);
            }
            assertThat(PdfFacts.renderHasInk(outputPath(back, owner), 1)).isTrue();
        }
    }

    @Test
    void twoPageLayoutWordRoundTripKeepsPageSizesAndHasNoExtraBlankPages() throws Exception {
        TestMedia.assumeTools(Tool.SOFFICE);
        User owner = register();
        String source = uploadOk(mixedPageLayoutPdf(), "mixed-layout.pdf", owner);
        JsonNode word = convert(source, "docx", Map.of("density", 96), owner);
        JsonNode back = convert(word.path("output").path("fileId").asText(), "pdf", Map.of(), owner);
        try (var original = PdfFacts.open(path(source, owner)); var actual = PdfFacts.open(outputPath(back, owner))) {
            assertThat(actual.getNumberOfPages()).as("one output page per source page, including section boundaries").isEqualTo(2);
            for (int i = 0; i < 2; i++) {
                var expectedSize = original.getPage(i).getMediaBox();
                var actualSize = actual.getPage(i).getMediaBox();
                assertThat((double) actualSize.getWidth()).as("page " + (i + 1) + " width")
                        .isCloseTo(expectedSize.getWidth(), offset(1.0));
                assertThat((double) actualSize.getHeight()).as("page " + (i + 1) + " height")
                        .isCloseTo(expectedSize.getHeight(), offset(1.0));
                assertThat(PdfFacts.renderHasInk(outputPath(back, owner), i + 1)).as("page " + (i + 1) + " has visible source content").isTrue();
            }
        }
    }

    @Test
    void officeCrossRoutesOfferLayoutWhenPageEngineExists() throws Exception {
        TestMedia.assumeTools(Tool.SOFFICE);
        User owner = register();
        JsonNode routes = json(get("/api/v2/documents/matrix", owner)).path("routes");
        int checked = 0;
        for (JsonNode route : routes) {
            if (!route.path("from").asText().equals("docx") || !List.of("html", "xlsx").contains(route.path("to").asText())) continue;
            assertThat(route.path("available").asBoolean()).isTrue();
            assertThat(route.path("defaultMode").asText()).isEqualTo("editable");
            assertThat(route.path("modes").toString()).contains("\"editable\"", "\"layout\"");
            checked++;
        }
        assertThat(checked).isEqualTo(2);

        String image = uploadOk(image("png"), "page.png", owner);
        JsonNode word = convert(image, "docx", Map.of("mode", "layout"), owner);
        String wordId = word.path("output").path("fileId").asText();
        String originalHash = checksum(path(wordId, owner));
        try (var in = Files.newInputStream(outputPath(word, owner)); var document = new XWPFDocument(in)) {
            assertThat(document.getAllPictures()).hasSize(1);
            assertThat(document.getParagraphs().stream().allMatch(p -> p.getText().isBlank())).isTrue();
        }

        JsonNode html = convert(wordId, "html", Map.of("mode", "layout", "density", 96), owner);
        assertThat(html.path("result").path("mode").asText()).isEqualTo("layout");
        var parsed = Jsoup.parse(outputPath(html, owner).toFile(), "UTF-8");
        var images = parsed.select("img[src^=\"data:image/png;base64,\"]");
        assertThat(images).hasSize(1);
        for (var embedded : images) {
            String data = embedded.attr("src");
            assertImage(java.util.Base64.getDecoder().decode(data.substring(data.indexOf(',') + 1)), "png");
        }

        JsonNode excel = convert(wordId, "xlsx", Map.of("mode", "layout", "density", 96), owner);
        assertThat(excel.path("result").path("mode").asText()).isEqualTo("layout");
        try (var in = Files.newInputStream(outputPath(excel, owner)); var workbook = new XSSFWorkbook(in)) {
            assertThat(workbook.getAllPictures()).hasSize(1);
            for (var embedded : workbook.getAllPictures()) assertImage(embedded.getData(), "png");
        }
        assertThat(checksum(path(wordId, owner))).isEqualTo(originalHash);
    }

    @Test
    void multipagePdfCreatesActualImageZipWithOrderedChecksums() throws Exception {
        User owner = register();
        String source = uploadOk(pdf(2, true), "bitmap.pdf", owner);
        for (String format : List.of("png", "jpg")) {
            JsonNode task = convert(source, format, Map.of("density", 96), owner);
            assertThat(task.path("output").path("mediaType").asText()).isEqualTo("ARCHIVE");
            assertThat(task.path("output").path("filename").asText()).endsWith(".zip");
            assertImageArchive(outputPath(task, owner), format, 2);
        }
    }

    @Test
    void bitmapPdfCanBecomeLayoutWordButNeverPretendsToHaveExtractedText() throws Exception {
        User owner = register();
        String source = uploadOk(pdf(1, true), "scan.pdf", owner);
        JsonNode layout = convert(source, "docx", Map.of("density", 96), owner);
        try (var in = Files.newInputStream(outputPath(layout, owner)); var word = new XWPFDocument(in)) {
            assertThat(word.getAllPictures()).hasSize(1);
        }
        MvcResult accepted = submit(source, "xlsx", Map.of("mode", "editable"), owner);
        assertThat(accepted.getResponse().getStatus()).isEqualTo(202);
        JsonNode failed = waitTask(json(accepted).path("taskId").asText(), owner, 30_000);
        assertThat(failed.path("status").asText()).as(failed.toString()).isEqualTo("FAILED");
        assertThat(failed.path("errorCode").asText()).isEqualTo("CAPABILITY_UNSUPPORTED");
        assertThat(failed.hasNonNull("output")).isFalse();
        assertThat(failed.path("message").asText()).contains("文字");
    }

    @Test
    void imageCrossFormatProducesAnImageAndMultipleImagesMergeIntoPdf() throws Exception {
        User owner = register();
        String png = uploadOk(image("png"), "first.png", owner);
        String jpg = uploadOk(image("jpg"), "second.jpg", owner);
        String pngHash = checksum(path(png, owner)), jpgHash = checksum(path(jpg, owner));
        for (var route : List.of(Map.entry(png, "jpg"), Map.entry(jpg, "png"))) {
            JsonNode converted = convert(route.getKey(), route.getValue(), Map.of(), owner);
            assertThat(converted.path("output").path("mediaType").asText()).isEqualTo("IMAGE");
            assertImage(Files.readAllBytes(outputPath(converted, owner)), route.getValue());
        }
        MvcResult submitted = postJson("/api/v2/documents/convert", Map.of("sourceFileIds", List.of(png, jpg), "targetFormat", "pdf"), owner);
        assertThat(submitted.getResponse().getStatus()).as(submitted.getResponse().getContentAsString()).isEqualTo(202);
        JsonNode pdf = waitTask(json(submitted).path("taskId").asText(), owner, 30_000);
        assertSuccess(pdf);
        try (var doc = PdfFacts.open(outputPath(pdf, owner))) {
            assertThat(doc.getNumberOfPages()).isEqualTo(2);
        }
        assertThat(PdfFacts.renderHasInk(outputPath(pdf, owner), 1)).isTrue();
        assertThat(PdfFacts.renderHasInk(outputPath(pdf, owner), 2)).isTrue();
        assertThat(checksum(path(png, owner))).isEqualTo(pngHash);
        assertThat(checksum(path(jpg, owner))).isEqualTo(jpgHash);
    }

    @Test
    void wordToExcelContainsActualCellsAndHtmlRoundTripOmitsActiveContent() throws Exception {
        User owner = register();
        String source = uploadOk(word(), "materials.docx", owner);
        JsonNode converted = convert(source, "xlsx", Map.of(), owner);
        assertThat(converted.path("result").path("mode").asText()).isEqualTo("editable");
        try (var in = Files.newInputStream(outputPath(converted, owner)); var book = new XSSFWorkbook(in)) {
            List<String> values = new ArrayList<>();
            for (var sheet : book) for (var row : sheet) for (var cell : row) {
                assertThat(cell.getCellType()).isNotEqualTo(CellType.FORMULA);
                values.add(cell.toString());
            }
            assertThat(values).contains("Shared material", "Item", "Count", "Alpha", "=2+3");
        }
        String html = uploadOk(("<html><body><script>HACKED_MARKER</script><p>Collected materials</p>"
                + "<table><tr><td>Name</td><td>Value</td></tr><tr><td>Beta</td><td>19</td></tr></table>"
                + "<iframe src='https://attacker.invalid'></iframe></body></html>").getBytes(StandardCharsets.UTF_8), "page.html", owner);
        JsonNode extracted = convert(html, "docx", Map.of(), owner);
        try (var in = Files.newInputStream(outputPath(extracted, owner)); var doc = new XWPFDocument(in)) {
            assertThat(doc.getParagraphs().stream().map(p -> p.getText()).toList().toString()).contains("Collected materials").doesNotContain("HACKED_MARKER");
            assertThat(doc.getTables()).hasSize(1);
            assertThat(doc.getTables().get(0).getText()).contains("Beta", "19");
        }
    }

    @Test
    void invalidOptionsIdentityAndUnavailableOcrCreateNoJobs() throws Exception {
        User owner = register();
        String document = uploadOk(word(), "a.docx", owner);
        String png = uploadOk(image("png"), "a.png", owner);
        for (Map<String, Object> body : List.of(
                Map.<String, Object>of("sourceFileId", document, "targetFormat", "exe"),
                Map.<String, Object>of("sourceFileId", document, "targetFormat", "docx"),
                Map.<String, Object>of("sourceFileId", document, "targetFormat", "xlsx", "options", Map.of("density", 600)),
                Map.<String, Object>of("sourceFileId", document, "targetFormat", "xlsx", "options", Map.of("mode", "magic")),
                Map.<String, Object>of("sourceFileId", document, "targetFormat", "xlsx", "options", Map.of("command", "unexpected")),
                Map.<String, Object>of("sourceFileId", png, "targetFormat", "docx", "options", Map.of("mode", "editable")),
                Map.<String, Object>of("sourceFileIds", List.of(document, png), "targetFormat", "pdf"))) {
            MvcResult rejected = postJson("/api/v2/documents/convert", body, owner);
            assertThat(rejected.getResponse().getStatus()).as(body + " => " + rejected.getResponse().getContentAsString()).isBetween(400, 499);
        }
        assertThat(json(get("/api/tasks", owner)).path("totalElements").asInt()).isZero();
        assertThat(get("/api/v2/documents/matrix", null).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void aiPlansUseTheRealDocumentImageAndArchiveOutputTypes() throws Exception {
        User owner = register();
        String png = uploadOk(image("png"), "photo.png", owner);
        var imagePlan = mapper.createObjectNode();
        var imageSteps = imagePlan.putArray("steps");
        imageSteps.addObject().put("id", 1).put("tool", "document.convert").put("input", png)
                .putObject("params").put("targetFormat", "jpg");
        imageSteps.addObject().put("id", 2).put("tool", "image.process").put("input", "step:1")
                .putObject("params").put("tool", "resize").put("width", 48);
        var imageOutcome = plans.validate(owner.id(), imagePlan);
        assertThat(imageOutcome.ok()).as(imageOutcome.errors().toString()).isTrue();

        String pdf = uploadOk(pdf(1, false), "text.pdf", owner);
        var documentPlan = mapper.createObjectNode();
        var documentSteps = documentPlan.putArray("steps");
        documentSteps.addObject().put("id", 1).put("tool", "document.convert").put("input", pdf)
                .putObject("params").put("targetFormat", "docx");
        documentSteps.addObject().put("id", 2).put("tool", "document.convert").put("input", "step:1")
                .putObject("params").put("targetFormat", "docx");
        var documentOutcome = plans.validate(owner.id(), documentPlan);
        assertThat(documentOutcome.ok()).isFalse();
        assertThat(documentOutcome.errors().toString()).contains("源格式与目标格式相同");

        ((ObjectNode) documentSteps.get(0).path("params")).put("targetFormat", "png");
        ((ObjectNode) documentSteps.get(1)).put("tool", "image.process")
                .putObject("params").put("tool", "resize").put("width", 48);
        var archiveOutcome = plans.validate(owner.id(), documentPlan);
        assertThat(archiveOutcome.ok()).isFalse();
        assertThat(archiveOutcome.errors().toString()).contains("只能处理图片文件");
        assertThat(json(get("/api/tasks", owner)).path("totalElements").asInt()).isZero();
    }

    @Test
    void aiReviewerReadsActualOfficeBytesAndRejectsAnInvalidOutputDespiteSuccessfulSummary() throws Exception {
        User owner = register();
        String source = uploadOk(word(), "source.docx", owner);
        JsonNode task = convert(source, "xlsx", Map.of(), owner);
        var params = mapper.createObjectNode().put("targetFormat", "xlsx");
        var result = new MediaExecutor.StepResult(1, "document.convert", "SUCCEEDED", task.path("taskId").asText(),
                task.path("output").path("fileId").asText(), params, task.path("result"), null);
        var plan = mapper.createObjectNode();
        plan.putArray("steps");
        plan.set("constraints", Constraints.normalize(mapper.valueToTree(Map.of("document", Map.of("format", "xlsx"))), mapper));
        assertThat(Constraints.describe(plan.path("constraints"))).contains("XLSX");
        var before = reviewer.review(owner.id(), "conversion-review-test", 1, plan, Map.of(1, result),
                update -> {}, () -> false, new HashSet<>());
        assertThat(before.allPassed()).as(before.checks().toString()).isTrue();
        Files.writeString(outputPath(task, owner), "not a workbook", StandardCharsets.UTF_8);
        var after = reviewer.review(owner.id(), "conversion-review-test", 1, plan, Map.of(1, result),
                update -> {}, () -> false, new HashSet<>());
        assertThat(after.allPassed()).isFalse();
        assertThat(after.checks().toString()).contains("实际文件无法按目标格式解析", "约束·文档格式");
    }

    @Test
    void all42CrossRoutesWriteReadableFiles() throws Exception {
        TestMedia.assumeTools(Tool.SOFFICE, Tool.CHROMIUM);
        assumeTrue(ofd.available(), "OFD worker not built");
        User owner = register();
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        bytes.put("docx", word());
        bytes.put("xlsx", excel());
        bytes.put("html", "<!doctype html><html><body><p>Shared material</p><table><tr><td>Alpha</td><td>7</td></tr></table></body></html>".getBytes(StandardCharsets.UTF_8));
        bytes.put("pdf", pdf(1, false));
        bytes.put("png", image("png"));
        bytes.put("jpg", image("jpg"));
        Path png = fixtures.resolve("seed.png"), seedOfd = fixtures.resolve("seed.ofd");
        Files.write(png, bytes.get("png"));
        ofd.fromImages(List.of(png), seedOfd, new CancellationToken());
        bytes.put("ofd", Files.readAllBytes(seedOfd));

        JsonNode routes = json(get("/api/v2/documents/matrix", owner)).path("routes");
        assertThat(routes.size()).isEqualTo(42);
        Map<String, String> ids = new LinkedHashMap<>(), hashes = new LinkedHashMap<>();
        for (var source : bytes.entrySet()) {
            String id = uploadOk(source.getValue(), "source." + source.getKey(), owner);
            ids.put(source.getKey(), id);
            hashes.put(source.getKey(), checksum(path(id, owner)));
        }
        int completed = 0;
        for (JsonNode route : routes) {
            String from = route.path("from").asText(), to = route.path("to").asText();
            assertThat(route.path("available").asBoolean()).as(from + " -> " + to).isTrue();
            JsonNode converted = convert(ids.get(from), to, Map.of("density", 96), owner);
            boolean archive = List.of("png", "jpg").contains(to) && !List.of("png", "jpg").contains(from);
            assertThat(converted.path("output").path("filename").asText()).endsWith("." + (archive ? "zip" : to));
            assertReadable(outputPath(converted, owner), to, archive);
            completed++;
        }
        assertThat(completed).isEqualTo(42);
        for (var source : ids.entrySet()) assertThat(checksum(path(source.getValue(), owner))).as(source.getKey()).isEqualTo(hashes.get(source.getKey()));
    }

    private String uploadOk(byte[] bytes, String filename, User owner) throws Exception {
        MvcResult response = uploadBytes("/api/files/upload", bytes, filename, owner);
        assertThat(response.getResponse().getStatus()).as(filename + " => " + response.getResponse().getContentAsString()).isEqualTo(200);
        return json(response).path("fileId").asText();
    }

    private MvcResult submit(String id, String target, Map<String, Object> options, User owner) throws Exception {
        return postJson("/api/v2/documents/convert", Map.of("sourceFileId", id, "targetFormat", target, "options", options), owner);
    }

    private JsonNode convert(String id, String target, Map<String, Object> options, User owner) throws Exception {
        MvcResult submitted = submit(id, target, options, owner);
        assertThat(submitted.getResponse().getStatus()).as(target + " => " + submitted.getResponse().getContentAsString()).isEqualTo(202);
        JsonNode task = waitTask(json(submitted).path("taskId").asText(), owner, 150_000);
        assertSuccess(task);
        return task;
    }

    private void assertSuccess(JsonNode task) {
        assertThat(task.path("status").asText()).as(task.toString()).isEqualTo("SUCCEEDED");
        assertThat(task.path("result").path("verification").path("status").asText()).isEqualTo("VERIFIED");
        assertThat(task.path("output").path("sizeBytes").asLong()).isPositive();
    }

    private Path path(String id, User owner) {
        MediaFile file = storage.require(owner.id(), id);
        return storage.pathOf(file);
    }

    private Path outputPath(JsonNode task, User owner) {
        return path(task.path("output").path("fileId").asText(), owner);
    }

    private static String checksum(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private void assertReadable(Path path, String format, boolean archive) throws Exception {
        if (archive) {
            assertImageArchive(path, format, 1);
            return;
        }
        switch (format) {
            case "pdf" -> {
                try (var doc = PdfFacts.open(path)) { assertThat(doc.getNumberOfPages()).isPositive(); }
                assertThat(PdfFacts.renderHasInk(path, 1)).isTrue();
            }
            case "docx" -> {
                try (var in = Files.newInputStream(path); var doc = new XWPFDocument(in)) {
                    boolean text = doc.getParagraphs().stream().anyMatch(p -> !p.getText().isBlank())
                            || doc.getTables().stream().anyMatch(t -> !t.getText().isBlank());
                    assertThat(text || !doc.getAllPictures().isEmpty()).isTrue();
                }
            }
            case "xlsx" -> {
                try (var in = Files.newInputStream(path); var book = new XSSFWorkbook(in)) {
                    int values = 0;
                    for (var sheet : book) for (var row : sheet) for (var cell : row) if (!cell.toString().isBlank()) values++;
                    assertThat(values > 0 || !book.getAllPictures().isEmpty()).isTrue();
                }
            }
            case "html" -> {
                var html = Jsoup.parse(path.toFile(), "UTF-8");
                assertThat(!html.body().text().isBlank() || !html.select("img[src^=data:image/]").isEmpty()).isTrue();
                assertThat(html.select("script,iframe,object,embed")).isEmpty();
            }
            case "ofd" -> {
                try (var zip = new ZipFile(path.toFile())) {
                    assertThat(zip.getEntry("OFD.xml")).isNotNull();
                    assertThat(zip.stream().filter(e -> e.getName().matches(".*Pages/.*/Content\\.xml")).count()).isPositive();
                }
            }
            case "png", "jpg" -> assertImage(Files.readAllBytes(path), format);
            default -> throw new AssertionError(format);
        }
    }

    private void assertImageArchive(Path path, String format, int pages) throws Exception {
        try (var zip = new ZipFile(path.toFile())) {
            JsonNode manifest;
            try (var in = zip.getInputStream(zip.getEntry("manifest.json"))) { manifest = mapper.readTree(in); }
            assertThat(manifest.path("pageCount").asInt()).isEqualTo(pages);
            assertThat(manifest.path("format").asText()).isEqualTo(format);
            assertThat(zip.size()).isEqualTo(pages + 1);
            int i = 0;
            for (JsonNode page : manifest.path("pages")) {
                String name = "page-" + String.format("%04d", ++i) + "." + format;
                assertThat(page.path("name").asText()).isEqualTo(name);
                byte[] bytes;
                try (var in = zip.getInputStream(zip.getEntry(name))) { bytes = in.readAllBytes(); }
                assertThat(page.path("sizeBytes").asLong()).isEqualTo(bytes.length);
                assertThat(page.path("sha256").asText()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
                assertImage(bytes, format);
            }
            assertThat(i).isEqualTo(pages);
        }
    }

    private static void assertImage(byte[] bytes, String format) throws Exception {
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            assertThat(readers.hasNext()).isTrue();
            var reader = readers.next();
            try {
                reader.setInput(stream);
                assertThat(reader.getFormatName().toLowerCase()).isIn(format.equals("jpg") ? List.of("jpeg", "jpg") : List.of(format));
                assertThat(reader.getWidth(0)).isPositive();
                assertThat(reader.getHeight(0)).isPositive();
                BufferedImage image = reader.read(0);
                assertThat(image).isNotNull();
                boolean ink = false;
                for (int y = 0; y < image.getHeight() && !ink; y++) for (int x = 0; x < image.getWidth(); x++) {
                    int rgb = image.getRGB(x, y);
                    if (((rgb >> 16) & 255) < 245 || ((rgb >> 8) & 255) < 245 || (rgb & 255) < 245) { ink = true; break; }
                }
                assertThat(ink).as("decoded image has visible content").isTrue();
                image.flush();
            } finally { reader.dispose(); }
        }
    }

    private static byte[] word() throws Exception {
        try (var doc = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("Shared material");
            var table = doc.createTable(2, 2);
            table.getRow(0).getCell(0).setText("Item");
            table.getRow(0).getCell(1).setText("Count");
            table.getRow(1).getCell(0).setText("Alpha");
            table.getRow(1).getCell(1).setText("=2+3");
            doc.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] excel() throws Exception {
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("Materials");
            sheet.createRow(0).createCell(0).setCellValue("Shared material");
            sheet.getRow(0).createCell(1).setCellValue("Count");
            sheet.createRow(1).createCell(0).setCellValue("Alpha");
            sheet.getRow(1).createCell(1).setCellValue(7);
            book.setPrintArea(0, "A1:B2");
            book.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] pdf(int pages, boolean bitmap) throws Exception {
        try (var doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            for (int i = 1; i <= pages; i++) {
                var page = new PDPage(new PDRectangle(240, 180));
                doc.addPage(page);
                try (var content = new PDPageContentStream(doc, page)) {
                    if (bitmap) {
                        BufferedImage image = ImageIO.read(new ByteArrayInputStream(image("png")));
                        content.drawImage(LosslessFactory.createFromImage(doc, image), 10, 10, 220, 160);
                        image.flush();
                    } else {
                        content.setNonStrokingColor(new Color(80, 130, 200));
                        content.addRect(20, 20, 200, 140);
                        content.fill();
                        content.setNonStrokingColor(Color.BLACK);
                        content.beginText();
                        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        content.newLineAtOffset(30, 125);
                        content.showText("Actual source page " + i);
                        content.newLineAtOffset(0, -25);
                        content.showText("Alpha 7");
                        content.endText();
                    }
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] image(String format) throws Exception {
        return image(format, 96, 64);
    }

    private static byte[] image(String format, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(30, 100, 180));
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(new Color(220, 140, 30));
            graphics.fillRect(3, 3, 18, 18);
        } finally { graphics.dispose(); }
        try (var out = new ByteArrayOutputStream()) {
            assertThat(ImageIO.write(image, format, out)).isTrue();
            return out.toByteArray();
        } finally { image.flush(); }
    }

    private static byte[] mixedPageLayoutPdf() throws Exception {
        try (var doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            for (var size : List.of(new PDRectangle(300, 180), new PDRectangle(180, 300))) {
                var page = new PDPage(size);
                doc.addPage(page);
                try (var content = new PDPageContentStream(doc, page)) {
                    content.setNonStrokingColor(doc.getNumberOfPages() == 1 ? new Color(35, 100, 175) : new Color(200, 115, 40));
                    content.addRect(6, 6, size.getWidth() - 12, size.getHeight() - 12);
                    content.fill();
                    content.setNonStrokingColor(Color.BLACK);
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(20, size.getHeight() - 35);
                    content.showText("Actual layout page " + doc.getNumberOfPages());
                    content.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
