package com.houqing.tools.video_compressor_backend.db;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

/** V6 upgrades existing task and file records in both supported databases without deleting audit data. */
class TaskHistoryUpgradeTests {
    @Test
    void existingSqliteV5TaskAndFilesSurviveHistoryMigration() throws Exception {
        String url = "jdbc:sqlite:" + Files.createTempDirectory("history-upgrade-").resolve("app.db") + "?foreign_keys=on";
        verify(url, null, null, "sqlite");
    }

    @Test
    void existingPostgresV5TaskAndFilesSurviveHistoryMigration() throws Exception {
        try (var pg = EmbeddedPostgres.builder().start()) {
            verify(pg.getJdbcUrl("postgres", "postgres"), "postgres", "", "postgresql");
        }
    }

    private void verify(String url, String username, String password, String vendor) throws Exception {
        String migrations = "classpath:db/migration/" + vendor;
        Flyway.configure().dataSource(url, username, password).locations(migrations).target("5").load().migrate();
        try (var connection = DriverManager.getConnection(url, username, password); var sql = connection.createStatement()) {
            sql.execute("insert into users(id,username,password_hash,display_name,created_at) values(1,'history_owner','x','已有用户',1)");
            sql.execute("insert into media_files(id,owner_id,stored_name,original_name,role,media_type,mime_type,size_bytes,created_at,expires_at) values('source',1,'source.png','原图.png','INPUT','IMAGE','image/png',42,1,9999999999999)");
            sql.execute("insert into media_files(id,owner_id,stored_name,original_name,role,media_type,mime_type,size_bytes,created_at,expires_at,source_task_id) values('result',1,'result.jpg','结果.jpg','OUTPUT','IMAGE','image/jpeg',37,1,9999999999999,'existing_task')");
            sql.execute("insert into tasks(id,owner_id,type,status,progress,params_json,input_files,output_file,result_json,origin,attempt,created_at,finished_at,updated_at) values('existing_task',1,'document.convert','SUCCEEDED',100,'{\"targetFormat\":\"jpg\"}','[\"source\"]','result','{\"verified\":true}','PRO',1,1,2,2)");
        }
        var result = Flyway.configure().dataSource(url, username, password).locations(migrations).load().migrate();
        assertThat(result.migrationsExecuted).isEqualTo(1);
        try (var connection = DriverManager.getConnection(url, username, password); var sql = connection.createStatement()) {
            try (var rows = sql.executeQuery("select owner_id,status,params_json,input_files,output_file,result_json,history_deleted_at from tasks where id='existing_task'")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getLong("owner_id")).isEqualTo(1);
                assertThat(rows.getString("status")).isEqualTo("SUCCEEDED");
                assertThat(rows.getString("params_json")).isEqualTo("{\"targetFormat\":\"jpg\"}");
                assertThat(rows.getString("input_files")).isEqualTo("[\"source\"]");
                assertThat(rows.getString("output_file")).isEqualTo("result");
                assertThat(rows.getString("result_json")).isEqualTo("{\"verified\":true}");
                assertThat(rows.getObject("history_deleted_at")).isNull();
            }
            try (var rows = sql.executeQuery("select count(*) from media_files where id in ('source','result')")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(2);
            }
            assertThat(sql.executeUpdate("update tasks set history_deleted_at=123 where id='existing_task' and history_deleted_at is null")).isEqualTo(1);
            try (var rows = sql.executeQuery("select history_deleted_at,output_file from tasks where id='existing_task'")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isEqualTo(123); assertThat(rows.getString(2)).isEqualTo("result");
            }
        }
        assertThat(Flyway.configure().dataSource(url, username, password).locations(migrations).load().migrate().migrationsExecuted).isZero();
    }
}
