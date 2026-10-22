package com.houqing.tools.video_compressor_backend.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.houqing.tools.video_compressor_backend.media.Tool;
import com.houqing.tools.video_compressor_backend.support.ApiTestBase;
import com.houqing.tools.video_compressor_backend.support.TestMedia;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The whole application on a real PostgreSQL 16 process with the production migrations — so the SQL that would
 * run in production is the SQL that was tested (V2 §7.1), not an untested translation of the SQLite schema.
 */
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class PostgresProfileTests extends ApiTestBase {

    static final EmbeddedPostgres PG;

    static {
        try {
            PG = EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @AfterAll
    static void stop() throws Exception { PG.close(); }

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> PG.getJdbcUrl("postgres", "postgres"));
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "");
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        r.add("spring.datasource.hikari.maximum-pool-size", () -> "5");
        r.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        r.add("spring.flyway.baseline-on-migrate", () -> "false"); // the prod setting
    }

    @Autowired DataSource dataSource;

    @Test
    void productionMigrationsApplyAndEveryTableIsWritableThroughTheApp() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            assertThat(c.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            try (ResultSet rs = c.createStatement().executeQuery("select version, success from flyway_schema_history where version = '1'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean("success")).isTrue();
            }
        }

        // users: identity ids, case-insensitive unique names
        User u = register();
        var dup = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("username", u.username().toUpperCase(), "password", "Passw0rd!123")))).andReturn();
        assertThat(dup.getResponse().getStatus()).isEqualTo(409);
        var login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("username", u.username().toUpperCase(), "password", "Passw0rd!123")))).andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(200);

        // media_files + tasks (incl. the partial unique idempotency index) + the aggregate queries of the overview
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE);
        String fileId = json(upload("/api/files/upload", TestMedia.get("demo.mp4"), "demo.mp4", u)).path("fileId").asText();
        String key = java.util.UUID.randomUUID().toString();
        String body = mapper.writeValueAsString(Map.of("uniqueFilename", fileId, "targetFormat", "mp3"));
        String t1 = json(mvc.perform(auth(post("/api/audio/extract"), u).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn()).path("taskId").asText();
        String t2 = json(mvc.perform(auth(post("/api/audio/extract"), u).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn()).path("taskId").asText();
        assertThat(t2).isEqualTo(t1);
        assertThat(waitTask(t1, u, 60_000).path("status").asText()).isEqualTo("SUCCEEDED");
        JsonNode summary = json(get("/api/v2/workspace/summary", u));
        assertThat(summary.path("tasksByStatus").path("SUCCEEDED").asInt()).isEqualTo(1);
        assertThat(summary.path("inputs").path("count").asInt()).isEqualTo(1);
        assertThat(json(get("/api/tasks?status=SUCCEEDED", u)).path("totalElements").asInt()).isEqualTo(1);

        // spec_projects + spec_items, with the optimistic version column
        String pid = json(postJson("/api/v2/specs", Map.of("name", "PG 项目"), u)).path("id").asText();
        String spec = "{\"slots\":[{\"key\":\"audio\",\"label\":\"音频\",\"kind\":\"AUDIO\",\"rules\":{\"extensions\":[\"mp3\"]}}]}";
        JsonNode saved = json(mvc.perform(auth(put("/api/v2/specs/" + pid + "/spec"), u).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("spec", mapper.readTree(spec), "version", 1)))).andReturn());
        assertThat(saved.path("version").asInt()).isEqualTo(2);
        String out = json(get("/api/tasks/" + t1, u)).path("downloadFilename").asText();
        JsonNode board = json(postJson("/api/v2/specs/" + pid + "/items", Map.of("files", java.util.List.of(Map.of("fileId", out, "relativePath", "a.mp3"))), u));
        assertThat(board.path("items").size()).isEqualTo(1);

        // ai_conversations / ai_messages / ai_events (identity seq) — creating a conversation writes all three paths
        String conv = json(postJson("/api/ai/conversations", Map.of(), u)).path("id").asText();
        assertThat(json(get("/api/ai/conversations/" + conv, u)).path("conversation").path("id").asText()).isEqualTo(conv);
    }
}
