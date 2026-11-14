package cn.iocoder.yudao.module.platform.wallet.orgpoint;

import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestPropertySource(properties = "platform.test.extra-flyway-locations=,classpath:db/migration/platform-wallet")
class OrgPointUndoGuardTest extends PlatformPgIntegrationTest {

    @Autowired private DataSource dataSource;
    private Connection connection;
    private String schema;

    @BeforeEach
    void setUp() throws Exception {
        connection = dataSource.getConnection();
        schema = "orgundo_" + UUID.randomUUID().toString().replace("-", "");
        execute("CREATE SCHEMA " + schema);
        execute("SET search_path TO " + schema);
        execute("CREATE TABLE system_menu(id BIGINT PRIMARY KEY,name TEXT,permission TEXT,type INT,sort INT,"
                + "parent_id BIGINT,path TEXT,icon TEXT,component TEXT,component_name TEXT,status INT,"
                + "visible BOOLEAN,keep_alive BOOLEAN,always_show BOOLEAN,creator TEXT,updater TEXT)");
        execute("CREATE TABLE system_role_menu(id BIGINT PRIMARY KEY,role_id BIGINT,menu_id BIGINT)");
        forward(911);
        forward(912);
        forward(913);
        forward(914);
        forward(917);
        forward(918);
    }

    @AfterEach
    void close() throws Exception {
        if (connection != null) connection.close();
    }

    @Test
    void nonEmptyLedgerRefusesPoolRollbackAndPreservesTables() throws Exception {
        execute("INSERT INTO org_point_account(id,org_id,balance) VALUES (1,1,10)");
        execute("INSERT INTO org_point_ledger(id,org_id,biz_key,type,available_delta) "
                + "VALUES (1,1,'opening','MANUAL_ADJUST',10)");

        assertThatThrownBy(() -> undo(911)).isInstanceOf(SQLException.class)
                .hasMessageContaining("company point pool rollback refused");
        execute("ROLLBACK");
        assertThat(value("SELECT count(*) FROM org_point_ledger")).isEqualTo(1);
        assertThat(value("SELECT count(*) FROM org_point_account")).isEqualTo(1);
    }

    @Test
    void nonEmptyBindingAndReceiptRefuseTheirRollback() throws Exception {
        execute("INSERT INTO design_billing_binding(id,origin,org_id,actor_user_id,ref_type,ref_id,subject,operation_id,biz_key) "
                + "VALUES (1,'BRIDGE',1,2,'ai_job',3,'copy',4,'binding')");
        execute("INSERT INTO design_prompt_receipt(id,job_id,attempt_no,outcome) VALUES (1,3,1,'UNKNOWN')");

        assertThatThrownBy(() -> undo(913)).isInstanceOf(SQLException.class)
                .hasMessageContaining("prompt receipt rollback refused");
        execute("ROLLBACK");
        assertThatThrownBy(() -> undo(912)).isInstanceOf(SQLException.class)
                .hasMessageContaining("billing binding rollback refused");
        execute("ROLLBACK");
        assertThat(value("SELECT count(*) FROM design_billing_binding")).isEqualTo(1);
        assertThat(value("SELECT count(*) FROM design_prompt_receipt")).isEqualTo(1);
    }

    @Test
    void emptySchemasCanRollbackInReverseOrder() throws Exception {
        execute("INSERT INTO system_role_menu(id,role_id,menu_id) VALUES(1,2,917000)");
        undo(918);
        undo(917);
        undo(914);
        undo(913);
        undo(912);
        undo(911);
        assertThat(regclass("org_point_subject_price")).isNull();
        assertThat(regclass("org_point_hq_price_request")).isNull();
        assertThat(regclass("design_prompt_receipt")).isNull();
        assertThat(regclass("design_billing_binding")).isNull();
        assertThat(regclass("org_point_ledger")).isNull();
        assertThat(value("SELECT count(*) FROM system_role_menu WHERE menu_id=917000")).isZero();
    }

    @Test
    void headquartersFactsRefuseRollbackAndRemainAuditable() throws Exception {
        execute("INSERT INTO org_point_hq_adjustment(id,org_id,delta,reason,request_key,request_hash,maker_id) "
                + "VALUES(1,2,3,'reason','key',repeat('a',64),4)");
        assertThatThrownBy(() -> undo(917)).isInstanceOf(SQLException.class)
                .hasMessageContaining("refuse undo");
        execute("ROLLBACK");
        assertThat(value("SELECT count(*) FROM org_point_hq_adjustment")).isEqualTo(1);
    }

    @Test
    void nonEmptyPriceAuditRefusesHeadquartersRollback() throws Exception {
        execute("INSERT INTO org_point_price_audit(id,subject,price_id,price_micros,min_charge,actor_id,reason) "
                + "VALUES(1,'ai_flat',2,3000000,1,4,'approved price')");
        assertThatThrownBy(() -> undo(917)).isInstanceOf(SQLException.class)
                .hasMessageContaining("refuse undo");
        execute("ROLLBACK");
        assertThat(value("SELECT count(*) FROM org_point_price_audit")).isEqualTo(1);
    }

    @Test
    void nonEmptyPriceRequestRefusesItsOwnRollbackAndRemainsImmutable() throws Exception {
        execute("INSERT INTO org_point_hq_price_request(id,request_key,request_hash,tenant_id,actor_id,subject,"
                + "price_micros,min_charge,reason,price_id) VALUES(1,'price-key',repeat('a',64),1,2,'copy',"
                + "1000000,1,'approved',3)");
        assertThatThrownBy(() -> undo(918)).isInstanceOf(SQLException.class)
                .hasMessageContaining("refuse undo");
        assertThatThrownBy(() -> execute("UPDATE org_point_hq_price_request SET reason='changed' WHERE id=1"))
                .isInstanceOf(SQLException.class).hasMessageContaining("append-only");
        execute("ROLLBACK");
        assertThat(value("SELECT count(*) FROM org_point_hq_price_request")).isEqualTo(1);
    }

    @Test
    void priceRequestRollbackLockPreventsConcurrentFactInsertion() throws Exception {
        connection.setAutoCommit(false);
        execute("LOCK TABLE org_point_hq_price_request IN ACCESS EXCLUSIVE MODE");
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var insertion = executor.submit(() -> {
                try (Connection other = dataSource.getConnection(); var statement = other.createStatement()) {
                    statement.execute("SET search_path TO " + schema);
                    statement.execute("INSERT INTO org_point_hq_price_request(id,request_key,request_hash,tenant_id,"
                            + "actor_id,subject,price_micros,min_charge,reason,price_id) VALUES(9,'race',repeat('b',64),"
                            + "1,2,'copy',1000000,1,'racing fact',3)");
                    return null;
                } catch (SQLException error) {
                    return error;
                }
            });
            assertThatThrownBy(() -> insertion.get(200, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            undo(918);
            connection.commit();
            Object insertionResult = insertion.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(insertionResult).isInstanceOf(SQLException.class);
            assertThat(regclass("org_point_hq_price_request")).isNull();
        } finally {
            executor.shutdownNow();
            connection.setAutoCommit(true);
        }
    }

    @Test
    void rollbackTableLocksPreventConcurrentFactInsertion() throws Exception {
        connection.setAutoCommit(false);
        execute("LOCK TABLE org_point_hq_adjustment IN ACCESS EXCLUSIVE MODE");
        execute("LOCK TABLE org_point_price_audit IN ACCESS EXCLUSIVE MODE");
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var insertion = executor.submit(() -> {
                try (Connection other = dataSource.getConnection(); var statement = other.createStatement()) {
                    statement.execute("SET search_path TO " + schema);
                    statement.execute("INSERT INTO org_point_hq_adjustment(id,org_id,delta,reason,request_key,request_hash,maker_id) "
                            + "VALUES(9,2,3,'racing fact','race',repeat('b',64),4)");
                    return null;
                } catch (SQLException error) {
                    return error;
                }
            });
            assertThatThrownBy(() -> insertion.get(200, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            undo(917);
            connection.commit();
            Object insertionResult = insertion.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(insertionResult).isInstanceOf(SQLException.class);
            assertThat(regclass("org_point_hq_adjustment")).isNull();
            assertThat(regclass("org_point_price_audit")).isNull();
        } finally {
            executor.shutdownNow();
            connection.setAutoCommit(true);
        }
    }

    @Test
    void activePriceRefusesRollbackAndPreservesConfiguration() throws Exception {
        execute("INSERT INTO org_point_subject_price(id,subject,unit,price_micros,min_charge,version,status) "
                + "VALUES (1,'ai_flat','CALL',2000000,1,1,'ACTIVE')");

        assertThatThrownBy(() -> undo(914)).isInstanceOf(SQLException.class)
                .hasMessageContaining("subject price rollback refused");
        execute("ROLLBACK");

        assertThat(value("SELECT count(*) FROM org_point_subject_price WHERE status='ACTIVE'"))
                .isEqualTo(1);
    }

    private void forward(int version) throws Exception { execute(script("migration", version)); }
    private void undo(int version) throws Exception {
        boolean manageTransaction = connection.getAutoCommit();
        if (manageTransaction) {
            connection.setAutoCommit(false);
        }
        try {
            execute(script("migration-undo", version));
            if (manageTransaction) {
                connection.commit();
            }
        } catch (Exception error) {
            if (manageTransaction) {
                connection.rollback();
            }
            throw error;
        } finally {
            if (manageTransaction) {
                connection.setAutoCommit(true);
            }
        }
    }

    private static String script(String kind, int version) throws Exception {
        String name = switch (version) {
            case 911 -> "org_point_pool";
            case 912 -> "design_billing_binding";
            case 913 -> "design_prompt_receipt";
            case 914 -> "org_point_subject_price";
            case 917 -> "org_point_headquarters_admin";
            case 918 -> "org_point_hq_price_idempotency";
            default -> throw new IllegalArgumentException("unsupported version " + version);
        };
        String undo = "migration-undo".equals(kind) ? "undo_" : "";
        var resource = new ClassPathResource("db/" + kind + "/platform-wallet/V20261114."
                + version + "__" + undo + name + ".sql");
        try (var stream = resource.getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void execute(String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            statement.execute(sql);
        }
    }

    private int value(String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private String regclass(String relation) throws SQLException {
        try (var statement = connection.createStatement();
             var rs = statement.executeQuery("SELECT to_regclass('" + relation + "')::text")) {
            rs.next();
            return rs.getString(1);
        }
    }
}
