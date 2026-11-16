package cn.iocoder.yudao.designprovider;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DesignProviderMigrationTest {
    @Test
    void ticketUndoRefusesToDropNonEmptyLedger() throws Exception {
        assertThat(undoSql("V20261114.950__undo_runtime_output_ticket.sql"))
                .contains("IF EXISTS (SELECT 1 FROM design_provider_output_ticket LIMIT 1)",
                "RAISE EXCEPTION", "DROP TABLE design_provider_output_ticket");
        assertThat(undoSql("V20261114.951__undo_prompt_event_receipt.sql"))
                .contains("IF EXISTS (SELECT 1 FROM design_provider_prompt_event_receipt LIMIT 1)",
                        "RAISE EXCEPTION", "DROP TABLE design_provider_prompt_event_receipt");
    }

    @Test
    void emptyIndependentPostgresBuildsCapabilityRuntimeClosure() throws Exception {
        try (var postgres = cn.iocoder.yudao.module.platform.test.TestPostgres.database(DockerImageName.parse("postgres:16.15-alpine"))
                .withDatabaseName("design_provider").withUsername("owner").withPassword("test-password")) {
            postgres.start();
            DesignProviderApplication.main(new String[]{"migrate",
                    "--spring.flyway.url=" + postgres.getJdbcUrl(),
                    "--spring.flyway.user=" + postgres.getUsername(),
                    "--spring.flyway.password=" + postgres.getPassword()});
            try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' "
                         + "AND table_name IN ('account','asset','design_project','budget_estimate','ai_job',"
                         + "'platform_cost_business','design_capability_organization','design_capability_receipt','outbox_event',"
                         + "'design_provider_output_ticket','design_provider_prompt_event_receipt')")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(11);
            }
            try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE provider_rollback_sentinel(id BIGINT PRIMARY KEY)");
                statement.execute("INSERT INTO provider_rollback_sentinel VALUES (1)");
            }
            DesignProviderApplication.main(new String[]{"migrate",
                    "--spring.flyway.url=" + postgres.getJdbcUrl(),
                    "--spring.flyway.user=" + postgres.getUsername(),
                    "--spring.flyway.password=" + postgres.getPassword()});
            try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT count(*) FROM provider_rollback_sentinel")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(1);
            }
            String undo = undoSql("V20261114.950__undo_runtime_output_ticket.sql");
            try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("INSERT INTO design_provider_output_ticket(id,job_id,attempt_no,fencing_token,"
                        + "candidate_slot_no,object_key,mime_type,expires_at) VALUES "
                        + "('00000000-0000-0000-0000-000000000950',1,1,1,1,'undo-guard','image/png',now())");
                assertThatThrownBy(() -> statement.execute(undo)).isInstanceOf(SQLException.class)
                        .hasMessageContaining("refusing to drop non-empty design_provider_output_ticket");
                try (var rows = statement.executeQuery("SELECT to_regclass('design_provider_output_ticket') IS NOT NULL")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getBoolean(1)).isTrue();
                }
                statement.execute("DELETE FROM design_provider_output_ticket");
                statement.execute(undo);
                try (var rows = statement.executeQuery("SELECT to_regclass('design_provider_output_ticket') IS NULL")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getBoolean(1)).isTrue();
                }
            }
            String promptUndo = undoSql("V20261114.951__undo_prompt_event_receipt.sql");
            try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("INSERT INTO design_provider_prompt_event_receipt(id,job_id,attempt_no,fencing_token,"
                        + "outcome,call_id,call_sha256,event_sha256,state,applied_at) VALUES "
                        + "('00000000-0000-0000-0000-000000000951',1,1,1,'DISPATCHED','plan-1','" + "a".repeat(64)
                        + "','" + "b".repeat(64) + "','APPLIED',now())");
                assertThatThrownBy(() -> statement.execute(promptUndo)).isInstanceOf(SQLException.class)
                        .hasMessageContaining("refusing to drop non-empty design_provider_prompt_event_receipt");
                statement.execute("DELETE FROM design_provider_prompt_event_receipt");
                statement.execute(promptUndo);
                try (var rows = statement.executeQuery(
                        "SELECT to_regclass('design_provider_prompt_event_receipt') IS NULL")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getBoolean(1)).isTrue();
                }
            }
        }
    }

    private static String undoSql(String file) throws Exception {
        try (var stream = DesignProviderMigrationTest.class.getResourceAsStream(
                "/db/migration-undo/design-provider/" + file)) {
            assertThat(stream).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
