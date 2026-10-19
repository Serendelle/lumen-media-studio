package com.houqing.tools.video_compressor_backend.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A database created by the old start-up schema.sql (no migration history, no spec tables, real rows) must be
 * upgraded in place by the migrations — never cleared — exactly as the application configures Flyway.
 */
class SqliteUpgradeTest {

    @Test
    void anExistingPreFlywayDatabaseIsUpgradedWithoutLosingData() throws Exception {
        Path dir = Files.createTempDirectory("vcb-upgrade-");
        String url = "jdbc:sqlite:" + dir.resolve("app.db");
        try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement()) {
            // the shape of a phase-1 database: users and tasks only
            s.execute("CREATE TABLE users (id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT NOT NULL UNIQUE COLLATE NOCASE,"
                    + " password_hash TEXT NOT NULL, display_name TEXT NOT NULL, created_at INTEGER NOT NULL)");
            s.execute("CREATE TABLE tasks (id TEXT PRIMARY KEY, owner_id INTEGER NOT NULL REFERENCES users(id), type TEXT NOT NULL,"
                    + " status TEXT NOT NULL, progress INTEGER NOT NULL DEFAULT 0, stage TEXT, message TEXT, params_json TEXT NOT NULL,"
                    + " input_files TEXT NOT NULL, output_file TEXT, result_json TEXT, error_code TEXT, attempt INTEGER NOT NULL DEFAULT 1,"
                    + " origin TEXT NOT NULL DEFAULT 'PRO', idempotency_key TEXT, created_at INTEGER NOT NULL, started_at INTEGER,"
                    + " finished_at INTEGER, updated_at INTEGER NOT NULL)");
            s.execute("INSERT INTO users(username, password_hash, display_name, created_at) VALUES ('old', 'x', '老用户', 1)");
        }

        // the same settings as application.properties
        var result = Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration/sqlite")
                .baselineOnMigrate(true).baselineVersion("0").load().migrate();
        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(1);

        try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT display_name FROM users WHERE username = 'OLD'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("老用户"); // data kept, NOCASE kept
            }
            for (String table : new String[]{"media_files", "ai_events", "spec_projects", "spec_items"}) {
                try (ResultSet rs = s.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='" + table + "'")) {
                    assertThat(rs.next()).as(table).isTrue();
                }
            }
        }
        // running again is a no-op
        assertThat(Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration/sqlite")
                .baselineOnMigrate(true).baselineVersion("0").load().migrate().migrationsExecuted).isZero();
    }
}
