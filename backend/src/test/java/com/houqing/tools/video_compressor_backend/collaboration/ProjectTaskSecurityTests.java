package com.houqing.tools.video_compressor_backend.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import com.houqing.tools.video_compressor_backend.media.StorageService;
import com.houqing.tools.video_compressor_backend.media.TaskEngine;
import com.houqing.tools.video_compressor_backend.support.ApiTestBase;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/** Actual short ZIP tasks exercise permission propagation through generated and nested project files. */
class ProjectTaskSecurityTests extends ApiTestBase {
    @Autowired ProjectTaskService tasks;
    @Autowired TaskEngine engine;
    @Autowired StorageService storage;
    @Autowired JdbcTemplate db;

    private record Team(User owner, User member, String id) {}
    private String base(Team team) { return "/api/v2/projects/" + team.id(); }

    private JsonNode ok(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isBetween(200, 299);
        return json(result);
    }

    private MvcResult send(User user, MockHttpServletRequestBuilder request, Object body) throws Exception {
        if (body != null) request.contentType("application/json").content(mapper.writeValueAsString(body));
        return mvc.perform(auth(request, user)).andReturn();
    }

    private Team team() throws Exception {
        User owner = register(), member = register();
        String id = ok(postJson("/api/v2/projects", Map.of("name", "交付包权限传递测试", "description", "生成真实ZIP验证私密文件权限"), owner)).path("id").asText();
        Team team = new Team(owner, member, id);
        ok(postJson(base(team) + "/members", Map.of("username", member.username(), "role", "EDITOR"), owner));
        return team;
    }

    private byte[] workbook() throws Exception {
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("私密名单");
            sheet.createRow(0).createCell(0).setCellValue("秘密列");
            sheet.createRow(1).createCell(0).setCellValue("PRIVATE_CONTENT_" + UUID.randomUUID());
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private JsonNode document(Team team, List<Long> memberIds) throws Exception {
        return ok(postJson(base(team) + "/documents", Map.of(
                "title", "指定成员私密表格", "externalUrl", "https://docs.qq.com/sheet/DQnVuZGxlUHJpdmFjeQ",
                "documentType", "SPREADSHEET", "visibility", "SELECTED", "visibleUserIds", memberIds), team.owner()));
    }

    private void approve(Team team, String versionId) throws Exception {
        ok(postJson(base(team) + "/files/" + versionId + "/review", Map.of("status", "APPROVED", "note", "已核对实际版本"), team.owner()));
    }

    private JsonNode delivery(Team team) throws Exception {
        JsonNode task = ok(postJson(base(team) + "/deliveries", Map.of("idempotencyKey", UUID.randomUUID().toString()), team.owner()));
        String taskId = task.path("id").asText();
        JsonNode terminal = waitTask(taskId, team.owner(), 30000);
        assertThat(terminal.path("status").asText()).as(terminal.toString()).isEqualTo("SUCCEEDED");
        for (int attempt = 0; attempt < 60; attempt++) {
            tasks.synchronizeTasks();
            for (JsonNode current : ok(get(base(team) + "/tasks", team.owner()))) {
                if (current.path("id").asText().equals(taskId) && !current.path("outputVersionId").isNull()
                        && !current.path("outputVersionId").asText().isBlank()) {
                    String versionId = current.path("outputVersionId").asText();
                    for (JsonNode file : ok(get(base(team) + "/files?archived=true", team.owner())).path("items")) {
                        if (file.path("id").asText().equals(versionId)) return file;
                    }
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Succeeded delivery did not register its immutable output");
    }

    private void archive(Team team, JsonNode file, boolean archived) throws Exception {
        ok(send(team.owner(), patch(base(team) + "/files/" + file.path("assetId").asText()), Map.of("archived", archived)));
    }

    private MvcResult content(Team team, JsonNode file, User user) throws Exception {
        return get(base(team) + "/files/" + file.path("id").asText() + "/content", user);
    }

    private Map<String, byte[]> unzip(byte[] bytes) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) entries.put(entry.getName(), zip.readAllBytes());
        }
        return entries;
    }

    private void deniedEverywhere(Team team, JsonNode bundle) throws Exception {
        String versionId = bundle.path("id").asText();
        assertThat(content(team, bundle, team.member()).getResponse().getStatus()).isEqualTo(404);
        assertThat(get(base(team) + "/files/" + bundle.path("assetId").asText() + "/versions", team.member()).getResponse().getStatus()).isEqualTo(404);
        assertThat(ok(get(base(team) + "/files?archived=true", team.member())).toString()).doesNotContain(versionId);
        assertThat(ok(get(base(team) + "/files", team.member())).toString()).doesNotContain(versionId);
        assertThat(ok(get(base(team) + "/tasks", team.member())).toString()).doesNotContain(versionId);
    }

    @Test void aBundleNestedInsideAnotherBundleRetainsAllPrivateSourceGrants() throws Exception {
        Team team = team();
        JsonNode document = document(team, List.of());
        byte[] secret = workbook();
        JsonNode original = ok(uploadBytes(base(team) + "/documents/" + document.path("id").asText() + "/imports",
                secret, "private-source.xlsx", team.owner()));
        approve(team, original.path("id").asText());
        JsonNode innerBundle = delivery(team);
        deniedEverywhere(team, innerBundle);

        // A legitimate owner can retain a previous delivery as the next delivery's only source.
        archive(team, original, true);
        archive(team, innerBundle, false);
        approve(team, innerBundle.path("id").asText());
        JsonNode outerBundle = delivery(team);

        MvcResult ownerDownload = content(team, outerBundle, team.owner());
        assertThat(ownerDownload.getResponse().getStatus()).isEqualTo(200);
        Map<String, byte[]> outer = unzip(ownerDownload.getResponse().getContentAsByteArray());
        JsonNode outerManifest = mapper.readTree(outer.get("manifest.json"));
        assertThat(outerManifest).hasSize(1);
        Map<String, byte[]> inner = unzip(outer.get(outerManifest.get(0).path("path").asText()));
        JsonNode innerManifest = mapper.readTree(inner.get("manifest.json"));
        assertThat(innerManifest).hasSize(1);
        assertThat(inner.get(innerManifest.get(0).path("path").asText())).isEqualTo(secret);

        deniedEverywhere(team, innerBundle);
        deniedEverywhere(team, outerBundle);
    }

    @Test void narrowingADocumentsScopeRevokesAnExistingDeliveryWithoutDeletingItsOriginal() throws Exception {
        Team team = team();
        JsonNode document = document(team, List.of(team.member().id()));
        byte[] secret = workbook();
        JsonNode original = ok(uploadBytes(base(team) + "/documents/" + document.path("id").asText() + "/imports",
                secret, "previously-shared-source.xlsx", team.owner()));
        approve(team, original.path("id").asText());
        JsonNode bundle = delivery(team);
        assertThat(content(team, bundle, team.member()).getResponse().getStatus()).isEqualTo(200);
        assertThat(ok(get(base(team) + "/files?archived=true", team.member())).toString()).contains(bundle.path("id").asText());

        ok(send(team.owner(), patch(base(team) + "/documents/" + document.path("id").asText()), Map.of(
                "visibility", "SELECTED", "visibleUserIds", List.of(), "version", document.path("version").asInt())));

        deniedEverywhere(team, bundle);
        assertThat(content(team, original, team.member()).getResponse().getStatus()).isEqualTo(404);
        assertThat(content(team, original, team.owner()).getResponse().getContentAsByteArray()).isEqualTo(secret);
        assertThat(content(team, bundle, team.owner()).getResponse().getStatus()).isEqualTo(200);
    }

    @Test void publicationCannotUseAnApprovedBundleWhoseUnlinkedSourceIsNowInaccessible() throws Exception {
        Team team = team();
        JsonNode document = document(team, List.of());
        JsonNode original = ok(uploadBytes(base(team) + "/documents/" + document.path("id").asText() + "/imports",
                workbook(), "unlinked-source.xlsx", team.owner()));
        approve(team, original.path("id").asText());
        JsonNode bundle = delivery(team);
        archive(team, original, true);
        archive(team, bundle, false);
        approve(team, bundle.path("id").asText());
        ok(send(team.owner(), delete(base(team) + "/documents/" + document.path("id").asText()), null));

        assertThat(content(team, bundle, team.owner()).getResponse().getStatus()).isEqualTo(404);
        assertThat(ok(get(base(team) + "/files", team.owner())).path("total").asInt()).isZero();
        int revision = ok(get(base(team), team.owner())).path("version").asInt();
        MvcResult publish = postJson(base(team) + "/publication", Map.of("status", "PUBLISHED", "version", revision), team.owner());
        assertThat(publish.getResponse().getStatus()).isEqualTo(400);
        assertThat(ok(get(base(team), team.owner())).path("status").asText()).isEqualTo("DRAFT");
    }

    @Test void reusingAnAssociatedTaskKeyForDifferentInputsConflictsWithoutChangingItsOriginalTask() throws Exception {
        Team team = team();
        JsonNode firstFile = ok(uploadBytes(base(team) + "/files", workbook(), "original-intent.xlsx", team.owner()));
        approve(team, firstFile.path("id").asText());
        String key = UUID.randomUUID().toString();
        JsonNode firstTask = ok(postJson(base(team) + "/deliveries", Map.of("idempotencyKey", key), team.owner()));
        String taskId = firstTask.path("id").asText();
        assertThat(waitTask(taskId, team.owner(), 30000).path("status").asText()).isEqualTo("SUCCEEDED");
        var original = engine.require(team.owner().id(), taskId);
        String originalInputs = original.getInputFiles(), originalParams = original.getParamsJson();
        // An unchanged retry still has the same association and never creates a second task.
        assertThat(ok(postJson(base(team) + "/deliveries", Map.of("idempotencyKey", key), team.owner())).path("id").asText()).isEqualTo(taskId);

        JsonNode additional = ok(uploadBytes(base(team) + "/files", workbook(), "different-input.xlsx", team.owner()));
        approve(team, additional.path("id").asText());
        MvcResult replay = postJson(base(team) + "/deliveries", Map.of("idempotencyKey", key), team.owner());

        assertThat(replay.getResponse().getStatus()).isEqualTo(409);
        assertThat(db.queryForList("select task_id from project_tasks where project_id=? and request_key=?", String.class, team.id(), key)).containsExactly(taskId);
        var unchanged = engine.require(team.owner().id(), taskId);
        assertThat(unchanged.getInputFiles()).isEqualTo(originalInputs);
        assertThat(unchanged.getParamsJson()).isEqualTo(originalParams);
        assertThat(db.queryForObject("select count(*) from tasks where owner_id=? and idempotency_key=?", Integer.class,
                team.owner().id(), "project:" + team.id() + ":" + key)).isEqualTo(1);
    }

    @Test void aPreexistingRealEngineTaskWithDifferentParamsCannotAcquireAProjectAssociation() throws Exception {
        Team team = team();
        byte[] originalBytes = workbook();
        JsonNode source = ok(uploadBytes(base(team) + "/files", originalBytes, "engine-intent.xlsx", team.owner()));
        approve(team, source.path("id").asText());
        String fileId = db.queryForObject("select file_id from project_file_versions where id=?", String.class, source.path("id").asText());
        String key = UUID.randomUUID().toString();
        var snapshot = mapper.createObjectNode();
        snapshot.put("projectId", team.id());
        snapshot.put("snapshotTag", "different-parameters-from-the-project-request");
        snapshot.putArray("entries").addObject().put("versionId", source.path("id").asText()).put("fileId", fileId).put("sha256", source.path("sha256").asText());
        // Reproduce an engine winner that committed before the project association (e.g. a concurrent request
        // or interrupted association). The engine still creates and verifies a real ZIP, with no test handler.
        var existing = engine.submit(team.owner().id(), "project.deliver", snapshot, List.of(fileId), "PROJECT", "project:" + team.id() + ":" + key);
        assertThat(waitTask(existing.getId(), team.owner(), 30000).path("status").asText()).isEqualTo("SUCCEEDED");
        var engineOutput = storage.require(team.owner().id(), engine.require(team.owner().id(), existing.getId()).getOutputFile());
        Map<String, byte[]> zip = unzip(Files.readAllBytes(storage.pathOf(engineOutput)));
        JsonNode manifest = mapper.readTree(zip.get("manifest.json"));
        assertThat(zip.get(manifest.get(0).path("path").asText())).isEqualTo(originalBytes);

        MvcResult collision = postJson(base(team) + "/deliveries", Map.of("idempotencyKey", key), team.owner());

        assertThat(collision.getResponse().getStatus()).isEqualTo(409);
        assertThat(db.queryForObject("select count(*) from project_tasks where project_id=? and request_key=?", Integer.class, team.id(), key)).isZero();
        var unchanged = engine.require(team.owner().id(), existing.getId());
        assertThat(mapper.readTree(unchanged.getParamsJson())).isEqualTo(snapshot);
        assertThat(mapper.readTree(unchanged.getInputFiles())).isEqualTo(mapper.valueToTree(List.of(fileId)));
        assertThat(unchanged.getStatus()).isEqualTo("SUCCEEDED");
    }

    @Test void aSuccessfulZipThatExceedsTheVersionQuotaReportsAStableOutputRegistrationFailure() throws Exception {
        Team team = team();
        byte[] originalBytes = workbook();
        JsonNode original = ok(uploadBytes(base(team) + "/files", originalBytes, "quota-boundary.xlsx", team.owner()));
        String assetId = original.path("assetId").asText();
        String fileId = db.queryForObject("select file_id from project_file_versions where id=?", String.class, original.path("id").asText());
        long now = System.currentTimeMillis();
        List<Object[]> history = new ArrayList<>();
        // Quota-boundary fixture: 2,000 logical historical versions reuse the exact same real file bytes,
        // original size and actual SHA-256. No media-file sizes or checksums are invented or enlarged.
        // Only the latest version is approved, so the real delivery has exactly one valid input.
        for (int number = 2; number <= 2000; number++) {
            history.add(new Object[]{UUID.randomUUID().toString(), assetId, team.id(), fileId, number,
                    team.owner().id(), now + number, original.path("sha256").asText(), number == 2000 ? "APPROVED" : "PENDING"});
        }
        db.batchUpdate("insert into project_file_versions(id,asset_id,project_id,file_id,version_number,uploaded_by,created_at,sha256,review_status) values(?,?,?,?,?,?,?,?,?)", history);
        assertThat(db.queryForObject("select count(*) from project_file_versions where project_id=?", Integer.class, team.id())).isEqualTo(2000);
        assertThat(storage.require(team.owner().id(), fileId).getSizeBytes()).isEqualTo(originalBytes.length);
        assertThat(ProjectFileService.sha256(storage.pathOf(storage.require(team.owner().id(), fileId)))).isEqualTo(original.path("sha256").asText());

        JsonNode submitted = ok(postJson(base(team) + "/deliveries", Map.of("idempotencyKey", UUID.randomUUID().toString()), team.owner()));
        String taskId = submitted.path("id").asText();
        assertThat(waitTask(taskId, team.owner(), 30000).path("status").asText()).isEqualTo("SUCCEEDED");
        var actualOutput = storage.require(team.owner().id(), engine.require(team.owner().id(), taskId).getOutputFile());
        Map<String, byte[]> zip = unzip(Files.readAllBytes(storage.pathOf(actualOutput)));
        JsonNode manifest = mapper.readTree(zip.get("manifest.json"));
        assertThat(manifest).hasSize(1);
        assertThat(zip.get(manifest.get(0).path("path").asText())).isEqualTo(originalBytes);

        JsonNode shared = null;
        for (int attempt = 0; attempt < 60; attempt++) {
            tasks.synchronizeTasks();
            for (JsonNode row : ok(get(base(team) + "/tasks", team.member()))) {
                if (row.path("id").asText().equals(taskId)) shared = row;
            }
            if (shared != null && shared.path("status").asText().equals("OUTPUT_UNAVAILABLE")) break;
            Thread.sleep(100);
        }
        assertThat(shared).isNotNull();
        assertThat(shared.path("status").asText()).isEqualTo("OUTPUT_UNAVAILABLE");
        assertThat(shared.path("processingStatus").asText()).isEqualTo("SUCCEEDED");
        assertThat(shared.path("errorCode").asText()).isEqualTo("PROJECT_OUTPUT_UNAVAILABLE");
        assertThat(shared.path("message").asText()).contains("2000");
        assertThat(shared.path("outputVersionId").isNull()).isTrue();
        assertThat(db.queryForObject("select last_status from project_tasks where task_id=?", String.class, taskId)).isEqualTo("OUTPUT_UNAVAILABLE");
        assertThat(db.queryForObject("select output_error from project_tasks where task_id=?", String.class, taskId)).contains("2000");
        long auditCount = db.queryForObject("select count(*) from project_activities where project_id=? and action='TASK_OUTPUT_UNAVAILABLE'", Long.class, team.id());
        assertThat(auditCount).isPositive();
        for (int attempt = 0; attempt < 3; attempt++) tasks.synchronizeTasks();
        assertThat(db.queryForObject("select count(*) from project_activities where project_id=? and action='TASK_OUTPUT_UNAVAILABLE'", Long.class, team.id())).isEqualTo(auditCount);
        assertThat(db.queryForObject("select count(*) from project_file_versions where project_id=?", Integer.class, team.id())).isEqualTo(2000);
        assertThat(db.queryForObject("select output_version_id from project_tasks where task_id=?", String.class, taskId)).isNull();
    }
}
