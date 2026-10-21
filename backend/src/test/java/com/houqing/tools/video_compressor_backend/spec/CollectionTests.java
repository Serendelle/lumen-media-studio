package com.houqing.tools.video_compressor_backend.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.houqing.tools.video_compressor_backend.ai.llm.LlmClient;
import com.houqing.tools.video_compressor_backend.media.MediaFileRepository;
import com.houqing.tools.video_compressor_backend.support.ApiTestBase;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class CollectionTests extends ApiTestBase {
    @Autowired SpecOrganizer organizer;
    @Autowired CollectionRepository links;
    @Autowired SpecItemRepository items;
    @Autowired MediaFileRepository files;
    private Object realLlm;
    @BeforeEach void before() { realLlm = ReflectionTestUtils.getField(organizer, "llm"); }
    @AfterEach void after() { ReflectionTestUtils.setField(organizer, "llm", realLlm); }

    private String project(User u) throws Exception {
        String id = json(postJson("/api/v2/specs", Map.of("name", "材料协作测试"), u)).path("id").asText();
        var saved = mvc.perform(auth(put("/api/v2/specs/" + id + "/spec"), u).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("spec", mapper.readTree(SpecDeskTests.SPEC), "version", 1)))).andReturn();
        assertThat(saved.getResponse().getStatus()).isEqualTo(200);
        return id;
    }
    private String base(String id) { return "/api/v2/specs/" + id; }
    private JsonNode link(String id, User u) throws Exception {
        MvcResult result = postJson(base(id) + "/collections", Map.of("days", 7), u);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json(result);
    }
    private String pub(JsonNode link) { return "/api/public/collections/" + link.path("path").asText().substring("/collect/".length()); }
    private static byte[] pdf() throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage()); doc.save(out); return out.toByteArray();
        }
    }
    private MvcResult submit(String url, String key, String name, String identifier, String slot, String filename) throws Exception {
        return mvc.perform(multipart(url + "/submissions").file(new MockMultipartFile("file", filename, "application/pdf", pdf()))
                .param("name", name).param("identifier", identifier).param("slotKey", slot).param("requestKey", key)).andReturn();
    }
    private String key() { return UUID.randomUUID().toString(); }
    private void importRoster(String id, User u) throws Exception {
        String file = json(uploadBytes("/api/files/upload", SpecDeskTests.roster(), "名单.xlsx", u)).path("fileId").asText();
        assertThat(postJson(base(id) + "/roster", Map.of("fileId", file), u).getResponse().getStatus()).isEqualTo(200);
    }

    @Test void anonymousCollectionIsWriteOnlyPrivateAndIdempotent() throws Exception {
        User owner = register(), other = register(); String id = project(owner); importRoster(id, owner);
        JsonNode link = link(id, owner); String url = pub(link);
        String publicInfo = get(url, null).getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(publicInfo).contains("报名表").doesNotContain("张三", "13800000001", "ownerId", "fileId", "roster", "noticeText");
        assertThat(get(url, null).getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(get(base(id) + "/collections", other).getResponse().getStatus()).isEqualTo(404);
        assertThat(postJson(base(id) + "/collections", Map.of("days", 7), other).getResponse().getStatus()).isEqualTo(404);
        assertThat(get(base(id) + "/items", null).getResponse().getStatus()).isEqualTo(401);
        assertThat(get(url + "/submissions", null).getResponse().getStatus()).isEqualTo(401);
        String request = key(); MvcResult first = submit(url, request, "张三", "20240001", "form", "报名表.pdf");
        assertThat(first.getResponse().getStatus()).as(first.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(json(first).fieldNames()).toIterable().containsExactlyInAnyOrder("receipt", "filename", "submittedAt");
        assertThat(json(submit(url, request, "张三", "20240001", "form", "报名表.pdf"))).isEqualTo(json(first));
        JsonNode board = json(get(base(id) + "/items", owner));
        assertThat(board.path("items")).hasSize(1);
        JsonNode item = board.path("items").get(0);
        assertThat(item.path("submittedBy").asText()).isEqualTo("张三");
        assertThat(item.path("status").asText()).isEqualTo("OK");
        assertThat(get("/api/files/" + item.path("fileId").asText() + "/content", other).getResponse().getStatus()).isEqualTo(404);
        assertThat(json(get(base(id) + "/collections", owner)).get(0).path("files").asInt()).isEqualTo(1);
    }

    @Test void linksCanExpireRotateAndRevokeAndProjectDeletionCascades() throws Exception {
        User owner = register(); String id = project(owner); JsonNode first = link(id, owner);
        JsonNode second = link(id, owner);
        assertThat(get(pub(first), null).getResponse().getStatus()).isEqualTo(404);
        var saved = links.findById(second.path("id").asText()).orElseThrow(); saved.setExpiresAt(1); links.save(saved);
        assertThat(submit(pub(second), key(), "张三", "", "form", "a.pdf").getResponse().getStatus()).isEqualTo(404);
        JsonNode third = link(id, owner);
        assertThat(mvc.perform(auth(delete(base(id) + "/collections/" + third.path("id").asText()), owner)).andReturn().getResponse().getStatus()).isEqualTo(204);
        assertThat(get(pub(third), null).getResponse().getStatus()).isEqualTo(404);
        JsonNode fourth = link(id, owner);
        assertThat(submit(pub(fourth), key(), "张三", "", "form", "a.pdf").getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(auth(delete(base(id)), owner)).andReturn().getResponse().getStatus()).isEqualTo(204);
        assertThat(get(pub(fourth), null).getResponse().getStatus()).isEqualTo(404);
        assertThat(links.findByProjectIdOrderByCreatedAtDesc(id)).isEmpty();
    }

    @Test void quotasTypeChecksAndRegistrationDoNotLeaveRejectedFiles() throws Exception {
        User owner = register(); String id = project(owner); JsonNode link = link(id, owner); String url = pub(link);
        long before = files.count();
        assertThat(submit(url, key(), "张三", "", "photo", "wrong.pdf").getResponse().getStatus()).isEqualTo(400);
        assertThat(files.count()).isEqualTo(before);
        assertThat(submit(url, key(), "张三", "", "deleted-slot", "wrong.pdf").getResponse().getStatus()).isEqualTo(400);
        assertThat(submit(url, key(), "张三", "20240001", "form", "a.pdf").getResponse().getStatus()).isEqualTo(200);
        assertThat(submit(url, key(), "张三", "20240001", "form", "b.pdf").getResponse().getStatus()).isEqualTo(200);
        JsonNode project = json(get(base(id), owner));
        assertThat(project.path("roster").path("source").asText()).isEqualTo("COLLECTION");
        assertThat(project.path("roster").path("people")).hasSize(1);
        var saved = links.findById(link.path("id").asText()).orElseThrow(); saved.setUsedFiles(500); links.save(saved);
        assertThat(submit(url, key(), "张三", "", "form", "c.pdf").getResponse().getStatus()).isEqualTo(429);
        assertThat(files.count()).isEqualTo(before + 2);
        MockMultipartFile huge = new MockMultipartFile("file", "huge.pdf", "application/pdf", new byte[]{1}) {
            @Override public long getSize() { return CollectionService.MAX_FILE_BYTES + 1; }
        };
        saved.setUsedFiles(2); links.save(saved);
        assertThat(mvc.perform(multipart(url + "/submissions").file(huge).param("name", "张三").param("slotKey", "form")
                .param("requestKey", key())).andReturn().getResponse().getStatus()).isEqualTo(413);
    }

    @Test void concurrentSubmissionsCannotExceedQuotaOrDuplicateAnIdempotencyKey() throws Exception {
        User owner = register(); String id = project(owner); JsonNode link = link(id, owner); String url = pub(link);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            String request = key();
            var start = new java.util.concurrent.CountDownLatch(1);
            var a = executor.submit(() -> { start.await(); return submit(url, request, "张三", "20240001", "form", "a.pdf"); });
            var b = executor.submit(() -> { start.await(); return submit(url, request, "张三", "20240001", "form", "a.pdf"); });
            start.countDown();
            var first = a.get(30, java.util.concurrent.TimeUnit.SECONDS); var second = b.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(first.getResponse().getStatus()).isEqualTo(200);
            assertThat(second.getResponse().getStatus()).isEqualTo(200);
            assertThat(json(first)).isEqualTo(json(second));
            assertThat(items.countByProjectId(id)).isEqualTo(1);
            var saved = links.findById(link.path("id").asText()).orElseThrow(); saved.setUsedFiles(499); links.save(saved);
            var quotaStart = new java.util.concurrent.CountDownLatch(1);
            var c = executor.submit(() -> { quotaStart.await(); return submit(url, key(), "李四", "20240002", "form", "b.pdf"); });
            var d = executor.submit(() -> { quotaStart.await(); return submit(url, key(), "王五", "20240003", "form", "c.pdf"); });
            quotaStart.countDown();
            assertThat(List.of(c.get(30, java.util.concurrent.TimeUnit.SECONDS).getResponse().getStatus(),
                    d.get(30, java.util.concurrent.TimeUnit.SECONDS).getResponse().getStatus())).containsExactlyInAnyOrder(200, 429);
            assertThat(items.countByProjectId(id)).isEqualTo(2);
            assertThat(links.findById(saved.getId()).orElseThrow().getUsedFiles()).isEqualTo(500);
        } finally { executor.shutdownNow(); }
    }

    private String looseFile(String id, User owner, String filename) throws Exception {
        String fileId = json(uploadBytes("/api/files/upload", pdf(), filename, owner)).path("fileId").asText();
        JsonNode board = json(postJson(base(id) + "/items", Map.of("files", List.of(Map.of("fileId", fileId))), owner));
        return java.util.stream.StreamSupport.stream(board.path("items").spliterator(), false)
                .filter(i -> i.path("fileId").asText().equals(fileId)).findFirst().orElseThrow().path("id").asText();
    }
    private void model(java.util.function.Function<JsonNode, JsonNode> answer, List<String> seen) {
        ReflectionTestUtils.setField(organizer, "llm", new LlmClient() {
            @Override public boolean configured() { return true; }
            @Override public String model() { return "local-test-stub"; }
            @Override public Result chat(com.fasterxml.jackson.databind.node.ArrayNode messages, com.fasterxml.jackson.databind.node.ArrayNode tools,
                                         String choice, java.util.function.Consumer<String> onText) {
                seen.add(messages.toString());
                try { return new Result("", List.of(new ToolCall("call", "submit_assignments", answer.apply(mapper.readTree(messages.get(1).path("content").asText())).toString())), "tool_calls", 1, 1); }
                catch (Exception e) { throw new RuntimeException(e); }
            }
        });
    }
    private JsonNode assignment(String item, String person, String slot, double confidence) {
        return mapper.createObjectNode().set("assignments", mapper.createArrayNode().add(mapper.createObjectNode().put("itemId", item)
                .put("personKey", person).put("slotKey", slot).put("confidence", confidence).put("reason", "文件名中的称谓对应名单姓名")));
    }

    @Test void aiFillsOnlyMissingAssignmentsAndOmitsPrivateRosterColumns() throws Exception {
        User owner = register(); String id = project(owner); importRoster(id, owner);
        String item = looseFile(id, owner, "小张的申请.pdf");
        String manual = looseFile(id, owner, "待确认.pdf");
        String person = json(get(base(id), owner)).path("roster").path("people").get(0).path("key").asText();
        mvc.perform(auth(patch(base(id) + "/items/" + manual), owner).contentType("application/json").content("{\"personKey\":null,\"slotKey\":null}"));
        List<String> seen = new ArrayList<>();
        model(data -> {
            assertThat(data.path("files")).hasSize(1);
            return assignment(item, person, "form", .99);
        }, seen);
        MvcResult result = postJson(base(id) + "/organize", Map.of(), owner);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(json(result).path("organization").path("matched").asInt()).isEqualTo(1);
        assertThat(seen.get(0)).doesNotContain("13800000001", "20240001", "电话", "学号", "第一中学");
        assertThat(items.findById(item).orElseThrow().getMatchMethod()).isEqualTo("AI");
        assertThat(items.findById(manual).orElseThrow().getMatchMethod()).isEqualTo("MANUAL");
        assertThat(items.findById(manual).orElseThrow().getPersonKey()).isNull();
    }

    @Test void invalidLowConfidenceAndStaleAiRepliesCannotCorruptAssignments() throws Exception {
        User owner = register(), other = register(); String id = project(owner); importRoster(id, owner);
        String item = looseFile(id, owner, "小张申请.pdf");
        String person = json(get(base(id), owner)).path("roster").path("people").get(0).path("key").asText();
        List<String> seen = new ArrayList<>();
        model(data -> assignment(item, "foreign-person", "form", 1), seen);
        assertThat(postJson(base(id) + "/organize", Map.of(), owner).getResponse().getStatus()).isEqualTo(503);
        assertThat(items.findById(item).orElseThrow().getPersonKey()).isNull();
        model(data -> assignment(item, person, "form", .4), seen);
        assertThat(json(postJson(base(id) + "/organize", Map.of(), owner)).path("organization").path("matched").asInt()).isZero();
        model(data -> {
            var changed = items.findById(item).orElseThrow(); changed.setMatchMethod("MANUAL"); items.save(changed);
            return assignment(item, person, "form", 1);
        }, seen);
        assertThat(postJson(base(id) + "/organize", Map.of(), owner).getResponse().getStatus()).isEqualTo(409);
        assertThat(items.findById(item).orElseThrow().getPersonKey()).isNull();
        assertThat(postJson(base(id) + "/organize", Map.of(), other).getResponse().getStatus()).isEqualTo(404);
    }
}
