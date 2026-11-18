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

/** 历史员工月额度停用验收：只保留数据，不再作为准入或展示上限。 */
@TestPropertySource(properties = "platform.test.extra-flyway-locations=,classpath:db/migration/platform-wallet")
class OrgMemberQuotaIntegrationTest extends PlatformPgIntegrationTest {

    private static final long ORG = 2001L;
    private static final long USER = 7L;

    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;
    private JdbcTemplate jdbc;
    private OrgPointService service;

    @BeforeEach
    void clean() {
        jdbc = new JdbcTemplate(dataSource);
        service = new OrgPointService(dataSource, transactionManager, message -> 0L,
                new OrgPointPriceService(dataSource, transactionManager));
        jdbc.execute("TRUNCATE org_point_simulation_review, org_point_ledger, org_point_account, org_member_quota, org_member_permission");
        service.ensurePool(ORG);
        service.adjust(ORG, 10_000, "adjust:base", "测试充值", 1L);
    }

    private OrgPointPort.ReserveCommand reserve(String key, long quantity) {
        return new OrgPointPort.ReserveCommand(key, ORG, USER, null, "ai_video_768p",
                quantity, "content_task", 1L);
    }

    @Test
    void historicalQuotaDoesNotLimitReserveOrAppearAsEffectiveQuota() {
        insertHistoricalQuota(1);

        service.reserve(reserve("content:legacy-quota", 60));

        assertThat(service.monthUsed(ORG, USER, OrgPointService.currentMonth())).isEqualTo(10);
        assertThat(service.memberQuotaOf(ORG, USER)).isNull();
        assertThat(service.memberQuotas(ORG)).isEmpty();
        assertThat(service.myUsage(ORG, USER, null, 1, 20).monthlyQuota()).isNull();
        assertThat(jdbc.queryForObject("SELECT monthly_credits FROM org_member_quota "
                + "WHERE org_id=? AND user_id=?", Long.class, ORG, USER)).isEqualTo(1L);
    }

    @Test
    void quotaAdministrationIsDisabledAndDoesNotMutateHistoricalRows() {
        insertHistoricalQuota(25);

        assertThatThrownBy(() -> service.setMemberQuota(ORG, USER, 50, 1L))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("已停用");
        assertThatThrownBy(() -> service.clearMemberQuota(ORG, USER, 1L))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("已停用");
        assertThat(jdbc.queryForObject("SELECT monthly_credits FROM org_member_quota "
                + "WHERE org_id=? AND user_id=?", Long.class, ORG, USER)).isEqualTo(25L);
    }

    @Test
    void balanceAndPermissionRemainHardLimitsDespiteHistoricalQuota() {
        insertHistoricalQuota(1);

        assertThatThrownBy(() -> service.reserve(new OrgPointPort.ReserveCommand(
                "content:no-balance", ORG, USER, null, "ai_flat", 6_000, "content_task", 2L)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("公司积分池余额不足");

        service.setMemberPermission(ORG, USER, List.of(), 1L);
        assertThatThrownBy(() -> service.reserve(reserve("content:forbidden", 60)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("未被授权");
    }

    @Test
    void crossMonthReleaseReturnsToOriginMonth() {
        long op = 9_001L;
        // 以历史月份初始创建预留夹具；账本上线后是 append-only，测试也不得改写已有流水。
        jdbc.update("UPDATE org_point_account SET balance = balance - 10 WHERE org_id = ?", ORG);
        jdbc.update("INSERT INTO org_point_ledger (id, org_id, operation_id, biz_key, operation_time, "
                        + "type, subject, actor_user_id, available_delta, billing_month, price_snapshot, "
                        + "ref_type, ref_id) VALUES (?, ?, ?, ?, now(), 'RESERVE', 'ai_video_768p', "
                        + "?, -10, '2020-01', '{}'::jsonb, 'content_task', 1)",
                9_001L, ORG, op, "content:cross", USER);

        service.release(op);

        assertThat(service.monthUsed(ORG, USER, "2020-01")).isZero();
        assertThat(service.monthUsed(ORG, USER, OrgPointService.currentMonth())).isZero();
    }

    @Test
    void concurrentReserveCannotOverdrawCompanyBalance() throws Exception {
        insertHistoricalQuota(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String key = "content:concurrent-" + i;
            tasks.add(() -> {
                start.await();
                try {
                    service.reserve(new OrgPointPort.ReserveCommand(
                            key, ORG, USER, null, "ai_flat", 3_000, "content_task", 9L));
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
        // 每笔 6000 积分、公司余额 10000：账户行锁串行化后恰一成功。
        assertThat(succeeded).isEqualTo(1);
        assertThat(service.findAccount(ORG).balance()).isEqualTo(4_000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM org_point_ledger WHERE type='RESERVE'",
                Integer.class)).isEqualTo(1);
    }

    private void insertHistoricalQuota(long monthlyCredits) {
        jdbc.update("INSERT INTO org_member_quota(id,org_id,user_id,monthly_credits) VALUES (?,?,?,?)",
                9_101L, ORG, USER, monthlyCredits);
    }
}
