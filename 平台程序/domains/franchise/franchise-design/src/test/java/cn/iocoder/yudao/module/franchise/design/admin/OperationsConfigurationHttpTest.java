package cn.iocoder.yudao.module.franchise.design.admin;

import cn.iocoder.yudao.module.platform.wallet.admin.*;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OperationsConfigurationHttpTest {
    public static class PermissionGate {
        public boolean hasPermission(String permission) { return false; }
    }
    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class Security { }
    private final PermissionGate ss = mock(PermissionGate.class);
    private final RechargePlanAdminService plans = mock(RechargePlanAdminService.class);
    private final OperationsAuditService audit = mock(OperationsAuditService.class);
    private final DesignPriceAdminService prices = mock(DesignPriceAdminService.class);
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withPropertyValues("platform.legacy-design-bridge.enabled=false")
            .withUserConfiguration(Security.class)
            .withBean("ss", PermissionGate.class, () -> ss)
            .withBean(RechargePlanAdminService.class, () -> plans)
            .withBean(OperationsAuditService.class, () -> audit)
            .withBean(DesignPriceAdminService.class, () -> prices)
            .withBean(WalletConfigurationController.class)
            .withBean(DesignPriceAdminController.class);
    private static final String PLAN = """
        {"expectedVersion":1,"name":"套餐","priceFen":199,"basePoints":10,"bonusPoints":0,
         "status":"DISABLED","sort":0,"reason":"变更验证"}
        """;
    private static final String PRICE = """
        {"expectedVersion":1,"operation":"NOTE_POLISH","resolution":"","promptPoints":0,
         "imagePoints":0,"usagePoints":5,"maxImageUnits":0,"active":true,"reason":"变更验证"}
        """;
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    private void login() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", null, List.of()));
    }
    @Test void readPermissionsCannotPublishOrReviewThroughHttp() {
        login();
        when(ss.hasPermission("platform:wallet:plan:query")).thenReturn(true);
        when(ss.hasPermission("platform:design:price:query")).thenReturn(true);
        when(ss.hasPermission("platform:wallet:anomaly:query")).thenReturn(true);
        context.run(ctx -> {
            var mvc = MockMvcBuilders.standaloneSetup(ctx.getBean(WalletConfigurationController.class),
                    ctx.getBean(DesignPriceAdminController.class)).build();
            assertThatThrownBy(() -> mvc.perform(put("/admin-api/platform/ops/recharge-plans/1")
                    .contentType("application/json").content(PLAN))).hasRootCauseInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> mvc.perform(post("/admin-api/platform/ops/recharge-plans")
                    .contentType("application/json").content(PLAN))).hasRootCauseInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> mvc.perform(put("/admin-api/platform/ops/design-prices/1")
                    .contentType("application/json").content(PRICE))).hasRootCauseInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> mvc.perform(post("/admin-api/platform/ops/design-prices")
                    .contentType("application/json").content(PRICE))).hasRootCauseInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> mvc.perform(post("/admin-api/platform/ops/anomalies/1/reviews")
                    .contentType("application/json").content("{\"note\":\"已核对\"}"))).hasRootCauseInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> mvc.perform(get("/admin-api/platform/ops/audit")))
                    .hasRootCauseInstanceOf(AccessDeniedException.class);
            verifyNoInteractions(plans, prices, audit);
        });
    }
    @Test void realHttpPathsPreserveStringIdsAndRejectCoercedJsonBeforeService() {
        login();
        when(ss.hasPermission(anyString())).thenReturn(true);
        when(plans.update(anyString(), any())).thenReturn(new WalletOperationsService.Plan(
                "9007199254740993", "套餐", 199, 10, 0, 2, "DISABLED", 0, null));
        when(prices.update(anyString(), any())).thenReturn(new DesignOperationsService.Price(
                "9007199254740995", 2, "NOTE_POLISH", "", "B_WALLET_POINTS", 0, 0, 5, 0, true));
        when(audit.reviews(anyString(), anyInt(), anyInt())).thenReturn(new PageResult<>(List.of(), 0L));
        context.run(ctx -> {
            var mvc = MockMvcBuilders.standaloneSetup(ctx.getBean(WalletConfigurationController.class),
                    ctx.getBean(DesignPriceAdminController.class)).build();
            mvc.perform(put("/admin-api/platform/ops/recharge-plans/9007199254740993")
                    .contentType("application/json").content(PLAN)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value("9007199254740993"))
                    .andExpect(jsonPath("$.data.priceFen").value(199));
            verify(plans).update(eq("9007199254740993"), argThat(c -> c.expectedVersion() == 1));
            mvc.perform(put("/admin-api/platform/ops/design-prices/9007199254740995")
                    .contentType("application/json").content(PRICE)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value("9007199254740995"));
            mvc.perform(get("/admin-api/platform/ops/anomalies/9007199254740997/reviews")
                    .param("pageNo", "2").param("pageSize", "20")).andExpect(status().isOk());
            verify(audit).reviews("9007199254740997", 2, 20);
            clearInvocations(plans, prices);
            for (String value : List.of("1.5", "\"199\"", "9007199254740992", "null")) {
                assertThatThrownBy(() -> mvc.perform(put("/admin-api/platform/ops/recharge-plans/1")
                        .contentType("application/json").content(PLAN.replace("199", value))))
                        .hasRootCauseInstanceOf(ServiceException.class);
                assertThatThrownBy(() -> mvc.perform(put("/admin-api/platform/ops/design-prices/1")
                        .contentType("application/json").content(PRICE.replace("\"usagePoints\":5", "\"usagePoints\":" + value))))
                        .hasRootCauseInstanceOf(ServiceException.class);
            }
            verifyNoInteractions(plans, prices);
        });
    }
    @Test void everyHeadquartersBeanRequiresExplicitBMode() {
        Class<?>[] components = {WalletOperationsService.class, WalletOperationsController.class,
                RechargePlanAdminService.class, OperationsAuditService.class, WalletConfigurationController.class,
                DesignOperationsService.class, DesignOperationsController.class,
                DesignPriceAdminService.class, DesignPriceAdminController.class};
        var wiring = new ApplicationContextRunner().withUserConfiguration(components)
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));
        wiring.run(ctx -> { for (Class<?> type : components) assertThat(ctx).doesNotHaveBean(type); });
        wiring.withPropertyValues("platform.legacy-design-bridge.enabled=true")
                .run(ctx -> { for (Class<?> type : components) assertThat(ctx).doesNotHaveBean(type); });
        wiring.withPropertyValues("platform.legacy-design-bridge.enabled=false")
                .run(ctx -> { for (Class<?> type : components) assertThat(ctx).hasSingleBean(type); });
    }
}
