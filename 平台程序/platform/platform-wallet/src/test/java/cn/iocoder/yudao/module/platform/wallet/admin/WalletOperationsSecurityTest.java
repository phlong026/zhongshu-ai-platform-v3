package cn.iocoder.yudao.module.platform.wallet.admin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class WalletOperationsSecurityTest {
    public static class PermissionGate {
        public boolean hasPermission(String permission) { return false; }
    }
    @Configuration(proxyBeanMethods = false) @EnableMethodSecurity static class Security { }
    private final PermissionGate ss = mock(PermissionGate.class);
    private final WalletOperationsService service = mock(WalletOperationsService.class);
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withPropertyValues("platform.legacy-design-bridge.enabled=false")
            .withUserConfiguration(Security.class).withBean("ss", PermissionGate.class, () -> ss)
            .withBean(WalletOperationsService.class, () -> service).withBean(WalletOperationsController.class);
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    @Test void legacyApplicationNeverLoadsHeadquartersWalletBeans() {
        var wiring = new ApplicationContextRunner()
                .withUserConfiguration(WalletOperationsService.class, WalletOperationsController.class)
                .withBean(javax.sql.DataSource.class, () -> mock(javax.sql.DataSource.class));
        wiring.run(ctx -> org.assertj.core.api.Assertions.assertThat(ctx).doesNotHaveBean(WalletOperationsService.class).doesNotHaveBean(WalletOperationsController.class));
        wiring.withPropertyValues("platform.legacy-design-bridge.enabled=true")
                .run(ctx -> org.assertj.core.api.Assertions.assertThat(ctx).doesNotHaveBean(WalletOperationsService.class).doesNotHaveBean(WalletOperationsController.class));
        wiring.withPropertyValues("platform.legacy-design-bridge.enabled=false")
                .run(ctx -> org.assertj.core.api.Assertions.assertThat(ctx).hasSingleBean(WalletOperationsService.class).hasSingleBean(WalletOperationsController.class));
    }
    @Test void headquartersNeedsDedicatedPermissionsWithoutPostFallback() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", null, List.of()));
        when(ss.hasPermission("platform:wallet:query")).thenReturn(true);
        context.run(ctx -> {
            var controller = ctx.getBean(WalletOperationsController.class);
            controller.wallets(1, 20, null);
            controller.walletSummary();
            verify(service).wallets(1, 20, null);
            verify(service).walletSummary();
            assertThatThrownBy(() -> controller.plans(1, 20)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> controller.orders(1, 20, null)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> controller.ledger(1, 20, null)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> controller.anomalies(1, 20, null)).isInstanceOf(AccessDeniedException.class);
            verifyNoMoreInteractions(service);
        });
    }
}
