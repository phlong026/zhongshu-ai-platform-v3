package cn.iocoder.yudao.module.platform.wallet.orgpoint;

import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class OrgPointHeadquartersSecurityTest {
    public static class PermissionGate { public boolean hasPermission(String permission) { return false; } }
    @Configuration(proxyBeanMethods = false) @EnableMethodSecurity static class Security { }

    private final PermissionGate ss = mock(PermissionGate.class);
    private final OrgPointHeadquartersService service = mock(OrgPointHeadquartersService.class);
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withPropertyValues("platform.legacy-design-bridge.enabled=false")
            .withUserConfiguration(Security.class)
            .withBean("ss", PermissionGate.class, () -> ss)
            .withBean(OrgPointHeadquartersService.class, () -> service)
            .withBean(OrgPointHeadquartersController.class);

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void legacyModeDoesNotLoadHeadquartersCompanyPointController() {
        new ApplicationContextRunner()
                .withPropertyValues("platform.legacy-design-bridge.enabled=true")
                .withBean(OrgPointHeadquartersService.class, () -> service)
                .withUserConfiguration(OrgPointHeadquartersController.class)
                .run(ctx -> org.assertj.core.api.Assertions.assertThat(ctx)
                        .doesNotHaveBean(OrgPointHeadquartersController.class));
    }

    @Test
    void queryPermissionCannotSubmitReviewOrPublishPrice() {
        authenticate();
        when(ss.hasPermission("platform:org-point:query")).thenReturn(true);
        try (MockedStatic<SecurityFrameworkUtils> security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(99L);
            context.run(ctx -> {
                var controller = ctx.getBean(OrgPointHeadquartersController.class);
                controller.companies();
                verify(service).companies(99L);
                assertThatThrownBy(() -> controller.submit(7L,
                        new OrgPointHeadquartersController.SubmitAdjustment(1, "reason", "key")))
                        .isInstanceOf(AccessDeniedException.class);
                assertThatThrownBy(() -> controller.review(1L,
                        new OrgPointHeadquartersController.ReviewAdjustment(true, "ok")))
                        .isInstanceOf(AccessDeniedException.class);
                assertThatThrownBy(() -> controller.publishPrice(
                        new OrgPointHeadquartersController.PublishPrice("ai_flat", 1_000_000, 1, "reason")))
                        .isInstanceOf(AccessDeniedException.class);
            });
        }
    }

    @Test
    void adjustmentSubmitAndReviewAreSeparatePermissions() {
        authenticate();
        when(ss.hasPermission("platform:org-point:adjustment:submit")).thenReturn(true);
        try (MockedStatic<SecurityFrameworkUtils> security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(100L);
            context.run(ctx -> {
                var controller = ctx.getBean(OrgPointHeadquartersController.class);
                controller.submit(8L, new OrgPointHeadquartersController.SubmitAdjustment(2, "reason", "key"));
                verify(service).submitAdjustment(100L, 8L, 2, "reason", "key");
                assertThatThrownBy(() -> controller.review(1L,
                        new OrgPointHeadquartersController.ReviewAdjustment(true, "ok")))
                        .isInstanceOf(AccessDeniedException.class);
            });
        }
    }

    private static void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", null, List.of()));
    }
}
