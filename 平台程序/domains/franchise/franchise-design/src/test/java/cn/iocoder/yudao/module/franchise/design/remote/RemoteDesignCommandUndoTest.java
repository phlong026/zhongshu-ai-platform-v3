package cn.iocoder.yudao.module.franchise.design.remote;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** Executes the actual forward/undo SQL on PostgreSQL; no mocked accounting or DDL. */
@Testcontainers
class RemoteDesignCommandUndoTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final String UNDO = "db/migration-undo/franchise-design-remote/";
    private static final String U925 = UNDO + "V20261111.925__undo_remote_design_commands.sql";
    private SimpleDriverDataSource source;
    private JdbcTemplate jdbc;

    @BeforeEach void setup() throws Exception {
        source = new SimpleDriverDataSource(new org.postgresql.Driver(), PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        jdbc = new JdbcTemplate(source);
        var flyway = Flyway.configure().dataSource(source).cleanDisabled(false).baselineOnMigrate(true)
                .locations("classpath:db/migration/franchise-design-remote").load();
        flyway.clean();
        // Existing HQ test schema shims; the wallet and command event tables below use real migrations.
        jdbc.execute("""
                CREATE TABLE system_menu (
                    id int8 PRIMARY KEY, name varchar(64), permission varchar(128), type int4, sort int4,
                    parent_id int8, path varchar(256), icon varchar(128), component varchar(256),
                    component_name varchar(128), status int4, visible bool, keep_alive bool, always_show bool,
                    creator varchar(64), updater varchar(64))
                """);
        jdbc.execute("CREATE TABLE system_role_menu(id int8 PRIMARY KEY,role_id int8,menu_id int8,creator varchar(64),updater varchar(64),tenant_id int8)");
        jdbc.execute("CREATE TABLE pf_org_unit(dept_id bigint PRIMARY KEY)");
        execute("db/migration/platform-wallet/V20261111.901__pf_wallet_accounting.sql");
        execute("db/migration/pf/V20261022.701__pf_command_event.sql");
        flyway.migrate();
        jdbc.update("INSERT INTO pf_org_unit VALUES(10)");
        jdbc.update("INSERT INTO pf_wallet(id,org_id,tenant_id) VALUES(1,10,1)");
        jdbc.update("INSERT INTO pf_point_account(wallet_id,tenant_id,available_points,reserved_points) VALUES(1,1,100,10)");
    }

    @Test void dependentMigrationsMustBeUndoneFirstWithoutRemovingTheirObjects() throws Exception {
        Map<String, List<String>> before = facts();
        assertThatThrownBy(() -> execute(U925)).isInstanceOf(SQLException.class).hasMessageContaining("U925_DEPENDENCIES_REMAIN");
        assertThat(facts()).isEqualTo(before);
        assertThat(exists("fr_ops_design_command_page")).isTrue();
        assertThat(exists("fr_design_billing")).isTrue();
        assertThat(exists("fr_design_price")).isTrue();
        execute(UNDO + "V20261111.961__undo_design_operations_admin.sql");
        assertThatThrownBy(() -> execute(U925)).isInstanceOf(SQLException.class).hasMessageContaining("U925_DEPENDENCIES_REMAIN");
        assertThat(exists("fr_design_remote_command")).isTrue();
    }

    @Test void outOfOrderPriceUndoCannotSilentlyRemoveRemaining961Objects() throws Exception {
        execute(UNDO + "V20261111.945__undo_design_wallet_pricing.sql");
        var before = facts();
        assertThatThrownBy(() -> execute(U925)).isInstanceOf(SQLException.class).hasMessageContaining("U925_DEPENDENCIES_REMAIN");
        assertThat(exists("fr_ops_design_command_page")).isTrue();
        assertThat(facts()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"MENU", "ROLE_MENU"})
    void remaining961MenuFactsCannotBeHiddenByDroppingItsIndex(String kind) throws Exception {
        orderedPredecessors();
        if (kind.equals("MENU")) jdbc.update("INSERT INTO system_menu(id,name) VALUES(960021,'retained design menu')");
        else jdbc.update("INSERT INTO system_role_menu(id,role_id,menu_id,tenant_id) VALUES(1,1,960021,1)");
        var before = facts();
        assertThatThrownBy(() -> execute(U925)).isInstanceOf(SQLException.class).hasMessageContaining("U925_DEPENDENCIES_REMAIN");
        assertThat(facts()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(ints = {Connection.TRANSACTION_REPEATABLE_READ, Connection.TRANSACTION_SERIALIZABLE})
    void staleSnapshotTransactionRefusesUndoWithoutDeletingNewlyCommittedFacts(int isolation) throws Exception {
        orderedPredecessors();
        String sql;
        try (var stream = new ClassPathResource(U925).getInputStream()) {
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection stale = source.getConnection(); var statement = stale.createStatement()) {
            stale.setTransactionIsolation(isolation);
            stale.setAutoCommit(false);
            try {
                try (var empty = statement.executeQuery("SELECT count(*) FROM fr_design_remote_command")) {
                    assertThat(empty.next()).isTrue();
                    assertThat(empty.getInt(1)).isZero();
                }
                insertCommand("UNKNOWN");
                var before = facts();
                assertThatThrownBy(() -> statement.execute(sql)).isInstanceOf(SQLException.class)
                        .hasMessageContaining("U925_REQUIRES_READ_COMMITTED");
                stale.rollback();
                assertThat(facts()).isEqualTo(before);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_design_remote_command", Integer.class)).isEqualTo(1);
            } finally {
                stale.rollback();
            }
        }
    }

    @Test void emptyOrderedUndoDropsOnlyTheCommandTableAndItsOwnedObjects() throws Exception {
        orderedPredecessors();
        String sequence = jdbc.queryForObject("SELECT pg_get_serial_sequence('fr_design_remote_command','command_id')", String.class);
        var before = facts();
        execute(U925);
        assertThat(exists("fr_design_remote_command")).isFalse();
        assertThat(exists("fr_design_remote_recovery")).isFalse();
        assertThat(exists("fr_design_remote_job")).isFalse();
        assertThat(exists(sequence)).isFalse();
        before.remove("fr_design_remote_command");
        assertThat(facts()).isEqualTo(before);
        assertThat(exists("pf_wallet_charge")).isTrue();
        assertThat(exists("pf_point_ledger")).isTrue();
    }

    @ParameterizedTest @ValueSource(strings = {"NEW", "SENT", "UNKNOWN", "SUCCEEDED", "REJECTED"})
    void everyExistingCommandStatePreventsUndoAndPreservesAllFacts(String state) throws Exception {
        orderedPredecessors();
        insertCommand(state);
        var before = facts();
        assertThatThrownBy(() -> execute(U925)).isInstanceOf(SQLException.class).hasMessageContaining("U925_COMMAND_FACTS_EXIST");
        assertThat(facts()).isEqualTo(before);
        assertThat(exists("fr_design_remote_recovery")).isTrue();
    }

    @Test void orphanedBillingWithoutACommandStillPreventsUndo() throws Exception {
        execute(UNDO + "V20261111.961__undo_design_operations_admin.sql");
        jdbc.update("""
                INSERT INTO fr_design_billing(command_id,tenant_id,org_id,actor_id,actor_post_id,operation,price_id,price_version,
                  prompt_points,image_points,usage_points,max_prompt_units,max_image_units,max_usage_units,reserved_points)
                VALUES(1,1,10,1,2,'NOTE_POLISH','price',1,0,0,1,0,0,1,1)
                """);
        var before = facts();
        assertThatThrownBy(() -> execute(U925)).isInstanceOf(SQLException.class).hasMessageContaining("U925_DEPENDENCIES_REMAIN");
        assertThatThrownBy(() -> execute(UNDO + "V20261111.945__undo_design_wallet_pricing.sql"))
                .isInstanceOf(SQLException.class).hasMessageContaining("Design billing records exist");
        assertThat(facts()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"RESERVED", "RELEASED", "LEDGER", "AUDIT"})
    void historicalWalletOrAuditFactsRefuseUndoEvenWithoutCommandOrBilling(String fact) throws Exception {
        orderedPredecessors();
        if (fact.equals("LEDGER")) {
            jdbc.update("""
                    INSERT INTO pf_point_ledger(id,tenant_id,wallet_id,actor_id,type,delta,available_after,reserved_after,
                      biz_type,biz_id,idempotency_key,request_hash,reason)
                    VALUES(1,1,1,1,'CHARGE',-1,100,10,'DESIGN_CAPABILITY','1','ledger',?,'fixture')
                    """, "a".repeat(64));
        } else if (fact.equals("AUDIT")) {
            jdbc.update("""
                    INSERT INTO pf_command_event(id,domain,aggregate_type,aggregate_id,revision,command,org_id,actor_user_id,payload_hash)
                    VALUES(1,'franchise','fr-design-remote',1,1,'design-note-polish',10,1,?)
                    """, "a".repeat(64));
        } else {
            insertCharge(fact);
        }
        var before = facts();
        assertThatThrownBy(() -> execute(U925)).isInstanceOf(SQLException.class)
                .hasMessageContaining(fact.equals("AUDIT") ? "U925_AUDIT_FACTS_EXIST" : "U925_WALLET_FACTS_EXIST");
        assertThat(facts()).isEqualTo(before);
        assertThat(exists("fr_design_remote_command")).isTrue();
    }

    @Test void unknownViewDependencyIsRejectedWithoutCascadeOrDataLoss() throws Exception {
        orderedPredecessors();
        jdbc.execute("CREATE VIEW retained_remote_commands AS SELECT command_id FROM fr_design_remote_command");
        var before = facts();
        assertThatThrownBy(() -> execute(U925)).isInstanceOfSatisfying(SQLException.class,
                error -> assertThat(error.getSQLState()).isEqualTo("2BP01"));
        assertThat(exists("retained_remote_commands")).isTrue();
        assertThat(exists("fr_design_remote_command")).isTrue();
        assertThat(facts()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"COMMAND", "CHARGE"})
    void committedConcurrentFactCannotSlipBetweenEmptyCheckAndDrop(String kind) throws Exception {
        orderedPredecessors();
        var pool = Executors.newSingleThreadExecutor();
        try (Connection writer = source.getConnection()) {
            writer.setAutoCommit(false);
            try (var statement = writer.createStatement()) {
                statement.execute(kind.equals("COMMAND") ? commandInsert("UNKNOWN") : chargeInsert("RESERVED"));
            }
            var result = pool.submit(() -> {
                try { execute(U925); return (SQLException) null; }
                catch (SQLException expected) { return expected; }
            });
            String relation = kind.equals("COMMAND") ? "fr_design_remote_command" : "pf_wallet_charge";
            await().atMost(Duration.ofSeconds(4)).pollInterval(Duration.ofMillis(20)).until(() ->
                    Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_locks WHERE relation=to_regclass(?) AND NOT granted)", Boolean.class, relation)));
            writer.commit();
            SQLException failure = result.get(5, TimeUnit.SECONDS);
            assertThat((Throwable) failure).isNotNull().hasMessageContaining(kind.equals("COMMAND") ? "U925_COMMAND_FACTS_EXIST" : "U925_WALLET_FACTS_EXIST");
            assertThat(exists("fr_design_remote_command")).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + relation, Integer.class)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private void orderedPredecessors() throws Exception {
        execute(UNDO + "V20261111.961__undo_design_operations_admin.sql");
        execute(UNDO + "V20261111.945__undo_design_wallet_pricing.sql");
    }
    private void insertCommand(String state) { jdbc.execute(commandInsert(state)); }
    private String commandInsert(String state) {
        return "INSERT INTO fr_design_remote_command(caller_app_id,tenant_id,org_id,operation,request_key,fingerprint,actor_json,payload_json,state) "
                + "VALUES('platform',1,10,'NOTE_POLISH','intent','" + "a".repeat(64) + "','{}','{}','" + state + "')";
    }
    private void insertCharge(String status) { jdbc.execute(chargeInsert(status)); }
    private String chargeInsert(String status) {
        return "INSERT INTO pf_wallet_charge(id,tenant_id,wallet_id,actor_id,biz_type,biz_id,amount,idempotency_key,request_hash,status,terminal_operation) "
                + "VALUES(1,1,1,1,'DESIGN_CAPABILITY','1',10,'charge','" + "a".repeat(64) + "','" + status + "',"
                + (status.equals("RELEASED") ? "'RELEASE'" : "NULL") + ")";
    }
    private boolean exists(String relation) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, relation));
    }
    private Map<String, List<String>> facts() {
        var result = new LinkedHashMap<String, List<String>>();
        for (String table : List.of("fr_design_remote_command", "fr_design_billing", "fr_design_price", "pf_wallet", "pf_point_account",
                "pf_wallet_charge", "pf_point_ledger", "pf_command_event", "system_menu", "system_role_menu", "flyway_schema_history")) {
            if (exists(table)) result.put(table, jdbc.queryForList("SELECT to_jsonb(t)::text FROM " + table + " t ORDER BY 1", String.class));
        }
        return result;
    }
    private void execute(String resource) throws Exception {
        String sql;
        try (var stream = new ClassPathResource(resource).getInputStream()) {
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            try { statement.execute(sql); }
            catch (SQLException failure) {
                try { statement.execute("ROLLBACK"); }
                catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }
}
