package cn.iocoder.yudao.module.platform.wallet;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.authz.PostPermissionEvaluator;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import cn.iocoder.yudao.module.platform.wallet.controller.admin.WalletController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WalletControllerSecurityTest {

    private final CurrentPost currentPost = mock(CurrentPost.class);
    private final SystemDirectory directory = mock(SystemDirectory.class);
    private final WalletQueryService service = mock(WalletQueryService.class);
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withPropertyValues("platform.legacy-design-bridge.enabled=false")
            .withUserConfiguration(MethodSecurity.class)
            .withBean(CurrentPost.class, () -> currentPost)
            .withBean(SystemDirectory.class, () -> directory)
            .withBean("post", PostPermissionEvaluator.class, () -> new PostPermissionEvaluator(currentPost, directory))
            .withBean(WalletQueryService.class, () -> service)
            .withBean(RechargePaymentService.class, () -> mock(RechargePaymentService.class))
            .withBean(WalletController.class);

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void missingAdminPostCannotReachWalletQuery() {
        authenticate();
        when(currentPost.find()).thenReturn(Optional.empty());
        context.run(ctx -> {
            assertThatThrownBy(() -> ctx.getBean(WalletController.class).account()).isInstanceOf(AccessDeniedException.class);
            verifyNoInteractions(service);
        });
    }

    @Test
    void currentRoleReadPermissionDoesNotGrantRechargePermission() {
        authenticate();
        when(currentPost.find()).thenReturn(Optional.of(new PostSnapshot(1, 2, 3, "store", "owner", null, false, Set.of())));
        when(directory.roleHasPermission("owner", "platform:wallet:read")).thenReturn(true);
        when(service.account()).thenReturn(new WalletQueryService.Account("9007199254740993", "3", "ACTIVE", 10, 0, 1));
        context.run(ctx -> {
            var controller = ctx.getBean(WalletController.class);
            assertThat(controller.account().getData().walletId()).isEqualTo("9007199254740993");
            assertThatThrownBy(controller::rechargePlans).isInstanceOf(AccessDeniedException.class);
        });
    }

    @Test
    void anotherPostManagePermissionCannotAuthorizeTheSelectedRole() {
        authenticate();
        when(currentPost.find()).thenReturn(Optional.of(new PostSnapshot(1, 2, 3, "store", "sales", null, false, Set.of())));
        when(directory.roleHasPermission("owner", "platform:wallet:read")).thenReturn(true);
        context.run(ctx -> {
            assertThatThrownBy(() -> ctx.getBean(WalletController.class).ledger(1, 20)).isInstanceOf(AccessDeniedException.class);
            verifyNoInteractions(service);
        });
    }

    @Test
    void largeIdsStayJsonStrings() {
        String json = JsonUtils.toJsonString(new WalletQueryService.Account("9007199254740993", "9007199254740995", "ACTIVE", 10, 0, 1));
        assertThat(json).contains("\"walletId\":\"9007199254740993\"", "\"orgId\":\"9007199254740995\"");
    }

    @Test
    void paymentCodeUsesTheSharedRedactorAndNeverAppearsInDtoToString() {
        var request = new WalletController.PayParamsRequest("sensitive-one-time-code", "attempt-1");
        String raw = JsonUtils.toJsonString(request);
        assertThat(raw).contains("\"code\":\"sensitive-one-time-code\"");
        String sanitized = cn.iocoder.yudao.framework.web.core.util.SensitiveLogRedactor.redactJsonBody(raw);
        assertThat(sanitized).doesNotContain("sensitive-one-time-code");
        assertThat(request.toString()).doesNotContain("sensitive-one-time-code");
    }

    private static void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", null, List.of()));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class MethodSecurity {
    }
}
