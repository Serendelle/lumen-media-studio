package com.houqing.tools.video_compressor_backend.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import com.houqing.tools.video_compressor_backend.media.MediaFileRepository;
import com.houqing.tools.video_compressor_backend.media.StorageService;
import com.houqing.tools.video_compressor_backend.support.ApiTestBase;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/** Actual HTTP/JWT, database, storage, Office parsing, SSE and ZIP flow; no Tencent network or paid AI calls. */
class ProjectCollaborationTests extends ApiTestBase {
    @Autowired JdbcTemplate db;
    @Autowired MediaFileRepository media;
    @Autowired StorageService storage;
    @Autowired ProjectTaskService projectTasks;
    @Autowired ProjectEventHub events;

    private record Team(User owner, String id) {}
    private String base(Team team) { return "/api/v2/projects/" + team.id(); }
    private MockHttpServletRequestBuilder requestGet(String url) { return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url); }
    private JsonNode ok(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isBetween(200, 299);
        return json(result);
    }
    private MvcResult send(User user, MockHttpServletRequestBuilder request, Object body) throws Exception {
        if (body != null) request.contentType("application/json").content(mapper.writeValueAsString(body));
        return mvc.perform(auth(request, user)).andReturn();
    }
    private Team team() throws Exception {
        User owner = register();
        JsonNode created = ok(postJson("/api/v2/projects", Map.of("name", "团队真实文件测试", "description", "多人协作验收"), owner));
        return new Team(owner, created.path("id").asText());
    }
    private void invite(Team team, User user, String role) throws Exception {
        ok(postJson(base(team) + "/members", Map.of("username", user.username(), "role", role), team.owner()));
    }
    private byte[] workbook(String value) throws Exception {
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("资料");
            var header = sheet.createRow(0); header.createCell(0).setCellValue("姓名"); header.createCell(1).setCellValue("数量");
            var row = sheet.createRow(1); row.createCell(0).setCellValue(value); row.createCell(1).setCellValue(3);
            workbook.write(out); return out.toByteArray();
        }
    }
    private JsonNode upload(Team team, User user, byte[] bytes, String assetId) throws Exception {
        var request = multipart(base(team) + "/files").file(new MockMultipartFile("file", "真实资料.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes));
        if (assetId != null) request.param("assetId", assetId);
        return ok(mvc.perform(auth(request, user)).andReturn());
    }
    private Map<String, Object> documentBody(String visibility, List<Long> users) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "实际腾讯表格链接"); body.put("externalUrl", "https://docs.qq.com/sheet/DVGVzdF9zaGVldA?tab=BB08J2");
        body.put("documentType", "SPREADSHEET"); body.put("visibility", visibility); body.put("visibleUserIds", users);
        return body;
    }
    private JsonNode document(Team team, String visibility, List<Long> users) throws Exception {
        return ok(postJson(base(team) + "/documents", documentBody(visibility, users), team.owner()));
    }
    private String content(Team team, String version) { return base(team) + "/files/" + version + "/content"; }

    @Test void existingMaterialProjectsShareOneIdentityAndRolesAreEnforced() throws Exception {
        User owner = register(), editor = register(), stranger = register();
        String oldId = ok(postJson("/api/v2/specs", Map.of("name", "原材料官项目"), owner)).path("id").asText();
        Team team = new Team(owner, oldId);
        assertThat(ok(get(base(team), owner)).path("name").asText()).isEqualTo("原材料官项目");
        invite(team, editor, "EDITOR");
        assertThat(ok(get(base(team), editor)).path("role").asText()).isEqualTo("EDITOR");
        assertThat(ok(get("/api/v2/projects", editor)).toString()).contains(oldId);
        for (String path : List.of("", "/members", "/files", "/documents", "/activities", "/todos", "/checks", "/tasks", "/events")) {
            assertThat(get(base(team) + path, stranger).getResponse().getStatus()).as(path).isEqualTo(404);
        }
        assertThat(get("/api/v2/specs/" + oldId, editor).getResponse().getStatus()).isEqualTo(404);
        assertThat(postJson(base(team) + "/members", Map.of("username", stranger.username(), "role", "EDITOR"), editor).getResponse().getStatus()).isEqualTo(403);
        ok(send(owner, patch(base(team) + "/members/" + editor.id()), Map.of("role", "VIEWER")));
        assertThat(uploadBytes(base(team) + "/files", workbook("只读账号"), "资料.xlsx", editor).getResponse().getStatus()).isEqualTo(403);
        assertThat(postJson(base(team) + "/todos", Map.of("title", "非法修改"), editor).getResponse().getStatus()).isEqualTo(403);
        assertThat(postJson(base(team) + "/leave", Map.of(), owner).getResponse().getStatus()).isEqualTo(400);
        ok(postJson(base(team) + "/leave", Map.of(), editor));
        assertThat(get(base(team), editor).getResponse().getStatus()).isEqualTo(404);
    }

    @Test void immutableRealXlsxVersionsKeepHashesAndCannotBeDeletedThroughPersonalLibrary() throws Exception {
        Team team = team(); User editor = register(), stranger = register(); invite(team, editor, "EDITOR");
        byte[] first = workbook("版本一"), second = workbook("版本二");
        JsonNode one = upload(team, editor, first, null);
        JsonNode two = upload(team, editor, second, one.path("assetId").asText());
        assertThat(one.path("id").asText()).isNotEqualTo(two.path("id").asText());
        assertThat(one.path("sha256").asText()).isNotEqualTo(two.path("sha256").asText());
        assertThat(two.path("versionNumber").asInt()).isEqualTo(2);
        assertThat(two.path("uploadedBy").asLong()).isEqualTo(editor.id());
        assertThat(get(content(team, one.path("id").asText()), editor).getResponse().getContentAsByteArray()).isEqualTo(first);
        assertThat(get(content(team, two.path("id").asText()), team.owner()).getResponse().getContentAsByteArray()).isEqualTo(second);
        assertThat(ok(get(base(team) + "/files/" + one.path("assetId").asText() + "/versions", editor))).hasSize(2);
        String fileId = db.queryForObject("select file_id from project_file_versions where id=?", String.class, one.path("id").asText());
        assertThat(media.findById(fileId).orElseThrow().getOwnerId()).isEqualTo(team.owner().id());
        assertThat(get("/api/files/" + fileId, editor).getResponse().getStatus()).isEqualTo(404);
        assertThat(get(content(team, one.path("id").asText()), stranger).getResponse().getStatus()).isEqualTo(404);
        assertThat(send(team.owner(), delete("/api/files/" + fileId), null).getResponse().getStatus()).isEqualTo(409);
        assertThat(get(content(team, one.path("id").asText()), editor).getResponse().getContentAsByteArray()).isEqualTo(first);
        // Old material-desk operations can shorten retention; cleanup must still preserve referenced history.
        storage.releaseRetention(media.findById(fileId).orElseThrow());
        db.update("update media_files set expires_at=1 where id=?", fileId);
        storage.purgeExpired();
        assertThat(get(content(team, one.path("id").asText()), editor).getResponse().getContentAsByteArray()).isEqualTo(first);
        JsonNode checks = ok(get(base(team) + "/checks", editor));
        assertThat(checks.path("summary").path("files").asInt()).isEqualTo(1);
        assertThat(checks.path("files").get(0).path("facts").path("integrity").asBoolean()).isTrue();
        assertThat(checks.toString()).contains("姓名").doesNotContain("版本二");
        assertThat(postJson(base(team) + "/assistant", Map.of("consent", false), editor).getResponse().getStatus()).isEqualTo(400);
    }

    @Test void documentScopeFiltersListsDownloadsActivityTasksAndFutureUploads() throws Exception {
        Team team = team(); User editor = register(); invite(team, editor, "EDITOR");
        JsonNode doc = document(team, "SELECTED", List.of()); String did = doc.path("id").asText();
        byte[] original = workbook("私密资料");
        JsonNode version = ok(uploadBytes(base(team) + "/documents/" + did + "/imports", original, "私密名单.xlsx", team.owner()));
        String vid = version.path("id").asText();
        ok(postJson(base(team) + "/files/" + vid + "/review", Map.of("status", "APPROVED", "note", "私密交付审批"), team.owner()));
        ok(postJson(base(team) + "/deliveries", Map.of("idempotencyKey", UUID.randomUUID().toString()), team.owner()));
        assertThat(ok(get(base(team) + "/documents", editor)).path("total").asInt()).isZero();
        assertThat(ok(get(base(team) + "/files", editor)).path("total").asInt()).isZero();
        assertThat(get(content(team, vid), editor).getResponse().getStatus()).isEqualTo(404);
        assertThat(get(base(team) + "/documents/" + did + "/versions", editor).getResponse().getStatus()).isEqualTo(404);
        assertThat(ok(get(base(team) + "/tasks", editor))).isEmpty();
        assertThat(ok(get(base(team) + "/activities", editor)).toString()).doesNotContain("FILE_UPLOADED", "DOCUMENT_LINKED");
        assertThat(uploadBytes(base(team) + "/documents/" + did + "/imports", original, "名单.xlsx", editor).getResponse().getStatus()).isEqualTo(404);
        Map<String, Object> grant = documentBody("SELECTED", List.of(editor.id())); grant.put("version", doc.path("version").asInt());
        doc = ok(send(team.owner(), patch(base(team) + "/documents/" + did), grant));
        assertThat(get(content(team, vid), editor).getResponse().getContentAsByteArray()).isEqualTo(original);
        JsonNode imported = ok(uploadBytes(base(team) + "/documents/" + did + "/imports", workbook("新版本"), "名单.xlsx", editor));
        assertThat(imported.path("assetId").asText()).isEqualTo(version.path("assetId").asText());
        assertThat(ok(get(base(team) + "/documents/" + did + "/versions", editor))).hasSize(2);
        Map<String, Object> widen = documentBody("ALL", List.of()); widen.put("version", doc.path("version").asInt());
        assertThat(send(editor, patch(base(team) + "/documents/" + did), widen).getResponse().getStatus()).isEqualTo(403);
        String asset = version.path("assetId").asText();
        ok(send(team.owner(), delete(base(team) + "/members/" + editor.id()), null));
        for (String path : List.of("/files", "/documents", "/activities", "/tasks", "/documents/" + did + "/versions", "/files/" + asset + "/versions", "/files/" + vid + "/content")) {
            assertThat(get(base(team) + path, editor).getResponse().getStatus()).as(path).isEqualTo(404);
        }
        invite(team, editor, "EDITOR");
        assertThat(ok(get(base(team) + "/documents", editor)).path("total").asInt()).isZero();
        assertThat(get(content(team, vid), editor).getResponse().getStatus()).isEqualTo(404);
        ok(send(team.owner(), delete(base(team) + "/documents/" + did), null));
        assertThat(get(content(team, vid), team.owner()).getResponse().getStatus()).isEqualTo(404);
    }

    @Test void invalidUrlsMalformedOfficeAndMismatchedExportsNeverProduceFalseVersions() throws Exception {
        Team team = team(); User editor = register(); invite(team, editor, "EDITOR");
        for (String url : List.of("https://docs.qq.com.evil.test/sheet/DAAAA", "https://evil@docs.qq.com/sheet/DAAAA", "https://docs.qq.com/sheet/DAAAA?redirect=https://evil.test", "javascript:alert(1)")) {
            var body = documentBody("ALL", List.of()); body.put("externalUrl", url);
            assertThat(postJson(base(team) + "/documents", body, team.owner()).getResponse().getStatus()).as(url).isEqualTo(400);
        }
        assertThat(db.queryForObject("select count(*) from project_documents where project_id=?", Integer.class, team.id())).isZero();
        JsonNode doc = document(team, "ALL", List.of()); String did = doc.path("id").asText();
        assertThat(uploadBytes(base(team) + "/documents/" + did + "/imports", new byte[]{1, 2, 3}, "假表格.xlsx", editor).getResponse().getStatus()).isBetween(400, 499);
        try (var out = new ByteArrayOutputStream(); var zip = new java.util.zip.ZipOutputStream(out)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("[Content_Types].xml")); zip.write("not valid Office".getBytes()); zip.closeEntry(); zip.finish();
            assertThat(uploadBytes(base(team) + "/documents/" + did + "/imports", out.toByteArray(), "伪造.xlsx", editor).getResponse().getStatus()).isBetween(400, 499);
        }
        assertThat(ok(get(base(team) + "/documents/" + did + "/versions", team.owner()))).isEmpty();
        assertThat(db.queryForObject("select count(*) from project_file_versions where project_id=?", Integer.class, team.id())).isZero();
    }

    @Test void optimisticMetadataAndTodosRejectStaleWritersAndReviewerControlsPublication() throws Exception {
        Team team = team(); User editor = register(), reviewer = register(); invite(team, editor, "EDITOR"); invite(team, reviewer, "REVIEWER");
        JsonNode original = ok(get(base(team), team.owner())); int revision = original.path("version").asInt();
        var pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            var one = pool.submit(() -> { start.await(); return send(team.owner(), patch(base(team)), Map.of("name", "修改甲", "description", "甲", "version", revision)).getResponse().getStatus(); });
            var two = pool.submit(() -> { start.await(); return send(editor, patch(base(team)), Map.of("name", "修改乙", "description", "乙", "version", revision)).getResponse().getStatus(); });
            start.countDown(); assertThat(List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        } finally { pool.shutdownNow(); }
        JsonNode v = upload(team, editor, workbook("待审核"), null); String vid = v.path("id").asText();
        assertThat(postJson(base(team) + "/files/" + vid + "/review", Map.of("status", "APPROVED", "note", "自行审批"), editor).getResponse().getStatus()).isEqualTo(403);
        ok(postJson(base(team) + "/files/" + vid + "/review", Map.of("status", "APPROVED", "note", "已核对原件"), reviewer));
        ok(postJson(base(team) + "/todos", Map.of("title", "完成归档", "assigneeId", editor.id()), editor));
        JsonNode todo = ok(get(base(team) + "/todos", editor)).get(0);
        int current = ok(get(base(team), team.owner())).path("version").asInt();
        assertThat(postJson(base(team) + "/publication", Map.of("status", "PUBLISHED", "version", current), team.owner()).getResponse().getStatus()).isEqualTo(400);
        Map<String, Object> done = Map.of("title", todo.path("title").asText(), "status", "DONE", "assigneeId", editor.id(), "version", todo.path("version").asInt());
        ok(send(editor, patch(base(team) + "/todos/" + todo.path("id").asText()), done));
        assertThat(send(editor, patch(base(team) + "/todos/" + todo.path("id").asText()), done).getResponse().getStatus()).isEqualTo(409);
        current = ok(get(base(team), team.owner())).path("version").asInt();
        assertThat(ok(postJson(base(team) + "/publication", Map.of("status", "PUBLISHED", "version", current), team.owner())).path("status").asText()).isEqualTo("PUBLISHED");
        upload(team, editor, workbook("后续变更"), v.path("assetId").asText());
        assertThat(ok(get(base(team), team.owner())).path("status").asText()).isEqualTo("DRAFT");
    }

    @Test void sseContainsOnlyInvalidationAndPresenceAndRevocationClosesAnExistingConnection() throws Exception {
        Team team = team(); User editor = register(); invite(team, editor, "EDITOR");
        MvcResult stream = mvc.perform(auth(requestGet(base(team) + "/events"), editor)).andExpect(request().asyncStarted()).andReturn();
        assertThat(events.online(team.id())).contains(editor.id());
        JsonNode secretDoc = document(team, "SELECTED", List.of());
        String did = secretDoc.path("id").asText();
        ok(uploadBytes(base(team) + "/documents/" + did + "/imports", workbook("私密单元格"), "不应发给成员.xlsx", team.owner()));
        String messages = stream.getResponse().getContentAsString();
        assertThat(messages).contains("snapshot", "presence", "change").doesNotContain(did, "不应发给成员", "私密单元格");
        ok(send(team.owner(), delete(base(team) + "/members/" + editor.id()), null));
        assertThatCode(() -> stream.getAsyncResult(3000)).doesNotThrowAnyException();
        assertThat(events.online(team.id())).doesNotContain(editor.id());
        assertThat(get(base(team) + "/events", editor).getResponse().getStatus()).isEqualTo(404);
        invite(team, editor, "VIEWER");
        MvcResult reconnect = mvc.perform(auth(requestGet(base(team) + "/events"), editor)).andExpect(request().asyncStarted()).andReturn();
        assertThat(reconnect.getResponse().getContentAsString()).contains("snapshot");
        reconnect.getRequest().getAsyncContext().complete();
        assertThat(ok(get(base(team) + "/members", team.owner())).toString()).contains(editor.username());
    }

    @Test void approvedFilesDeliverARealVerifiedZipAndProjectDeletionReleasesRetainedFiles() throws Exception {
        Team team = team(); User reviewer = register(); invite(team, reviewer, "REVIEWER");
        byte[] bytes = workbook("完整交付"); JsonNode version = upload(team, team.owner(), bytes, null); String vid = version.path("id").asText();
        ok(postJson(base(team) + "/files/" + vid + "/review", Map.of("status", "APPROVED", "note", "验收"), reviewer));
        String retry = UUID.randomUUID().toString();
        JsonNode submitted = ok(postJson(base(team) + "/deliveries", Map.of("idempotencyKey", retry), team.owner()));
        String taskId = submitted.path("id").asText();
        assertThat(ok(postJson(base(team) + "/deliveries", Map.of("idempotencyKey", retry), team.owner())).path("id").asText()).isEqualTo(taskId);
        JsonNode terminal = waitTask(taskId, team.owner(), 30000);
        assertThat(terminal.path("status").asText()).as(terminal.toString()).isEqualTo("SUCCEEDED");
        String output = null;
        for (int i = 0; i < 50 && output == null; i++) {
            projectTasks.synchronizeTasks();
            var result = db.queryForList("select output_version_id from project_tasks where task_id=?", String.class, taskId);
            if (!result.isEmpty()) output = result.get(0);
            if (output == null) Thread.sleep(100);
        }
        assertThat(output).isNotBlank();
        MvcResult download = get(content(team, output), team.owner());
        assertThat(download.getResponse().getStatus()).isEqualTo(200);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(download.getResponse().getContentAsByteArray()))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) entries.put(entry.getName(), zip.readAllBytes());
        }
        assertThat(entries).hasSize(2).containsKey("manifest.json");
        JsonNode manifest = mapper.readTree(entries.get("manifest.json"));
        assertThat(manifest).hasSize(1);
        JsonNode record = manifest.get(0); byte[] actual = entries.get(record.path("path").asText());
        assertThat(actual).isEqualTo(bytes);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(actual))).isEqualTo(record.path("sha256").asText());
        assertThat(actual.length).isEqualTo(record.path("sizeBytes").asInt());
        List<String> ids = db.queryForList("select file_id from project_file_versions where project_id=?", String.class, team.id());
        assertThat(ids).hasSize(2);
        for (String id : ids) assertThat(media.findById(id).orElseThrow().getExpiresAt()).isEqualTo(Long.MAX_VALUE);
        ok(send(team.owner(), delete(base(team)), null));
        for (String table : List.of("project_members", "project_documents", "project_assets", "project_file_versions", "project_activities", "project_todos", "project_tasks")) {
            assertThat(db.queryForObject("select count(*) from " + table + " where project_id=?", Integer.class, team.id())).as(table).isZero();
        }
        for (String id : ids) {
            var file = media.findById(id).orElseThrow();
            assertThat(file.getExpiresAt()).isGreaterThan(System.currentTimeMillis()).isLessThan(Long.MAX_VALUE);
            assertThat(Files.exists(storage.pathOf(file))).isTrue();
        }
        assertThat(get(base(team), reviewer).getResponse().getStatus()).isEqualTo(404);
    }
}
