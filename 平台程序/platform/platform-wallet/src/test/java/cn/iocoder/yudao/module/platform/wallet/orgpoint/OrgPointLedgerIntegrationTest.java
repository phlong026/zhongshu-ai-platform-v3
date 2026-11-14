package cn.iocoder.yudao.module.platform.wallet.orgpoint;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.franchise.api.OrgPointPort;
import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

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
 * 批 A 账本验收（24 号 §七 OrgPointLedgerIntegrationTest）：真实 PostgreSQL。
 * 覆盖：开通幂等、三态代数=-实收、biz_key 重放幂等、参数变化拒绝、终态互斥、余额 CHECK、调点幂等。
 */
@TestPropertySource(properties = "platform.test.extra-flyway-locations=,classpath:db/migration/platform-wallet")
class OrgPointLedgerIntegrationTest extends PlatformPgIntegrationTest {

    @Autowired
    private DataSource dataSource;
    @Autowired
    private PlatformTransactionManager transactionManager;
    private JdbcTemplate jdbc;
    private OrgPointService service;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE org_point_ledger, org_point_account, org_member_quota, org_member_permission");
        service = new OrgPointService(dataSource, transactionManager, message -> 0L,
                new OrgPointPriceService(dataSource, transactionManager));
    }

    private long balance(long orgId) {
        Long sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(available_delta),0) FROM org_point_ledger WHERE org_id = ?",
                Long.class, orgId);
        return sum == null ? 0 : sum;
    }

    @Test
    void ensurePoolIsIdempotent() {
        var first = service.ensurePool(1001L);
        var second = service.ensurePool(1001L);
        assertThat(second.orgId()).isEqualTo(first.orgId());
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM org_point_account WHERE org_id = 1001", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void reserveSettleAlgebraEqualsActualCharge() {
        service.ensurePool(1001L);
        service.adjust(1001L, 1000, "adjust:t-1", "测试充值", 1L);
        // digital 300 秒：15000 微积分/秒 → 15 积分
        long op = service.reserve(new OrgPointPort.ReserveCommand(
                "content:1:digital", 1001L, 7L, null, "digital", 300, "content_task", 1L));
        assertThat(service.findAccount(1001L).balance()).isEqualTo(985);
        assertThat(service.inFlight(1001L)).isEqualTo(15);

        // 实际交付 120 秒 → 实收 6 积分，退差 9
        var settlement = service.settle(op, 120);
        assertThat(settlement.charged()).isEqualTo(15);
        assertThat(settlement.refunded()).isEqualTo(9);
        assertThat(service.findAccount(1001L).balance()).isEqualTo(994);
        // 代数和 = -实收
        assertThat(balance(1001L)).isEqualTo(994);
        Long sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(available_delta),0) FROM org_point_ledger "
                        + "WHERE operation_id = ?", Long.class, op);
        assertThat(sum).isEqualTo(-6);
        assertThat(service.inFlight(1001L)).isZero();
    }

    @Test
    void replaySameBizKeyReturnsSameOperation() {
        service.ensurePool(1002L);
        service.adjust(1002L, 100, "adjust:t-2", "测试充值", 1L);
        var cmd = new OrgPointPort.ReserveCommand("content:2:copy", 1002L, 7L, null,
                "copy", 1, "content_task", 2L);
        long first = service.reserve(cmd);
        long second = service.reserve(cmd);
        assertThat(second).isEqualTo(first);
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM org_point_ledger WHERE biz_key = 'content:2:copy'", Integer.class);
        assertThat(rows).isEqualTo(1);
        assertThat(service.findAccount(1002L).balance()).isEqualTo(98);
    }

    @Test
    void replayWithDifferentParamsRejected() {
        service.ensurePool(1003L);
        service.adjust(1003L, 100, "adjust:t-3", "测试充值", 1L);
        service.reserve(new OrgPointPort.ReserveCommand("content:3:copy", 1003L, 7L, null,
                "copy", 1, "content_task", 3L));
        assertThatThrownBy(() -> service.reserve(new OrgPointPort.ReserveCommand(
                "content:3:copy", 1003L, 8L, null, "copy", 1, "content_task", 3L)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("幂等键");
    }

    @Test
    void terminalIsMutuallyExclusive() throws Exception {
        service.ensurePool(1004L);
        service.adjust(1004L, 100, "adjust:t-4", "测试充值", 1L);
        long op = service.reserve(new OrgPointPort.ReserveCommand(
                "content:4:copy", 1004L, 7L, null, "copy", 1, "content_task", 4L));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        tasks.add(() -> {
            start.await();
            try {
                service.settle(op, 1);
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        });
        tasks.add(() -> {
            start.await();
            try {
                service.release(op);
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        });
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
        assertThat(succeeded).isEqualTo(1);
        Integer terminals = jdbc.queryForObject(
                "SELECT count(*) FROM org_point_ledger WHERE operation_id = ? "
                        + "AND type IN ('SETTLE','RELEASE')", Integer.class, op);
        assertThat(terminals).isEqualTo(1);
    }

    @Test
    void settleWithoutReserveFails() {
        assertThatThrownBy(() -> service.settle(999999L, 1))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void insufficientBalanceRejected() {
        service.ensurePool(1005L);
        service.adjust(1005L, 1, "adjust:t-5", "测试充值", 1L);
        assertThatThrownBy(() -> service.reserve(new OrgPointPort.ReserveCommand(
                "content:5:digital", 1005L, 7L, null, "digital", 300, "content_task", 5L)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("余额不足");
    }

    @Test
    void adjustIsIdempotentAndRejectsNegativeOverdraft() {
        service.ensurePool(1006L);
        service.adjust(1006L, 50, "adjust:t-6", "首次", 1L);
        service.adjust(1006L, 50, "adjust:t-6", "重放", 1L);
        assertThat(service.findAccount(1006L).balance()).isEqualTo(50);
        assertThatThrownBy(() -> service.adjust(1006L, -60, "adjust:t-6b", "越界测试", 1L))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void adjustReplayWithDifferentAmountRejected() {
        service.ensurePool(1008L);
        service.adjust(1008L, 50, "adjust:t-8", "首次", 1L);
        assertThatThrownBy(() -> service.adjust(1008L, 100, "adjust:t-8", "改量", 1L))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("幂等键");
        assertThat(service.findAccount(1008L).balance()).isEqualTo(50);
    }

    @Test
    void adjustRequiresOpenedPool() {
        assertThatThrownBy(() -> service.adjust(1009L, 10, "adjust:t-9", "未开池", 1L))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("未开通");
        assertThat(service.findAccount(1009L)).isNull();
    }

    @Test
    void grantOnboardOnlyOnce() {
        service.ensurePool(1007L);
        assertThat(service.grantOnboardOnce(1007L, 20, "开通赠点")).isTrue();
        assertThat(service.grantOnboardOnce(1007L, 20, "重复")).isFalse();
        assertThat(service.findAccount(1007L).balance()).isEqualTo(20);
    }

    @Test
    void ledgerRowsAreDatabaseProtectedFromUpdateAndDelete() {
        service.ensurePool(1010L);
        service.adjust(1010L, 20, "adjust:append-only", "append-only", 1L);
        Long id = jdbc.queryForObject("SELECT id FROM org_point_ledger WHERE org_id=1010", Long.class);

        assertThatThrownBy(() -> jdbc.update("UPDATE org_point_ledger SET note='tampered' WHERE id=?", id))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM org_point_ledger WHERE id=?", id))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM org_point_ledger WHERE id=?", Integer.class, id))
                .isEqualTo(1);
    }

    @Test
    void concurrentOnboardGrantAppliesExactlyOnceWithoutUniqueKeyFailure() throws Exception {
        service.ensurePool(1011L);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Boolean> grant = () -> {
                start.await();
                return service.grantOnboardOnce(1011L, 20, "concurrent onboard");
            };
            Future<Boolean> first = pool.submit(grant);
            Future<Boolean> second = pool.submit(grant);
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }
        assertThat(service.findAccount(1011L).balance()).isEqualTo(20);
    }
}
