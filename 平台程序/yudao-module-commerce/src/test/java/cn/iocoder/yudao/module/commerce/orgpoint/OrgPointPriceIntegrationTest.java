package cn.iocoder.yudao.module.commerce.orgpoint;

import cn.iocoder.yudao.module.franchise.api.OrgPointPort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批 D / D8 验收：科目价格表化（后台可调价，预扣写快照）。
 * 覆盖：DB 价优先生效、发布新版本退休旧版、停用回退代码默认价、未知科目拒绝、在途任务按快照结算不受调价影响。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrgPointPriceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("zhongshu_design")
            .withUsername("zhongshu")
            .withPassword("zhongshu");

    private static final long ORG = 6001L;

    private JdbcTemplate jdbc;
    private OrgPointService orgPoint;
    private OrgPointPriceService prices;

    @BeforeAll
    void setUp() {
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriverClass(org.postgresql.Driver.class);
        ds.setUrl(PG.getJdbcUrl());
        ds.setUsername(PG.getUsername());
        ds.setPassword(PG.getPassword());
        DataSource dataSource = ds;
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration/platform", "classpath:db/migration/commerce")
                .load().migrate();
        DataSourceTransactionManager tx = new DataSourceTransactionManager(dataSource);
        orgPoint = new OrgPointService(dataSource, tx, m -> 0L);
        prices = new OrgPointPriceService(dataSource, tx);
        ReflectionTestUtils.setField(orgPoint, "priceService", prices);
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE org_point_ledger, org_point_account, org_member_quota, "
                + "org_member_permission, org_point_subject_price");
        orgPoint.ensurePool(ORG);
        orgPoint.adjust(ORG, 1000, "adjust:price-base", "测试充值", 1L);
    }

    @Test
    void dbPriceOverridesCodeDefault() {
        // 代码默认 ai_flat = 2 积分/张
        assertThat(orgPoint.quoteCredits("ai_flat", 2)).isEqualTo(4);
        prices.publish("ai_flat", 5_000_000L, 1, "admin-1"); // 5 积分/张
        assertThat(orgPoint.quoteCredits("ai_flat", 2)).isEqualTo(10);

        long op = orgPoint.reserve(new OrgPointPort.ReserveCommand(
                "design:job:1:ai_flat", ORG, 9L, null, "ai_flat", 2, "ai_job", 1L));
        assertThat(orgPoint.findAccount(ORG).balance()).isEqualTo(990);
        String snapshot = jdbc.queryForObject(
                "SELECT price_snapshot::text FROM org_point_ledger WHERE operation_id = ? AND type='RESERVE'",
                String.class, op);
        assertThat(snapshot).contains("\"priceVersion\": \"db-v1\"").contains("\"priceMicrosPerUnit\": 5000000");
    }

    @Test
    void publishRetiresPreviousVersion() {
        long first = prices.publish("copy", 3_000_000L, 1, "admin-1");
        long second = prices.publish("copy", 1_000_000L, 1, "admin-1");

        assertThat(orgPoint.quoteCredits("copy", 1)).isEqualTo(1);
        var rows = prices.list("copy");
        assertThat(rows).hasSize(2);
        assertThat(rows.stream().filter(r -> "ACTIVE".equals(r.status())).count()).isEqualTo(1);
        assertThat(rows.stream().filter(r -> r.id() == first).findFirst().orElseThrow().status())
                .isEqualTo("RETIRED");
        assertThat(rows.stream().filter(r -> r.id() == second).findFirst().orElseThrow().version())
                .isEqualTo(2);
    }

    @Test
    void retireFallsBackToCodeDefault() {
        long id = prices.publish("ai_elevation", 9_000_000L, 1, "admin-1");
        assertThat(orgPoint.quoteCredits("ai_elevation", 1)).isEqualTo(9);

        prices.retire(id, "admin-2");

        assertThat(orgPoint.quoteCredits("ai_elevation", 1)).isEqualTo(3); // 代码默认
        assertThat(prices.resolve("ai_elevation")).isNull();
        // 停用不覆盖发布人，停用人单独记录（版本历史可追责）
        var row = prices.list("ai_elevation").get(0);
        assertThat(row.operator()).isEqualTo("admin-1");
        assertThat(row.retiredBy()).isEqualTo("admin-2");
        // 重复停用 → 稳定业务码（不是 500）
        assertThatThrownBy(() -> prices.retire(id, "admin-3"))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class)
                .hasFieldOrPropertyWithValue("code", 1_072_000_016);
    }

    @Test
    void inFlightOperationKeepsSnapshotPriceAfterRepricing() {
        prices.publish("budget_estimate", 5_000_000L, 1, "admin-1");
        long op = orgPoint.reserve(new OrgPointPort.ReserveCommand(
                "design:estimate:1:budget_estimate", ORG, 9L, null, "budget_estimate", 1,
                "design_estimate", 1L));
        assertThat(orgPoint.findAccount(ORG).balance()).isEqualTo(995);

        prices.publish("budget_estimate", 1_000_000L, 1, "admin-1"); // 调价到 1
        var settlement = orgPoint.settle(op, 1);

        assertThat(settlement.charged()).isEqualTo(5); // 在途按快照，不按新价
        assertThat(settlement.refunded()).isZero();
    }

    @Test
    void catalogListsAllSubjectsWithSource() {
        var defaults = prices.catalog();
        assertThat(defaults).hasSize(BillingPriceCatalog.all().size());
        assertThat(defaults).allMatch(row -> !row.configured());
        assertThat(defaults).filteredOn(row -> "ai_flat".equals(row.subject())).singleElement()
                .satisfies(row -> {
                    assertThat(row.priceMicros()).isEqualTo(2_000_000L);
                    assertThat(row.versionText()).isEqualTo("v1");
                });

        prices.publish("ai_flat", 4_500_000L, 2, "admin-1");
        assertThat(prices.catalog()).filteredOn(row -> "ai_flat".equals(row.subject())).singleElement()
                .satisfies(row -> {
                    assertThat(row.configured()).isTrue();
                    assertThat(row.priceMicros()).isEqualTo(4_500_000L);
                    assertThat(row.minCharge()).isEqualTo(2);
                    assertThat(row.versionText()).isEqualTo("db-v1");
                });
    }

    @Test
    void concurrentPublishSerializesVersionAndActive() throws Exception {
        int threads = 4;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<Long>> futures = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                int idx = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return prices.publish("copy", (idx + 1) * 1_000_000L, 1, "admin-" + idx);
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<Long> future : futures) {
                assertThat(future.get(30, java.util.concurrent.TimeUnit.SECONDS)).isNotNull();
            }
        } finally {
            pool.shutdownNow();
        }
        var rows = prices.list("copy");
        assertThat(rows).hasSize(threads);
        // 版本号科目内唯一且连续（事务级 advisory lock 先锁后读 MAX）
        assertThat(rows.stream().map(OrgPointPriceService.PriceRow::version).distinct().count())
                .isEqualTo(threads);
        assertThat(rows.stream().filter(r -> "ACTIVE".equals(r.status())).count()).isEqualTo(1);
        assertThat(orgPoint.quoteCredits("copy", 1)).isEqualTo(
                rows.stream().filter(r -> "ACTIVE".equals(r.status())).findFirst().orElseThrow()
                        .priceMicros() / 1_000_000L);
    }

    @Test
    void unknownSubjectRejected() {
        assertThatThrownBy(() -> prices.publish("no_such_subject", 1_000_000L, 1, "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> prices.publish("copy", 0L, 1, "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> prices.publish("copy", 1_000_000L, 0, "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        // 单价上限：数量×单价不得溢出 long（否则会静默少扣）
        assertThatThrownBy(() -> prices.publish("copy", 1_000_000_001L, 1, "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        // 最低收费上限：N 直接成为预扣额，不封顶会让「当月已用+N」溢出绕过额度
        assertThatThrownBy(() -> prices.publish("copy", 1_000_000L, 1_000_000_001L, "admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
