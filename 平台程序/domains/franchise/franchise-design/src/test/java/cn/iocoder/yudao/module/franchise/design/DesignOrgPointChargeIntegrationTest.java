package cn.iocoder.yudao.module.franchise.design;

import cn.iocoder.yudao.module.franchise.api.DesignServicePort;
import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.*;
import cn.iocoder.yudao.module.franchise.design.billing.DesignWalletChargeAdapter;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.wallet.orgpoint.OrgPointPriceService;
import cn.iocoder.yudao.module.platform.wallet.orgpoint.OrgPointService;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DesignOrgPointChargeIntegrationTest {
    private PostgreSQLContainer<?> container;
    private SimpleDriverDataSource dataSource;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private OrgPointService points;
    private OrgPointPriceService prices;
    private DesignWalletChargeAdapter charges;
    private final PostSnapshot actor = new PostSnapshot(41, 51, 21, "store", "owner", null, false, Set.of());

    @BeforeAll
    void database() throws Exception {
        container = new PostgreSQLContainer<>("postgres:17-alpine");
        container.start();
        dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.postgresql.Driver.class);
        dataSource.setUrl(container.getJdbcUrl());
        dataSource.setUsername(container.getUsername());
        dataSource.setPassword(container.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        for (String path : List.of(
                "db/migration/franchise-design-remote/V20261111.925__remote_design_commands.sql",
                "db/migration/platform-wallet/V20261114.911__org_point_pool.sql",
                "db/migration/platform-wallet/V20261114.912__design_billing_binding.sql",
                "db/migration/platform-wallet/V20261114.914__org_point_subject_price.sql")) {
            try (var stream = new ClassPathResource(path).getInputStream()) {
                jdbc.execute(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        var manager = new DataSourceTransactionManager(dataSource);
        transaction = new TransactionTemplate(manager);
        prices = new OrgPointPriceService(dataSource, manager);
        points = new OrgPointService(dataSource, manager, message -> 0L, prices);
        charges = new DesignWalletChargeAdapter(dataSource, manager, points);
    }

    @AfterAll
    void stop() {
        container.stop();
    }

    @BeforeEach
    void fixture() {
        jdbc.execute("DROP TABLE IF EXISTS fr_design_billing");
        jdbc.execute("TRUNCATE fr_design_remote_command, design_billing_binding, org_point_ledger, org_point_account, "
                + "org_member_permission, org_member_quota, org_point_subject_price");
        points.ensurePool(21);
        points.adjust(21, 1_000, "test:fund", "test funding", 1L);
    }

    @Test
    void readinessAllowsEmptyNewAndFullyTerminalLegacyCommands() {
        charges.requireReady();
        insertCommand("new-command", "{\"priceId\":\"ai_flat\",\"version\":1}");
        charges.requireReady();

        jdbc.execute("CREATE TABLE fr_design_billing(command_id BIGINT PRIMARY KEY, terminal BOOLEAN NOT NULL)");
        long rejected = insertCommand("rejected-legacy-command", "{\"priceId\":\"30\",\"version\":1}",
                "REJECTED", null, false);
        long synchronous = insertCommand("synchronous-legacy-command", "{\"priceId\":\"31\",\"version\":1}",
                "SUCCEEDED", null, false);
        long settledAsync = insertCommand("settled-async-legacy-command", "{\"priceId\":\"32\",\"version\":1}",
                "SUCCEEDED", 8001L, true);
        jdbc.batchUpdate("INSERT INTO fr_design_billing VALUES (?,true)", List.of(
                new Object[]{rejected}, new Object[]{synchronous}, new Object[]{settledAsync}));
        charges.requireReady();
    }

    @Test
    void readinessRejectsRecoverableLegacyAndEveryUnknownSnapshotShape() {
        jdbc.execute("CREATE TABLE fr_design_billing(command_id BIGINT PRIMARY KEY, terminal BOOLEAN NOT NULL)");
        long unknown = insertCommand("unknown-legacy", "{\"priceId\":\"41\"}", "UNKNOWN", null, false);
        long asyncUnsettled = insertCommand("async-unsettled-legacy", "{\"priceId\":\"42\"}",
                "SUCCEEDED", 9001L, false);
        long unknownSubject = insertCommand("unknown-subject", "{\"priceId\":\"future_subject\"}",
                "UNKNOWN", null, false);
        long missingPriceId = insertCommand("missing-price-id", "{\"version\":1}", "UNKNOWN", null, false);
        jdbc.batchUpdate("INSERT INTO fr_design_billing VALUES (?,true)", List.of(
                new Object[]{unknown}, new Object[]{asyncUnsettled},
                new Object[]{unknownSubject}, new Object[]{missingPriceId}));

        assertThatThrownBy(charges::requireReady)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PLATFORM_DESIGN_LEGACY_IN_FLIGHT_REQUIRES_MANUAL_CUTOVER:4");
    }

    @Test
    void commandAndReserveRollBackTogetherAndNeverTouchLegacyDesignWalletTables() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS command_probe(id BIGINT PRIMARY KEY)");
        var payload = new FlatJob(71, new DesignServicePort.FlatJobCreate(2, "2K", "PORTRAIT", "request"));
        var price = transaction.execute(status -> charges.quote(actor, Operation.CREATE_FLAT_JOB, payload));

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO command_probe VALUES (101)");
            charges.reserve(101, actor, price);
            throw new IllegalStateException("rollback probe");
        })).hasMessage("rollback probe");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM command_probe", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM org_point_ledger WHERE type='RESERVE'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_billing_binding", Integer.class)).isZero();
        assertThat(tableExists("pf_wallet_charge")).isFalse();
        assertThat(tableExists("fr_design_price")).isFalse();
        assertThat(tableExists("fr_design_billing")).isFalse();
    }

    @Test
    void nonTerminalFactsStayPendingAndTerminalSettlementOccursOnce() {
        var payload = new FlatJob(71, new DesignServicePort.FlatJobCreate(2, "2K", "PORTRAIT", "request"));
        var price = transaction.execute(status -> charges.quote(actor, Operation.CREATE_FLAT_JOB, payload));
        transaction.executeWithoutResult(status -> charges.reserve(102, actor, price));
        prices.publish("ai_flat", 9_000_000L, 1, "test-admin");

        transaction.executeWithoutResult(status -> charges.settle(102, actor, price,
                settlement(2, false, 1)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM org_point_ledger WHERE type IN ('SETTLE','RELEASE')", Integer.class)).isZero();

        transaction.executeWithoutResult(status -> charges.settle(102, actor, price,
                settlement(3, true, 1)));
        transaction.executeWithoutResult(status -> charges.settle(102, actor, price,
                settlement(3, true, 1)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM org_point_ledger WHERE type='SETTLE'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT balance FROM org_point_account WHERE org_id=21", Long.class)).isEqualTo(998);
    }

    @Test
    void zeroDeliveryAndDefinitiveRejectionReleaseButRevisionUsesElevationSubject() {
        var revision = new Revision(71, new DesignServicePort.RevisionCreate(
                "change facade", java.util.Map.of(), 2, "2K", "PORTRAIT", "revision"));
        var price = transaction.execute(status -> charges.quote(actor, Operation.CREATE_REVISION_REQUEST, revision));
        assertThat(price.priceId()).isEqualTo("ai_elevation");
        transaction.executeWithoutResult(status -> charges.reserve(103, actor, price));
        transaction.executeWithoutResult(status -> charges.settle(103, actor, price,
                settlement(2, true, 0)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM org_point_ledger WHERE type='RELEASE'", Integer.class)).isOne();

        var note = transaction.execute(status -> charges.quote(actor, Operation.NOTE_POLISH,
                new Note("copy", "sales")));
        assertThat(note.priceId()).isEqualTo("note_polish");
        transaction.executeWithoutResult(status -> charges.reserve(104, actor, note));
        transaction.executeWithoutResult(status -> charges.reject(104, actor, note, 429));
        transaction.executeWithoutResult(status -> charges.reject(104, actor, note, 429));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM org_point_ledger WHERE type='RELEASE'", Integer.class)).isEqualTo(2);
    }

    @Test
    void concurrentIdenticalTerminalFactsCreateOneTerminalLedgerEntry() throws Exception {
        var payload = new FlatJob(71, new DesignServicePort.FlatJobCreate(2, "2K", "PORTRAIT", "concurrent"));
        var price = transaction.execute(status -> charges.quote(actor, Operation.CREATE_FLAT_JOB, payload));
        transaction.executeWithoutResult(status -> charges.reserve(105, actor, price));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> transaction.executeWithoutResult(status ->
                    charges.settle(105, actor, price, settlement(2, true, 1))));
            var second = pool.submit(() -> transaction.executeWithoutResult(status ->
                    charges.settle(105, actor, price, settlement(2, true, 1))));
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM org_point_ledger WHERE type='SETTLE'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT balance FROM org_point_account WHERE org_id=21", Long.class)).isEqualTo(998);
    }

    @Test
    void ownershipConflictsNeverSettleOrChangeBalance() {
        var payload = new FlatJob(71, new DesignServicePort.FlatJobCreate(2, "2K", "PORTRAIT", "ownership"));
        var price = transaction.execute(status -> charges.quote(actor, Operation.CREATE_FLAT_JOB, payload));
        transaction.executeWithoutResult(status -> charges.reserve(106, actor, price));
        long reservedBalance = jdbc.queryForObject("SELECT balance FROM org_point_account WHERE org_id=21", Long.class);

        for (PostSnapshot mismatch : List.of(
                new PostSnapshot(41, 51, 22, "store", "owner", null, false, Set.of()),
                new PostSnapshot(41, 52, 21, "store", "owner", null, false, Set.of()),
                new PostSnapshot(42, 51, 21, "store", "owner", null, false, Set.of()))) {
            assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                    charges.settle(106, mismatch, price, settlement(2, true, 1))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("PLATFORM_DESIGN_BILLING_CONFLICT");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM org_point_ledger WHERE type IN ('SETTLE','RELEASE')", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT balance FROM org_point_account WHERE org_id=21", Long.class)).isEqualTo(reservedBalance);
    }

    private Settlement settlement(long version, boolean terminal, int valid) {
        return new Settlement(91, version, terminal ? "SUCCEEDED" : "PROCESSING", terminal,
                2, valid, 0, 0, 0, 0, valid, 0);
    }

    private boolean tableExists(String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, table));
    }

    private long insertCommand(String requestKey, String priceSnapshot) {
        return insertCommand(requestKey, priceSnapshot, "UNKNOWN", null, false);
    }

    private long insertCommand(String requestKey, String priceSnapshot, String state, Long jobId,
                               boolean settledTerminal) {
        return jdbc.queryForObject("""
                INSERT INTO fr_design_remote_command(caller_app_id,tenant_id,org_id,operation,request_key,
                    fingerprint,actor_json,payload_json,price_snapshot,state,job_id,settled_terminal)
                VALUES('B',31,21,'CREATE_FLAT_JOB',?,repeat('a',64),'{}','{}',?::jsonb,?,?,?)
                RETURNING command_id
                """, Long.class, requestKey, priceSnapshot, state, jobId, settledTerminal);
    }
}
