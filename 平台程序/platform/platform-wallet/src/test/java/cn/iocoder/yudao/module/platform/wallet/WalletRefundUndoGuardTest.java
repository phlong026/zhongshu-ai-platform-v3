package cn.iocoder.yudao.module.platform.wallet;

import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/** 真实PG回滚守卫测试；钱包表使用原901-906迁移，外围表复用现有PG测试基座的结构。 */
@TestPropertySource(properties = "platform.test.extra-flyway-locations=,classpath:db/migration/platform-wallet")
class WalletRefundUndoGuardTest extends PlatformPgIntegrationTest {
    private static final List<String> MIGRATIONS = List.of("pf_wallet_accounting", "pf_wallet_service_mapping_plans_permissions",
            "pf_wallet_payment", "pf_wallet_refund", "pf_wallet_manual_adjustment", "pf_wallet_recovery");
    @Autowired DataSource dataSource;
    private Connection connection;
    private String schema;

    @BeforeEach
    void setUp() throws Exception {
        connection = dataSource.getConnection();
        schema = "undo904_" + UUID.randomUUID().toString().replace("-", "");
        execute(connection, "CREATE SCHEMA " + schema);
        execute(connection, "SET search_path TO " + schema);
        for (String table : List.of("pf_org_unit", "system_menu", "system_role", "system_role_menu")) {
            execute(connection, "CREATE TABLE " + table + " (LIKE public." + table + " INCLUDING ALL)");
        }
        for (int version = 901; version <= 906; version++) forward(version);
    }

    @AfterEach
    void close() throws Exception {
        if (connection != null) {
            try { execute(connection, "ROLLBACK"); }
            finally { connection.close(); }
        }
        // 保留隔离schema用于当前测试诊断，最终由Testcontainers清理整个临时实例。
    }

    @Test
    void nonEmptyRefundMustRefuseAndPreserveDataAndSchema() throws Exception {
        order();
        refund("UNKNOWN", "NULL", "NULL");
        undo(906); undo(905);
        refuseAndPreserve(904);
    }

    @Test
    void succeededRefundMustRefuseAndPreserveDataAndSchema() throws Exception {
        order();
        refund("SUCCEEDED", "1", "8");
        undo(906); undo(905);
        refuseAndPreserve(904);
    }

    @Test
    void failedRefundStillPreventsDestructiveSchemaRollback() throws Exception {
        order();
        refund("FAILED", "1", "7");
        undo(906); undo(905);
        refuseAndPreserve(904);
    }

    @Test
    void rechargeOrderWithoutRefundStillPreventsRollback() throws Exception {
        order();
        undo(906); undo(905);
        refuseAndPreserve(904);
    }

    @Test
    void trustedPaymentWithoutRefundStillPreventsRollback() throws Exception {
        order();
        execute(connection, """
                INSERT INTO pf_payment_transaction (id,tenant_id,wallet_id,order_id,channel,appid,offer_id,environment,
                    transaction_id,order_type,amount_fen,paid_at)
                VALUES (71,33,11,55,'XPAY','test-app','test-offer',0,'test-payment',0,100,now())
                """);
        undo(906); undo(905);
        refuseAndPreserve(904);
    }

    @Test
    void unknownUnboundInboxAlonePreventsRollback() throws Exception {
        inbox(connection);
        undo(906); undo(905);
        refuseAndPreserve(904);
    }

    @Test
    void emptyReverseOrderRollbackAndReapplySucceeds() throws Exception {
        Snapshot before = snapshot();
        undo(906); undo(905); undo(904);
        assertThat(value(connection, "SELECT to_regclass('pf_refund_order')::text")).isNull();
        assertThat(value(connection, "SELECT to_regclass('pf_recharge_order')::text")).isNotNull();
        undo(903);
        assertThat(value(connection, "SELECT to_regclass('pf_payment_notification_inbox')::text")).isNull();
        for (int version = 903; version <= 906; version++) forward(version);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void emptyUndoAndReapplyRemainAtomicWithinCallerRollback() throws Exception {
        Snapshot before = snapshot();
        String originalOid = value(connection, "SELECT 'pf_refund_order'::regclass::oid::text");
        connection.setAutoCommit(false);
        try {
            execute(connection, "CREATE TABLE undo_atomicity_marker (id BIGINT)");
            undo(906); undo(905); undo(904);
            for (int version = 904; version <= 906; version++) forward(version);
            assertThat(value(connection, "SELECT 'pf_refund_order'::regclass::oid::text")).isNotEqualTo(originalOid);
        } finally {
            connection.rollback();
            connection.setAutoCommit(true);
        }
        assertThat(value(connection, "SELECT to_regclass('undo_atomicity_marker')::text")).isNull();
        assertThat(value(connection, "SELECT 'pf_refund_order'::regclass::oid::text")).isEqualTo(originalOid);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void paymentUndoBeforeRefundUndoIsRejectedWithoutPartialSchemaChanges() throws Exception {
        undo(906); undo(905);
        assertThat(refuseAndPreserve(903).getSQLState()).isEqualTo("2BP01");
        undo(904); undo(903);
    }

    @Test
    void dependentViewBlocksRefundDropWithoutCascadeOrPartialChanges() throws Exception {
        undo(906); undo(905);
        execute(connection, "CREATE VIEW refund_dependency AS SELECT id, channel_state FROM pf_refund_order");
        assertThat(refuseAndPreserve(904).getSQLState()).isEqualTo("2BP01");
        execute(connection, "DROP VIEW refund_dependency");
        undo(904);
    }

    @Test
    void staleRepeatableReadSnapshotCannotBypassNonEmptyInboxGuard() throws Exception {
        fixedSnapshotIsolationPreservesCommittedFacts(Connection.TRANSACTION_REPEATABLE_READ);
    }

    @Test
    void staleSerializableSnapshotCannotBypassNonEmptyInboxGuard() throws Exception {
        fixedSnapshotIsolationPreservesCommittedFacts(Connection.TRANSACTION_SERIALIZABLE);
    }

    private void fixedSnapshotIsolationPreservesCommittedFacts(int isolation) throws Exception {
        undo(906); undo(905);
        connection.setTransactionIsolation(isolation);
        connection.setAutoCommit(false);
        Snapshot committed;
        try {
            assertThat(value(connection, "SELECT count(*)::text FROM pf_payment_notification_inbox")).isEqualTo("0");
            try (var writer = dataSource.getConnection()) {
                execute(writer, "SET search_path TO " + schema);
                inbox(writer);
                committed = snapshot(writer);
            }
            SQLException rejection = null;
            try { undo(904); }
            catch (SQLException failure) { rejection = failure; }
            assertThat((Throwable) rejection).as("Stale transaction snapshot must not bypass committed inbox data")
                    .isNotNull().hasMessageContaining("Refund schema rollback requires READ COMMITTED isolation");
        } finally {
            connection.rollback();
            connection.setAutoCommit(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        }
        assertThat(snapshot()).isEqualTo(committed);
        assertThat(value(connection, "SELECT to_regclass('pf_refund_order')::text")).isNotNull();
        assertThat(value(connection, "SELECT count(*)::text FROM pf_payment_notification_inbox")).isEqualTo("1");
    }

    @Test
    void inboxWriterCommittedWhileUndoWaitsCannotBeDeletedOrBypassed() throws Exception {
        undo(906); undo(905);
        try (var writer = dataSource.getConnection(); var rollback = dataSource.getConnection()) {
            execute(writer, "SET search_path TO " + schema);
            execute(rollback, "SET search_path TO " + schema);
            execute(writer, "BEGIN");
            inbox(writer);
            int pid = Integer.parseInt(value(rollback, "SELECT pg_backend_pid()::text"));
            var pool = Executors.newSingleThreadExecutor();
            try {
                var result = pool.submit(() -> {
                    try {
                        execute(rollback, script("migration-undo", 904));
                        return null;
                    } catch (SQLException failure) {
                        execute(rollback, "ROLLBACK");
                        return failure;
                    }
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!"Lock".equals(value(connection, "SELECT wait_event_type FROM pg_stat_activity WHERE pid = " + pid))) {
                    if (result.isDone()) fail("Rollback did not wait for the concurrent inbox writer");
                    if (System.nanoTime() > deadline) fail("Rollback did not reach the table lock");
                    Thread.sleep(10);
                }
                execute(writer, "COMMIT");
                assertThat((Throwable) result.get(5, TimeUnit.SECONDS)).isNotNull();
                assertThat(value(connection, "SELECT to_regclass('pf_refund_order')::text")).isNotNull();
                assertThat(value(connection, "SELECT count(*)::text FROM pf_payment_notification_inbox")).isEqualTo("1");
                assertThat(value(connection, "SELECT encode(payload,'hex') FROM pf_payment_notification_inbox")).isEqualTo("7b7d");
            } finally {
                execute(writer, "ROLLBACK");
                pool.shutdownNow();
                assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    private SQLException refuseAndPreserve(int version) throws Exception {
        Snapshot before = snapshot();
        SQLException rejection = null;
        try { undo(version); }
        catch (SQLException failure) { rejection = failure; execute(connection, "ROLLBACK"); }
        assertThat((Throwable) rejection).as("Non-empty or dependent schema rollback must be rejected; refund relation=%s",
                value(connection, "SELECT to_regclass('pf_refund_order')::text")).isNotNull();
        assertThat(snapshot()).as("Every table row, relation, column, constraint, index and function must remain unchanged").isEqualTo(before);
        return rejection;
    }

    private void order() throws Exception {
        execute(connection, """
                INSERT INTO pf_org_unit (dept_id,kind,org_id,org_type) VALUES (22,'ORG',22,'undo-test');
                INSERT INTO pf_wallet (id,org_id,tenant_id) VALUES (11,22,33);
                INSERT INTO pf_point_account (wallet_id,tenant_id) VALUES (11,33);
                INSERT INTO pf_recharge_plan (id,tenant_id,name,price_fen,base_points,bonus_points) VALUES (44,33,'Test',100,10,2);
                INSERT INTO pf_recharge_order (id,tenant_id,wallet_id,org_id,actor_id,post_id,binding_id,order_no,plan_id,
                    plan_snapshot,price_fen,base_points,bonus_points,payer_appid,payer_openid,channel,offer_id,environment,idempotency_key,request_hash)
                VALUES (55,33,11,22,1,2,3,'PFW55',44,'{}',100,10,2,'test-app','test-payer','XPAY','test-offer',0,'order-55',repeat('a',64))
                """);
    }

    private void refund(String state, String type, String status) throws Exception {
        execute(connection, """
                INSERT INTO pf_refund_order (id,tenant_id,wallet_id,order_id,refund_no,origin,actor_id,request_key,request_hash,
                    amount_fen,base_points,bonus_points,reason,channel,appid,offer_id,environment,channel_refund_id,channel_state,point_state,order_type,order_status)
                VALUES (66,33,11,55,'refund-66','MERCHANT',1,'refund-66',repeat('b',64),100,10,2,0,'XPAY','test-app','test-offer',0,
                    'test-channel-refund','%s','RESERVED',%s,%s)
                """.formatted(state, type, status));
    }

    private static void inbox(Connection connection) throws Exception {
        execute(connection, """
                INSERT INTO pf_payment_notification_inbox (id,tenant_id,channel,appid,offer_id,environment,body_hash,payload)
                VALUES (77,33,'XPAY','test-app','test-offer',0,repeat('c',64),decode('7b7d','hex'))
                """);
    }

    private void forward(int version) throws Exception { execute(connection, script("migration", version)); }
    private void undo(int version) throws Exception { execute(connection, script("migration-undo", version)); }
    private static String script(String kind, int version) throws Exception {
        String name = ("migration-undo".equals(kind) ? "undo_" : "") + MIGRATIONS.get(version - 901);
        var resource = new org.springframework.core.io.ClassPathResource("db/" + kind + "/platform-wallet/V20261111." + version + "__" + name + ".sql");
        try (var stream = resource.getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.setQueryTimeout(10); statement.execute(sql); }
    }

    private static String value(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            return result.next() ? result.getString(1) : null;
        }
    }

    private Snapshot snapshot() throws Exception { return snapshot(connection); }

    private Snapshot snapshot(Connection connection) throws Exception {
        Map<String, String> data = new TreeMap<>();
        List<String> metadata = new ArrayList<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=current_schema() AND c.relkind IN ('r','v') ORDER BY c.relname")) {
            while (rows.next()) {
                String table = rows.getString(1);
                data.put(table, value(connection, "SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM " + table + " t"));
            }
        }
        for (String sql : List.of(
                "SELECT c.relname||':'||c.relkind::text FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=current_schema() ORDER BY 1",
                "SELECT table_name||':'||column_name||':'||data_type||':'||coalesce(column_default,'')||':'||is_nullable FROM information_schema.columns WHERE table_schema=current_schema() ORDER BY table_name,ordinal_position",
                "SELECT c.relname||':'||k.conname||':'||pg_get_constraintdef(k.oid) FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=current_schema() ORDER BY 1",
                "SELECT indexdef FROM pg_indexes WHERE schemaname=current_schema() ORDER BY indexname",
                "SELECT pg_get_functiondef(p.oid) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname=current_schema() ORDER BY p.proname")) {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
                while (rows.next()) metadata.add(rows.getString(1));
            }
        }
        return new Snapshot(data, metadata);
    }

    record Snapshot(Map<String, String> data, List<String> metadata) { }
}
