package cn.iocoder.yudao.server;

import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.infra.zhongshu.audit.AuditPort;
import cn.iocoder.yudao.server.controller.admin.AdminDataDeleteController;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
class AdminDataDeleteContractTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");
    static SimpleDriverDataSource ds;
    static JdbcTemplate jdbc;
    AdminDataDeleteController controller;

    @BeforeAll static void migrate() {
        ds = new SimpleDriverDataSource(new org.postgresql.Driver(), PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        jdbc = new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/platform", "classpath:db/migration/commerce")
                .load().migrate();
    }

    @BeforeEach void setUp() {
        jdbc.execute("TRUNCATE recharge_order, payment_transaction, audit_event, design_point_ledger, service_usage_charge");
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(501L).setUserType(2).setTenantId(1L),
                new MockHttpServletRequest());
        controller = new AdminDataDeleteController();
        ReflectionTestUtils.setField(controller, "dataSource", ds);
        ReflectionTestUtils.setField(controller, "auditPort", mock(AuditPort.class));
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void bothDeleteEndpointsRequireTheMatchingBusinessPermission() throws Exception {
        var single = AdminDataDeleteController.class.getMethod("deleteOne", String.class, String.class)
                .getAnnotation(PreAuthorize.class).value();
        var batch = AdminDataDeleteController.class.getMethod("deleteBatch", Map.class)
                .getAnnotation(PreAuthorize.class).value();
        for (String expression : new String[]{single, batch}) {
            assertThat(allowed(expression, "recharge-order", "design:export:manage")).isFalse();
            assertThat(allowed(expression, "recharge-order", "commerce:recharge-order:delete")).isFalse();
            assertThat(allowed(expression, "recharge-order", "design:export:manage",
                    "commerce:recharge-order:delete")).isTrue();
            assertThat(allowed(expression, "ai-job", "design:export:manage")).isFalse();
            assertThat(allowed(expression, "point-ledger", "design:export:manage")).isFalse();
            assertThat(allowed(expression, "account", "design:export:manage",
                    "commerce:recharge-order:delete")).isFalse();
        }
    }

    @org.springframework.context.annotation.Configuration
    @org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
    static class MethodSecurityConfig {
        @org.springframework.context.annotation.Bean DataSource dataSource() { return ds; }
        @org.springframework.context.annotation.Bean AuditPort auditPort() { return mock(AuditPort.class); }
        @org.springframework.context.annotation.Bean cn.iocoder.yudao.module.identity.session.UserSessionService userSessionService() {
            return new cn.iocoder.yudao.module.identity.session.UserSessionService(ds,
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
        }
        @org.springframework.context.annotation.Bean SecurityFrameworkService ss() { return mock(SecurityFrameworkService.class); }
        @org.springframework.context.annotation.Bean AdminDataDeleteController adminDataDeleteController() {
            return new AdminDataDeleteController();
        }
    }

    @Test void springMethodSecurityBlocksUnauthorizedSingleAndBatchDeletion() {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext(MethodSecurityConfig.class)) {
            var secured = context.getBean(AdminDataDeleteController.class);
            var ss = context.getBean(SecurityFrameworkService.class);
            assertThatThrownBy(() -> secured.deleteOne("point-ledger", "1"))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            when(ss.hasPermission("design:export:manage")).thenReturn(true);
            assertThatThrownBy(() -> secured.deleteBatch(Map.of("type", "point-ledger", "ids", List.of("1"))))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            when(ss.hasPermission("commerce:points:delete")).thenReturn(true);
            assertThat(secured.deleteOne("point-ledger", "999999").getData()).isFalse();
            assertThatThrownBy(() -> secured.deleteOne("ai-job", "1"))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            when(ss.hasPermission("aiorchestration:job:delete")).thenReturn(true);
            assertThatThrownBy(() -> secured.deleteOne("ai-job", "1")).hasMessageContaining("暂不支持删除");
            assertThatThrownBy(() -> secured.deleteBatch(Map.of("type", "ai-job", "ids", List.of("1"))))
                    .hasMessageContaining("暂不支持删除");
        }
    }

    private boolean allowed(String expression, String type, String... permissions) {
        SecurityFrameworkService ss = mock(SecurityFrameworkService.class);
        for (String permission : permissions) when(ss.hasPermission(permission)).thenReturn(true);
        StandardEvaluationContext context = new StandardEvaluationContext();
        context.setVariable("p0", expression.contains("['type']") ? Map.of("type", type) : type);
        context.setBeanResolver((ctx, name) -> switch (name) {
            case "ss" -> ss;
            case "adminDataDeleteController" -> controller;
            default -> throw new IllegalArgumentException(name);
        });
        return Boolean.TRUE.equals(new SpelExpressionParser().parseExpression(expression).getValue(context, Boolean.class));
    }

    @Test void payableAndUnsettledOrdersStayVisible() {
        seedOrder(1L, "PENDING", "NOT_READY");
        seedOrder(2L, "UNKNOWN", "NOT_READY");
        seedOrder(3L, "SUCCEEDED", "PENDING");
        for (long id = 1; id <= 3; id++) {
            assertThat(controller.deleteOne("recharge-order", String.valueOf(id)).getData()).isFalse();
            assertThat(jdbc.queryForObject("SELECT deleted FROM recharge_order WHERE id=?", Boolean.class, id)).isFalse();
        }
    }

    @Test void onlyUnpaidTerminalOrderWithoutPaymentFactCanBeHidden() {
        seedOrder(4L, "CLOSED", "NOT_READY");
        seedOrder(5L, "FAILED", "NOT_READY");
        jdbc.update("INSERT INTO payment_transaction(id,channel,merchant_id,channel_transaction_id,order_no,amount_cents,paid_at) "
                + "VALUES (50,'STUB','merchant','txn-5','order-5',1000,now())");
        assertThat(controller.deleteOne("recharge-order", "4").getData()).isTrue();
        assertThat(controller.deleteOne("recharge-order", "5").getData()).isFalse();
        assertThat(jdbc.queryForObject("SELECT deleted FROM recharge_order WHERE id=4", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT deleted FROM recharge_order WHERE id=5", Boolean.class)).isFalse();
    }

    @Test void batchDeleteSkipsPayableOrders() {
        seedOrder(6L, "PENDING", "NOT_READY");
        seedOrder(7L, "FAILED", "NOT_READY");
        assertThat(controller.deleteBatch(Map.of("type", "recharge-order", "ids", List.of("6", "7")))
                .getData()).containsEntry("deleted", 1);
        assertThat(jdbc.queryForObject("SELECT deleted FROM recharge_order WHERE id=6", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT deleted FROM recharge_order WHERE id=7", Boolean.class)).isTrue();
    }

    @Test void aiTaskDeleteIsRejectedBeforeAnyDatabaseAccessIncludingEmptyBatches() {
        ReflectionTestUtils.setField(controller, "dataSource", null);
        for (int replay = 0; replay < 2; replay++) {
            assertThatThrownBy(() -> controller.deleteOne("ai-job", "1"))
                    .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class)
                    .hasMessageContaining("暂不支持删除");
            assertThatThrownBy(() -> controller.deleteBatch(Map.of("type", "ai-job", "ids", List.of("1", "2"))))
                    .hasMessageContaining("暂不支持删除");
            assertThatThrownBy(() -> controller.deleteBatch(Map.of("type", "ai-job", "ids", List.of())))
                    .hasMessageContaining("暂不支持删除");
        }
    }

    @Test void taskAndPromptLedgersSurviveSingleBatchAndReplay() {
        String[] types = {"FLAT_GENERATION_DEBIT", "ELEVATION_GENERATION_DEBIT", "TASK_SETTLEMENT_REFUND", "AI_PROMPT_DEBIT", "MANUAL_CREDIT", "MANUAL_CREDIT"};
        for (int i = 0; i < types.length; i++) {
            jdbc.update("INSERT INTO design_point_ledger(id,user_id,type,delta,available_after,reserved_after,biz_type,biz_id) "
                    + "VALUES (?,1,?,1,10,0,?, '88')", i + 1, types[i], i == 4 ? "ai_job" : "legacy");
        }
        jdbc.update("INSERT INTO design_point_ledger(id,user_id,type,delta,available_after,reserved_after) VALUES (7,1,'MANUAL_CREDIT',1,10,0)");
        jdbc.update("INSERT INTO service_usage_charge(id,product,user_id,biz_type,biz_id,price_rule_id,price_rule_version,point_cost,ledger_id,state) "
                + "VALUES (77,'AI_PROMPT',1,'ai_job','88',1,1,1,7,'CHARGED')");
        for (int replay = 0; replay < 2; replay++) {
            assertThat(controller.deleteOne("point-ledger", "7").getData()).isFalse();
            for (int id = 1; id <= 5; id++) assertThat(controller.deleteOne("point-ledger", String.valueOf(id)).getData()).isFalse();
            assertThat(controller.deleteBatch(Map.of("type", "point-ledger", "ids", List.of("1","2","3","4","5","6")))
                    .getData()).containsEntry("deleted", replay == 0 ? 1 : 0);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_point_ledger WHERE id<=5 AND deleted=FALSE", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT SUM(delta) FROM design_point_ledger", Long.class)).isEqualTo(7L);
    }

    private void seedOrder(long id, String paymentState, String fulfillmentState) {
        jdbc.update("INSERT INTO recharge_order(id,order_no,user_id,plan_id,plan_snapshot,amount_cents,base_points,bonus_points,"
                        + "payment_state,fulfillment_state) VALUES (?, ?, 1, 1, '{}'::jsonb, 1000, 100, 0, ?, ?)",
                id, "order-" + id, paymentState, fulfillmentState);
    }
}
