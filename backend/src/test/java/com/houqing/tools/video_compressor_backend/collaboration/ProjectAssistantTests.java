package com.houqing.tools.video_compressor_backend.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.houqing.tools.video_compressor_backend.ai.llm.LlmClient;
import com.houqing.tools.video_compressor_backend.support.ApiTestBase;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/** Real project authorization and Office parsing, with a scripted model that never calls a network. */
class ProjectAssistantTests extends ApiTestBase {
    private static final String SCRIPT_ANSWER = "SCRIPT_ONLY_ANSWER_72c5c3: 请补充尚未提交的材料。";

    @Autowired ProjectChecksService checks;
    private Object originalLlm;

    private record Team(User owner, String id) {}
    private record WorkbookData(byte[] bytes, String privateHeader, String privateCell) {}

    /** Latches let another authenticated request revoke a grant while the model is genuinely in flight. */
    private static final class ScriptedLlm implements LlmClient {
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release;
        volatile ArrayNode received;

        ScriptedLlm(boolean blocking) { release = new CountDownLatch(blocking ? 1 : 0); }

        @Override public Result chat(ArrayNode messages, ArrayNode tools, String toolChoice, Consumer<String> onText) {
            calls.incrementAndGet();
            received = messages.deepCopy();
            entered.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("Scripted model release timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Scripted model interrupted", e);
            }
            return new Result(SCRIPT_ANSWER, List.of(), "stop", 1, 1);
        }

        @Override public boolean configured() { return true; }
        @Override public String model() { return "scripted-project-assistant"; }
    }

    @BeforeEach void captureConfiguredModel() { originalLlm = ReflectionTestUtils.getField(checks, "llm"); }
    @AfterEach void restoreConfiguredModel() { ReflectionTestUtils.setField(checks, "llm", originalLlm); }

    private ScriptedLlm script(boolean blocking) {
        ScriptedLlm model = new ScriptedLlm(blocking);
        ReflectionTestUtils.setField(checks, "llm", model);
        return model;
    }

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
        JsonNode created = ok(postJson("/api/v2/projects", Map.of("name", "项目助手隐私测试", "description", "只使用真实文件与脚本模型"), owner));
        return new Team(owner, created.path("id").asText());
    }

    private String base(Team team) { return "/api/v2/projects/" + team.id(); }

    private void invite(Team team, User editor) throws Exception {
        ok(postJson(base(team) + "/members", Map.of("username", editor.username(), "role", "EDITOR"), team.owner()));
    }

    private WorkbookData workbook() throws Exception {
        String header = "PRIVATE_HEADER_" + UUID.randomUUID();
        String cell = "PRIVATE_CELL_" + UUID.randomUUID();
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("资料");
            sheet.createRow(0).createCell(0).setCellValue(header);
            sheet.createRow(1).createCell(0).setCellValue(cell);
            workbook.write(out);
            return new WorkbookData(out.toByteArray(), header, cell);
        }
    }

    private JsonNode document(Team team, List<Long> visibleUsers) throws Exception {
        return ok(postJson(base(team) + "/documents", Map.of(
                "title", "有成员可见范围的腾讯表格", "externalUrl", "https://docs.qq.com/sheet/DQXNzaXN0YW50VGVzdA",
                "documentType", "SPREADSHEET", "visibility", "SELECTED", "visibleUserIds", visibleUsers), team.owner()));
    }

    private JsonNode importWorkbook(Team team, JsonNode document, WorkbookData workbook, String filename) throws Exception {
        return ok(uploadBytes(base(team) + "/documents/" + document.path("id").asText() + "/imports", workbook.bytes(), filename, team.owner()));
    }

    private JsonNode modelReport(ScriptedLlm model) throws Exception {
        assertThat(model.received).isNotNull();
        assertThat(model.calls.get()).isEqualTo(1);
        return mapper.readTree(model.received.get(1).path("content").asText());
    }

    private void stop(ExecutorService pool, ScriptedLlm model) throws InterruptedException {
        model.release.countDown();
        pool.shutdownNow();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).as("assistant request finished before restoring its model").isTrue();
    }

    @Test void explicitConsentIsRequiredBeforeAnyModelCall() throws Exception {
        Team team = team();
        ScriptedLlm model = script(false);
        for (Object consent : List.of(false, "true")) {
            MvcResult response = postJson(base(team) + "/assistant", Map.of("consent", consent), team.owner());
            assertThat(response.getResponse().getStatus()).isEqualTo(400);
            assertThat(response.getResponse().getContentAsString()).doesNotContain(SCRIPT_ANSWER);
        }
        assertThat(postJson(base(team) + "/assistant", Map.of(), team.owner()).getResponse().getStatus()).isEqualTo(400);
        assertThat(model.calls.get()).isZero();
        assertThat(model.received).isNull();
    }

    @Test void visibleFileFactsReachTheModelButWorkbookHeadingsAndCellsStayLocal() throws Exception {
        Team team = team();
        WorkbookData workbook = workbook();
        String filename = "visible-metadata.xlsx";
        JsonNode version = ok(uploadBytes(base(team) + "/files", workbook.bytes(), filename, team.owner()));
        JsonNode localReport = ok(get(base(team) + "/checks", team.owner()));
        assertThat(localReport.path("files").get(0).path("fields").toString()).contains(workbook.privateHeader());
        ScriptedLlm model = script(false);

        JsonNode response = ok(postJson(base(team) + "/assistant", Map.of("consent", true), team.owner()));

        assertThat(response.path("content").asText()).isEqualTo(SCRIPT_ANSWER);
        assertThat(response.path("model").asText()).isEqualTo(model.model());
        assertThat(response.path("generatedAt").asLong()).isPositive();
        JsonNode report = modelReport(model), file = report.path("files").get(0);
        assertThat(report.path("files")).hasSize(1);
        assertThat(file.path("filename").asText()).isEqualTo(filename);
        assertThat(file.path("sha256").asText()).isEqualTo(version.path("sha256").asText());
        assertThat(file.path("sizeBytes").asLong()).isEqualTo(workbook.bytes().length);
        assertThat(file.path("facts").path("integrity").asBoolean()).isTrue();
        assertThat(file.has("fields")).isFalse();
        assertThat(model.received.toString()).doesNotContain(workbook.privateHeader(), workbook.privateCell());
    }

    @Test void selectedDocumentsOutsideTheActorsScopeNeverEnterTheModelRequest() throws Exception {
        Team team = team(); User editor = register(); invite(team, editor);
        WorkbookData visible = workbook(), hidden = workbook();
        String visibleName = "visible-team-file.xlsx", hiddenName = "hidden-roster-9137.xlsx";
        JsonNode visibleVersion = ok(uploadBytes(base(team) + "/files", visible.bytes(), visibleName, team.owner()));
        JsonNode hiddenDocument = document(team, List.of());
        JsonNode hiddenVersion = importWorkbook(team, hiddenDocument, hidden, hiddenName);
        ScriptedLlm model = script(false);

        assertThat(ok(postJson(base(team) + "/assistant", Map.of("consent", true), editor)).path("content").asText()).isEqualTo(SCRIPT_ANSWER);

        JsonNode report = modelReport(model);
        assertThat(report.path("summary").path("files").asInt()).isEqualTo(1);
        assertThat(report.path("files")).hasSize(1);
        assertThat(model.received.toString()).contains(visibleName, visibleVersion.path("sha256").asText())
                .doesNotContain(hiddenName, hiddenVersion.path("sha256").asText(), hiddenDocument.path("id").asText(),
                        hidden.privateHeader(), hidden.privateCell(), visible.privateHeader(), visible.privateCell());
    }

    @Test void memberRevokedDuringAnInFlightModelRequestCannotReceiveTheAnswer() throws Exception {
        Team team = team(); User editor = register(); invite(team, editor);
        ok(uploadBytes(base(team) + "/files", workbook().bytes(), "previously-visible.xlsx", team.owner()));
        ScriptedLlm model = script(true);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            var pending = pool.submit(() -> postJson(base(team) + "/assistant", Map.of("consent", true), editor));
            assertThat(model.entered.await(10, TimeUnit.SECONDS)).as("model request entered before membership revocation").isTrue();
            ok(send(team.owner(), delete(base(team) + "/members/" + editor.id()), null));
            model.release.countDown();
            MvcResult response = pending.get(20, TimeUnit.SECONDS);
            assertThat(response.getResponse().getStatus()).isEqualTo(404);
            assertThat(response.getResponse().getContentAsString()).doesNotContain(SCRIPT_ANSWER);
            assertThat(get(base(team), editor).getResponse().getStatus()).isEqualTo(404);
        } finally { stop(pool, model); }
    }

    @Test void documentVisibilityWithdrawnDuringAModelRequestRejectsItsStaleAnswer() throws Exception {
        Team team = team(); User editor = register(); invite(team, editor);
        JsonNode document = document(team, List.of(editor.id()));
        WorkbookData workbook = workbook();
        JsonNode imported = importWorkbook(team, document, workbook, "authorized-then-private.xlsx");
        ScriptedLlm model = script(true);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            var pending = pool.submit(() -> postJson(base(team) + "/assistant", Map.of("consent", true), editor));
            assertThat(model.entered.await(10, TimeUnit.SECONDS)).as("model request entered while document was visible").isTrue();
            assertThat(modelReport(model).path("files").get(0).path("sha256").asText()).isEqualTo(imported.path("sha256").asText());
            assertThat(model.received.toString()).doesNotContain(workbook.privateHeader(), workbook.privateCell());
            ok(send(team.owner(), patch(base(team) + "/documents/" + document.path("id").asText()), Map.of(
                    "visibility", "SELECTED", "visibleUserIds", List.of(), "version", document.path("version").asInt())));
            model.release.countDown();
            MvcResult response = pending.get(20, TimeUnit.SECONDS);
            assertThat(response.getResponse().getStatus()).isEqualTo(409);
            assertThat(response.getResponse().getContentAsString()).doesNotContain(SCRIPT_ANSWER);
            ok(get(base(team), editor));
            assertThat(get(base(team) + "/files/" + imported.path("id").asText() + "/content", editor).getResponse().getStatus()).isEqualTo(404);
        } finally { stop(pool, model); }
    }
}
