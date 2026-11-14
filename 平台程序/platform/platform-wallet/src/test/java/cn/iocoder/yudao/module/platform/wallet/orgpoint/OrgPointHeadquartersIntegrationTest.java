package cn.iocoder.yudao.module.platform.wallet.orgpoint;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.infra.zhongshu.audit.AuditPort;
import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestPropertySource(properties = "platform.test.extra-flyway-locations=,classpath:db/migration/platform-wallet")
class OrgPointHeadquartersIntegrationTest extends PlatformPgIntegrationTest {
    private static final long TENANT = 916L;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactionManager;
    JdbcTemplate jdbc;
    OrgPointService points;
    OrgPointHeadquartersService service;
    long maker;
    long checker;
    long store;

    @BeforeEach
    void setup() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS system_users(
                  id BIGINT PRIMARY KEY, username VARCHAR(30) NOT NULL, nickname VARCHAR(30) NOT NULL,
                  mobile VARCHAR(11), status SMALLINT NOT NULL DEFAULT 0,
                  deleted SMALLINT NOT NULL DEFAULT 0, tenant_id BIGINT NOT NULL DEFAULT 0)
                """);
        OrgPointPriceService prices = new OrgPointPriceService(dataSource, transactionManager);
        points = new OrgPointService(dataSource, transactionManager, (AuditPort) message -> 0L, prices);
        service = new OrgPointHeadquartersService(dataSource, transactionManager, points, prices, TENANT);
        jdbc.execute("TRUNCATE org_point_price_audit,org_point_hq_adjustment,org_point_ledger,"
                + "org_point_account,org_point_subject_price");
        jdbc.update("INSERT INTO pf_org_type(code,domain,name) VALUES('headquarters','test','总部'),('store','test','门店') ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO pf_post_template(role_code,domain,org_types) VALUES('hq-admin','test','headquarters') ON CONFLICT DO NOTHING");
        long headquarters = org("headquarters", "测试总部");
        store = org("store", "测试公司");
        maker = actor(headquarters, "maker");
        checker = actor(headquarters, "checker");
        points.ensurePool(store);
        TenantContextHolder.setTenantId(TENANT);
    }

    @AfterEach void clearTenant() { TenantContextHolder.clear(); }

    @Test
    void adjustmentRequiresDifferentReviewerAndIsIdempotent() {
        long request = service.submitAdjustment(maker, store, 100, "合同赠点", "hq:test:1");
        assertThat(service.submitAdjustment(maker, store, 100, "合同赠点", "hq:test:1")).isEqualTo(request);
        assertThatThrownBy(() -> service.reviewAdjustment(maker, request, true, "复核通过"))
                .hasMessageContaining("必须不同");

        assertThat(service.reviewAdjustment(checker, request, true, "复核通过")).isEqualTo(100L);
        assertThat(service.reviewAdjustment(checker, request, true, "复核通过")).isEqualTo(100L);
        assertThat(points.findAccount(store).balance()).isEqualTo(100L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM org_point_ledger WHERE biz_key=?",
                Integer.class, "hq-adjust:" + request)).isEqualTo(1);
    }

    @Test
    void requestKeyConflictAndInsufficientDebitFailClosed() {
        long request = service.submitAdjustment(maker, store, -1, "扣回错误赠点", "hq:test:2");
        assertThatThrownBy(() -> service.submitAdjustment(maker, store, -2, "扣回错误赠点", "hq:test:2"))
                .hasMessageContaining("冲突");
        assertThatThrownBy(() -> service.reviewAdjustment(checker, request, true, "同意扣回"))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM org_point_hq_adjustment WHERE id=?",
                String.class, request)).isEqualTo("SUBMITTED");
    }

    @Test
    void concurrentSameKeyWithDifferentPayloadCreatesOneRequestAndRejectsTheOther() throws Exception {
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> concurrentSubmit(maker, 10, start));
            var second = executor.submit(() -> concurrentSubmit(checker, 20, start));
            start.countDown();
            Object one = first.get(20, java.util.concurrent.TimeUnit.SECONDS);
            Object two = second.get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(java.util.List.of(one, two).stream().filter(Long.class::isInstance).count()).isEqualTo(1);
            assertThat(java.util.List.of(one, two).stream()
                    .filter(cn.iocoder.yudao.framework.common.exception.ServiceException.class::isInstance).count())
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM org_point_hq_adjustment WHERE request_key='hq:race'",
                    Integer.class)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentSameKeyAndPayloadReturnSameRequestId() throws Exception {
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> concurrentSubmit(maker, 30, "hq:same", start));
            var second = executor.submit(() -> concurrentSubmit(maker, 30, "hq:same", start));
            start.countDown();
            Object one = first.get(20, java.util.concurrent.TimeUnit.SECONDS);
            Object two = second.get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(one).isInstanceOf(Long.class).isEqualTo(two);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM org_point_hq_adjustment WHERE request_key='hq:same'",
                    Integer.class)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void tenantActorAndTargetOrganizationAreRechecked() {
        TenantContextHolder.setTenantId(917L);
        assertThatThrownBy(() -> service.submitAdjustment(maker, store, 1, "非法跨租户", "hq:test:3"))
                .hasMessageContaining("权限范围");
        TenantContextHolder.setTenantId(TENANT);
        jdbc.update("UPDATE pf_org_unit SET status='DISABLED' WHERE dept_id=?", store);
        assertThatThrownBy(() -> service.submitAdjustment(maker, store, 1, "停用公司", "hq:test:4"))
                .hasMessageContaining("权限范围");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM org_point_hq_adjustment", Integer.class)).isZero();
        jdbc.update("UPDATE pf_org_unit SET status='ACTIVE' WHERE dept_id=?", store);
        jdbc.update("UPDATE system_users SET status=1 WHERE id=?", maker);
        assertThatThrownBy(() -> service.submitAdjustment(maker, store, 1, "停用账号", "hq:test:5"))
                .hasMessageContaining("权限范围");
    }

    @Test
    void pricePublicationRequiresHeadquartersActorAndWritesAppendOnlyAudit() {
        long id = service.publishPrice(maker, "ai_flat", 4_000_000L, 1, "经审批调整新任务价格");
        assertThat(id).isPositive();
        assertThat(points.quoteCredits("ai_flat", 2)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT actor_id FROM org_point_price_audit WHERE price_id=?",
                Long.class, id)).isEqualTo(maker);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM org_point_price_audit WHERE price_id=?", id))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    private long org(String type, String name) {
        long id = IdWorker.getId();
        jdbc.update("INSERT INTO system_dept(id,name,tenant_id) VALUES(?,?,?)", id, name, TENANT);
        jdbc.update("INSERT INTO pf_org_unit(dept_id,kind,org_id,org_type) VALUES(?,'ORG',?,?)", id, id, type);
        return id;
    }

    private long actor(long headquarters, String suffix) {
        long id = IdWorker.getId();
        jdbc.update("INSERT INTO system_users(id,username,nickname,tenant_id,mobile) VALUES(?,?,?,?,'13800000000')",
                id, "hq-" + suffix + id, suffix, TENANT);
        jdbc.update("INSERT INTO pf_staff_post(id,user_id,org_id,role_code) VALUES(?,?,?,'hq-admin')",
                IdWorker.getId(), id, headquarters);
        return id;
    }

    private Object concurrentSubmit(long actor, long delta, java.util.concurrent.CountDownLatch start) {
        return concurrentSubmit(actor, delta, "hq:race", start);
    }

    private Object concurrentSubmit(long actor, long delta, String requestKey,
                                    java.util.concurrent.CountDownLatch start) {
        TenantContextHolder.setTenantId(TENANT);
        try {
            start.await();
            return service.submitAdjustment(actor, store, delta, "并发调点", requestKey);
        } catch (Exception error) {
            return error;
        } finally {
            TenantContextHolder.clear();
        }
    }
}
