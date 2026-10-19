package com.houqing.tools.video_compressor_backend.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.houqing.tools.video_compressor_backend.media.Tool;
import com.houqing.tools.video_compressor_backend.support.ApiTestBase;
import com.houqing.tools.video_compressor_backend.support.TestMedia;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Workspace overview, task-centre filtering and task input names (V2 navigation, P0 item 1). */
class WorkspaceApiTests extends ApiTestBase {

    @Test
    void summaryCountsOnlyTheCallersOwnTasksAndFiles() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE);
        User a = register(), b = register();
        String fileId = json(upload("/api/files/upload", TestMedia.get("demo.mp4"), "课程.mp4", a)).path("fileId").asText();
        String taskId = json(postJson("/api/audio/extract", Map.of("uniqueFilename", fileId, "targetFormat", "mp3"), a))
                .path("taskId").asText();
        waitTask(taskId, a, 60_000);

        JsonNode s = json(get("/api/v2/workspace/summary", a));
        assertThat(s.path("tasksByStatus").path("SUCCEEDED").asInt()).isEqualTo(1);
        assertThat(s.path("tasksByStatus").path("FAILED").asInt()).isZero();
        assertThat(s.path("inputs").path("count").asInt()).isEqualTo(1);
        assertThat(s.path("inputs").path("bytes").asLong()).isPositive();
        assertThat(s.path("outputs").path("count").asInt()).isEqualTo(1);
        assertThat(s.path("recentTasks").get(0).path("taskId").asText()).isEqualTo(taskId);
        assertThat(s.path("recentFiles").get(0).path("fileId").asText()).isEqualTo(fileId);
        assertThat(s.path("nextExpiryAt").asLong()).isGreaterThan(System.currentTimeMillis());

        JsonNode other = json(get("/api/v2/workspace/summary", b));
        assertThat(other.path("tasksByStatus").path("SUCCEEDED").asInt()).isZero();
        assertThat(other.path("inputs").path("count").asInt()).isZero();
        assertThat(other.path("recentTasks").size()).isZero();
        assertThat(other.path("recentFiles").size()).isZero();
        assertThat(other.path("nextExpiryAt").isMissingNode() || other.path("nextExpiryAt").isNull()).isTrue();

        assertThat(get("/api/v2/workspace/summary", null).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void taskListFiltersByStatusAndNamesItsInputs() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE);
        User u = register();
        String fileId = json(upload("/api/files/upload", TestMedia.get("demo.mp4"), "第一讲.mp4", u)).path("fileId").asText();
        String taskId = json(postJson("/api/audio/extract", Map.of("uniqueFilename", fileId, "targetFormat", "mp3"), u))
                .path("taskId").asText();
        JsonNode done = waitTask(taskId, u, 60_000);
        assertThat(done.path("inputs").get(0).path("fileId").asText()).isEqualTo(fileId);
        assertThat(done.path("inputs").get(0).path("filename").asText()).isEqualTo("第一讲.mp4");

        assertThat(json(get("/api/tasks?status=SUCCEEDED", u)).path("totalElements").asInt()).isEqualTo(1);
        assertThat(json(get("/api/tasks?status=QUEUED,RUNNING", u)).path("totalElements").asInt()).isZero();
        assertThat(json(get("/api/tasks?status=FAILED", u)).path("totalElements").asInt()).isZero();
        assertThat(json(get("/api/tasks", u)).path("totalElements").asInt()).isEqualTo(1);

        var bad = get("/api/tasks?status=DONE", u);
        assertThat(bad.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(bad).path("code").asText()).isEqualTo("VALIDATION_FAILED");

        // once the input is deleted the task keeps the id but no longer reveals a name
        mvc.perform(auth(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/files/" + fileId), u));
        JsonNode after = json(get("/api/tasks/" + taskId, u));
        assertThat(after.path("inputs").get(0).path("fileId").asText()).isEqualTo(fileId);
        assertThat(after.path("inputs").get(0).has("filename")).isFalse();
    }
}
