package com.houqing.tools.video_compressor_backend.db;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.sql.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Upgrade a real V4 database containing existing collected rows; no destructive schema reset. */
class ProjectUpgradeTests {
    @Test void sqliteV4RecordsSurviveV5() throws Exception {
        var path=Files.createTempDirectory("lumen-v4-upgrade-").resolve("data.db");
        verify("jdbc:sqlite:"+path+"?foreign_keys=on",null,null,"sqlite");
    }
    @Test void postgresV4RecordsSurviveV5() throws Exception {
        try(var pg=EmbeddedPostgres.builder().start()){verify(pg.getJdbcUrl("postgres","postgres"),"postgres","","postgresql");}
    }
    private void verify(String url,String user,String password,String vendor)throws Exception {
        Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration/"+vendor).target("4").load().migrate();
        try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement()){
            s.execute("insert into users(id,username,password_hash,display_name,created_at) values(1,'before_upgrade','x','已有用户',1)");
            s.execute("insert into spec_projects(id,owner_id,name,spec_json,version,created_at,updated_at) values('existing',1,'原项目','{\"slots\":[]}',4,1,2)");
            s.execute("insert into spec_forms(project_id,config_json,version,updated_at) values('existing','{\"viewMode\":\"OWN\"}',3,2)");
            s.execute("insert into spec_form_rows(id,project_id,creator_key,creator_name,values_json,version,deleted,request_key,created_at,updated_at) values('row1','existing','guest_1','已有提交者','{\"name\":\"保留原资料\"}',5,0,'req1',1,2)");
        }
        var migrated=Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration/"+vendor).load().migrate();
        assertThat(migrated.migrationsExecuted).isEqualTo(2); // V5 collaboration and V6 personal task history.
        try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement()){
            try(var rs=s.executeQuery("select p.owner_id,p.name,p.version,p.team_version,p.description,p.publication_status,r.values_json,r.version as row_version from spec_projects p join spec_form_rows r on r.project_id=p.id where p.id='existing'")){
                assertThat(rs.next()).isTrue();assertThat(rs.getLong("owner_id")).isEqualTo(1);assertThat(rs.getString("name")).isEqualTo("原项目");assertThat(rs.getInt("version")).isEqualTo(4);assertThat(rs.getInt("team_version")).isEqualTo(1);assertThat(rs.getString("publication_status")).isEqualTo("DRAFT");assertThat(rs.getString("description")).isEmpty();assertThat(rs.getString("values_json")).isEqualTo("{\"name\":\"保留原资料\"}");assertThat(rs.getInt("row_version")).isEqualTo(5);
            }
        }
        assertThat(Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration/"+vendor).load().migrate().migrationsExecuted).isZero();
    }
}
