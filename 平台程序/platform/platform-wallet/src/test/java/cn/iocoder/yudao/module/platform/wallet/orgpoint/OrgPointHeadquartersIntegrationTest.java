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
        jdbc.execute("ALTER TABLE system_role ADD COLUMN IF NOT EXISTS deleted smallint DEFAULT 0");
        jdbc.execute("CREATE TABLE IF NOT EXISTS system_user_role(id bigint PRIMARY KEY,user_id bigint,role_id bigint,tenant_id bigint,deleted smallint DEFAULT 0)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS system_users(
                  id BIGINT PRIMARY KEY, username VARCHAR(30) NOT NULL, nickname VARCHAR(30) NOT NULL,
                  mobile VARCHAR(11), status SMALLINT NOT NULL DEFAULT 0,
                  deleted SMALLINT NOT NULL DEFAULT 0, tenant_id BIGINT NOT NULL DEFAULT 0)
                """);
        OrgPointPriceService prices = new OrgPointPriceService(dataSource, transactionManager);
        points = new OrgPointService(dataSource, transactionManager, (AuditPort) message -> 0L, prices);
        service = new OrgPointHeadquartersService(dataSource, transactionManager, points, prices, TENANT);
        jdbc.execute("TRUNCATE org_point_hq_price_request,org_point_price_audit,org_point_hq_adjustment,org_point_simulation_review, org_point_ledger,"
                + "org_point_account,org_point_subject_price,org_point_simulated_purchase");
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

    private void makeSuperAdministrator(long userId) {
        long role=IdWorker.getId();
        jdbc.update("INSERT INTO system_role(id,code,name,status,deleted,tenant_id) VALUES(?,'super_admin','超管',0,0,?)",role,TENANT);
        jdbc.update("INSERT INTO system_user_role(id,user_id,role_id,tenant_id) VALUES(?,?,?,?)",IdWorker.getId(),userId,role,TENANT);
    }

    @Test void explicitSimulatedPackageCreditsOnceAndReportsSimulatedReceipts() throws Exception {
        long plan=IdWorker.getId();
        jdbc.update("INSERT INTO pf_recharge_plan(id,tenant_id,name,price_fen,base_points,bonus_points,status) VALUES (?,?,'体验套餐',990,100,20,'ACTIVE')",plan,TENANT);
        points.configurePurchaseOrgs(Long.toString(store));
        assertThat(points.simulatedPackages(TENANT,store)).anyMatch(p->p.id().equals(Long.toString(plan)));
        assertThat(points.simulatedPackages(TENANT,store+1)).isEmpty();
        var task=new cn.iocoder.yudao.module.franchise.api.OrgPointPort.ReserveCommand("package-check:"+plan,store,maker,null,"digital",300,"content_task",plan);
        assertThatThrownBy(()->points.reserve(task)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->points.purchaseSimulated(TENANT,store,maker,51,plan,2,"changed-plan")).isInstanceOf(RuntimeException.class);
        assertThat(points.findAccount(store).balance()).isZero();
        var workers=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var start=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<String> pay=()->{start.await();return points.purchaseSimulated(TENANT,store,maker,51,plan,1,"same-payment").id();};
            var a=workers.submit(pay);var b=workers.submit(pay);start.countDown();
            assertThat(a.get(10,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(b.get(10,java.util.concurrent.TimeUnit.SECONDS));
        } finally { workers.shutdownNow(); }
        assertThat(points.findAccount(store).balance()).isZero();
        assertThat(jdbc.queryForObject("SELECT sum(test_points) FROM org_point_simulated_purchase WHERE org_id=?", Long.class, store)).isEqualTo(120L);
        assertThat(service.companies(maker)).filteredOn(c->c.orgId()==store).extracting(c->c.simulatedReceiptFen()).containsExactly("990");
        assertThat(service.ledger(maker,store,1,20)).filteredOn(l->"SIMULATED_PACKAGE".equals(l.type())).isEmpty();
        points.configurePurchaseOrgs("");
        assertThat(points.purchaseSimulated(TENANT,store,maker,51,plan,1,"same-payment").points()).isEqualTo(120);
        assertThatThrownBy(()->points.purchaseSimulated(TENANT,store,maker,51,plan,1,"another-order")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->points.reserve(task)).isInstanceOf(RuntimeException.class);
        points.adjustBalance(store,120,"formal-"+plan,"正式调增",maker);
        points.reserve(task);
        assertThat(points.findAccount(store).balance()).isEqualTo(105);
    }

    @Test void superAdministratorCreditsImmediatelyAndReplaysWithoutDoubleCredit() {
        makeSuperAdministrator(maker);
        var first=service.creditDirectly(maker,store,12,"联调赠分","direct:test");
        assertThat(first.beforeBalance()).isEqualTo("0");
        assertThat(first.afterBalance()).isEqualTo("12");
        assertThat(service.creditDirectly(maker,store,12,"联调赠分","direct:test")).isEqualTo(first);
        assertThat(points.findAccount(store).balance()).isEqualTo(12);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM org_point_ledger WHERE org_id=?",Long.class,store)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM org_point_hq_adjustment WHERE request_key='direct:test'",String.class)).isEqualTo("EXECUTED");
        assertThatThrownBy(()->service.creditDirectly(maker,store,13,"联调赠分","direct:test")).hasMessageContaining("冲突");
    }

    @Test void directCreditRequiresSuperAdministratorAndPositiveAmount() {
        assertThatThrownBy(()->service.creditDirectly(maker,store,2,"测试","direct:denied")).hasMessageContaining("权限范围");
        assertThatThrownBy(()->service.creditDirectly(maker,store,0,"测试","direct:zero")).hasMessageContaining("正整数");
        assertThatThrownBy(()->service.creditDirectly(maker,store,-1,"测试","direct:negative")).hasMessageContaining("正整数");
        assertThat(points.findAccount(store).balance()).isZero();
    }

    @Test void superAdministratorWithoutHeadquartersCanOpenPoolAndCredit() {
        long administrator=actor(store,"平台超管");
        makeSuperAdministrator(administrator);
        long target=org("store","未开通积分门店");
        var receipt=service.creditDirectly(administrator,target,3,"首次添加","direct:initial");
        assertThat(receipt.beforeBalance()).isEqualTo("0");
        assertThat(receipt.afterBalance()).isEqualTo("3");
    }

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
        long id = service.publishPrice(maker, "ai_flat", 4_000_000L, 1,
                "经审批调整新任务价格", "hq-price:test:1");
        assertThat(id).isPositive();
        assertThat(points.quoteCredits("ai_flat", 2)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT actor_id FROM org_point_price_audit WHERE price_id=?",
                Long.class, id)).isEqualTo(maker);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM org_point_price_audit WHERE price_id=?", id))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void pricePublicationReplayReturnsOriginalAndChangedBindingConflicts() {
        String reason = "审批单 PR-100";
        long id = service.publishPrice(maker, "copy", 2_000_000L, 1, reason, "hq-price:replay");

        assertThat(service.publishPrice(maker, "copy", 2_000_000L, 1, reason, "hq-price:replay"))
                .isEqualTo(id);
        assertThatThrownBy(() -> service.publishPrice(checker, "copy", 2_000_000L, 1,
                reason, "hq-price:replay")).hasMessageContaining("冲突");
        assertThatThrownBy(() -> service.publishPrice(maker, "copy", 3_000_000L, 1,
                reason, "hq-price:replay")).hasMessageContaining("冲突");
        assertThatThrownBy(() -> service.publishPrice(maker, "ai_flat", 2_000_000L, 1,
                reason, "hq-price:replay")).hasMessageContaining("冲突");
        assertThatThrownBy(() -> service.publishPrice(maker, "copy", 2_000_000L, 1,
                "另一审批单", "hq-price:replay")).hasMessageContaining("冲突");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM org_point_subject_price WHERE subject='copy'",
                Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM org_point_price_audit WHERE price_id=?",
                Integer.class, id)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM org_point_hq_price_request WHERE request_key=?",
                Integer.class, "hq-price:replay")).isOne();
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM org_point_hq_price_request WHERE request_key=?",
                Long.class, "hq-price:replay")).isEqualTo(TENANT);
    }

    @Test
    void concurrentPriceReplayCreatesOneVersionAndOneAudit() throws Exception {
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> concurrentPrice(start));
            var second = executor.submit(() -> concurrentPrice(start));
            start.countDown();
            Object one = first.get(20, java.util.concurrent.TimeUnit.SECONDS);
            Object two = second.get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(one).isInstanceOf(Long.class).isEqualTo(two);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM org_point_subject_price WHERE subject='ai_elevation'",
                    Integer.class)).isOne();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM org_point_price_audit WHERE subject='ai_elevation'",
                    Integer.class)).isOne();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM org_point_hq_price_request WHERE request_key=?",
                    Integer.class, "hq-price:race")).isOne();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void invalidPriceOrRequestKeyLeavesNoPriceFacts() {
        assertThatThrownBy(() -> service.publishPrice(maker, "copy", 0, 1,
                "无效价格", "hq-price:invalid")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.publishPrice(maker, "copy", 1_000_000L, 1,
                "缺少幂等键", " ")).hasMessageContaining("文本参数无效");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM org_point_subject_price", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM org_point_price_audit", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM org_point_hq_price_request", Integer.class)).isZero();
    }

    @Test
    void priceRequestFactFailureRollsBackPriceVersionAndAudit() {
        jdbc.execute("CREATE FUNCTION reject_hq_price_request() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'forced price request failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_hq_price_request BEFORE INSERT ON org_point_hq_price_request "
                + "FOR EACH ROW EXECUTE FUNCTION reject_hq_price_request()");
        try {
            assertThatThrownBy(() -> service.publishPrice(maker, "copy", 2_000_000L, 1,
                    "审批通过", "hq-price:forced-failure"))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class)
                    .hasMessageContaining("forced price request failure");
        } finally {
            jdbc.execute("DROP TRIGGER reject_hq_price_request ON org_point_hq_price_request");
            jdbc.execute("DROP FUNCTION reject_hq_price_request()");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM org_point_subject_price", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM org_point_price_audit", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM org_point_hq_price_request", Integer.class)).isZero();
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

    private Object concurrentPrice(java.util.concurrent.CountDownLatch start) {
        TenantContextHolder.setTenantId(TENANT);
        try {
            start.await();
            return service.publishPrice(maker, "ai_elevation", 5_000_000L, 1,
                    "并发审批", "hq-price:race");
        } catch (Exception error) {
            return error;
        } finally {
            TenantContextHolder.clear();
        }
    }
}
