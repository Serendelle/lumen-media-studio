package com.houqing.tools.video_compressor_backend.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import com.houqing.tools.video_compressor_backend.media.*;
import com.houqing.tools.video_compressor_backend.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Real JWT, database and file-storage tests; seeded tasks need no external conversion tools. */
class TaskHistoryTests extends ApiTestBase {
    @Autowired TaskRepository tasks;
    @Autowired TaskHistoryService history;
    @Autowired TaskEngine engine;
    @Autowired StorageService storage;
    @Autowired JdbcTemplate db;

    @Test
    void deletingOneRecordPersistsAcrossReadsAndKeepsOriginalAndResultBytes() throws Exception {
        User owner = register();
        TaskEntity task = seed(owner, "SUCCEEDED");
        byte[] bytes = png();
        MediaFile input = storage.storeUpload(owner.id(), new MockMultipartFile("file", "原图.png", "image/png", bytes), "INPUT");
        var generated = Files.createTempFile(DATA_DIR.resolve("tmp"), "history-output-", ".png");
        Files.write(generated, bytes);
        MediaFile output = storage.registerOutput(owner.id(), generated, "结果.png", MediaKind.IMAGE, "image/png", task.getId());
        task.setInputFiles(mapper.writeValueAsString(List.of(input.getId())));
        task.setOutputFile(output.getId());
        tasks.saveAndFlush(task);
        String inputHash = hash(Files.readAllBytes(storage.pathOf(input)));
        String outputHash = hash(Files.readAllBytes(storage.pathOf(output)));

        assertThat(json(get("/api/tasks", owner)).path("totalElements").asInt()).isEqualTo(1);
        JsonNode deleted = ok(remove(task.getId(), owner));
        assertThat(deleted.path("deletedCount").asInt()).isEqualTo(1);
        assertThat(deleted.path("taskIds").get(0).asText()).isEqualTo(task.getId());
        for (String path : List.of("/api/tasks", "/api/tasks?status=SUCCEEDED"))
            assertThat(json(get(path, owner)).path("totalElements").asInt()).isZero();
        assertThat(get("/api/tasks/" + task.getId(), owner).getResponse().getStatus()).isEqualTo(404);
        assertThat(postJson("/api/tasks/" + task.getId() + "/retry", Map.of(), owner).getResponse().getStatus()).isEqualTo(404);
        assertThat(postJson("/api/tasks/" + task.getId() + "/cancel", Map.of(), owner).getResponse().getStatus()).isEqualTo(404);
        assertThat(get("/api/tasks/" + task.getId() + "/events", owner).getResponse().getStatus()).isEqualTo(404);
        JsonNode summary = json(get("/api/v2/workspace/summary", owner));
        assertThat(summary.path("tasksByStatus").path("SUCCEEDED").asInt()).isZero();
        assertThat(summary.path("recentTasks").isEmpty()).isTrue();
        assertThat(summary.path("outputs").path("count").asInt()).isEqualTo(1);

        assertThat(engine.require(owner.id(), task.getId()).getOutputFile()).isEqualTo(output.getId());
        assertThat(tasks.findById(task.getId()).orElseThrow().getHistoryDeletedAt()).isNotNull();
        for (MediaFile file : List.of(input, output)) {
            assertThat(get("/api/files/" + file.getId() + "/content", owner).getResponse().getContentAsByteArray()).isEqualTo(bytes);
        }
        assertThat(hash(Files.readAllBytes(storage.pathOf(input)))).isEqualTo(inputHash);
        assertThat(hash(Files.readAllBytes(storage.pathOf(output)))).isEqualTo(outputHash);
    }

    @Test
    void foreignMissingOrActiveRowsRejectTheWholeBatch() throws Exception {
        User owner = register(), other = register();
        TaskEntity done = seed(owner, "FAILED"), queued = seed(owner, "QUEUED"), running = seed(owner, "RUNNING");
        TaskEntity foreign = seed(other, "SUCCEEDED");
        assertThat(remove(foreign.getId(), owner).getResponse().getStatus()).isEqualTo(404);
        assertThat(remove(done.getId(), null).getResponse().getStatus()).isEqualTo(401);
        for (TaskEntity active : List.of(queued, running)) {
            assertThat(remove(active.getId(), owner).getResponse().getStatus()).isEqualTo(409);
            assertThat(batch(List.of(done.getId(), active.getId()), owner).getResponse().getStatus()).isEqualTo(409);
        }
        for (String unavailable : List.of(foreign.getId(), UUID.randomUUID().toString()))
            assertThat(batch(List.of(done.getId(), unavailable), owner).getResponse().getStatus()).isEqualTo(404);
        for (TaskEntity task : List.of(done, queued, running, foreign)) {
            assertThat(tasks.findById(task.getId()).orElseThrow().getHistoryDeletedAt()).isNull();
            assertThat(get("/api/tasks/" + task.getId(), task == foreign ? other : owner).getResponse().getStatus()).isEqualTo(200);
        }
    }

    @Test
    void batchDeletesTerminalStatesOnceAndValidatesTheBoundedPayload() throws Exception {
        User owner = register();
        List<TaskEntity> rows = List.of(seed(owner, "SUCCEEDED"), seed(owner, "FAILED"), seed(owner, "CANCELLED"));
        List<String> ids = rows.stream().map(TaskEntity::getId).toList();
        var duplicated = new ArrayList<>(ids); duplicated.add(ids.get(0));
        JsonNode deleted = ok(batch(duplicated, owner));
        assertThat(deleted.path("deletedCount").asInt()).isEqualTo(3);
        assertThat(deleted.path("taskIds").size()).isEqualTo(3);
        assertThat(json(get("/api/tasks", owner)).path("totalElements").asInt()).isZero();
        assertThat(batch(List.of(), owner).getResponse().getStatus()).isEqualTo(400);
        assertThat(postJson("/api/tasks/history/delete", Map.of(), owner).getResponse().getStatus()).isEqualTo(400);
        assertThat(batch(Collections.singletonList(null), owner).getResponse().getStatus()).isEqualTo(400);
        assertThat(batch(List.of(" "), owner).getResponse().getStatus()).isEqualTo(400);
        TaskEntity boundary = seed(owner, "FAILED");
        assertThat(batch(Collections.nCopies(201, boundary.getId()), owner).getResponse().getStatus()).isEqualTo(400);
        assertThat(tasks.findById(boundary.getId()).orElseThrow().getHistoryDeletedAt()).isNull();
        assertThat(ok(batch(Collections.nCopies(200, boundary.getId()), owner)).path("deletedCount").asInt()).isEqualTo(1);
    }

    @Test
    void clearKeepsOtherUsersActiveAndAfterCutoffRecordsAndIsIdempotent() throws Exception {
        User owner = register(), other = register();
        List<TaskEntity> finished = List.of(seed(owner, "SUCCEEDED"), seed(owner, "FAILED"), seed(owner, "CANCELLED"));
        TaskEntity legacy = seed(owner, "FAILED"); legacy.setFinishedAt(null); tasks.saveAndFlush(legacy);
        TaskEntity queued = seed(owner, "QUEUED"), running = seed(owner, "RUNNING"), foreign = seed(other, "FAILED");
        TaskEntity future = seed(owner, "SUCCEEDED"); future.setFinishedAt(System.currentTimeMillis() + 3_600_000); tasks.saveAndFlush(future);
        JsonNode cleared = ok(clear(owner));
        assertThat(cleared.path("deletedCount").asInt()).isEqualTo(4);
        assertThat(cleared.path("taskIds").isEmpty()).isTrue();
        assertThat(cleared.path("clearedBefore").asLong()).isPositive();
        for (TaskEntity row : finished) assertThat(get("/api/tasks/" + row.getId(), owner).getResponse().getStatus()).isEqualTo(404);
        assertThat(get("/api/tasks/" + legacy.getId(), owner).getResponse().getStatus()).isEqualTo(404);
        for (TaskEntity row : List.of(queued, running, future)) assertThat(get("/api/tasks/" + row.getId(), owner).getResponse().getStatus()).isEqualTo(200);
        assertThat(get("/api/tasks/" + foreign.getId(), other).getResponse().getStatus()).isEqualTo(200);
        assertThat(ok(clear(owner)).path("deletedCount").asInt()).isZero();
        TaskEntity later = seed(owner, "FAILED");
        assertThat(get("/api/tasks/" + later.getId(), owner).getResponse().getStatus()).isEqualTo(200);
        assertThat(json(get("/api/tasks", owner)).path("totalElements").asInt()).isEqualTo(4);
    }

    @Test
    void lateWorkerMergeCannotRestoreHistoryAndRetryCompetesWithDeletionAtomically() throws Exception {
        User owner = register();
        TaskEntity deleted = seed(owner, "FAILED");
        TaskEntity staleWorker = tasks.findById(deleted.getId()).orElseThrow();
        ok(remove(deleted.getId(), owner));
        staleWorker.setMessage("迟到的工作线程快照");
        tasks.saveAndFlush(staleWorker);
        assertThat(tasks.findById(deleted.getId()).orElseThrow().getHistoryDeletedAt()).isNotNull();
        assertThat(get("/api/tasks/" + deleted.getId(), owner).getResponse().getStatus()).isEqualTo(404);
        assertThat(tasks.requeueVisible(owner.id(), deleted.getId(), 1, System.currentTimeMillis())).isZero();

        TaskEntity retryWins = seed(owner, "CANCELLED");
        assertThat(tasks.requeueVisible(owner.id(), retryWins.getId(), 1, System.currentTimeMillis())).isEqualTo(1);
        assertThat(tasks.requeueVisible(owner.id(), retryWins.getId(), 1, System.currentTimeMillis())).isZero();
        assertThat(remove(retryWins.getId(), owner).getResponse().getStatus()).isEqualTo(409);
        TaskEntity queued = engine.require(owner.id(), retryWins.getId());
        assertThat(queued.getStatus()).isEqualTo("QUEUED");
        assertThat(queued.getAttempt()).isEqualTo(2);
        assertThat(queued.getHistoryDeletedAt()).isNull();
    }

    @Test
    void hiddenIdempotencyReplayDoesNotRevealOrRunTheOldTask() throws Exception {
        User owner = register();
        TaskEntity original = seed(owner, "FAILED");
        String key = UUID.randomUUID().toString(); original.setIdempotencyKey(key); tasks.saveAndFlush(original);
        ok(remove(original.getId(), owner));
        MvcResult replay = mvc.perform(auth(post("/api/tasks"), owner).header("Idempotency-Key", key)
                .contentType("application/json").content(mapper.writeValueAsString(Map.of("type", "document.convert", "inputs", List.of("not-opened"), "params", Map.of("targetFormat", "pdf"))))).andReturn();
        assertThat(replay.getResponse().getStatus()).isEqualTo(404);
        assertThat(json(replay).path("message").asText()).contains("删除");
        assertThat(db.queryForObject("select count(*) from tasks where owner_id=?", Integer.class, owner.id())).isEqualTo(1);
        assertThat(engine.require(owner.id(), original.getId()).getAttempt()).isEqualTo(1);
        assertThatThrownBy(() -> engine.retry(owner.id(), original.getId())).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void deletionKeepsProjectAndAiAuditReferencesReadable() throws Exception {
        User owner = register();
        TaskEntity task = seed(owner, "FAILED");
        String project = ok(postJson("/api/v2/projects", Map.of("name", "保留项目审计", "description", "只删除个人任务历史"), owner)).path("id").asText();
        db.update("insert into project_tasks(task_id,project_id,created_by,last_status,created_at,request_key) values(?,?,?,?,?,?)",
                task.getId(), project, owner.id(), "FAILED", task.getCreatedAt(), UUID.randomUUID().toString());
        String conversation = ok(postJson("/api/ai/conversations", Map.of("title", "保留 AI 审核依据"), owner)).path("id").asText();
        String context = mapper.writeValueAsString(Map.of("steps", List.of(Map.of("taskId", task.getId()))));
        db.update("update ai_conversations set context_json=? where id=?", context, conversation);
        ok(remove(task.getId(), owner));
        assertThat(get("/api/tasks/" + task.getId(), owner).getResponse().getStatus()).isEqualTo(404);
        assertThat(ok(get("/api/v2/projects/" + project + "/tasks", owner)).toString()).contains(task.getId());
        assertThat(ok(get("/api/ai/conversations/" + conversation, owner)).path("conversation").path("context").path("steps").get(0).path("taskId").asText()).isEqualTo(task.getId());
        assertThat(engine.require(owner.id(), task.getId()).getStatus()).isEqualTo("FAILED");
        assertThat(db.queryForObject("select count(*) from project_tasks where task_id=?", Integer.class, task.getId())).isEqualTo(1);
    }

    private TaskEntity seed(User owner, String status) {
        TaskEntity t = new TaskEntity(); long now = System.currentTimeMillis() - 1000;
        t.setId(UUID.randomUUID().toString()); t.setOwnerId(owner.id()); t.setType("document.convert"); t.setStatus(status);
        t.setProgress(status.equals("SUCCEEDED") ? 100 : 0); t.setParamsJson("{}"); t.setInputFiles("[]");
        t.setCreatedAt(now); t.setUpdatedAt(now); t.setOrigin("PRO");
        if (TaskStatus.valueOf(status).terminal()) t.setFinishedAt(now);
        return tasks.saveAndFlush(t);
    }
    private MvcResult remove(String id, User owner) throws Exception {
        var request = delete("/api/tasks/" + id);
        return mvc.perform(owner == null ? request : auth(request, owner)).andReturn();
    }
    private MvcResult clear(User owner) throws Exception { return mvc.perform(auth(delete("/api/tasks/history"), owner)).andReturn(); }
    private MvcResult batch(List<String> ids, User owner) throws Exception { return postJson("/api/tasks/history/delete", Map.of("taskIds", ids), owner); }
    private JsonNode ok(MvcResult response) throws Exception {
        assertThat(response.getResponse().getStatus()).as(response.getResponse().getContentAsString()).isBetween(200, 299);
        return json(response);
    }
    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB); var graphics = image.createGraphics();
        graphics.setColor(Color.BLUE); graphics.fillRect(0, 0, 8, 6); graphics.dispose();
        try (var out = new ByteArrayOutputStream()) { ImageIO.write(image, "png", out); return out.toByteArray(); }
        finally { image.flush(); }
    }
    private static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
}
