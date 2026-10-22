package com.houqing.tools.video_compressor_backend.collaboration;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The same authorization, immutable upload, concurrency, SSE and delivery contract against real PostgreSQL. */
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class PostgresProjectCollaborationTests extends ProjectCollaborationTests {
    static final EmbeddedPostgres PG;
    static { try { PG = EmbeddedPostgres.builder().start(); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    @AfterAll static void stopPostgres() throws Exception { PG.close(); }
    @DynamicPropertySource static void postgres(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> PG.getJdbcUrl("postgres", "postgres"));
        r.add("spring.datasource.username", () -> "postgres"); r.add("spring.datasource.password", () -> "");
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        r.add("spring.datasource.hikari.maximum-pool-size", () -> "5");
        r.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        r.add("spring.flyway.baseline-on-migrate", () -> "false");
    }
}
