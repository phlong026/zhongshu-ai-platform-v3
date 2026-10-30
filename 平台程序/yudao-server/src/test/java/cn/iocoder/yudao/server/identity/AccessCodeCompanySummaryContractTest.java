package cn.iocoder.yudao.server.identity;

import cn.iocoder.yudao.module.identity.controller.admin.AccessCodeBatchAdminController;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 公司耗点汇总合同测试（真实 PostgreSQL）。
 * 复现并锁定 R1：公司汇总必须先按（公司,账号）去重、再按账号聚合流水、最后汇总到公司——
 * 既不能 max 漏算多账号（100+200 只显示 200），也不能 naive sum 沿兑换连接行放大同账号多码。
 * 耗点口径沿用既有定义：design_point_ledger 中负 delta 取绝对值计入，退款/人工调增等正 delta 不计。
 * 测试间用互不重叠的公司名隔离，避免依赖共享容器内的执行顺序。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccessCodeCompanySummaryContractTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");

    static SimpleDriverDataSource ds;
    static JdbcTemplate jdbc;
    static AccessCodeBatchAdminController controller;
    static final AtomicLong SEQ = new AtomicLong(5000);

    @BeforeAll
    void migrate() {
        ds = new SimpleDriverDataSource(new org.postgresql.Driver(), PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        jdbc = new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds)
                .locations("classpath:db/migration/identity", "classpath:db/migration/commerce")
                .load().migrate();
        controller = new AccessCodeBatchAdminController();
        ReflectionTestUtils.setField(controller, "dataSource", (DataSource) ds);
    }

    private long redemption(String company, long accountId, long tenantId) {
        long id = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO access_code_redemption (id, code_id, account_id, appid, openid, company_name, tenant_id) "
                        + "VALUES (?, ?, ?, 'wx-app', ?, ?, ?)",
                id, SEQ.incrementAndGet(), accountId, "open-" + id, company, tenantId);
        return id;
    }

    private void consume(long userId, long delta) {
        consumeAs(userId, delta, delta < 0 ? "FLAT_GENERATION_DEBIT" : "TASK_SETTLEMENT_REFUND");
    }

    private void consumeAs(long userId, long delta, String type) {
        jdbc.update("INSERT INTO design_point_ledger (id, user_id, type, delta, available_after, reserved_after) "
                        + "VALUES (?, ?, ?, ?, 0, 0)",
                SEQ.incrementAndGet(), userId, type, delta);
    }

    private void grant(long accountId, String status, boolean deleted, long tenantId) {
        jdbc.update("INSERT INTO design_access_grant (id, account_id, status, deleted, tenant_id) VALUES (?, ?, ?, ?, ?)",
                SEQ.incrementAndGet(), accountId, status, deleted, tenantId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> rowOf(String company) {
        List<Map<String, Object>> rows = controller.companySummary().getData();
        return rows.stream().filter(r -> company.equals(r.get("company"))).findFirst().orElseThrow();
    }

    @Test
    void 同公司两账号分别消费时公司汇总为账号合计而非最大值() {
        // 复现 R1：账号1耗100、账号2耗200，公司必须显示 300（修复前 max 只显示 200）
        redemption("复现-两账号300", 101L, 0);
        redemption("复现-两账号300", 102L, 0);
        consume(101L, -100);
        consume(102L, -200);

        Map<String, Object> row = rowOf("复现-两账号300");

        assertThat(((Number) row.get("pointsConsumed")).longValue()).isEqualTo(300L);
        assertThat(((Number) row.get("accountCount")).longValue()).isEqualTo(2L);
        assertThat(((Number) row.get("codeCount")).longValue()).isEqualTo(2L);
        assertThat(row.get("lastRedeemedAt")).isNotNull();
    }

    @Test
    void 同账号多码不放大耗点_码数仍按兑换事实计() {
        // 防回归：naive sum 会把同账号两次兑换的同一笔耗点计两次（150→300）
        redemption("单账号多码", 201L, 0);
        redemption("单账号多码", 201L, 0);
        consume(201L, -150);

        Map<String, Object> row = rowOf("单账号多码");

        assertThat(((Number) row.get("pointsConsumed")).longValue()).isEqualTo(150L);
        assertThat(((Number) row.get("codeCount")).longValue()).isEqualTo(2L);
        assertThat(((Number) row.get("accountCount")).longValue()).isEqualTo(1L);
    }

    @Test
    void 多账号各多码不随连接行数膨胀() {
        // 2账号×2码耗100 + 3账号中的1账号×3码耗250：总额必须是 350，而不是 max(250) 或 100*2+250*3
        redemption("多账号多码", 301L, 0);
        redemption("多账号多码", 301L, 0);
        redemption("多账号多码", 302L, 0);
        redemption("多账号多码", 302L, 0);
        redemption("多账号多码", 302L, 0);
        consume(301L, -100);
        consume(302L, -250);

        Map<String, Object> row = rowOf("多账号多码");

        assertThat(((Number) row.get("pointsConsumed")).longValue()).isEqualTo(350L);
        assertThat(((Number) row.get("accountCount")).longValue()).isEqualTo(2L);
        assertThat(((Number) row.get("codeCount")).longValue()).isEqualTo(5L);
    }

    @Test
    void 无流水或零流水账号计0且不报空值() {
        redemption("无流水", 401L, 0);
        redemption("零流水", 402L, 0);
        consume(402L, 30); // 仅正向（退款）流水，消耗仍为 0

        Map<String, Object> row = rowOf("无流水");
        Map<String, Object> zeroRow = rowOf("零流水");

        assertThat(((Number) row.get("pointsConsumed")).longValue()).isEqualTo(0L);
        assertThat(((Number) zeroRow.get("pointsConsumed")).longValue()).isEqualTo(0L);
    }

    @Test
    void 退款与人工调点按既有口径_负delta计消耗_正delta不计() {
        // 既有口径：sum(CASE WHEN delta < 0 THEN -delta ELSE 0 END)——退款/人工调增不冲减已耗口径
        redemption("口径-正负delta", 501L, 0);
        consume(501L, -100);                          // FLAT_GENERATION_DEBIT
        consume(501L, 30);                            // TASK_SETTLEMENT_REFUND（退款，不计入消耗）
        consumeAs(501L, 50, "MANUAL_CREDIT");         // 人工调增（不计入消耗）
        consumeAs(501L, -20, "MANUAL_DEBIT");         // 人工调减（按既有口径计入消耗）

        Map<String, Object> row = rowOf("口径-正负delta");

        assertThat(((Number) row.get("pointsConsumed")).longValue()).isEqualTo(120L);
    }

    @Test
    void 跨公司历史账号_每家公司按账号去重计一次_不擅自分摊() {
        // 账号601共耗300，在公司戊兑换1次、公司己兑换2次：两家各自显示300（既有按兑换公司归属的口径），
        // 修复目标是消除同公司内多码放大（naive sum 会显示 己=600），不在公司间发明分摊规则
        redemption("跨公司-戊", 601L, 0);
        redemption("跨公司-己", 601L, 0);
        redemption("跨公司-己", 601L, 0);
        consume(601L, -300);

        assertThat(((Number) rowOf("跨公司-戊").get("pointsConsumed")).longValue()).isEqualTo(300L);
        assertThat(((Number) rowOf("跨公司-己").get("pointsConsumed")).longValue()).isEqualTo(300L);
        assertThat(((Number) rowOf("跨公司-己").get("codeCount")).longValue()).isEqualTo(2L);
    }

    @Test
    void 不同租户同名公司互不混算() {
        // 租户0与租户1各有一个同名公司；当前上下文只统计业务租户0，租户1的99999不得混入
        redemption("跨租户-同名公司", 701L, 0);
        consume(701L, -100);
        redemption("跨租户-同名公司", 702L, 1);
        consume(702L, -99999);

        Map<String, Object> row = rowOf("跨租户-同名公司");

        assertThat(((Number) row.get("pointsConsumed")).longValue()).isEqualTo(100L);
        assertThat(((Number) row.get("accountCount")).longValue()).isEqualTo(1L);
    }

    @Test
    void 删除记录与失效授权不退化() {
        // 已删除兑换/已删除流水不参与统计；REVOKED 授权不计有效授权
        // （uk_design_access_grant_active：同账号同时最多一条 ACTIVE 授权，撤销后重新兑换=新行）
        redemption("删除与授权", 801L, 0);
        long deletedRedemption = redemption("删除与授权", 801L, 0);
        jdbc.update("UPDATE access_code_redemption SET deleted = TRUE WHERE id = ?", deletedRedemption);
        consume(801L, -90);
        long deletedLedger = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO design_point_ledger (id, user_id, type, delta, available_after, reserved_after, deleted) "
                + "VALUES (?, 801, 'FLAT_GENERATION_DEBIT', -500, 0, 0, TRUE)", deletedLedger);
        grant(801L, "REVOKED", false, 0);
        grant(801L, "ACTIVE", false, 0);

        Map<String, Object> row = rowOf("删除与授权");

        assertThat(((Number) row.get("pointsConsumed")).longValue()).isEqualTo(90L);
        assertThat(((Number) row.get("codeCount")).longValue()).isEqualTo(1L);
        assertThat(((Number) row.get("activeGrantCount")).longValue()).isEqualTo(1L);
    }

    @Test
    void 有效授权跨多账号统计且空授权公司计0() {
        // 同账号多张 ACTIVE 被唯一索引排除；撤销后重新兑换产生的旧 REVOKED 行不得计入
        redemption("授权列-空", 903L, 0);
        redemption("授权列", 901L, 0);
        redemption("授权列", 902L, 0);
        grant(901L, "REVOKED", false, 0);
        grant(901L, "ACTIVE", false, 0);

        Map<String, Object> row = rowOf("授权列");

        assertThat(((Number) row.get("activeGrantCount")).longValue()).isEqualTo(1L);
        assertThat(((Number) rowOf("授权列-空").get("activeGrantCount")).longValue()).isEqualTo(0L);
    }

    @Test
    void 管理端点保留访问控制注解() {
        // 行级隔离由租户过滤保证（见跨租户用例）；此处锁定接口仍要求 ACCESS_CODE_MANAGE 权限，不被修复放宽
        try {
            var method = AccessCodeBatchAdminController.class.getDeclaredMethod("companySummary");
            var preAuthorize = method.getAnnotation(
                    org.springframework.security.access.prepost.PreAuthorize.class);
            assertThat(preAuthorize).isNotNull();
            assertThat(preAuthorize.value()).contains("hasPermission");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }
}
