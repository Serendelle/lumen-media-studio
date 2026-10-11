package com.houqing.tools.video_compressor_backend.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.houqing.tools.video_compressor_backend.media.Tool;
import com.houqing.tools.video_compressor_backend.support.ApiTestBase;
import com.houqing.tools.video_compressor_backend.support.TestMedia;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Authentication, authorization (horizontal isolation), validation and error hygiene over the real stack. */
class ApiSecurityTests extends ApiTestBase {

    // ---------------- authentication ----------------

    @Test
    void protectedEndpointsRequireToken() throws Exception {
        for (String url : List.of("/api/tasks", "/api/files", "/api/auth/me", "/api/tasks/x", "/api/video/progress/x")) {
            MvcResult r = get(url, null);
            assertThat(r.getResponse().getStatus()).as(url).isEqualTo(401);
            assertThat(json(r).path("code").asText()).isEqualTo("UNAUTHORIZED");
        }
        assertThat(postJson("/api/video/compress", Map.of("uniqueFilename", "x"), null).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void forgedOrMalformedTokensAreRejected() throws Exception {
        // HS256 token signed with a different secret, otherwise well formed
        String forged = "eyJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJsdW1pZXJlLW1lZGlhIiwic3ViIjoiMSIsImV4cCI6NDEwMjQ0NDgwMH0."
                + "c2lnbmF0dXJlLW5vdC12YWxpZC1mb3ItdGhpcy1zZXJ2ZXI";
        for (String t : List.of(forged, "not-a-jwt", "")) {
            int s = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/tasks")
                    .header("Authorization", "Bearer " + t)).andReturn().getResponse().getStatus();
            assertThat(s).isEqualTo(401);
        }
    }

    @Test
    void registrationValidationAndDuplicates() throws Exception {
        assertThat(postJson("/api/auth/register", Map.of("username", "ab", "password", "Passw0rd!123"), null)
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(postJson("/api/auth/register", Map.of("username", "valid_name1", "password", "short"), null)
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(postJson("/api/auth/register", Map.of("username", "bad/name", "password", "Passw0rd!123"), null)
                .getResponse().getStatus()).isEqualTo(400);
        User u = register();
        MvcResult dup = postJson("/api/auth/register", Map.of("username", u.username().toUpperCase(), "password", "Passw0rd!123"), null);
        assertThat(dup.getResponse().getStatus()).isEqualTo(409); // case-insensitive uniqueness
    }

    @Test
    void concurrentRegistrationOfSameNameYieldsOneAccount() throws Exception {
        String name = "race_" + UUID.randomUUID().toString().substring(0, 8);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> fs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                return postJson("/api/auth/register", Map.of("username", name, "password", "Passw0rd!123"), null)
                        .getResponse().getStatus();
            }));
        }
        go.countDown();
        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> f : fs) codes.add(f.get(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertThat(codes).containsOnly(200, 409);
        assertThat(codes.stream().filter(c -> c == 200).count()).isEqualTo(1);
    }

    @Test
    void loginSuccessFailureAndLockout() throws Exception {
        User u = register();
        assertThat(postJson("/api/auth/login", Map.of("username", u.username(), "password", "Passw0rd!123"), null)
                .getResponse().getStatus()).isEqualTo(200);
        MvcResult bad = postJson("/api/auth/login", Map.of("username", u.username(), "password", "wrong-pass"), null);
        assertThat(bad.getResponse().getStatus()).isEqualTo(401);
        // unknown user gives the same answer as a wrong password (no username enumeration)
        MvcResult unknown = postJson("/api/auth/login", Map.of("username", "nobody_" + UUID.randomUUID().toString().substring(0, 6), "password", "wrong-pass"), null);
        assertThat(json(unknown).path("code").asText()).isEqualTo(json(bad).path("code").asText());
        int last = 0;
        for (int i = 0; i < 10; i++) {
            last = postJson("/api/auth/login", Map.of("username", u.username(), "password", "wrong-pass"), null).getResponse().getStatus();
        }
        assertThat(last).isEqualTo(429);
    }

    // ---------------- horizontal isolation ----------------

    @Test
    void usersCannotSeeOrUseEachOthersFilesAndTasks() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE, Tool.EXIFTOOL);
        User a = register(), b = register();
        String fileId = json(upload("/api/video/upload", TestMedia.get("demo.mp4"), "demo.mp4", a)).path("uniqueFilename").asText();
        JsonNode task = json(postJson("/api/audio/extract", Map.of("uniqueFilename", fileId, "targetFormat", "mp3"), a));
        String taskId = task.path("taskId").asText();
        waitTask(taskId, a, 60_000);

        assertThat(get("/api/files/" + fileId, b).getResponse().getStatus()).isEqualTo(404);
        assertThat(get("/api/tasks/" + taskId, b).getResponse().getStatus()).isEqualTo(404);
        assertThat(get("/api/audio/progress/" + taskId, b).getResponse().getStatus()).isEqualTo(404);
        assertThat(postJson("/api/tasks/" + taskId + "/retry", Map.of(), b).getResponse().getStatus()).isEqualTo(404);
        assertThat(postJson("/api/files/" + fileId + "/ticket", Map.of(), b).getResponse().getStatus()).isEqualTo(404);
        assertThat(postJson("/api/video/compress", Map.of("uniqueFilename", fileId), b).getResponse().getStatus()).isEqualTo(404);
        assertThat(postJson("/api/metadata/view", Map.of("uniqueFilename", fileId), b).getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(auth(delete("/api/files/" + fileId), b)).andReturn().getResponse().getStatus()).isEqualTo(404);

        String outId = json(get("/api/tasks/" + taskId, a)).path("downloadFilename").asText();
        assertThat(get("/api/video/download/" + outId, b).getResponse().getStatus()).isEqualTo(404);
        assertThat(get("/api/video/download/" + outId, null).getResponse().getStatus()).isEqualTo(401);
        assertThat(get("/api/video/download/" + outId, a).getResponse().getStatus()).isEqualTo(200);

        // a ticket A issued for one file does not open another file, and B's lists stay empty
        String ticket = json(postJson("/api/files/" + outId + "/ticket", Map.of(), a)).path("ticket").asText();
        assertThat(get("/api/files/" + outId + "/content?ticket=" + ticket, null).getResponse().getStatus()).isEqualTo(200);
        assertThat(get("/api/files/" + fileId + "/content?ticket=" + ticket, null).getResponse().getStatus()).isEqualTo(401);
        assertThat(json(get("/api/tasks", b)).path("totalElements").asInt()).isZero();
        assertThat(json(get("/api/files?role=OUTPUT", b)).path("totalElements").asInt()).isZero();
    }

    @Test
    void pathLikeIdentifiersNeverReachTheFileSystem() throws Exception {
        User a = register();
        for (String id : List.of("..\\..\\secret.png", "../../etc/passwd", "C:\\Windows\\win.ini", "%2e%2e%2fsecret")) {
            MvcResult r = postJson("/api/metadata/view", Map.of("uniqueFilename", id), a);
            assertThat(r.getResponse().getStatus()).as(id).isEqualTo(404);
            r = postJson("/api/video/compress", Map.of("uniqueFilename", id, "mode", "crf"), a);
            assertThat(r.getResponse().getStatus()).as(id).isEqualTo(404);
        }
    }

    // ---------------- validation & error hygiene ----------------

    @Test
    void invalidParametersAreRejectedWithFieldErrors() throws Exception {
        User a = register();
        MvcResult r = postJson("/api/video/compress", Map.of("uniqueFilename", "x", "mode", "crf", "crfValue", 99), a);
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(r).path("details").path("fields").has("crfValue")).isTrue();

        List<Map<String, Object>> bad = List.of(
                Map.of("uniqueFilename", "x", "targetFormat", "x\\..\\..\\PWNED.mp4"),
                Map.of("uniqueFilename", "x", "targetFormat", "exe"),
                Map.of("uniqueFilename", "", "targetFormat", "mp4"));
        for (Map<String, Object> body : bad) {
            assertThat(postJson("/api/video/convert", body, a).getResponse().getStatus()).as(body.toString()).isEqualTo(400);
        }
        assertThat(postJson("/api/audio/extract", Map.of("uniqueFilename", "x", "targetFormat", "exe"), a).getResponse().getStatus()).isEqualTo(400);
        assertThat(postJson("/api/video/watermark", Map.of("videoUniqueFilename", "x", "watermarkUniqueFilename", "y", "opacity", 5), a).getResponse().getStatus()).isEqualTo(400);
        assertThat(postJson("/api/metadata/modify", Map.of("uniqueFilename", "x", "dateTimeOriginal", "yesterday"), a).getResponse().getStatus()).isEqualTo(400);
        assertThat(postJson("/api/metadata/modify", Map.of("uniqueFilename", "x", "gpsLatitude", 91), a).getResponse().getStatus()).isEqualTo(400);
        assertThat(postJson("/api/image/process", Map.of("filenames", List.of(), "tool", "compress"), a).getResponse().getStatus()).isEqualTo(400);
        // malformed JSON
        int s = mvc.perform(auth(post("/api/video/compress").contentType(MediaType.APPLICATION_JSON).content("{not json"), a))
                .andReturn().getResponse().getStatus();
        assertThat(s).isEqualTo(400);
    }

    @Test
    void handlerValidationRejectsMissingFieldsInsteadOfLeavingTasksStuck() throws Exception {
        // original bug H-07: {"tool":"compress"} without filenames left a task PENDING forever
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE, Tool.MAGICK);
        User a = register();
        assertThat(postJson("/api/image/process", Map.of("tool", "compress"), a).getResponse().getStatus()).isEqualTo(400);
        String img = json(upload("/api/image/upload", TestMedia.get("logo.png"), "logo.png", a)).path("uniqueFilename").asText();
        MvcResult r = postJson("/api/image/process", Map.of("filenames", List.of(img), "tool", "explode"), a);
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(get("/api/tasks", a)).path("totalElements").asInt()).isZero(); // nothing was queued
    }

    @Test
    void errorResponsesNeverLeakServerPaths() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE);
        User a = register();
        List<MvcResult> results = new ArrayList<>();
        results.add(uploadBytes("/api/video/upload", "plain text".getBytes(), "../../PLANTED.txt", a));
        results.add(upload("/api/video/upload", TestMedia.get("truncated.mp4"), "broken.mp4", a));
        results.add(postJson("/api/metadata/view", Map.of("uniqueFilename", "nope"), a));
        results.add(postJson("/api/video/compress", Map.of("uniqueFilename", "nope"), a));
        String root = DATA_DIR.toString();
        for (MvcResult r : results) {
            String body = r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertThat(r.getResponse().getStatus()).isBetween(400, 499);
            assertThat(body).doesNotContain(root).doesNotContain(":\\").doesNotContain("Exception").doesNotContain("at com.");
        }
        assertThat(results.get(0).getResponse().getStatus()).isEqualTo(415);
        assertThat(results.get(1).getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void uploadsAreCheckedByContentAndEndpoint() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE, Tool.EXIFTOOL);
        User a = register();
        // jpg disguised as mp4 is detected as an image and refused by the video endpoint
        assertThat(upload("/api/video/upload", TestMedia.get("photo.jpg"), "movie.mp4", a).getResponse().getStatus()).isEqualTo(415);
        // video refused by image endpoint
        assertThat(upload("/api/image/upload", TestMedia.get("demo.mp4"), "demo.mp4", a).getResponse().getStatus()).isEqualTo(415);
        // the stored extension follows the content, and the client name is kept only for display
        JsonNode ok = json(upload("/api/image/upload", TestMedia.get("photo.jpg"), "..\\..\\evil.png", a));
        assertThat(ok.path("file").path("mimeType").asText()).isEqualTo("image/jpeg");
        assertThat(ok.path("file").path("originalFilename").asText()).isEqualTo("evil.png");
        // empty upload
        assertThat(uploadBytes("/api/files/upload", new byte[0], "x.png", a).getResponse().getStatus()).isEqualTo(400);
        // truncated video is rejected and not kept
        assertThat(upload("/api/files/upload", TestMedia.get("truncated.mp4"), "t.mp4", a).getResponse().getStatus()).isEqualTo(422);
        assertThat(json(get("/api/files", a)).path("totalElements").asInt()).isEqualTo(1);
    }

    // ---------------- concurrency, idempotency, quotas ----------------

    @Test
    void concurrentSubmitsWithSameIdempotencyKeyCreateOneTask() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE);
        User a = register();
        String fileId = json(upload("/api/video/upload", TestMedia.get("demo.mp4"), "demo.mp4", a)).path("uniqueFilename").asText();
        String key = UUID.randomUUID().toString();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<String>> futures = new ArrayList<>();
        CountDownLatch go = new CountDownLatch(1);
        for (int i = 0; i < 6; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                MvcResult r = mvc.perform(auth(post("/api/audio/extract").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key)
                        .content("{\"uniqueFilename\":\"" + fileId + "\",\"targetFormat\":\"wav\"}"), a)).andReturn();
                return r.getResponse().getStatus() + ":" + json(r).path("taskId").asText();
            }));
        }
        go.countDown();
        Set<String> results = new HashSet<>();
        for (Future<String> f : futures) results.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();
        assertThat(results).hasSize(1);
        assertThat(results.iterator().next()).startsWith("200:");
        assertThat(json(get("/api/tasks", a)).path("totalElements").asInt()).isEqualTo(1);
        waitTask(results.iterator().next().substring(4), a, 60_000);
    }

    @Test
    void perUserActiveTaskLimitIsEnforced() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE);
        User a = register();
        String fileId = json(upload("/api/video/upload", TestMedia.get("demo.mp4"), "demo.mp4", a)).path("uniqueFilename").asText();
        List<String> ids = new ArrayList<>();
        int tooMany = 0;
        for (int i = 0; i < 6; i++) {
            MvcResult r = postJson("/api/video/compress", Map.of("uniqueFilename", fileId, "mode", "crf", "crfValue", 18,
                    "preset", "slow", "codec", "h265"), a);
            if (r.getResponse().getStatus() == 200) ids.add(json(r).path("taskId").asText());
            else if (r.getResponse().getStatus() == 429) tooMany++;
        }
        assertThat(ids).hasSize(4);
        assertThat(tooMany).isEqualTo(2);
        for (String id : ids) postJson("/api/tasks/" + id + "/cancel", Map.of(), a);
        for (String id : ids) assertThat(waitTask(id, a, 30_000).path("status").asText()).isIn("CANCELLED", "SUCCEEDED");
    }

    @Test
    void systemCapabilitiesArePublicAndSecretFree() throws Exception {
        MvcResult r = get("/api/system/capabilities", null);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        String body = r.getResponse().getContentAsString();
        assertThat(body).doesNotContain("apiKey").doesNotContain("sk-").doesNotContain("integration-test-secret");
        assertThat(json(r).path("features").has("video.compress")).isTrue();
        assertThat(get("/actuator/env", null).getResponse().getStatus()).isIn(401, 404); // never exposed
    }
}
