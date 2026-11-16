package cn.iocoder.yudao.module.platform.wallet.orgpoint;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import javax.sql.DataSource;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrgPointPricingSecurityTest {
    public static class Gate { public boolean hasPermission(String key) { return false; } }
    @Configuration(proxyBeanMethods = false) @EnableMethodSecurity static class Security { }
    private final Gate gate = mock(Gate.class);
    private final DataSource source = mock(DataSource.class);
    private final OrgPointHeadquartersService headquarters = mock(OrgPointHeadquartersService.class);
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withPropertyValues("platform.legacy-design-bridge.enabled=false")
            .withUserConfiguration(Security.class)
            .withBean("ss", Gate.class, () -> gate)
            .withBean(DataSource.class, () -> source)
            .withBean(OrgPointHeadquartersService.class, () -> headquarters)
            .withBean(OrgPointPricingController.class);
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", null, List.of()));
    }

    @Test void permissionDeniedNeverReadsHistory() {
        authenticate();
        context.run(ctx -> assertThatThrownBy(() -> ctx.getBean(OrgPointPricingController.class).history(null))
                .isInstanceOf(AccessDeniedException.class));
        verifyNoInteractions(source, headquarters);
    }

    @Test void missingActorNeverReadsHistory() {
        authenticate();
        when(gate.hasPermission("platform:org-point:price:query")).thenReturn(true);
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(null);
            context.run(ctx -> assertThatThrownBy(() -> ctx.getBean(OrgPointPricingController.class).history(null))
                    .isInstanceOf(ServiceException.class).hasFieldOrPropertyWithValue("code", 401));
        }
        verifyNoInteractions(source, headquarters);
    }

    @Test void queryPermissionDoesNotBypassHeadquartersTenantAndActorGuard() {
        authenticate();
        when(gate.hasPermission("platform:org-point:price:query")).thenReturn(true);
        when(headquarters.catalog(88L)).thenThrow(new ServiceException(403, "无总部权限"));
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(88L);
            context.run(ctx -> assertThatThrownBy(() -> ctx.getBean(OrgPointPricingController.class).history("copy"))
                    .isInstanceOf(ServiceException.class).hasFieldOrPropertyWithValue("code", 403));
        }
        verifyNoInteractions(source);
    }

    @Test void rejectsUnknownSubjectBeforeDatabaseQuery() {
        authenticate();
        when(gate.hasPermission("platform:org-point:price:query")).thenReturn(true);
        when(headquarters.catalog(88L)).thenReturn(List.of());
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(88L);
            context.run(ctx -> assertThatThrownBy(() -> ctx.getBean(OrgPointPricingController.class).history("unknown"))
                    .isInstanceOf(ServiceException.class).hasFieldOrPropertyWithValue("code", 400));
        }
        verifyNoInteractions(source);
    }

    @Test void legacyModeDoesNotRegisterPricingHistory() {
        new ApplicationContextRunner().withPropertyValues("platform.legacy-design-bridge.enabled=true")
                .withBean(DataSource.class, () -> source)
                .withBean(OrgPointHeadquartersService.class, () -> headquarters)
                .withUserConfiguration(OrgPointPricingController.class)
                .run(ctx -> assertThat(ctx).doesNotHaveBean(OrgPointPricingController.class));
    }
}
