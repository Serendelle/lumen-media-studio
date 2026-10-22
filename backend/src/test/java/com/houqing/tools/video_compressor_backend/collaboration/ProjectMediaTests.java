package com.houqing.tools.video_compressor_backend.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import com.houqing.tools.video_compressor_backend.media.MediaProbe;
import com.houqing.tools.video_compressor_backend.media.Tool;
import com.houqing.tools.video_compressor_backend.support.ApiTestBase;
import com.houqing.tools.video_compressor_backend.support.TestMedia;
import org.apache.pdfbox.Loader;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/** Real FFmpeg and LibreOffice project processing, including inherited document access on derived outputs. */
class ProjectMediaTests extends ApiTestBase {
    @Autowired ProjectTaskService projectTasks;
    @Autowired ProjectFileService files;
    @Autowired MediaProbe probe;
    @Autowired JdbcTemplate db;

    private record Team(User owner, User editor, String id) {}
    private String base(Team team) { return "/api/v2/projects/" + team.id(); }
    private String content(Team team, String versionId) { return base(team) + "/files/" + versionId + "/content"; }
    private JsonNode ok(MvcResult response) throws Exception {
        assertThat(response.getResponse().getStatus()).as(response.getResponse().getContentAsString()).isBetween(200, 299);
        return json(response);
    }
    private Team team() throws Exception {
        User owner = register(), editor = register();
        JsonNode project = ok(postJson("/api/v2/projects", Map.of("name", "实际媒体处理协作", "description", "验证原件与处理结果权限"), owner));
        Team team = new Team(owner, editor, project.path("id").asText());
        ok(postJson(base(team) + "/members", Map.of("username", editor.username(), "role", "EDITOR"), owner));
        return team;
    }
    private JsonNode process(Team team, String inputVersion, String type, Map<String, Object> params, String key) throws Exception {
        return ok(postJson(base(team) + "/files/" + inputVersion + "/tasks",
                Map.of("type", type, "params", params, "idempotencyKey", key), team.editor()));
    }
    private String output(Team team, String taskId) throws Exception {
        JsonNode terminal = waitTask(taskId, team.owner(), 120000);
        assertThat(terminal.path("status").asText()).as(terminal.toString()).isEqualTo("SUCCEEDED");
        long deadline = System.currentTimeMillis() + 10000;
        do {
            projectTasks.synchronizeTasks();
            List<String> values = db.queryForList("select output_version_id from project_tasks where task_id=?", String.class, taskId);
            if (!values.isEmpty() && values.get(0) != null) return values.get(0);
            Thread.sleep(100);
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError("Successful processing did not register a project output version: " + taskId);
    }
    private byte[] workbook() throws Exception {
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("项目材料");
            var header = sheet.createRow(0); header.createCell(0).setCellValue("材料"); header.createCell(1).setCellValue("数量");
            for (int i = 1; i <= 6; i++) {
                var row = sheet.createRow(i); row.createCell(0).setCellValue("材料 " + i); row.createCell(1).setCellValue(i);
            }
            sheet.setFitToPage(true); workbook.setPrintArea(0, "A1:B7"); workbook.write(out); return out.toByteArray();
        }
    }
    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test void editorExtractsRealMp3AndRevocationBlocksBothOriginalAndDerivedOutput() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE, Tool.EXIFTOOL);
        Team team = team(); byte[] original = Files.readAllBytes(TestMedia.get("demo.mp4"));
        JsonNode input = ok(uploadBytes(base(team) + "/files", original, "原始视频.mp4", team.editor()));
        String inputId = input.path("id").asText(), retry = UUID.randomUUID().toString();
        assertThat(input.path("mediaType").asText()).isEqualTo("VIDEO");
        assertThat(input.path("sha256").asText()).isEqualTo(sha256(original));
        JsonNode submitted = process(team, inputId, "audio.extract", Map.of("format", "mp3"), retry);
        String taskId = submitted.path("id").asText();
        assertThat(process(team, inputId, "audio.extract", Map.of("format", "mp3"), retry).path("id").asText()).isEqualTo(taskId);
        String outputId = output(team, taskId);
        assertThat(get("/api/tasks/" + taskId, team.editor()).getResponse().getStatus()).isEqualTo(404);
        byte[] outputBytes = get(content(team, outputId), team.editor()).getResponse().getContentAsByteArray();
        assertThat(outputBytes).isNotEmpty().isNotEqualTo(original);
        var actualOutput = files.file(team.owner().id(), team.id(), outputId);
        var facts = probe.probe(files.path(actualOutput));
        assertThat(facts.formatName()).contains("mp3");
        assertThat(facts.audioCodec()).isEqualTo("mp3");
        assertThat(facts.hasAudio()).isTrue(); assertThat(facts.hasVideo()).isFalse();
        assertThat(facts.duration()).isBetween(5.5, 6.5);
        assertThat(get(content(team, inputId), team.editor()).getResponse().getContentAsByteArray()).isEqualTo(original);
        assertThat(files.version(team.editor().id(), team.id(), inputId).get("sha256")).isEqualTo(sha256(original));
        assertThat(files.version(team.editor().id(), team.id(), outputId).get("sha256")).isEqualTo(sha256(outputBytes));
        assertThat(db.queryForObject("select count(*) from project_tasks where project_id=?", Integer.class, team.id())).isEqualTo(1);
        ok(mvc.perform(auth(delete(base(team) + "/members/" + team.editor().id()), team.owner())).andReturn());
        for (String path : List.of(content(team, inputId), content(team, outputId), base(team) + "/tasks")) {
            assertThat(get(path, team.editor()).getResponse().getStatus()).as(path).isEqualTo(404);
        }
        assertThat(get(content(team, inputId), team.owner()).getResponse().getContentAsByteArray()).isEqualTo(original);
        assertThat(get(content(team, outputId), team.owner()).getResponse().getContentAsByteArray()).isEqualTo(outputBytes);
    }

    @Test void officePdfConversionKeepsSelectedDocumentPermissionsOnActualPdfOutput() throws Exception {
        TestMedia.assumeTools(Tool.SOFFICE);
        Team team = team(); byte[] original = workbook();
        String url = "https://docs.qq.com/sheet/DVGVhbV9TaGVldA";
        JsonNode document = ok(postJson(base(team) + "/documents", Map.of("title", "指定成员工作表", "externalUrl", url,
                "documentType", "SPREADSHEET", "visibility", "SELECTED", "visibleUserIds", List.of(team.editor().id())), team.owner()));
        String documentId = document.path("id").asText();
        JsonNode input = ok(uploadBytes(base(team) + "/documents/" + documentId + "/imports", original, "项目资料.xlsx", team.editor()));
        String inputId = input.path("id").asText();
        String taskId = process(team, inputId, "document.convert", Map.of("targetFormat", "pdf"), UUID.randomUUID().toString()).path("id").asText();
        String outputId = output(team, taskId);
        MvcResult download = get(content(team, outputId), team.editor());
        assertThat(download.getResponse().getStatus()).isEqualTo(200);
        byte[] pdfBytes = download.getResponse().getContentAsByteArray();
        try (var pdf = Loader.loadPDF(pdfBytes)) { assertThat(pdf.getNumberOfPages()).isGreaterThanOrEqualTo(1); }
        Map<String, Object> derived = files.version(team.owner().id(), team.id(), outputId);
        assertThat(derived.get("mediaType")).isEqualTo("PDF");
        assertThat(derived.get("documentId")).isEqualTo(documentId);
        assertThat(derived.get("sha256")).isEqualTo(sha256(pdfBytes));
        assertThat(get(content(team, inputId), team.editor()).getResponse().getContentAsByteArray()).isEqualTo(original);
        assertThat(files.version(team.editor().id(), team.id(), inputId).get("sha256")).isEqualTo(sha256(original));
        int documentVersion = ok(get(base(team) + "/documents", team.owner())).path("items").get(0).path("version").asInt();
        ok(mvc.perform(auth(patch(base(team) + "/documents/" + documentId), team.owner()).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("title", "指定成员工作表", "externalUrl", url, "documentType", "SPREADSHEET",
                        "visibility", "SELECTED", "visibleUserIds", List.of(), "version", documentVersion)))).andReturn());
        assertThat(ok(get(base(team) + "/files", team.editor())).path("total").asInt()).isZero();
        assertThat(ok(get(base(team) + "/tasks", team.editor()))).isEmpty();
        for (String path : List.of(content(team, inputId), content(team, outputId), base(team) + "/documents/" + documentId + "/versions",
                base(team) + "/files/" + derived.get("assetId") + "/versions", base(team) + "/files/" + input.path("assetId").asText() + "/versions")) {
            assertThat(get(path, team.editor()).getResponse().getStatus()).as(path).isEqualTo(404);
        }
        assertThat(get(content(team, outputId), team.owner()).getResponse().getContentAsByteArray()).isEqualTo(pdfBytes);
        assertThat(ok(get(base(team) + "/tasks", team.owner())).toString()).contains(taskId);
    }

    @Test void invalidPdfDoesNotCreateAProjectVersionOrLeaveAnUploadedFile() throws Exception {
        Team team = team();
        long before = db.queryForObject("select count(*) from media_files where owner_id=?", Long.class, team.owner().id());
        byte[] invalid = "%PDF-1.7\nThis is not a PDF object tree\n%%EOF".getBytes(StandardCharsets.US_ASCII);
        MvcResult response = uploadBytes(base(team) + "/files", invalid, "损坏文件.pdf", team.editor());
        assertThat(response.getResponse().getStatus()).as(response.getResponse().getContentAsString()).isBetween(400, 499);
        assertThat(db.queryForObject("select count(*) from project_file_versions where project_id=?", Integer.class, team.id())).isZero();
        assertThat(db.queryForObject("select count(*) from media_files where owner_id=?", Long.class, team.owner().id())).isEqualTo(before);
    }
}
