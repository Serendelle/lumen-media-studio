package com.houqing.tools.video_compressor_backend.api;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Run the same ownership, atomic update and audit-preservation contract against real PostgreSQL. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PostgresTaskHistoryTests extends TaskHistoryTests {
    static final EmbeddedPostgres PG;
    static {
        try { PG = EmbeddedPostgres.builder().start(); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    @AfterAll static void stopPostgres() throws Exception { PG.close(); }

    @DynamicPropertySource static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> PG.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "5");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.flyway.baseline-on-migrate", () -> "false");
    }
}
