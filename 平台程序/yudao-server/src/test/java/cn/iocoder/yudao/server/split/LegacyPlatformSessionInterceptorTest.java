package cn.iocoder.yudao.server.split;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.framework.web.core.handler.GlobalResponseBodyHandler;
import cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils;
import cn.iocoder.yudao.module.identity.account.AccountLoginService;
import cn.iocoder.yudao.module.identity.controller.app.AppAuthController;
import cn.iocoder.yudao.module.identity.controller.app.vo.AppAuthLoginReqVO;
import cn.iocoder.yudao.module.identity.session.UserSessionService;
import cn.iocoder.yudao.module.identity.wechat.WechatIdentityPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static cn.iocoder.yudao.server.split.LegacyPlatformWritePort.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * G2d legacy-session：真实 MVC + 真实 PG（identity 源码迁移）验证
 * AppAuthController 三入口（wechat-login/token-refresh/logout）接入共用停写门禁。
 * 业务服务零改动；门禁行为与 G2b/G2c 共用机制逐字一致。
 */
class LegacyPlatformSessionInterceptorTest {
    private static final String SCOPE = "e".repeat(64);
    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    private static final String APPID = "synthetic-appid";
    private static final String OPENID = "synthetic-openid";
    private static final long ACCOUNT_ID = 5001L;
    private static final long DISABLED_ACCOUNT_ID = 5002L;

    private JdbcTemplate jdbc;
    private JdbcTemplate observer;
    private LegacyPlatformWriteGate gate;
    private LegacyPlatformWalletSessionInterceptorTest.MemoryStore store;
    private UserSessionService sessions;
    private AppAuthController controller;
    private final AtomicInteger commitsWithPermit = new AtomicInteger();
    private final AtomicInteger rollbacksWithPermit = new AtomicInteger();
    private final AtomicInteger completionCalls = new AtomicInteger();
    private final AtomicReference<Exception> completionException = new AtomicReference<>();
    private final ApiErrorLogCommonApi errorLogs = mock(ApiErrorLogCommonApi.class);
    private volatile CountDownLatch commitEntered;
    private volatile CountDownLatch commitRelease;

    @BeforeAll static void startDatabase() throws Exception {
        PG.start();
        var dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        // Execute the unchanged identity source migrations in version order, not a hand-written schema.
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/identity/V20260905.101__p2a_identity_access.sql"),
                new ClassPathResource("db/migration/identity/V20260909.102__independent_refresh_expiry.sql"),
                new ClassPathResource("db/migration/identity/V20261010.107__redemption_account_binding.sql"),
                new ClassPathResource("db/migration/identity/V20261010.108__access_code_binding_audit.sql"),
                new ClassPathResource("db/migration/identity/V20261016.109__wechat_identity_reregister_after_account_delete.sql"),
                new ClassPathResource("db/migration/identity/V20261102.101__account_external_key.sql"))
                .execute(dataSource);
        // 106 is a single dollar-quoted DO block (no-op without system_menu); ScriptUtils cannot split it,
        // so the unchanged file content executes as the one statement it is.
        try (var input = new ClassPathResource("db/migration/identity/V20260913.106__privacy_management_permission.sql")
                .getInputStream()) {
            new JdbcTemplate(dataSource).execute(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @AfterAll static void stopDatabase() { PG.stop(); }

    @BeforeEach void fixture() throws Exception {
        var dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        observer = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        jdbc.execute("TRUNCATE account, wechat_identity, user_session, design_access_grant");
        jdbc.update("INSERT INTO account (id, status) VALUES (?, 'ACTIVE')", ACCOUNT_ID);
        jdbc.update("INSERT INTO design_access_grant (id, account_id, status) VALUES (6001, ?, 'ACTIVE')", ACCOUNT_ID);
        jdbc.update("INSERT INTO wechat_identity (id, appid, openid, unionid, account_id) VALUES (7001, ?, ?, 'synthetic-unionid', ?)",
                APPID, OPENID, ACCOUNT_ID);
        // 停用账号：issue 在事务内抛 403，验证 permit 下整笔回滚。
        jdbc.update("INSERT INTO account (id, status) VALUES (?, 'DISABLED')", DISABLED_ACCOUNT_ID);
        jdbc.update("INSERT INTO wechat_identity (id, appid, openid, account_id) VALUES (7002, ?, 'disabled-openid', ?)",
                APPID, DISABLED_ACCOUNT_ID);
        store = new LegacyPlatformWalletSessionInterceptorTest.MemoryStore();
        gate = new LegacyPlatformWriteGate("g2d", SCOPE, Set.of(), Set.of("legacy-session"), store);
        gate.apply(new Control(1, State.OPEN, SCOPE), FreezeEvidence.missing());
        new WebFrameworkUtils(new WebProperties());
        // 真实 UserSessionService/AccountLoginService；微信端口用桩，无外呼；事务管理器加计数/门闩代理。
        var transactions = counting(new DataSourceTransactionManager(dataSource));
        sessions = new UserSessionService(dataSource, transactions);
        WechatIdentityPort stubWechat = (appid, code) -> "synthetic-code".equals(code)
                ? new WechatIdentityPort.WechatSession(OPENID, "synthetic-unionid")
                : new WechatIdentityPort.WechatSession("disabled-openid", null);
        var logins = new AccountLoginService(dataSource, transactions, stubWechat, sessions);
        controller = new AppAuthController();
        ReflectionTestUtils.setField(controller, "accountLoginService", logins);
        ReflectionTestUtils.setField(controller, "sessionService", sessions);
        ReflectionTestUtils.setField(controller, "wechatAppid", APPID);
    }

    /** 真实事务管理器代理：commit 前可挂门闩（提交前切 DRAINING），物理提交/回滚按 pending 非空计数。
     *  TransactionTemplate 对内层参与事务也调用 manager.commit（不物理提交），故只计 isNewTransaction 的边界。 */
    private PlatformTransactionManager counting(PlatformTransactionManager delegate) {
        return (PlatformTransactionManager) java.lang.reflect.Proxy.newProxyInstance(
                PlatformTransactionManager.class.getClassLoader(), new Class<?>[]{PlatformTransactionManager.class},
                (proxy, method, args) -> {
                    boolean physicalBoundary = method.getName().equals("commit")
                            && args != null && args.length == 1
                            && args[0] instanceof org.springframework.transaction.TransactionStatus status
                            && status.isNewTransaction();
                    if (commitEntered != null && physicalBoundary) {
                        commitEntered.countDown();
                        await(commitRelease);
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    } finally {
                        boolean inFlight = !gate.snapshot().checkpoint().pending().isEmpty();
                        if (physicalBoundary && inFlight) commitsWithPermit.incrementAndGet();
                        if (method.getName().equals("rollback") && inFlight) rollbacksWithPermit.incrementAndGet();
                    }
                });
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("synthetic commit timeout");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    private HandlerInterceptor interceptor() { return new LegacyPlatformHttpWriteInterceptor(gate, true); }

    private MockMvc mvc() { return mvc(interceptor(), true, null); }

    private MockMvc mvc(HandlerInterceptor selected, boolean responseAdvice, HttpMessageConverter<?> converter) {
        var builder = MockMvcBuilders.standaloneSetup(controller, new LegacyPlatformWalletSessionInterceptorTest.AController())
                .setControllerAdvice(responseAdvice
                        ? new Object[]{new GlobalExceptionHandler("g2d", errorLogs), new GlobalResponseBodyHandler()}
                        : new Object[]{new GlobalExceptionHandler("g2d", errorLogs)})
                .setMessageConverters(converter == null ? new MappingJackson2HttpMessageConverter(JSON) : converter)
                .addFilters(new cn.iocoder.yudao.framework.web.core.filter.CacheRequestBodyFilter())
                .addInterceptors(selected == null ? new HandlerInterceptor[0] : new HandlerInterceptor[]{selected})
                .addInterceptors(new HandlerInterceptor() {
                    @Override public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                                          Object handler, Exception failure) {
                        completionCalls.incrementAndGet();
                        completionException.set(failure);
                    }
                });
        return builder.build();
    }

    private MvcResult login(MockMvc mvc, String code) throws Exception {
        return mvc.perform(post("/design/v1/auth/wechat-login").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("code", code)))).andReturn();
    }

    private MvcResult refresh(MockMvc mvc, String token) throws Exception {
        return mvc.perform(post("/design/v1/auth/token-refresh").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("refreshToken", token)))).andReturn();
    }

    private MvcResult logout(MockMvc mvc, String token) throws Exception {
        var request = post("/design/v1/auth/logout");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return mvc.perform(request).andReturn();
    }

    private int code(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString()).get("code").intValue();
    }

    private int sessionRows() {
        return observer.queryForObject("SELECT count(*) FROM user_session", Integer.class);
    }

    private void unknownPending(String operation) {
        assertEquals(1, gate.snapshot().checkpoint().pending().size());
        for (Work work : gate.snapshot().checkpoint().pending().values()) {
            assertEquals("legacy-session", work.writer());
            assertEquals(operation, work.operation());
            assertTrue(work.unknown());
        }
    }

    // ---------- OPEN：真实签发/轮换/吊销在 permit 下完成 ----------

    @Test void openWechatLoginIssuesRealSessionUnderPermit() throws Exception {
        assertEquals(0, code(login(mvc(), "synthetic-code")));
        assertEquals(1, sessionRows());
        var row = observer.queryForMap("SELECT * FROM user_session");
        assertEquals(ACCOUNT_ID, ((Number) row.get("account_id")).longValue());
        assertEquals(APPID, row.get("appid"));
        assertEquals(OPENID, row.get("openid"));
        assertNull(row.get("revoked_at"));
        assertEquals(Boolean.FALSE, row.get("restricted"));
        assertEquals(1, commitsWithPermit.get());
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void openRefreshRotatesTokensUnderSinglePermit() throws Exception {
        var tokens = sessions.issue(ACCOUNT_ID, APPID, OPENID, false, null);
        assertEquals(0, code(refresh(mvc(), tokens.refreshToken())));
        assertEquals(2, sessionRows());
        assertNotNull(observer.queryForObject(
                "SELECT revoked_at FROM user_session WHERE id = ?", java.sql.Timestamp.class, tokens.sessionId()));
        assertEquals(1, commitsWithPermit.get());
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void openLogoutRevokesSessionUnderPermit() throws Exception {
        var tokens = sessions.issue(ACCOUNT_ID, APPID, OPENID, false, null);
        var result = logout(mvc(), tokens.accessToken());
        assertEquals(0, code(result));
        assertTrue(JSON.readTree(result.getResponse().getContentAsString()).get("data").asBoolean());
        assertEquals(1, sessionRows());
        assertNotNull(observer.queryForObject(
                "SELECT revoked_at FROM user_session WHERE id = ?", java.sql.Timestamp.class, tokens.sessionId()));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    // ---------- DRAINING / 处理后异常 / 回滚 ----------

    @Test void drainingRejectsAllThreeSessionEntriesWithoutAnySessionWrite() throws Exception {
        gate.apply(new Control(2, State.DRAINING, SCOPE), FreezeEvidence.missing());
        assertEquals(503, code(login(mvc(), "synthetic-code")));
        assertEquals(503, code(refresh(mvc(), "bogus-refresh-token")));
        assertEquals(503, code(logout(mvc(), "bogus-access-token")));
        assertEquals(0, sessionRows());
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void invalidRefreshTokenIsHandledFailureRetainingUnknownPending() throws Exception {
        sessions.issue(ACCOUNT_ID, APPID, OPENID, false, null);
        var result = refresh(mvc(), "bogus-refresh-token");
        assertEquals(200, result.getResponse().getStatus());
        assertEquals(401, code(result));
        assertEquals(1, sessionRows());
        assertEquals(1, completionCalls.get());
        assertNull(completionException.get());
        unknownPending("session-refresh");
    }

    @Test void logoutWithInvalidTokenNeverWritesAndRetainsUnknownPending() throws Exception {
        sessions.issue(ACCOUNT_ID, APPID, OPENID, false, null);
        assertEquals(401, code(logout(mvc(), null)));
        assertEquals(1, sessionRows());
        assertNull(observer.queryForObject(
                "SELECT revoked_at FROM user_session", java.sql.Timestamp.class));
        unknownPending("session-revoke");
    }

    @Test void disabledAccountRollsBackWholeIssuedTxUnderPermit() throws Exception {
        assertEquals(403, code(login(mvc(), "disabled-code")));
        assertEquals(0, sessionRows());
        // resolveAccount 幂等命中未开事务；issue 事务在 permit 下整笔回滚。
        assertEquals(1, rollbacksWithPermit.get());
        unknownPending("session-issue");
    }

    // ---------- c31 非阻断要求#2：logout 自动提交下"写成功但响应失败→UNKNOWN"具体断言 ----------

    @Test void logoutWithoutCommonResultEvidenceKeepsUnknownPendingAfterRealRevoke() throws Exception {
        var tokens = sessions.issue(ACCOUNT_ID, APPID, OPENID, false, null);
        assertEquals(0, code(logout(mvc(interceptor(), false, null), tokens.accessToken())));
        // 写已真实发生（自动提交，无事务可回滚）
        assertEquals(1, sessionRows());
        assertNotNull(observer.queryForObject(
                "SELECT revoked_at FROM user_session", java.sql.Timestamp.class));
        // 终态未证 → pending 保 UNKNOWN（宁多记不漏记）
        unknownPending("session-revoke");
    }

    @Test void logoutSerializationFailureAfterCommitRetainsPendingAndBuildsErrorLog() throws Exception {
        var tokens = sessions.issue(ACCOUNT_ID, APPID, OPENID, false, null);
        var module = new com.fasterxml.jackson.databind.module.SimpleModule();
        module.addSerializer(Boolean.class, new com.fasterxml.jackson.databind.JsonSerializer<Boolean>() {
            @Override public void serialize(Boolean value, com.fasterxml.jackson.core.JsonGenerator output,
                                            com.fasterxml.jackson.databind.SerializerProvider provider) throws java.io.IOException {
                throw new java.io.IOException("synthetic serialization failure");
            }
        });
        logout(mvc(interceptor(), true, new MappingJackson2HttpMessageConverter(JSON.copy().registerModule(module))),
                tokens.accessToken());
        assertEquals(1, sessionRows());
        assertNotNull(observer.queryForObject(
                "SELECT revoked_at FROM user_session", java.sql.Timestamp.class));
        verify(errorLogs).createApiErrorLogAsync(any());
        unknownPending("session-revoke");
    }

    // ---------- 提交前切 DRAINING ----------

    @Test void drainingBeforeCommitRetainsFirstSessionPermitAndRejectsSecondLogin() throws Exception {
        commitEntered = new CountDownLatch(1);
        commitRelease = new CountDownLatch(1);
        var mvc = mvc();
        var pool = Executors.newSingleThreadExecutor();
        try {
            var first = pool.submit(() -> login(mvc, "synthetic-code"));
            assertTrue(commitEntered.await(3, TimeUnit.SECONDS));
            gate.apply(new Control(2, State.DRAINING, SCOPE), FreezeEvidence.missing());
            assertEquals(1, gate.snapshot().checkpoint().pending().size());
            assertEquals(503, code(login(mvc, "synthetic-code")));
            commitRelease.countDown();
            assertEquals(0, code(first.get(3, TimeUnit.SECONDS)));
            assertEquals(1, sessionRows());
            assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
        } finally {
            commitRelease.countDown();
            pool.shutdown();
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
                fail("synthetic request did not terminate");
            }
        }
    }

    // ---------- 非目标A直通 ----------

    @Test void nonTargetARequestDoesNotWaitForSessionCheckpoint() throws Exception {
        store.entered = new CountDownLatch(1);
        store.release = new CountDownLatch(1);
        var mvc = mvc();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var blocked = pool.submit(() -> gate.enter(Owner.B_LEGACY, "legacy-session", "synthetic-block"));
            assertTrue(store.entered.await(3, TimeUnit.SECONDS));
            var response = pool.submit(() -> mvc.perform(get("/app-api/design/probe")).andReturn()).get(1, TimeUnit.SECONDS);
            assertEquals(0, code(response));
            store.release.countDown();
            blocked.get(3, TimeUnit.SECONDS).close();
        } finally {
            store.release.countDown();
            pool.shutdown();
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
                fail("synthetic request did not terminate");
            }
        }
    }

    // ---------- 白名单合同：三入口精确 writer/operation ----------

    @Test void wiredSessionHandlersEnterGateWithExactWriterAndOperation() throws Exception {
        var port = mock(LegacyPlatformWritePort.class);
        var permit = mock(Permit.class);
        when(port.enter(any(), any(), any())).thenReturn(permit);
        var selected = new LegacyPlatformHttpWriteInterceptor(port, true);
        var captor = ArgumentCaptor.forClass(String.class);
        var methods = List.of(
                AppAuthController.class.getDeclaredMethod("refresh", Map.class),
                AppAuthController.class.getDeclaredMethod("wechatLogin", AppAuthLoginReqVO.class),
                AppAuthController.class.getDeclaredMethod("logout", String.class));
        for (var method : methods) {
            assertTrue(selected.preHandle(new org.springframework.mock.web.MockHttpServletRequest(),
                    new org.springframework.mock.web.MockHttpServletResponse(), new HandlerMethod(controller, method)));
        }
        verify(port, times(3)).enter(eq(Owner.B_LEGACY), eq("legacy-session"), captor.capture());
        assertEquals(Set.of("session-refresh", "session-issue", "session-revoke"), Set.copyOf(captor.getAllValues()));
    }

    // ---------- 装配矩阵 ----------

    @Test void bridgeFalseStillGuardsAllThreeSessionHandlers() throws Exception {
        configurationCase(Map.of("platform.legacy-b-write-control-directory", "synthetic-unused",
                "platform.legacy-design-bridge.enabled", "false"), true);
    }

    @Test void installedDefaultConfigurationGuardsSessions() throws Exception {
        configurationCase(Map.of("platform.legacy-b-write-control-directory", "synthetic-unused"), true);
    }

    @Test void notInstalledLeavesSessionBehaviorWithoutFreezeEvidence() throws Exception {
        configurationCase(Map.of(), false);
    }

    private void configurationCase(Map<String, Object> properties, boolean installed) throws Exception {
        // 夹具会话：refresh/logout 需要真实 token 才能在未装配分支走到业务层。
        var forRefresh = sessions.issue(ACCOUNT_ID, APPID, OPENID, false, null);
        var forLogout = sessions.issue(ACCOUNT_ID, APPID, OPENID, false, null);
        gate.apply(new Control(2, State.DRAINING, SCOPE), FreezeEvidence.missing());
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic", properties));
            context.registerBean(LegacyPlatformWriteGate.class, () -> gate);
            context.register(LegacyPlatformHttpWriteWebConfiguration.class);
            context.refresh();
            var registry = new LegacyPlatformWalletSessionInterceptorTest.TestRegistry();
            context.getBeansOfType(WebMvcConfigurer.class).values().forEach(value -> value.addInterceptors(registry));
            assertEquals(installed ? 1 : 0, registry.handlers().size());
            var selected = registry.handlers().isEmpty() ? null : (HandlerInterceptor) registry.handlers().get(0);
            var configuredMvc = mvc(selected, true, null);
            // bridge=false 只豁免钱包会话；identity 三入口无论开关一律受控。
            assertEquals(installed ? 503 : 0, code(login(configuredMvc, "synthetic-code")));
            assertEquals(installed ? 503 : 0, code(refresh(configuredMvc, forRefresh.refreshToken())));
            assertEquals(installed ? 503 : 0, code(logout(configuredMvc, forLogout.accessToken())));
            // 装配：仅夹具两会话；未装配：+login 新会话 +refresh 轮换新会话（logout 吊销的是 forLogout 自身）。
            assertEquals(installed ? 2 : 4, sessionRows());
            assertThrows(IllegalStateException.class,
                    () -> gate.apply(new Control(3, State.FROZEN, SCOPE), FreezeEvidence.missing()));
        }
    }
}
