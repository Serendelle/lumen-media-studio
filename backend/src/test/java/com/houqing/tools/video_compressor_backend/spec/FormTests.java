package com.houqing.tools.video_compressor_backend.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.houqing.tools.video_compressor_backend.media.MediaFileRepository;
import com.houqing.tools.video_compressor_backend.support.ApiTestBase;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** The same full HTTP permission contract runs on SQLite and PostgreSQL; there are no paid model calls. */
class FormTests extends ApiTestBase {
    @Autowired JdbcTemplate db;
    @Autowired MediaFileRepository files;
    private MockHttpServletRequestBuilder get(String url) { return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url); }
    record Project(User owner, String id, String token, int version) {}
    record Participant(String token, String secret, User user) {}
    private String owner(Project p) { return "/api/v2/specs/" + p.id() + "/form"; }
    private String base(Participant a) { return "/api/public/collections/" + a.token() + "/form"; }
    private ObjectNode config(String view, String edit) {
        ObjectNode n = FormSchema.defaults(mapper).put("viewMode", view).put("editMode", edit);
        var fields = n.putArray("fields");
        fields.addObject().put("key", "department").put("label", "部门").put("type", "TEXT").put("required", true);
        fields.addObject().put("key", "quantity").put("label", "数量").put("type", "NUMBER").put("required", true);
        fields.addObject().put("key", "category").put("label", "类别").put("type", "SELECT").put("required", true).putArray("options").add("A").add("B");
        return n;
    }
    private MvcResult settings(Project p, ObjectNode c, int version) throws Exception {
        return mvc.perform(auth(put(owner(p)), p.owner()).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("config", c, "version", version)))).andReturn();
    }
    private Project project(String view, String edit) throws Exception {
        User owner = register();
        String id = json(postJson("/api/v2/specs", Map.of("name", "协作收集测试"), owner)).path("id").asText();
        Project before = new Project(owner, id, "", 0);
        assertThat(settings(before, config(view, edit), 0).getResponse().getStatus()).isEqualTo(200);
        MvcResult link = postJson("/api/v2/specs/" + id + "/collections", Map.of("days", 7), owner);
        assertThat(link.getResponse().getStatus()).as(link.getResponse().getContentAsString()).isEqualTo(200);
        return new Project(owner, id, json(link).path("path").asText().substring("/collect/".length()), 1);
    }
    private Participant participant(Project p, String name, User user) throws Exception {
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString().getBytes()).substring(0, 43);
        Participant a = new Participant(p.token(), secret, user);
        assertThat(send(a, post(base(a) + "/participants"), Map.of("name", name, "secret", secret)).getResponse().getStatus()).isEqualTo(200);
        return a;
    }
    private MvcResult send(Participant a, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("X-Participant-Token", a.secret()).with(r -> { r.setRemoteAddr("192.0.2." + (1 + Math.floorMod(a.secret().hashCode(), 253))); return r; });
        if (a.user() != null) auth(request, a.user());
        if (body != null) request.contentType("application/json").content(mapper.writeValueAsString(body));
        return mvc.perform(request).andReturn();
    }
    private Map<String, Object> values(String department, String quantity) { return Map.of("department", department, "quantity", quantity, "category", "A"); }
    private MvcResult create(Participant a, Object values, String key, int cv) throws Exception {
        return send(a, post(base(a) + "/rows"), Map.of("values", values, "requestKey", key, "configVersion", cv));
    }
    private JsonNode row(Participant a, String department, String quantity) throws Exception {
        var response = create(a, values(department, quantity), UUID.randomUUID().toString(), 1);
        assertThat(response.getResponse().getStatus()).as(response.getResponse().getContentAsString()).isEqualTo(200);
        return json(response);
    }
    private MvcResult update(Participant a, JsonNode row, Object values, int cv) throws Exception {
        return send(a, patch(base(a) + "/rows/" + row.path("id").asText()), Map.of("values", values, "version", row.path("version").asInt(), "configVersion", cv));
    }

    @Test void privateRowsCommentsStatisticsAndExportsCannotLeakAcrossSameNamedGuests() throws Exception {
        Project p = project("OWN", "OWN"); Participant a = participant(p, "同名", null), b = participant(p, "同名", null);
        JsonNode row = row(a, "隐私部门", "1.25"); row(b, "另一个部门", "9.75");
        JsonNode board = json(send(a, get(base(a) + "/rows"), null));
        assertThat(board.path("total").asInt()).isEqualTo(1);
        assertThat(board.toString()).contains("隐私部门").doesNotContain("另一个部门");
        String rowId = row.path("id").asText();
        assertThat(send(b, get(base(b) + "/rows/" + rowId + "/discussion"), null).getResponse().getStatus()).isEqualTo(404);
        assertThat(update(b, row, Map.of("quantity", "100"), 1).getResponse().getStatus()).isEqualTo(404);
        assertThat(send(a, post(base(a) + "/rows/" + rowId + "/comments"), Map.of("content", "仅我与负责人可见")).getResponse().getStatus()).isEqualTo(200);
        assertThat(json(send(a, get(base(a) + "/rows/" + rowId + "/discussion"), null)).path("comments").get(0).path("content").asText()).contains("仅我");
        JsonNode stats = json(send(a, get(base(a) + "/statistics"), null));
        assertThat(stats.path("rows").asInt()).isEqualTo(1); assertThat(stats.toString()).doesNotContain("另一个部门");
        assertThat(stats.path("fields").get(1).path("sum").asText()).isEqualTo("1.25");
        var exported = send(a, get(base(a) + "/export"), null);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(exported.getResponse().getContentAsByteArray()))) {
            assertThat(workbook.getSheetAt(0).getLastRowNum()).isEqualTo(1);
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(3).getStringCellValue()).isEqualTo("隐私部门");
        }
        assertThat(send(a, get(base(a) + "/rows"), null).getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(json(get(owner(p) + "/rows", p.owner())).path("total").asInt()).isEqualTo(2);
        assertThat(json(get("/api/v2/specs", p.owner())).path("items").get(0).path("records").asInt()).isEqualTo(2);
        assertThat(mvc.perform(get(base(a) + "/rows")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test void designatedAccountsHaveExplicitRolesAndRevocationAppliesToEveryReadPath() throws Exception {
        Project p = project("OWN", "DESIGNATED"); Participant creator = participant(p, "填表人", null); JsonNode r = row(creator, "研发", "2");
        User editor = register(), viewer = register();
        mvc.perform(auth(put(owner(p) + "/members"), p.owner()).contentType("application/json").content(mapper.writeValueAsString(Map.of("username", editor.username(), "role", "EDITOR"))));
        mvc.perform(auth(put(owner(p) + "/members"), p.owner()).contentType("application/json").content(mapper.writeValueAsString(Map.of("username", viewer.username(), "role", "VIEWER"))));
        Participant e = participant(p, "不可伪造的角色名", editor), v = participant(p, "只读", viewer);
        assertThat(json(send(e, get(base(e) + "/rows"), null)).path("total").asInt()).isEqualTo(1);
        assertThat(update(e, r, Map.of("quantity", "3"), 1).getResponse().getStatus()).isEqualTo(200);
        assertThat(update(v, r, Map.of("quantity", "4"), 1).getResponse().getStatus()).isEqualTo(403);
        assertThat(update(creator, r, Map.of("quantity", "4"), 1).getResponse().getStatus()).isEqualTo(403);
        assertThat(create(v, values("恶意", "1"), UUID.randomUUID().toString(), 1).getResponse().getStatus()).isEqualTo(403);
        assertThat(send(v, post(base(v) + "/rows/" + r.path("id").asText() + "/comments"), Map.of("content", "只读账号尝试留言")).getResponse().getStatus()).isEqualTo(403);
        ObjectNode revoke = mapper.createObjectNode().put("username", editor.username()).putNull("role");
        mvc.perform(auth(put(owner(p) + "/members"), p.owner()).contentType("application/json").content(revoke.toString()));
        assertThat(json(send(e, get(base(e) + "/rows"), null)).path("total").asInt()).isZero();
        assertThat(json(send(e, get(base(e) + "/statistics"), null)).path("rows").asInt()).isZero();
        assertThat(send(e, get(base(e) + "/rows/" + r.path("id").asText() + "/discussion"), null).getResponse().getStatus()).isEqualTo(404);
        assertThat(send(e, put(base(e)), Map.of("config", config("ALL", "ALL"), "version", 1)).getResponse().getStatus()).isEqualTo(403);
        assertThat(send(e, get(base(e) + "/members"), null).getResponse().getStatus()).isEqualTo(403);
    }

    @Test void sharedEditorsUseVersionsAndConcurrentWritesCannotOverwriteEachOther() throws Exception {
        Project p = project("ALL", "ALL"); Participant a = participant(p, "甲", null), b = participant(p, "乙", null);
        JsonNode r = row(a, "原值", "1");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            var one = executor.submit(() -> { start.await(); return update(a, r, Map.of("quantity", "2"), 1); });
            var two = executor.submit(() -> { start.await(); return update(b, r, Map.of("quantity", "3"), 1); });
            start.countDown();
            assertThat(List.of(one.get(30, TimeUnit.SECONDS).getResponse().getStatus(), two.get(30, TimeUnit.SECONDS).getResponse().getStatus())).containsExactlyInAnyOrder(200, 409);
        } finally { executor.shutdownNow(); }
        JsonNode latest = json(send(a, get(base(a) + "/rows"), null)).path("rows").get(0);
        String number = latest.path("values").path("quantity").asText();
        assertThat(update(b, latest, Map.of("department", "只改部门"), 1).getResponse().getStatus()).isEqualTo(200);
        latest = json(send(a, get(base(a) + "/rows"), null)).path("rows").get(0);
        assertThat(latest.path("values").path("quantity").asText()).isEqualTo(number);
        assertThat(send(b, delete(base(b) + "/rows/" + latest.path("id").asText()).param("version", latest.path("version").asText()), null).getResponse().getStatus()).isEqualTo(403);
        assertThat(json(send(a, get(base(a) + "/rows/" + r.path("id").asText() + "/discussion"), null)).path("events")).hasSize(3);
    }

    @Test void schemasValidateValuesAndCannotDestructivelyChangeExistingFields() throws Exception {
        Project p = project("OWN", "OWN"); Participant a = participant(p, "甲", null);
        assertThat(create(a, Map.of("department", "缺少必填"), UUID.randomUUID().toString(), 1).getResponse().getStatus()).isEqualTo(400);
        assertThat(create(a, values("部门", "NaN"), UUID.randomUUID().toString(), 1).getResponse().getStatus()).isEqualTo(400);
        assertThat(create(a, values("部门", "123456789012.123456"), UUID.randomUUID().toString(), 1).getResponse().getStatus()).isEqualTo(400);
        assertThat(create(a, Map.of("department", "部门", "quantity", "1", "category", "未知"), UUID.randomUUID().toString(), 1).getResponse().getStatus()).isEqualTo(400);
        JsonNode r = row(a, "正常", "0.1");
        assertThat(update(a, r, Map.of("creator_key", "other"), 1).getResponse().getStatus()).isEqualTo(400);
        ObjectNode changed = config("OWN", "OWN"); ((ObjectNode) changed.path("fields").get(0)).put("type", "NUMBER");
        assertThat(settings(p, changed, 1).getResponse().getStatus()).isEqualTo(400);
        ObjectNode added = config("OWN", "OWN");
        ((com.fasterxml.jackson.databind.node.ArrayNode) added.path("fields")).addObject().put("key", "date").put("label", "日期").put("type", "DATE").put("required", true);
        assertThat(settings(p, added, 1).getResponse().getStatus()).isEqualTo(200);
        assertThat(update(a, r, Map.of("date", "2026-02-31"), 2).getResponse().getStatus()).isEqualTo(400);
        assertThat(update(a, r, Map.of("date", "2026-10-21"), 2).getResponse().getStatus()).isEqualTo(200);
        assertThat(settings(p, added, 1).getResponse().getStatus()).isEqualTo(409);
    }

    @Test void submissionsAreIdempotentStatisticsUseExactDecimalsAndExportCannotExecuteFormulas() throws Exception {
        Project p = project("ALL", "ALL"); Participant a = participant(p, "甲", null), b = participant(p, "乙", null);
        String key = UUID.randomUUID().toString();
        var first = create(a, values("=HYPERLINK(\"http://example.invalid\")", "0.1"), key, 1);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(create(a, values("已变更的重试内容", "9"), key, 1)).path("id").asText()).isEqualTo(json(first).path("id").asText());
        assertThat(create(a, values("另一条", "0.1"), UUID.randomUUID().toString(), 1).getResponse().getStatus()).isEqualTo(409);
        row(b, "其他", "0.2");
        JsonNode stat = json(send(a, get(base(a) + "/statistics"), null));
        assertThat(stat.path("fields").get(1).path("sum").asText()).isEqualTo("0.3");
        assertThat(stat.path("fields").get(1).path("average").asText()).isEqualTo("0.15");
        assertThat(stat.path("fields").get(2).path("duplicates").asInt()).isEqualTo(1);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(send(a, get(base(a) + "/export"), null).getResponse().getContentAsByteArray()))) {
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(3).getCellType()).isEqualTo(CellType.STRING);
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(3).getStringCellValue()).startsWith("=HYPERLINK");
        }
    }

    @Test void recycleBinIsOwnerOnlyAndClosingOrRevokingCollectionStopsWrites() throws Exception {
        Project p = project("OWN", "OWN"); Participant a = participant(p, "甲", null); JsonNode r = row(a, "部门", "1");
        assertThat(send(a, delete(base(a) + "/rows/" + r.path("id").asText()).param("version", "1"), null).getResponse().getStatus()).isEqualTo(200);
        assertThat(json(send(a, get(base(a) + "/rows"), null)).path("total").asInt()).isZero();
        assertThat(send(a, get(base(a) + "/rows?deleted=true"), null).getResponse().getStatus()).isEqualTo(403);
        JsonNode deleted = json(get(owner(p) + "/rows?deleted=true", p.owner())).path("rows").get(0);
        assertThat(mvc.perform(auth(post(owner(p) + "/rows/" + r.path("id").asText() + "/restore?version=" + deleted.path("version").asText()), p.owner())).andReturn().getResponse().getStatus()).isEqualTo(200);
        ObjectNode closed = config("OWN", "OWN").put("accepting", false); settings(p, closed, 1);
        JsonNode current = json(send(a, get(base(a) + "/rows"), null)).path("rows").get(0);
        assertThat(update(a, current, Map.of("quantity", "9"), 2).getResponse().getStatus()).isEqualTo(403);
        assertThat(send(a, post(base(a) + "/rows/" + r.path("id").asText() + "/comments"), Map.of("content", "已关闭")).getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(auth(patch(owner(p) + "/rows/" + r.path("id").asText()), p.owner()).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("version", current.path("version").asInt(), "configVersion", 2, "values", Map.of("quantity", "5"))))).andReturn().getResponse().getStatus()).isEqualTo(200);
        JsonNode link = json(get("/api/v2/specs/" + p.id() + "/collections", p.owner())).get(0);
        mvc.perform(auth(delete("/api/v2/specs/" + p.id() + "/collections/" + link.path("id").asText()), p.owner()));
        assertThat(send(a, get(base(a) + "/export"), null).getResponse().getStatus()).isEqualTo(404);
        assertThat(send(a, get(base(a) + "/statistics"), null).getResponse().getStatus()).isEqualTo(404);
    }

    @Test void accountBindingRequiresJwtAndKeepsAnonymousCredentialFromBecomingAnAccountBackdoor() throws Exception {
        Project p = project("OWN", "OWN"); Participant guest = participant(p, "游客", null); row(guest, "我的记录", "1");
        User signedIn = register(), stranger = register(); Participant logged = new Participant(p.token(), guest.secret(), signedIn);
        assertThat(send(logged, post(base(logged) + "/participants"), Map.of("name", "游客", "secret", guest.secret())).getResponse().getStatus()).isEqualTo(200);
        assertThat(send(guest, get(base(guest) + "/rows"), null).getResponse().getStatus()).isEqualTo(401);
        assertThat(json(send(logged, get(base(logged) + "/rows"), null)).path("total").asInt()).isEqualTo(1);
        Participant other = new Participant(p.token(), guest.secret(), stranger);
        assertThat(send(other, post(base(other) + "/participants"), Map.of("name", "冒用", "secret", guest.secret())).getResponse().getStatus()).isEqualTo(401);
        Participant anotherDevice = participant(p, "另一个浏览器", signedIn);
        assertThat(json(send(anotherDevice, get(base(anotherDevice) + "/rows"), null)).path("total").asInt()).isEqualTo(1);
    }

    @Test void lockedModeAllowsSubmissionButOnlyOwnerCanChangeItAndMultipleModeIsExplicit() throws Exception {
        Project p = project("OWN", "LOCKED"); User editor = register();
        mvc.perform(auth(put(owner(p) + "/members"), p.owner()).contentType("application/json").content(mapper.writeValueAsString(Map.of("username", editor.username(), "role", "EDITOR"))));
        Participant a = participant(p, "普通参与者", null), e = participant(p, "编辑者", editor);
        JsonNode r = row(a, "部门", "1");
        assertThat(update(a, r, Map.of("quantity", "2"), 1).getResponse().getStatus()).isEqualTo(403);
        assertThat(update(e, r, Map.of("quantity", "2"), 1).getResponse().getStatus()).isEqualTo(403);
        assertThat(create(a, values("部门", "2"), UUID.randomUUID().toString(), 1).getResponse().getStatus()).isEqualTo(409);
        ObjectNode multiple = config("OWN", "OWN").put("allowMultiple", true); settings(p, multiple, 1);
        assertThat(create(a, values("第二条", "2"), UUID.randomUUID().toString(), 2).getResponse().getStatus()).isEqualTo(200);
        assertThat(json(send(a, get(base(a) + "/rows"), null)).path("total").asInt()).isEqualTo(2);
    }

    @Test void simultaneousRetriesCreateExactlyOneRow() throws Exception {
        Project p = project("OWN", "OWN"); Participant a = participant(p, "甲", null);
        String key = UUID.randomUUID().toString(); ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            var first = executor.submit(() -> { start.await(); return create(a, values("部门", "1"), key, 1); });
            var second = executor.submit(() -> { start.await(); return create(a, values("部门", "1"), key, 1); });
            start.countDown();
            var one = first.get(30, TimeUnit.SECONDS); var two = second.get(30, TimeUnit.SECONDS);
            assertThat(one.getResponse().getStatus()).isEqualTo(200); assertThat(two.getResponse().getStatus()).isEqualTo(200);
            assertThat(json(one).path("id").asText()).isEqualTo(json(two).path("id").asText());
            assertThat(json(send(a, get(base(a) + "/rows"), null)).path("total").asInt()).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }

    @Test void attachmentDownloadsRespectRowPermissionsAndProjectDeletionCascades() throws Exception {
        Project p = project("OWN", "OWN"); Participant a = participant(p, "甲", null), b = participant(p, "乙", null); JsonNode row = row(a, "部门", "1");
        ObjectNode spec = mapper.createObjectNode(); spec.putArray("slots").addObject().put("key", "document").put("label", "资料").put("kind", "DOCUMENT").putObject("rules").putArray("extensions").add("pdf");
        mvc.perform(auth(put("/api/v2/specs/" + p.id() + "/spec"), p.owner()).contentType("application/json").content(mapper.writeValueAsString(Map.of("version", 1, "spec", spec))));
        byte[] bytes; try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) { doc.addPage(new PDPage()); doc.save(out); bytes = out.toByteArray(); }
        String id = row.path("id").asText(), requestKey = UUID.randomUUID().toString();
        var request = multipart(base(a) + "/rows/" + id + "/files").file(new MockMultipartFile("file", "资料.pdf", "application/pdf", bytes))
                .param("slotKey", "document").param("requestKey", requestKey).header("X-Participant-Token", a.secret());
        var uploaded = mvc.perform(request).andReturn(); assertThat(uploaded.getResponse().getStatus()).as(uploaded.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode file = json(send(a, get(base(a) + "/rows"), null)).path("rows").get(0).path("files").get(0);
        assertThat(file.has("fileId")).isFalse(); String fileId = file.path("id").asText();
        assertThat(send(b, get(base(b) + "/rows/" + id + "/files/" + fileId), null).getResponse().getStatus()).isEqualTo(404);
        assertThat(send(a, get(base(a) + "/rows/" + id + "/files/" + fileId), null).getResponse().getContentAsByteArray()).isEqualTo(bytes);
        assertThat(json(send(b, get(base(b) + "/statistics"), null)).path("attachments").asInt()).isZero();
        assertThat(json(send(a, get(base(a) + "/statistics"), null)).path("attachments").asInt()).isEqualTo(1);
        JsonNode otherRow = row(b, "另一个部门", "2");
        var replay = mvc.perform(multipart(base(b) + "/rows/" + otherRow.path("id").asText() + "/files")
                .file(new MockMultipartFile("file", "其他.pdf", "application/pdf", bytes)).param("slotKey", "document")
                .param("requestKey", requestKey).header("X-Participant-Token", b.secret())).andReturn();
        assertThat(replay.getResponse().getStatus()).isEqualTo(409);
        assertThat(replay.getResponse().getContentAsString()).doesNotContain("资料.pdf");
        mvc.perform(auth(delete("/api/v2/specs/" + p.id()), p.owner()));
        for (String table : List.of("spec_forms", "spec_form_rows", "spec_form_participants", "spec_form_events", "spec_form_comments"))
            assertThat(db.queryForObject("select count(*) from " + table + " where project_id=?", Long.class, p.id())).isZero();
    }
}
