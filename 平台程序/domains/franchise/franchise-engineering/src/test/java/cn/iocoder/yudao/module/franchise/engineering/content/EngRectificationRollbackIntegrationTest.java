package cn.iocoder.yudao.module.franchise.engineering.content;

import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class EngRectificationRollbackIntegrationTest extends FranchisePgIntegrationTest {
    @Resource JdbcTemplate jdbc;

    private static String script(String resource) throws Exception {
        return new ClassPathResource(resource).getContentAsString(StandardCharsets.UTF_8);
    }

    @Test void rollbackRetainsBothAssignmentSnapshotsAndWaitsForConcurrentWriters() throws Exception {
        String forward=script("db/migration/franchise/V20261126.1006__engineering_rectification_assignment.sql");
        String undo=script("db/migration-undo/franchise/V20261126.1006__undo_engineering_rectification_assignment.sql");
        String schema="issue_undo_"+UUID.randomUUID().toString().replace("-","");
        String scope="SET LOCAL search_path TO "+schema;
        var worker=Executors.newSingleThreadExecutor();
        jdbc.execute("CREATE SCHEMA "+schema);
        try (var writer=jdbc.getDataSource().getConnection();var sql=writer.createStatement()) {
            writer.setAutoCommit(false);
            sql.execute(scope);
            sql.execute("CREATE TABLE fr_eng_rectification(id bigint,title text)");
            sql.execute("CREATE TABLE fr_eng_rectification_event(id bigint,issue_id bigint,create_time timestamptz,note text)");
            sql.execute("INSERT INTO fr_eng_rectification VALUES(1,'旧原单')");
            sql.execute("INSERT INTO fr_eng_rectification_event VALUES(2,1,now(),'历史记录')");
            sql.execute(forward);
            writer.commit();

            sql.execute(scope);
            sql.execute("UPDATE fr_eng_rectification_event SET due_date='2026-12-04' WHERE id=2");
            var undoResult=worker.submit(()->{
                try(var other=jdbc.getDataSource().getConnection();var rollback=other.createStatement()){
                    other.setAutoCommit(false);
                    try {
                        rollback.execute(scope);
                        rollback.execute(undo);
                        return "";
                    } catch(SQLException error) {
                        return error.getMessage();
                    } finally {
                        other.rollback();
                    }
                }
            });
            try {
                await().atMost(Duration.ofSeconds(8)).pollInterval(Duration.ofMillis(30)).until(()->
                        Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_locks WHERE relation=to_regclass(?) AND NOT granted)",
                                Boolean.class,schema+".fr_eng_rectification_event")));
            } finally {
                writer.commit();
            }
            assertThat(undoResult.get(8,TimeUnit.SECONDS)).contains("RECTIFICATION_ASSIGNMENT_IN_USE");
            sql.execute(scope);
            try(var event=sql.executeQuery("SELECT due_date::text,note FROM fr_eng_rectification_event")){
                assertThat(event.next()).isTrue();assertThat(event.getString(1)).isEqualTo("2026-12-04");
                assertThat(event.getString(2)).isEqualTo("历史记录");
            }
            sql.execute("UPDATE fr_eng_rectification_event SET due_date=NULL");
            sql.execute("UPDATE fr_eng_rectification SET owner_user_id=7");
            var beforeUndo=writer.setSavepoint();
            assertThatThrownBy(()->sql.execute(undo)).isInstanceOf(SQLException.class).hasMessageContaining("RECTIFICATION_ASSIGNMENT_IN_USE");
            writer.rollback(beforeUndo);
            sql.execute("UPDATE fr_eng_rectification SET owner_user_id=NULL");
            sql.execute(undo);
            sql.execute(forward);
            try(var legacy=sql.executeQuery("SELECT title,owner_user_id,due_date FROM fr_eng_rectification")){
                assertThat(legacy.next()).isTrue();assertThat(legacy.getString(1)).isEqualTo("旧原单");
                assertThat(legacy.getObject(2)).isNull();assertThat(legacy.getObject(3)).isNull();
            }
            writer.rollback();
            writer.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            sql.execute(scope);
            assertThatThrownBy(()->sql.execute(undo)).isInstanceOf(SQLException.class).hasMessageContaining("READ COMMITTED");
            writer.rollback();
        } finally {
            worker.shutdownNow();
            jdbc.execute("DROP SCHEMA "+schema+" CASCADE");
        }
    }
}
