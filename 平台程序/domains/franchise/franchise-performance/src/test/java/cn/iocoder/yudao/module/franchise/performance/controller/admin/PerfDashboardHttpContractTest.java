package cn.iocoder.yudao.module.franchise.performance.controller.admin;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.module.franchise.performance.content.PerfDashboardService;
import cn.iocoder.yudao.module.franchise.test.FixedCurrentPost;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.authz.PostPermissionEvaluator;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P1 批1：经营看板 GET 契约与权限门禁（红期=端点未注册/类不存在；绿期=200）。
 *
 * <p>装配方式与 FinancePageHttpContractTest 一致：ApplicationContextRunner + @EnableMethodSecurity
 * 让 @post.has 真源代理生效，MockMvc standaloneSetup 使用被代理的 Controller bean。
 * 真实 SQL 由 {@link cn.iocoder.yudao.module.franchise.performance.content.PerfDashboardPgIntegrationTest} 覆盖。
 */
class PerfDashboardHttpContractTest {

    private static final PostSnapshot FINANCE =
            new PostSnapshot(21L, 7L, 100L, "store", "finance", null, false, Set.of());

    private final FixedCurrentPost currentPost = new FixedCurrentPost();
    private final SystemDirectory directory = mock(SystemDirectory.class);
    private final PerfDashboardService service = mock(PerfDashboardService.class);

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(Security.class)
            .withBean(CurrentPost.class, () -> currentPost)
            .withBean(SystemDirectory.class, () -> directory)
            .withBean("post", PostPermissionEvaluator.class, () -> new PostPermissionEvaluator(currentPost, directory))
            .withBean(PerfDashboardService.class, () -> service)
            .withBean(PerfDashboardController.class);

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", null, List.of()));
        currentPost.use(FINANCE);
        when(directory.roleHasPermission("finance", "franchise:performance:operate")).thenReturn(true);
        when(service.dashboard(any(), eq("2026-11"))).thenReturn(payload());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        currentPost.clear();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class Security {
    }

    private MockMvc mvc(org.springframework.context.ApplicationContext ctx) {
        return MockMvcBuilders.standaloneSetup(ctx.getBean(PerfDashboardController.class)).build();
    }

    /** 挂生产 GlobalExceptionHandler：错误统一 HTTP 200 + CommonResult.code（P0 批3 先例）。 */
    private MockMvc mvcWithAdvice(org.springframework.context.ApplicationContext ctx) {
        return MockMvcBuilders.standaloneSetup(ctx.getBean(PerfDashboardController.class))
                .setControllerAdvice(new GlobalExceptionHandler("test", mock(ApiErrorLogCommonApi.class)))
                .build();
    }

    @Test
    void dashboardReturnsAggregatedMetrics() {
        context.run(ctx -> mvc(ctx).perform(get("/franchise/performance/dashboard").param("period", "2026-11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.period").value("2026-11"))
                .andExpect(jsonPath("$.data.crm.newCustomers").value(3))
                .andExpect(jsonPath("$.data.crm.newCustomersByStatus.REGISTERED").value(2))
                .andExpect(jsonPath("$.data.crm.newLeads").value(5))
                .andExpect(jsonPath("$.data.engineering.created").value(4))
                .andExpect(jsonPath("$.data.engineering.byStatus.PENDING_START").value(2))
                .andExpect(jsonPath("$.data.design.serviceCommissions").value(1)));
    }

    @Test
    void periodIsRequiredUsesProductionErrorEnvelope() {
        context.run(ctx -> mvcWithAdvice(ctx).perform(get("/franchise/performance/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400)));
    }

    @Test
    void operatePermissionAllowsDashboard() {
        context.run(ctx -> mvc(ctx).perform(get("/franchise/performance/dashboard").param("period", "2026-11"))
                .andExpect(status().isOk()));
    }

    @Test
    void missingOperatePermissionIsDeniedWithProductionErrorEnvelope() {
        when(directory.roleHasPermission("finance", "franchise:performance:operate")).thenReturn(false);
        when(directory.roleHasPermission("finance", "franchise:performance:query")).thenReturn(true);
        context.run(ctx -> mvcWithAdvice(ctx).perform(
                        get("/franchise/performance/dashboard").param("period", "2026-11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403)));
    }

    @Test
    void dashboardIsReadOnlyGetWithProductionErrorEnvelope() {
        context.run(ctx -> mvcWithAdvice(ctx).perform(
                        post("/franchise/performance/dashboard").param("period", "2026-11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(405)));
    }

    @Test
    void controllerPassesRequestedPeriodToService() {
        when(service.dashboard(any(), eq("2026-10"))).thenReturn(payload());
        context.run(ctx -> mvc(ctx).perform(get("/franchise/performance/dashboard").param("period", "2026-10"))
                .andExpect(status().isOk()));
        verify(service).dashboard(any(), eq("2026-10"));
    }

    private static Map<String, Object> payload() {
        Map<String, Object> customerStatus = new LinkedHashMap<>();
        customerStatus.put("REGISTERED", 2L);
        customerStatus.put("FOLLOWING", 1L);
        Map<String, Object> crm = new LinkedHashMap<>();
        crm.put("newCustomers", 3L);
        crm.put("newCustomersByStatus", customerStatus);
        crm.put("newLeads", 5L);

        Map<String, Object> engineeringStatus = new LinkedHashMap<>();
        engineeringStatus.put("PENDING_START", 2L);
        engineeringStatus.put("IN_PROGRESS", 1L);
        engineeringStatus.put("READY_DELIVER", 1L);
        engineeringStatus.put("DELIVERED", 0L);
        engineeringStatus.put("ARCHIVED", 0L);
        Map<String, Object> engineering = new LinkedHashMap<>();
        engineering.put("created", 4L);
        engineering.put("byStatus", engineeringStatus);

        Map<String, Object> design = new LinkedHashMap<>();
        design.put("serviceCommissions", 1L);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("period", "2026-11");
        payload.put("crm", crm);
        payload.put("engineering", engineering);
        payload.put("design", design);
        return payload;
    }
}
