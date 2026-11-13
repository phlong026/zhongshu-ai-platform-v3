package cn.iocoder.yudao.module.commerce.orgpoint;

import cn.iocoder.yudao.module.franchise.api.OrgPointPort;
import cn.iocoder.yudao.module.platform.org.OrgOnboardedEvent;
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

/**
 * 批 B 验收（24 号 §七 B-8/B-10/B-11）：开通事件建池赠点、任务合并视图折叠、逐笔流水分页。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrgPointOnboardAndViewIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("zhongshu_design")
            .withUsername("zhongshu")
            .withPassword("zhongshu");

    private JdbcTemplate jdbc;
    private OrgPointService service;
    private OrgOnboardListener listener;

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
        service = new OrgPointService(dataSource, new DataSourceTransactionManager(dataSource), message -> 0L);
        listener = new OrgOnboardListener(service);
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE org_point_ledger, org_point_account, org_member_quota, org_member_permission");
    }

    @Test
    void onboardEventCreatesPoolAndGrantsOnce() {
        listener.onOnboarded(new OrgOnboardedEvent(3001L, true, 50));
        assertThat(service.findAccount(3001L).balance()).isEqualTo(50);
        // 重复开通：赠点不重复
        listener.onOnboarded(new OrgOnboardedEvent(3001L, true, 50));
        assertThat(service.findAccount(3001L).balance()).isEqualTo(50);
    }

    @Test
    void designDisabledDoesNotCreatePool() {
        listener.onOnboarded(new OrgOnboardedEvent(3002L, false, 0));
        assertThat(service.findAccount(3002L)).isNull();
    }

    @Test
    void operationsViewFoldsThreeStates() {
        listener.onOnboarded(new OrgOnboardedEvent(3003L, true, 1000));
        long pending = service.reserve(new OrgPointPort.ReserveCommand(
                "content:31:copy", 3003L, 7L, null, "copy", 1, "content_task", 31L));
        long completed = service.reserve(new OrgPointPort.ReserveCommand(
                "content:32:copy", 3003L, 7L, null, "copy", 1, "content_task", 32L));
        long released = service.reserve(new OrgPointPort.ReserveCommand(
                "content:33:copy", 3003L, 7L, null, "copy", 1, "content_task", 33L));
        service.settle(completed, 1);
        service.release(released);

        var views = service.operations(3003L, 1, 20);
        assertThat(views).hasSize(3);
        assertThat(views).extracting(OrgPointPort.OperationView::state)
                .containsExactlyInAnyOrder("PENDING", "COMPLETED", "RELEASED");
        var pendingView = views.stream().filter(v -> v.state().equals("PENDING")).findFirst().orElseThrow();
        assertThat(pendingView.requested()).isEqualTo(2);
        assertThat(pendingView.charged()).isEqualTo(2);
        assertThat(pendingView.operationId()).isEqualTo(pending);
    }

    @Test
    void summaryShowsBalanceAndInFlightSeparately() {
        listener.onOnboarded(new OrgOnboardedEvent(3004L, true, 100));
        service.reserve(new OrgPointPort.ReserveCommand(
                "content:41:copy", 3004L, 7L, null, "copy", 1, "content_task", 41L));
        var summary = service.summary(3004L);
        // 可用余额已扣 2；在途另列展示
        assertThat(summary.balance()).isEqualTo(98);
        assertThat(summary.inFlight()).isEqualTo(2);
        assertThat(summary.status()).isEqualTo("ACTIVE");
    }

    @Test
    void ledgerPagingAndFilters() {
        listener.onOnboarded(new OrgOnboardedEvent(3005L, true, 100));
        for (int i = 0; i < 5; i++) {
            service.reserve(new OrgPointPort.ReserveCommand(
                    "content:5" + i + ":copy", 3005L, 7L, null, "copy", 1, "content_task", 50L + i));
        }
        assertThat(service.ledger(3005L, null, null, null, 1, 3)).hasSize(3);
        assertThat(service.ledger(3005L, 7L, "copy", null, 1, 50)).hasSize(5);
        assertThat(service.ledger(3005L, 8L, null, null, 1, 50)).isEmpty();
    }
}
