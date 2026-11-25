package cn.iocoder.yudao.module.platform.wallet.orgpoint;

import cn.iocoder.yudao.module.infra.zhongshu.audit.AuditPort;
import cn.iocoder.yudao.module.platform.org.CompanyAccountRegistered;
import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestPropertySource(properties = "platform.test.extra-flyway-locations=,classpath:db/migration/platform-wallet")
@Import({OrgPointService.class, OrgPointPriceService.class, CompanyPoolOnboardingListener.class,
        CompanyPoolOnboardingIntegrationTest.Config.class})
class CompanyPoolOnboardingIntegrationTest extends PlatformPgIntegrationTest {
    @TestConfiguration
    static class Config {
        @Bean AuditPort auditPort() { return message -> 0L; }
    }

    @Resource private JdbcTemplate jdbc;
    @Resource private OrgPointService points;
    @Resource private ApplicationEventPublisher events;
    @Resource private PlatformTransactionManager transactions;
    private long tenant;
    private long org;

    @BeforeEach
    void company() {
        tenant = IdWorker.getId();
        org = IdWorker.getId();
        jdbc.update("INSERT INTO pf_org_type(code,domain,name) VALUES ('store','franchise','门店') ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO system_dept(id,name,tenant_id,status,deleted) VALUES (?,?,?,0,0)", org, "开户额度测试", tenant);
        jdbc.update("INSERT INTO pf_org_unit(dept_id,kind,org_id,org_type,status) VALUES (?,'ORG',?,'store','ACTIVE')", org, org);
    }

    @Test
    void registrationCreatesZeroPoolAndReplayNeverResetsBalanceOrSuspension() {
        var event = new CompanyAccountRegistered(tenant, org);
        events.publishEvent(event);
        assertThat(points.summary(org).balance()).isZero();
        points.adjust(org, 200, "registration-test:" + org, "测试入账", 1L);
        jdbc.update("UPDATE org_point_account SET status='SUSPENDED' WHERE org_id=?", org);
        events.publishEvent(event);
        assertThat(points.summary(org).balance()).isEqualTo(200);
        assertThat(points.summary(org).status()).isEqualTo("SUSPENDED");
        assertThat(count("org_point_account")).isEqualTo(1);
        assertThat(count("org_point_ledger")).isEqualTo(1);
    }

    @Test
    void rejectedTenantOrDisabledCompanyCannotProvisionPool() {
        assertThatThrownBy(() -> events.publishEvent(new CompanyAccountRegistered(tenant + 1, org)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("租户");
        jdbc.update("UPDATE system_dept SET status=1 WHERE id=?", org);
        assertThatThrownBy(() -> events.publishEvent(new CompanyAccountRegistered(tenant, org)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(count("org_point_account")).isZero();
    }

    @Test
    void failedOuterOnboardingTransactionRollsBackThePool() {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            events.publishEvent(new CompanyAccountRegistered(tenant, org));
            assertThat(count("org_point_account")).isEqualTo(1);
            throw new IllegalStateException("开户后续失败");
        })).hasMessageContaining("开户后续失败");
        assertThat(count("org_point_account")).isZero();
    }

    @Test
    void concurrentRegistrationCreatesOnePoolWithoutGrantingQuota() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Void> work = () -> {
                start.await();
                events.publishEvent(new CompanyAccountRegistered(tenant, org));
                return null;
            };
            var first = executor.submit(work);
            var second = executor.submit(work);
            start.countDown();
            first.get(20, java.util.concurrent.TimeUnit.SECONDS);
            second.get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(count("org_point_account")).isEqualTo(1);
            assertThat(count("org_point_ledger")).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE org_id=?", Long.class, org);
    }
}
