package cn.iocoder.yudao.module.franchise.design.admin;
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
class DesignOperationsSecurityTest {
    public static class Gate { public boolean hasPermission(String permission) { return false; } }
    @Configuration(proxyBeanMethods=false) @EnableMethodSecurity static class Security { }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    @Test void priceReadCannotReadCostsAndHasNoPostFallback() {
        var gate = mock(Gate.class);
        var service = mock(DesignOperationsService.class);
        when(gate.hasPermission("platform:design:price:query")).thenReturn(true);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", null, List.of()));
        new ApplicationContextRunner().withPropertyValues("platform.legacy-design-bridge.enabled=false").withUserConfiguration(Security.class)
                .withBean("ss", Gate.class, () -> gate).withBean(DesignOperationsService.class, () -> service)
                .withBean(DesignOperationsController.class).run(ctx -> {
                    var controller=ctx.getBean(DesignOperationsController.class);
                    controller.prices(1,20);
                    controller.activePrices(1,20);
                    verify(service).prices(1,20);
                    verify(service).activePrices(1,20);
                    assertThatThrownBy(() -> controller.costs(1,20,null)).isInstanceOf(AccessDeniedException.class);
                    verifyNoMoreInteractions(service);
                });
    }
}
