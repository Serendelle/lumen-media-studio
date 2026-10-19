package com.houqing.tools.video_compressor_backend.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.houqing.tools.video_compressor_backend.ai.llm.LlmClient;
import com.houqing.tools.video_compressor_backend.media.StorageService;
import com.houqing.tools.video_compressor_backend.media.Tool;
import com.houqing.tools.video_compressor_backend.support.ApiTestBase;
import com.houqing.tools.video_compressor_backend.support.TestMedia;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** V2 P0 item 4 — 材料规范官: notice → spec, roster, folder intake & matching, checks & fixes, template delivery. */
class SpecDeskTests extends ApiTestBase {

    @Autowired SpecExtractor extractor;
    @Autowired StorageService storage;
    private Object realLlm;

    static final String NOTICE = """
            关于举办第三届科普讲解大赛的通知
            一、参赛材料
            1. 讲解视频：MP4 格式，大小不超过 2MB，分辨率不高于 720P。
            2. 个人照片：JPG 格式，不超过 500KB。
            3. 报名表：请提交 PDF 版本。
            二、文件请按"学校_姓名_材料名称"命名。
            联系人：赵老师 13900000000
            """;

    /** Answers with scripted tool calls; records what it was sent so the test can prove no roster data leaked. */
    static final class Scripted implements LlmClient {
        final Deque<Function<com.fasterxml.jackson.databind.node.ArrayNode, Result>> turns = new ArrayDeque<>();
        final List<String> seen = new ArrayList<>();
        @Override public Result chat(com.fasterxml.jackson.databind.node.ArrayNode messages, com.fasterxml.jackson.databind.node.ArrayNode tools,
                                     String toolChoice, java.util.function.Consumer<String> onText) {
            seen.add(messages.toString());
            return turns.removeFirst().apply(messages);
        }
        @Override public boolean configured() { return true; }
        @Override public String model() { return "scripted"; }
        static Result call(String args) {
            return new Result("", List.of(new ToolCall("c1", "submit_spec", args)), "tool_calls", 10, 10);
        }
    }

    @BeforeEach
    void swap() { realLlm = ReflectionTestUtils.getField(extractor, "llm"); }

    @AfterEach
    void restore() { ReflectionTestUtils.setField(extractor, "llm", realLlm); }

    // ---------------- fixtures ----------------

    static final String SPEC = """
            {"title":"科普讲解大赛","sizeUnit":"DECIMAL",
             "slots":[
              {"key":"video","label":"讲解视频","kind":"VIDEO","required":true,"rules":{"extensions":["mp4"],"maxBytes":2000000,"maxHeight":720},
               "evidence":"讲解视频：MP4 格式，大小不超过 2MB，分辨率不高于 720P。"},
              {"key":"photo","label":"个人照片","kind":"IMAGE","required":true,"rules":{"extensions":["jpg"],"maxBytes":500000},
               "evidence":"个人照片：JPG 格式，不超过 500KB。"},
              {"key":"form","label":"报名表","kind":"DOCUMENT","required":false,"rules":{"extensions":["pdf"]},
               "evidence":"报名表：请提交 PDF 版本。"}],
             "naming":{"pattern":"{学校}_{姓名}_{材料}","evidence":"学校_姓名_材料名称"},"notes":[]}""";

    static byte[] roster() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet s = wb.createSheet("名单");
            s.createRow(0).createCell(0).setCellValue("2026 科普讲解大赛报名名单");
            String[][] rows = {{"姓名", "学校", "学号", "电话"}, {"张三", "第一中学", "20240001", "13800000001"},
                    {"张三丰", "武当中学", "20240002", "13800000002"}, {"李四", "第二中学", "20240003", "13800000003"},
                    {"=HYPERLINK(\"http://x.example\")", "第三中学", "20240004", "13800000004"}};
            for (int r = 0; r < rows.length; r++) {
                var row = s.createRow(r + 1);
                for (int c = 0; c < rows[r].length; c++) row.createCell(c).setCellValue(rows[r][c]);
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    static byte[] docxTemplate() throws Exception {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("报名表 · {{项目名称}}");
            doc.createParagraph().createRun().setText("姓名：{{姓名}}    学校：{{ 学校 }}");
            XWPFParagraph split = doc.createParagraph(); // Word often splits a placeholder over runs
            split.createRun().setText("学号：${学");
            split.createRun().setText("号}");
            split.createRun().setText("  状态：{{状态}}");
            doc.createParagraph().createRun().setText("备注：{{不存在的字段}}");
            doc.write(out);
            return out.toByteArray();
        }
    }

    static byte[] xlsxTemplate(boolean dirty) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet s = wb.createSheet("汇总");
            s.createRow(0).createCell(0).setCellValue("科普讲解大赛汇总表");
            var h = s.createRow(1);
            String[] cols = {"序号", "姓名", "学校", "讲解视频", "状态"};
            for (int c = 0; c < cols.length; c++) h.createCell(c).setCellValue(cols[c]);
            if (dirty) s.createRow(3).createCell(1).setCellValue("已有内容");
            s.createRow(40).createCell(0).setCellFormula("COUNTA(B3:B39)");
            wb.write(out);
            return out.toByteArray();
        }
    }

    private String up(byte[] bytes, String name, User u) throws Exception {
        MvcResult r = uploadBytes("/api/files/upload", bytes, name, u);
        assertThat(r.getResponse().getStatus()).as(name + " " + r.getResponse().getContentAsString()).isEqualTo(200);
        return json(r).path("fileId").asText();
    }

    private String project(User u) throws Exception {
        return json(postJson("/api/v2/specs", Map.of("name", "科普讲解大赛"), u)).path("id").asText();
    }

    private JsonNode putSpec(String id, String spec, int version, User u) throws Exception {
        return json(mvc.perform(auth(put("/api/v2/specs/" + id + "/spec"), u).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("spec", mapper.readTree(spec), "version", version, "noticeText", NOTICE)))).andReturn());
    }

    private JsonNode item(JsonNode board, String pathPart) {
        for (JsonNode it : board.path("items")) if (it.path("relativePath").asText().contains(pathPart)) return it;
        throw new AssertionError("no item " + pathPart + " in " + board);
    }

    private String personKey(JsonNode board, String name) {
        for (JsonNode p : board.path("people")) if (p.path("name").asText().equals(name)) return p.path("key").asText();
        throw new AssertionError("no person " + name);
    }

    // ---------------- AI draft ----------------

    @Test
    void aiDraftIsValidatedRepairedOnceAndEveryRuleMustQuoteTheNotice() throws Exception {
        User u = register();
        String id = project(u);
        Scripted llm = new Scripted();
        // round 1: an unknown kind -> validator errors are fed back; round 2: valid, but one quote is invented
        llm.turns.add(m -> Scripted.call("{\"slots\":[{\"key\":\"video\",\"label\":\"讲解视频\",\"kind\":\"MOVIE\",\"evidence\":\"讲解视频\"}]}"));
        llm.turns.add(m -> {
            assertThat(m.toString()).contains("类型需为");
            return Scripted.call("""
                    {"sizeUnit":"DECIMAL","slots":[
                     {"key":"video","label":"讲解视频","kind":"VIDEO","rules":{"extensions":["MP4"],"maxBytes":2000000,"maxHeight":720},
                      "evidence":"讲解视频：MP4 格式，大小不超过 2MB，分辨率不高于 720P。"},
                     {"key":"photo","label":"个人照片","kind":"IMAGE","rules":{"extensions":["jpeg"],"maxBytes":500000},
                      "evidence":"照片须为近期免冠证件照"}],
                     "naming":{"pattern":"{学校}_{姓名}_{材料}","evidence":"学校_姓名_材料名称"}}""");
        });
        ReflectionTestUtils.setField(extractor, "llm", llm);
        JsonNode d = json(postJson("/api/v2/specs/" + id + "/extract", Map.of("noticeText", NOTICE), u));
        assertThat(d.path("rounds").asInt()).isEqualTo(2);
        assertThat(d.path("errors").size()).isZero();
        JsonNode slots = d.path("spec").path("slots");
        assertThat(slots.get(0).path("rules").path("extensions").get(0).asText()).isEqualTo("mp4");
        assertThat(slots.get(1).path("rules").path("extensions").get(0).asText()).isEqualTo("jpg");
        assertThat(slots.get(0).path("evidenceVerified").asBoolean()).isTrue();
        assertThat(slots.get(1).path("evidenceVerified").asBoolean()).isFalse(); // the invented quote is caught
        assertThat(d.path("warnings").toString()).contains("找不到");
        // the draft is not saved until confirmed
        assertThat(json(get("/api/v2/specs/" + id, u)).path("spec").path("slots").size()).isZero();

        // only the notice went to the model; a roster imported later is never part of a prompt
        assertThat(String.join("", llm.seen)).contains("科普讲解大赛").doesNotContain("13800000001");
    }

    @Test
    void invalidSpecsAreRefusedAndVersionsProtectConcurrentEdits() throws Exception {
        User u = register();
        String id = project(u);
        JsonNode bad = putSpec(id, "{\"slots\":[{\"key\":\"Video\",\"label\":\"视频\",\"kind\":\"VIDEO\",\"rules\":{\"maxFps\":30}}]}", 1, u);
        assertThat(bad.path("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(bad.path("details").path("errors").toString()).contains("key", "maxFps");
        JsonNode ok = putSpec(id, SPEC, 1, u);
        assertThat(ok.path("version").asInt()).isEqualTo(2);
        assertThat(ok.path("spec").path("slots").get(0).path("evidenceVerified").asBoolean()).isTrue();
        assertThat(putSpec(id, SPEC, 1, u).path("code").asText()).isEqualTo("VERSION_CONFLICT");
        // the notice's wording "材料名称" means the built-in {材料}, otherwise it would come out empty
        JsonNode aliased = putSpec(id, SPEC.replace("{学校}_{姓名}_{材料}", "{学校}_{姓名}_{材料名称}"), 2, u);
        assertThat(aliased.path("spec").path("naming").path("pattern").asText()).isEqualTo("{学校}_{姓名}_{材料}");
    }

    // ---------------- the full desk ----------------

    @Test
    void messyFolderIsMatchedCheckedFixedAndDeliveredInTheOrganisersTemplates() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE, Tool.MAGICK);
        assumeTrue(TestMedia.tools().available(Tool.SOFFICE), "LibreOffice not installed");
        User u = register(), other = register();
        String id = project(u);
        putSpec(id, SPEC, 1, u);
        String rosterFile = up(roster(), "名单.xlsx", u);
        JsonNode p = json(postJson("/api/v2/specs/" + id + "/roster", Map.of("fileId", rosterFile), u));
        assertThat(p.path("roster").path("headerRow").asInt()).isEqualTo(2); // title row above the header is skipped
        assertThat(p.path("roster").path("nameField").asText()).isEqualTo("姓名");
        assertThat(p.path("roster").path("idField").asText()).isEqualTo("学号");
        assertThat(p.path("roster").path("people").size()).isEqualTo(4);

        // a messy hand-in folder
        String v = up(Files.readAllBytes(TestMedia.get("demo.mp4")), "VID_0001.mp4", u);          // 4.6 MB, 720p
        String ph = up(Files.readAllBytes(TestMedia.get("photo.jpg")), "IMG_2231.jpg", u);
        String form = up(DocTemplates.docx(), "报名表.docx", u);
        String stray = up(Files.readAllBytes(TestMedia.get("song.mp3")), "bgm.mp3", u);
        String byId = up(Files.readAllBytes(TestMedia.get("photo.jpg")), "x.jpg", u);
        JsonNode board = json(postJson("/api/v2/specs/" + id + "/items", Map.of("files", List.of(
                Map.of("fileId", v, "relativePath", "收件箱/第一中学 张三/VID_0001.mp4"),
                Map.of("fileId", ph, "relativePath", "收件箱/张三丰-武当/IMG_2231.jpg"),
                Map.of("fileId", form, "relativePath", "收件箱/李四/报名表.docx"),
                Map.of("fileId", stray, "relativePath", "收件箱/bgm.mp3"),
                Map.of("fileId", byId, "relativePath", "收件箱/20240003_证件照.jpg"))), u));
        String zhang = personKey(board, "张三"), zhangsf = personKey(board, "张三丰"), li = personKey(board, "李四");

        JsonNode video = item(board, "VID_0001");
        assertThat(video.path("personKey").asText()).isEqualTo(zhang);
        assertThat(video.path("slotKey").asText()).isEqualTo("video");
        assertThat(video.path("status").asText()).isEqualTo("FIXABLE");
        assertThat(video.path("check").path("violations").toString()).contains("体积");
        assertThat(item(board, "IMG_2231").path("personKey").asText()).as("longest name wins").isEqualTo(zhangsf);
        assertThat(item(board, "IMG_2231").path("status").asText()).isEqualTo("OK");
        assertThat(item(board, "20240003").path("personKey").asText()).as("matched by 学号").isEqualTo(li);
        JsonNode docx = item(board, "报名表.docx");
        assertThat(docx.path("status").asText()).isEqualTo("FIXABLE");
        assertThat(docx.path("check").path("fix").path("type").asText()).isEqualTo("document.convert");
        assertThat(item(board, "bgm.mp3").path("status").asText()).isEqualTo("UNMATCHED");
        assertThat(item(board, "bgm.mp3").path("matchNote").asText()).contains("没有出现名单里的姓名");

        // a manual correction is kept as such
        JsonNode moved = json(mvc.perform(auth(patch("/api/v2/specs/" + id + "/items/" + item(board, "20240003").path("id").asText()), u)
                .contentType("application/json").content(mapper.writeValueAsString(Map.of("personKey", zhang, "slotKey", "photo")))).andReturn());
        assertThat(moved.path("matchMethod").asText()).isEqualTo("MANUAL");
        assertThat(moved.path("status").asText()).isEqualTo("OK");

        // fix everything fixable with the existing engines, then read the outcome back
        json(postJson("/api/v2/specs/" + id + "/fix", Map.of(), u));
        long end = System.currentTimeMillis() + 240_000;
        do {
            board = json(get("/api/v2/specs/" + id + "/items", u));
            Thread.sleep(500);
        } while (board.toString().contains("\"FIXING\"") && System.currentTimeMillis() < end);
        video = item(board, "VID_0001");
        assertThat(video.path("status").asText()).as(video.toString()).isEqualTo("FIXED");
        assertThat(Files.size(storage.pathOf(u.id(), video.path("fixedFileId").asText()))).isLessThanOrEqualTo(2_000_000);
        assertThat(item(board, "报名表.docx").path("status").asText()).isEqualTo("FIXED");
        assertThat(board.path("summary").path("complete").asInt()).isEqualTo(1); // 张三: video + photo (form optional)

        // templates: placeholders and columns are resolved against roster + spec before anything is generated
        String dt = up(docxTemplate(), "个人表模板.docx", u);
        String xt = up(xlsxTemplate(false), "汇总模板.xlsx", u);
        JsonNode t = json(mvc.perform(auth(put("/api/v2/specs/" + id + "/templates"), u).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("docxFileId", dt, "xlsxFileId", xt)))).andReturn());
        assertThat(t.path("templates").path("docx").path("unresolved").toString()).contains("不存在的字段");
        assertThat(t.path("templates").path("xlsx").path("detectedHeaderRow").asInt()).isEqualTo(2);

        JsonNode task = waitTask(json(postJson("/api/v2/specs/" + id + "/deliveries", Map.of("includeOriginals", true), u))
                .path("taskId").asText(), u, 120_000);
        assertThat(task.path("status").asText()).as(task.toString()).isEqualTo("SUCCEEDED");
        assertThat(task.path("result").path("overall").asText()).isEqualTo("PARTIAL_SUCCESS"); // others are missing materials
        assertThat(task.path("result").path("people").asInt()).isEqualTo(4);

        Map<String, byte[]> zip = unzip(Files.readAllBytes(storage.pathOf(u.id(), task.path("downloadFilename").asText())));
        assertThat(zip.keySet()).contains("00_交付说明.txt", "01_汇总表/汇总.xlsx", "02_个人表/0001_张三.docx",
                "03_材料/0001_张三/第一中学_张三_讲解视频.mp4", "03_材料/0001_张三/第一中学_张三_个人照片.jpg",
                "90_校验报告/manifest.csv", "90_校验报告/exceptions.csv", "90_校验报告/verification.json", "sha256sums.txt");
        assertThat(zip.keySet().stream().anyMatch(k -> k.startsWith("03_材料/0001_张三/原始素材/"))).isTrue();
        assertThat(zip.get("03_材料/0001_张三/第一中学_张三_讲解视频.mp4").length).isLessThanOrEqualTo(2_000_000);
        assertThat(zip.keySet()).noneMatch(k -> k.contains("bgm")); // unmatched files are reported, not packed

        // DOCX: values in place (also the split placeholder), unknown placeholder left visible and reported
        try (XWPFDocument d = new XWPFDocument(new ByteArrayInputStream(zip.get("02_个人表/0001_张三.docx")))) {
            String text = d.getParagraphs().stream().map(XWPFParagraph::getText).reduce("", (a, b) -> a + "\n" + b);
            assertThat(text).contains("姓名：张三", "学校：第一中学", "学号：20240001", "报名表 · 科普讲解大赛", "状态：材料齐全");
            assertThat(text).doesNotContain("{{姓名}}", "${学号}");
            assertThat(text).contains("{{不存在的字段}}");
        }
        String exceptions = new String(zip.get("90_校验报告/exceptions.csv"), StandardCharsets.UTF_8);
        assertThat(exceptions).contains("不存在的字段", "未提交", "bgm.mp3");
        assertThat(exceptions).contains("'=HYPERLINK"); // CSV cells that could start a formula are neutralised

        // XLSX: header and footer formula kept, one row per person, the hostile name stays plain text
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(zip.get("01_汇总表/汇总.xlsx")))) {
            var s = wb.getSheetAt(0);
            assertThat(s.getRow(0).getCell(0).getStringCellValue()).isEqualTo("科普讲解大赛汇总表");
            assertThat(s.getRow(2).getCell(1).getStringCellValue()).isEqualTo("张三");
            assertThat(s.getRow(2).getCell(3).getStringCellValue()).startsWith("已提交");
            assertThat(s.getRow(5).getCell(1).getCellType()).isEqualTo(CellType.STRING);
            assertThat(s.getRow(5).getCell(1).getStringCellValue()).startsWith("=HYPERLINK");
            assertThat(s.getRow(40).getCell(0).getCellType()).isEqualTo(CellType.FORMULA);
        }
        // every packed file matches its checksum line
        String sums = new String(zip.get("sha256sums.txt"), StandardCharsets.UTF_8);
        for (String line : sums.split("\n")) {
            if (line.isBlank()) continue;
            String[] parts = line.split("  ", 2);
            assertThat(SpecDeliveryHandler.sha256Bytes(zip.get(parts[1]))).as(parts[1]).isEqualTo(parts[0]);
        }

        // isolation
        assertThat(get("/api/v2/specs/" + id, other).getResponse().getStatus()).isEqualTo(404);
        assertThat(get("/api/v2/specs/" + id + "/items", other).getResponse().getStatus()).isEqualTo(404);
        assertThat(postJson("/api/v2/specs/" + id + "/deliveries", Map.of(), other).getResponse().getStatus()).isEqualTo(404);
        assertThat(json(get("/api/v2/specs", other)).path("total").asInt()).isZero();
    }

    @Test
    void aTemplateWhoseDataAreaIsNotEmptyIsNeverOverwritten() throws Exception {
        User u = register();
        String id = project(u);
        putSpec(id, SPEC, 1, u);
        json(postJson("/api/v2/specs/" + id + "/roster", Map.of("fileId", up(roster(), "名单.xlsx", u)), u));
        mvc.perform(auth(put("/api/v2/specs/" + id + "/templates"), u).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("xlsxFileId", up(xlsxTemplate(true), "脏模板.xlsx", u))))).andReturn();
        JsonNode task = waitTask(json(postJson("/api/v2/specs/" + id + "/deliveries", Map.of(), u)).path("taskId").asText(), u, 60_000);
        assertThat(task.path("status").asText()).isEqualTo("FAILED");
        assertThat(task.path("message").asText()).contains("未覆盖", "汇总!B4");
        assertThat(task.has("output")).isFalse();
    }

    static Map<String, byte[]> unzip(byte[] bytes) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) out.put(e.getName(), z.readAllBytes());
        }
        return out;
    }

    /** A real (if small) Word document for the "form" material. */
    static final class DocTemplates {
        static byte[] docx() throws Exception {
            try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                doc.createParagraph().createRun().setText("李四的报名表");
                doc.write(out);
                return out.toByteArray();
            }
        }
    }
}
