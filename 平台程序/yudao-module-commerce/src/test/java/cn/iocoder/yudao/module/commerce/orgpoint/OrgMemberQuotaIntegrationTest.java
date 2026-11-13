package cn.iocoder.yudao.module.commerce.orgpoint;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.franchise.api.OrgPointPort;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批 A 员工月度额度验收（24 号 §七 OrgMemberQuotaIntegrationTest / ConcurrencyTest）：
 * 超限拒 → RELEASE 释放后可再提交 → 跨月退回归操作发起月 → 在途占用计入 → 并发边界恰一成功。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrgMemberQuotaIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("zhongshu_design")
            .withUsername("zhongshu")
            .withPassword("zhongshu");

    private static final long ORG = 2001L;
    private static final long USER = 7L;

    private JdbcTemplate jdbc;
    private OrgPointService service;

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
        service = new OrgPointService(dataSource, new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE org_point_ledger, org_point_account, org_member_quota, org_member_permission");
        service.ensurePool(ORG);
        service.adjust(ORG, 10_000, "adjust:base", "测试充值", 1L);
    }

    private OrgPointPort.ReserveCommand reserve(String key, long quantity) {
        return new OrgPointPort.ReserveCommand(key, ORG, USER, null, "ai_video_768p",
                quantity, "content_task", 1L);
    }

    /** ai_video_768p = 166666 微积分/秒：60 秒 = 10 积分。 */
    @Test
    void quotaExceededThenReleaseAllowsResubmit() {
        service.setMemberQuota(ORG, USER, 100, 1L);
        service.reserve(reserve("content:q1", 60));
        assertThat(service.monthUsed(ORG, USER, OrgPointService.currentMonth())).isEqualTo(10);

        // 再预扣 10×10=100 会超 100
        assertThatThrownBy(() -> service.reserve(reserve("content:q2", 600)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("额度上限");

        // 释放后可继续提交（R1 新增验收）
        long op = service.reserve(new OrgPointPort.ReserveCommand(
                "content:q3", ORG, USER, null, "ai_video_768p", 60, "content_task", 3L));
        service.release(op);
        assertThat(service.monthUsed(ORG, USER, OrgPointService.currentMonth())).isEqualTo(10);
        service.reserve(new OrgPointPort.ReserveCommand(
                "content:q4", ORG, USER, null, "ai_video_768p", 60, "content_task", 4L));
    }

    @Test
    void inFlightReservationCountsTowardQuota() {
        service.setMemberQuota(ORG, USER, 15, 1L);
        service.reserve(reserve("content:inflight", 60)); // 10 积分在途
        assertThatThrownBy(() -> service.reserve(reserve("content:inflight2", 60)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("额度上限");
    }

    @Test
    void crossMonthReleaseReturnsToOriginMonth() {
        service.setMemberQuota(ORG, USER, 100, 1L);
        long op = service.reserve(reserve("content:cross", 60));
        // 模拟上月发起的操作：直接改写 RESERVE 快照月份（终态必须复制该月份）
        jdbc.update("UPDATE org_point_ledger SET billing_month = '2020-01' WHERE operation_id = ?", op);

        service.release(op);

        assertThat(service.monthUsed(ORG, USER, "2020-01")).isZero();
        assertThat(service.monthUsed(ORG, USER, OrgPointService.currentMonth())).isZero();
    }

    @Test
    void concurrentReserveAtQuotaBoundaryAllowsExactlyOne() throws Exception {
        service.setMemberQuota(ORG, USER, 100, 1L);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String key = "content:concurrent-" + i;
            tasks.add(() -> {
                start.await();
                try {
                    service.reserve(new OrgPointPort.ReserveCommand(
                            key, ORG, USER, null, "ai_video_768p", 360, "content_task", 9L));
                    return true;
                } catch (RuntimeException e) {
                    return false;
                }
            });
        }
        List<Future<Boolean>> futures = new ArrayList<>();
        for (Callable<Boolean> task : tasks) {
            futures.add(pool.submit(task));
        }
        start.countDown();
        int succeeded = 0;
        for (Future<Boolean> future : futures) {
            if (future.get()) {
                succeeded++;
            }
        }
        pool.shutdown();
        // 每笔 60 积分，额度 100：行锁串行化后恰一成功
        assertThat(succeeded).isEqualTo(1);
    }

    @Test
    void clearQuotaMeansUnlimited() {
        service.setMemberQuota(ORG, USER, 10, 1L);
        service.clearMemberQuota(ORG, USER, 1L);
        assertThat(service.memberMonthlyQuota(ORG, USER)).isNull();
        service.reserve(reserve("content:unlimited", 600)); // 100 积分，无额度限制
    }
}
