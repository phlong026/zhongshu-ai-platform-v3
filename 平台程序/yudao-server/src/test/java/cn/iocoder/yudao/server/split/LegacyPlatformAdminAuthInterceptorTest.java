package cn.iocoder.yudao.server.split;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.security.config.SecurityProperties;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.framework.web.core.handler.GlobalResponseBodyHandler;
import cn.iocoder.yudao.module.system.controller.admin.auth.AuthController;
import cn.iocoder.yudao.module.system.controller.admin.auth.vo.AuthLoginReqVO;
import cn.iocoder.yudao.module.system.controller.admin.auth.vo.AuthRegisterReqVO;
import cn.iocoder.yudao.module.system.controller.admin.auth.vo.AuthResetPasswordReqVO;
import cn.iocoder.yudao.module.system.controller.admin.auth.vo.AuthSmsLoginReqVO;
import cn.iocoder.yudao.module.system.controller.admin.auth.vo.AuthSmsSendReqVO;
import cn.iocoder.yudao.module.system.controller.admin.auth.vo.AuthSocialLoginReqVO;
import cn.iocoder.yudao.module.system.controller.admin.oauth2.OAuth2OpenController;
import cn.iocoder.yudao.module.system.controller.admin.oauth2.OAuth2TokenController;
import cn.iocoder.yudao.module.system.controller.admin.oauth2.OAuth2UserController;
import cn.iocoder.yudao.module.system.controller.admin.oauth2.vo.user.OAuth2UserUpdateReqVO;
import cn.iocoder.yudao.module.system.service.auth.AdminAuthService;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.method.HandlerMethod;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.Set;

import static cn.iocoder.yudao.server.split.LegacyPlatformWritePort.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * G2s-b admin AuthController 8 + OAuth2开放/令牌面 6（writer=legacy-session 扩展 3→17）：
 * 真实 MVC 验证接入共用停写门禁。两层承载声明（c31③/G2r同型）：mock层=门禁语义（本套件——
 * 服务替身承载业务层）；真实层=业务行证据（system模块自身测试覆盖+AuthController登录链真实行
 * 由 system_oauth2_access_token 经 AdminAuthService 自身测试锁定——披露边界同G2k）。
 * check-token=纯读不收口（c32源码级核验）；Captcha 2=豁免登记（Redis-only+消费方全gate）。
 */
class LegacyPlatformAdminAuthInterceptorTest {
    private static final String SCOPE = "8".repeat(64);
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();

    private final ApiErrorLogCommonApi errorLogs = mock(ApiErrorLogCommonApi.class);
    private final AdminAuthService authService = mock(AdminAuthService.class);
    private final SecurityProperties securityProperties = mock(SecurityProperties.class);
    private LegacyPlatformWriteGate gate;
    private LegacyPlatformWalletSessionInterceptorTest.MemoryStore store;
    private MockMvc authMvc;
    private MockMvc oauth2OpenMvc;
    private MockMvc oauth2TokenMvc;
    private MockMvc oauth2UserMvc;
    private AuthController authController;
    private OAuth2OpenController oauth2OpenController;
    private OAuth2TokenController oauth2TokenController;
    private OAuth2UserController oauth2UserController;

    record Entry(Class<?> ctrl, Object controller, String method, Class<?>[] params,
                 String verb, String url, String op) {}

    private final List<Entry> entries = new java.util.ArrayList<>();

    @BeforeEach void fixture() {
        SecurityContextHolder.clearContext();
        store = new LegacyPlatformWalletSessionInterceptorTest.MemoryStore();
        gate = new LegacyPlatformWriteGate("g2s-b", SCOPE, Set.of(), Set.of("legacy-session"), store);
        gate.apply(new Control(1, State.OPEN, SCOPE), FreezeEvidence.missing());
        new WebProperties();

        authController = new AuthController();
        ReflectionTestUtils.setField(authController, "authService", authService);
        ReflectionTestUtils.setField(authController, "securityProperties", securityProperties);
        when(securityProperties.getTokenHeader()).thenReturn("Authorization");
        when(securityProperties.getTokenParameter()).thenReturn("");

        oauth2OpenController = new OAuth2OpenController();
        ReflectionTestUtils.setField(oauth2OpenController, "oauth2GrantService",
                mock(cn.iocoder.yudao.module.system.service.oauth2.OAuth2GrantService.class));
        ReflectionTestUtils.setField(oauth2OpenController, "oauth2ClientService",
                mock(cn.iocoder.yudao.module.system.service.oauth2.OAuth2ClientService.class));
        ReflectionTestUtils.setField(oauth2OpenController, "oauth2TokenService",
                mock(cn.iocoder.yudao.module.system.service.oauth2.OAuth2TokenService.class));

        oauth2TokenController = new OAuth2TokenController();
        ReflectionTestUtils.setField(oauth2TokenController, "oauth2TokenService",
                mock(cn.iocoder.yudao.module.system.service.oauth2.OAuth2TokenService.class));

        oauth2UserController = new OAuth2UserController();
        ReflectionTestUtils.setField(oauth2UserController, "userService",
                mock(AdminUserService.class));

        authMvc = build(authController);
        oauth2OpenMvc = build(oauth2OpenController);
        oauth2TokenMvc = build(oauth2TokenController);
        oauth2UserMvc = build(oauth2UserController);

        var HSR = HttpServletRequest.class; var S = String.class; var L = List.class;
        entries.add(new Entry(AuthController.class, authController, "login", new Class<?>[]{AuthLoginReqVO.class}, "POST", "/system/auth/login", "auth-login"));
        entries.add(new Entry(AuthController.class, authController, "logout", new Class<?>[]{HSR}, "POST", "/system/auth/logout", "auth-logout"));
        entries.add(new Entry(AuthController.class, authController, "refreshToken", new Class<?>[]{S}, "POST", "/system/auth/refresh-token?refreshToken=rt", "auth-refresh-token"));
        entries.add(new Entry(AuthController.class, authController, "register", new Class<?>[]{AuthRegisterReqVO.class}, "POST", "/system/auth/register", "auth-register"));
        entries.add(new Entry(AuthController.class, authController, "smsLogin", new Class<?>[]{AuthSmsLoginReqVO.class}, "POST", "/system/auth/sms-login", "auth-sms-login"));
        entries.add(new Entry(AuthController.class, authController, "sendLoginSmsCode", new Class<?>[]{AuthSmsSendReqVO.class}, "POST", "/system/auth/send-sms-code", "auth-send-sms-code"));
        entries.add(new Entry(AuthController.class, authController, "resetPassword", new Class<?>[]{AuthResetPasswordReqVO.class}, "POST", "/system/auth/reset-password", "auth-reset-password"));
        entries.add(new Entry(AuthController.class, authController, "socialQuickLogin", new Class<?>[]{AuthSocialLoginReqVO.class}, "POST", "/system/auth/social-login", "auth-social-login"));
        entries.add(new Entry(OAuth2OpenController.class, oauth2OpenController, "postAccessToken",
                new Class<?>[]{HSR, S, S, S, S, S, S, S, S}, "POST",
                "/system/oauth2/token?grant_type=client_credentials&client_id=c&client_secret=s", "oauth2-token-create"));
        entries.add(new Entry(OAuth2OpenController.class, oauth2OpenController, "revokeToken",
                new Class<?>[]{HSR, S}, "DELETE", "/system/oauth2/token?token=tok", "oauth2-token-revoke"));
        entries.add(new Entry(OAuth2OpenController.class, oauth2OpenController, "approveOrDeny",
                new Class<?>[]{S, S, S, S, Boolean.class, S}, "POST",
                "/system/oauth2/authorize?response_type=code&client_id=c&redirect_uri=http://x&auto_approve=true", "oauth2-authorize"));
        entries.add(new Entry(OAuth2TokenController.class, oauth2TokenController, "deleteAccessToken",
                new Class<?>[]{S}, "DELETE", "/system/oauth2-token/delete?accessToken=tok", "oauth2-token-delete"));
        entries.add(new Entry(OAuth2TokenController.class, oauth2TokenController, "deleteAccessTokenList",
                new Class<?>[]{L}, "DELETE", "/system/oauth2-token/delete-list?accessTokens=a,b", "oauth2-token-delete-list"));
        entries.add(new Entry(OAuth2UserController.class, oauth2UserController, "updateUserInfo",
                new Class<?>[]{OAuth2UserUpdateReqVO.class}, "PUT", "/system/oauth2/user/update", "oauth2-user-update"));
    }

    private MockMvc build(Object controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new Object[]{new GlobalExceptionHandler("g2s-b", errorLogs),
                        new GlobalResponseBodyHandler()})
                .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON),
                        new ByteArrayHttpMessageConverter())
                .addFilters(new cn.iocoder.yudao.framework.web.core.filter.CacheRequestBodyFilter())
                .addInterceptors(new LegacyPlatformHttpWriteInterceptor(gate, true))
                .build();
    }

    private MockMvc mvcFor(Entry e) {
        if (e.controller() instanceof AuthController) return authMvc;
        if (e.controller() instanceof OAuth2OpenController) return oauth2OpenMvc;
        if (e.controller() instanceof OAuth2TokenController) return oauth2TokenMvc;
        return oauth2UserMvc;
    }

    private int code(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString()).get("code").intValue();
    }

    // ---------- DRAINING：14入口 503 ----------

    @Test void drainingRejectsAllFourteenAdminAuthEntries() throws Exception {
        gate.apply(new Control(2, State.DRAINING, SCOPE), FreezeEvidence.missing());
        for (Entry e : entries) {
            var request = switch (e.verb()) {
                case "PUT" -> put(e.url());
                case "DELETE" -> delete(e.url());
                default -> post(e.url());
            };
            if (e.verb().equals("POST") || e.verb().equals("PUT")) {
                if (e.url().contains("authorize") || e.url().contains("refresh-token") || e.url().contains("token?")) {
                    // query-param 面：无 body
                } else {
                    request = request.contentType(MediaType.APPLICATION_JSON).content("{}");
                }
            }
            assertEquals(503, code(mvcFor(e).perform(request).andReturn()), e.op());
        }
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    // ---------- OPEN 代表：logout 真实交互（token解析→service吊销） ----------

    @Test void openLogoutParsesTokenAndRevokesViaService() throws Exception {
        var result = authMvc.perform(post("/system/auth/logout")
                .header("Authorization", "Bearer g2s-token")).andReturn();
        assertEquals(0, code(result));
        verify(authService).logout(eq("g2s-token"), any());
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    // ---------- 白名单合同：14项 captor ----------

    @Test void wiredAdminAuthHandlersEnterGateWithExactWriterAndOperation() throws Exception {
        var port = mock(LegacyPlatformWritePort.class);
        var permit = mock(Permit.class);
        when(port.enter(any(), any(), any())).thenReturn(permit);
        var selected = new LegacyPlatformHttpWriteInterceptor(port, true);
        var operations = new java.util.HashSet<String>();
        for (Entry e : entries) {
            var m = e.ctrl().getDeclaredMethod(e.method(), e.params());
            assertTrue(selected.preHandle(new org.springframework.mock.web.MockHttpServletRequest(),
                    new org.springframework.mock.web.MockHttpServletResponse(),
                    new HandlerMethod(e.controller(), m)), e.op());
        }
        var captor = ArgumentCaptor.forClass(String.class);
        verify(port, times(14)).enter(eq(Owner.B_LEGACY), eq("legacy-session"), captor.capture());
        captor.getAllValues().forEach(operations::add);
        assertEquals(Set.of("auth-login", "auth-logout", "auth-refresh-token", "auth-register",
                "auth-sms-login", "auth-send-sms-code", "auth-reset-password", "auth-social-login",
                "oauth2-token-create", "oauth2-token-revoke", "oauth2-authorize",
                "oauth2-token-delete", "oauth2-token-delete-list", "oauth2-user-update"), operations);
    }

    // ---------- 装配矩阵（login 代表） ----------

    @Test void bridgeFalseStillGuardsAdminAuthHandlers() throws Exception {
        configurationCase(Map.of("platform.legacy-b-write-control-directory", "synthetic-unused",
                "platform.legacy-design-bridge.enabled", "false"), true);
    }

    @Test void installedDefaultConfigurationGuardsAdminAuthHandlers() throws Exception {
        configurationCase(Map.of("platform.legacy-b-write-control-directory", "synthetic-unused"), true);
    }

    @Test void notInstalledLeavesAdminAuthBehaviorWithoutFreezeEvidence() throws Exception {
        configurationCase(Map.of(), false);
    }

    private void configurationCase(Map<String, Object> properties, boolean installed) throws Exception {
        gate.apply(new Control(2, State.DRAINING, SCOPE), FreezeEvidence.missing());
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new org.springframework.core.env.MapPropertySource("synthetic", properties));
            context.registerBean(LegacyPlatformWriteGate.class, () -> gate);
            context.register(LegacyPlatformHttpWriteWebConfiguration.class);
            context.refresh();
            var registry = new LegacyPlatformWalletSessionInterceptorTest.TestRegistry();
            context.getBeansOfType(org.springframework.web.servlet.config.annotation.WebMvcConfigurer.class)
                    .values().forEach(value -> value.addInterceptors(registry));
            assertEquals(installed ? 1 : 0, registry.handlers().size());
            var selected = registry.handlers().isEmpty() ? null : (org.springframework.web.servlet.HandlerInterceptor) registry.handlers().get(0);
            var configuredMvc = MockMvcBuilders.standaloneSetup(authController)
                    .setControllerAdvice(new Object[]{new GlobalExceptionHandler("g2s-b", errorLogs)})
                    .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
                    .addInterceptors(selected == null ? new org.springframework.web.servlet.HandlerInterceptor[0] : new org.springframework.web.servlet.HandlerInterceptor[]{selected})
                    .build();
            // notInstalled 分支=无门禁原行为（业务结果不论——@Valid/登录链细节由 system 模块自身测试锁定）；
            // 断言语义=未装配时无 503 门禁介入（冻结证据边界），装配时 503。
            if (installed) {
                assertEquals(503, code(configuredMvc.perform(post("/system/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"g2s\",\"password\":\"g2s\"}")).andReturn()));
            } else {
                assertNotEquals(503, code(configuredMvc.perform(post("/system/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"g2s\",\"password\":\"g2s\"}")).andReturn()));
            }
            assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
            assertThrows(IllegalStateException.class,
                    () -> gate.apply(new Control(3, State.FROZEN, SCOPE), FreezeEvidence.missing()));
        }
    }

    // ---------- 非目标读直通（GET /system/auth/get-permission-info 纯读） ----------

    @Test void nonTargetPermissionInfoDoesNotWaitForAdminAuthCheckpoint() throws Exception {
        store.entered = new CountDownLatch(1);
        store.release = new CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var blocked = pool.submit(() -> gate.enter(Owner.B_LEGACY, "legacy-session", "synthetic-block"));
            assertTrue(store.entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
            var response = pool.submit(() -> {
                try {
                    return authMvc.perform(get("/system/auth/get-permission-info")).andReturn();
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }).get(2, java.util.concurrent.TimeUnit.SECONDS);
            assertNotEquals(503, code(response)); // 纯读不受门禁
            store.release.countDown();
            blocked.get(3, java.util.concurrent.TimeUnit.SECONDS).close();
        } finally {
            store.release.countDown();
            pool.shutdown();
            if (!pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                pool.shutdownNow();
                fail("synthetic request did not terminate");
            }
        }
    }
}
