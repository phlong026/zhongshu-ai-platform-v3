package cn.iocoder.yudao.module.commerce.orgpoint;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批 D / B-16 验收：存量个人点迁入公司池（真实 PostgreSQL）。
 * 覆盖：预检聚合、未确认映射阻塞、在途预留阻塞、幂等重跑、守恒（个人迁出=池迁入）、个人账户清零。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DesignPointMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("zhongshu_design")
            .withUsername("zhongshu")
            .withPassword("zhongshu");

    private JdbcTemplate jdbc;
    private DataSource dataSource;
    private DesignPointMigrationService service;

    @BeforeAll
    void setUp() {
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriverClass(org.postgresql.Driver.class);
        ds.setUrl(PG.getJdbcUrl());
        ds.setUsername(PG.getUsername());
        ds.setPassword(PG.getPassword());
        this.dataSource = ds;
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration/platform", "classpath:db/migration/identity",
                        "classpath:db/migration/commerce")
                .load().migrate();
        service = new DesignPointMigrationService(dataSource, new DataSourceTransactionManager(dataSource));
        // 手工构造时开关默认为 false（Spring 由 @Value 注入）；测试显式开启以覆盖历史迁移逻辑
        org.springframework.test.util.ReflectionTestUtils.setField(service, "migrationEnabled", true);
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE org_point_ledger, org_point_account, design_point_ledger, "
                + "design_point_account, design_identity_mapping");
    }

    private void account(long accountId, long available, long reserved) {
        jdbc.update("INSERT INTO design_point_account (id, user_id, available_points, reserved_points) "
                + "VALUES (?, ?, ?, ?)", accountId * 10, accountId, available, reserved);
    }

    private void mapping(long accountId, long orgId, long userId, String status) {
        jdbc.update("INSERT INTO design_identity_mapping (id, account_id, matched_user_id, matched_org_id, "
                        + "match_type, status) VALUES (?, ?, ?, ?, 'MANUAL', ?)",
                accountId * 100, accountId, userId, orgId, status);
    }

    @Test
    void executeIsDisabledByArchitectureDecision() {
        // 架构决策：两系统独立运营 → 默认关闭，execute 必须拒绝且零写入
        var disabled = new DesignPointMigrationService(dataSource,
                new DataSourceTransactionManager(dataSource));  // 默认 migrationEnabled=false
        account(100L, 10, 0);
        mapping(100L, 7001L, 500L, "CONFIRMED");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> disabled.execute("admin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("独立运营");
        assertThat(jdbc.queryForObject("SELECT available_points FROM design_point_account WHERE user_id=100",
                Long.class)).isEqualTo(10);
    }

    @Test
    void dryRunAggregatesByOrgAndReportsBlocked() {
        account(101L, 30, 0);
        account(102L, 20, 0);
        account(103L, 10, 5);   // 在途 → 阻塞
        account(104L, 7, 0);    // 未确认映射 → 阻塞
        mapping(101L, 7001L, 501L, "CONFIRMED");
        mapping(102L, 7001L, 502L, "CONFIRMED");
        mapping(103L, 7001L, 503L, "CONFIRMED");
        mapping(104L, 7001L, 504L, "CANDIDATE");

        var report = service.dryRun();

        assertThat(report.totalAvailable()).isEqualTo(50);
        assertThat(report.eligibleAccounts()).isEqualTo(2);
        assertThat(report.byOrg()).singleElement()
                .satisfies(b -> {
                    assertThat(b.orgId()).isEqualTo(7001L);
                    assertThat(b.credits()).isEqualTo(50);
                    assertThat(b.accountCount()).isEqualTo(2);
                });
        assertThat(report.blocked()).hasSize(2);
    }

    @Test
    void executeRejectedWhenBlocked() {
        account(201L, 10, 3);
        mapping(201L, 7001L, 601L, "CONFIRMED");

        assertThatThrownBy(() -> service.execute("admin")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("阻塞");
    }

    @Test
    void executeMigratesAndBalances() {
        account(301L, 30, 0);
        account(302L, 20, 0);
        mapping(301L, 7001L, 501L, "CONFIRMED");
        mapping(302L, 7002L, 502L, "CONFIRMED");

        var result = service.execute("admin-1");

        assertThat(result.migratedAccounts()).isEqualTo(2);
        assertThat(result.migratedCredits()).isEqualTo(50);
        assertThat(result.balanced()).isTrue();
        // 个人账户清零
        assertThat(jdbc.queryForObject("SELECT available_points FROM design_point_account WHERE user_id=301",
                Long.class)).isZero();
        // 池余额到位
        assertThat(jdbc.queryForObject("SELECT balance FROM org_point_account WHERE org_id=7001",
                Long.class)).isEqualTo(30);
        assertThat(jdbc.queryForObject("SELECT balance FROM org_point_account WHERE org_id=7002",
                Long.class)).isEqualTo(20);
        // 个人留痕
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_point_ledger WHERE type='MIGRATION_OUT'",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void blockedBatchWritesNothing() {
        account(501L, 30, 0);   // 可迁，但因同批存在阻塞账号必须零写入
        account(502L, 10, 4);   // 在途阻塞
        account(503L, 5, 0);    // 未确认映射阻塞
        mapping(501L, 7001L, 501L, "CONFIRMED");
        mapping(502L, 7001L, 502L, "CONFIRMED");
        mapping(503L, 7001L, 503L, "CANDIDATE");

        assertThatThrownBy(() -> service.execute("admin")).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_point_ledger WHERE type='MIGRATION_OUT'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM org_point_ledger WHERE type='MIGRATION'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT available_points FROM design_point_account WHERE user_id=501",
                Long.class)).isEqualTo(30);
    }

    @Test
    void secondBatchUsesCumulativeBaseline() {
        account(601L, 10, 0);
        mapping(601L, 7001L, 501L, "CONFIRMED");
        service.execute("admin");
        account(602L, 25, 0);
        mapping(602L, 7002L, 502L, "CONFIRMED");

        var second = service.execute("admin");

        assertThat(second.migratedAccounts()).isEqualTo(1);
        assertThat(second.migratedCredits()).isEqualTo(25);
        assertThat(second.personalLedgerSum()).isEqualTo(35);
        assertThat(second.orgLedgerSum()).isEqualTo(35);
        assertThat(second.balanced()).isTrue();
    }

    @Test
    void ledgerPairsMatchBizKey() {
        account(701L, 12, 0);
        account(702L, 8, 0);
        mapping(701L, 7001L, 501L, "CONFIRMED");
        mapping(702L, 7001L, 502L, "CONFIRMED");
        service.execute("admin");

        Integer pairs = jdbc.queryForObject(
                "SELECT count(*) FROM org_point_ledger o JOIN design_point_ledger p "
                        + "ON p.idempotency_key = o.biz_key || ':OUT' AND p.type = 'MIGRATION_OUT' "
                        + "WHERE o.type = 'MIGRATION' AND o.available_delta = -p.delta", Integer.class);
        assertThat(pairs).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_point_account "
                + "WHERE available_points > 0", Integer.class)).isZero();
    }

    @Test
    void executeIsIdempotentOnRerun() {
        account(401L, 15, 0);
        mapping(401L, 7001L, 501L, "CONFIRMED");
        service.execute("admin-1");

        var second = service.execute("admin-1");

        assertThat(second.migratedAccounts()).isZero();
        assertThat(second.migratedCredits()).isZero();
        assertThat(jdbc.queryForObject("SELECT balance FROM org_point_account WHERE org_id=7001",
                Long.class)).isEqualTo(15);
        assertThat(second.balanced()).isTrue();
    }
}
