package cn.iocoder.yudao.server.split;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.framework.web.core.handler.GlobalResponseBodyHandler;
import cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils;
import cn.iocoder.yudao.module.identity.controller.app.AppPrivacyController;
import cn.iocoder.yudao.module.identity.privacy.PrivacyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.http.HttpOutputMessage;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.testcontainers.containers.PostgreSQLContainer;
import java.lang.reflect.InvocationTargetException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.server.split.LegacyPlatformWritePort.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * G2i 快批：真实 MVC + 真实 PG 验证 acceptConsents 接入共用停写门禁
 * （writer=identity-provision为分派归属，非供给链语义扩展；隐私生命周期/擦除面仍归类别12）。
 * commerce/identity 模块生产代码零改动。
 */
class LegacyPlatformPrivacyConsentInterceptorTest {
    private static final String SCOPE = "e".repeat(64);
    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final long USER_ID = 7777L;
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();

    private final ApiErrorLogCommonApi errorLogs = mock(ApiErrorLogCommonApi.class);
    private JdbcTemplate observer;
    private LegacyPlatformWriteGate gate;
    private LegacyPlatformWalletSessionInterceptorTest.MemoryStore store;
    private MockMvc privacyMvc;
    private AppPrivacyController privacyController;
    private cn.iocoder.yudao.module.identity.session.UserSessionService sessions;
    private String validToken;
    private final AtomicInteger commitsWithPermit = new AtomicInteger();
    private volatile CountDownLatch commitEntered;
    private volatile CountDownLatch commitRelease;

    @BeforeAll static void startDatabase() {
        PG.start();
        var dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        // 版本序逐字执行：identity 101（account）→ design 206（privacy_consent 表，跨模块既有生产形态）。
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/identity/V20260905.101__p2a_identity_access.sql"),
                new ClassPathResource("db/migration/design/V20260905.206__p7b_budget_message_privacy.sql"),
                new ClassPathResource("db/migration/identity/V20260909.102__independent_refresh_expiry.sql"),
                new ClassPathResource("db/migration/design/V20260913.213__privacy_request_execution.sql"))
                .execute(dataSource);
        new WebFrameworkUtils(new WebProperties());
    }

    @AfterAll static void stopDatabase() { PG.stop(); }

    @BeforeEach void fixture() {
        var dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        observer = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        observer.execute("TRUNCATE privacy_consent, account, user_session, design_access_grant");
        observer.update("INSERT INTO account (id, status) VALUES (?, 'ACTIVE')", USER_ID);
        store = new LegacyPlatformWalletSessionInterceptorTest.MemoryStore();
        gate = new LegacyPlatformWriteGate("g2i", SCOPE, Set.of(), Set.of("identity-provision"), store);
        gate.apply(new Control(1, State.OPEN, SCOPE), FreezeEvidence.missing());
        new WebFrameworkUtils(new WebProperties());
        commitEntered = null;
        commitRelease = null;
        var transactions = counting(new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        sessions = new cn.iocoder.yudao.module.identity.session.UserSessionService(dataSource, transactions);
        validToken = sessions.issue(USER_ID, "wx-app", "wallet-openid", false, null).accessToken();
        var privacyService = new PrivacyService(dataSource);
        privacyController = new AppPrivacyController();
        ReflectionTestUtils.setField(privacyController, "privacyService", privacyService);
        ReflectionTestUtils.setField(privacyController, "sessionService", sessions);
        privacyMvc = MockMvcBuilders.standaloneSetup(privacyController)
                .setControllerAdvice(new Object[]{new GlobalExceptionHandler("g2i", errorLogs),
                        new GlobalResponseBodyHandler()})
                .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
                .addFilters(new cn.iocoder.yudao.framework.web.core.filter.CacheRequestBodyFilter())
                .addInterceptors(new LegacyPlatformHttpWriteInterceptor(gate, true))
                .build();
    }

    /** 真实事务管理器代理：物理提交前可挂门闩；commit 按 pending 非空计数（G2d/E 口径）。 */
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

    private MvcResult postAccept() throws Exception {
        return privacyMvc.perform(post("/design/v1/privacy-consents")
                .header("Authorization", "Bearer " + validToken)).andReturn();
    }

    private MvcResult getConsents() throws Exception {
        return privacyMvc.perform(get("/design/v1/privacy-consents")
                .header("Authorization", "Bearer " + validToken)).andReturn();
    }

    private int rows() {
        return observer.queryForObject("SELECT count(*) FROM privacy_consent WHERE user_id = ?",
                Integer.class, USER_ID);
    }

    private int code(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString()).get("code").intValue();
    }

    @Test void openAcceptWritesThreeDistinctConsentRowsWithVersionColumn() throws Exception {
        assertEquals(0, rows());
        assertEquals(0, code(postAccept()));
        // 列级断言（c31要求）：三行 = 三类不同 policy_type × CURRENT_PRIVACY_VERSION。
        var types = observer.queryForList(
                "SELECT policy_type FROM privacy_consent WHERE user_id = ? ORDER BY policy_type",
                String.class, USER_ID);
        assertEquals(3, types.size());
        assertEquals("AI_PROCESSING_NOTICE,PRIVACY_POLICY,USER_AGREEMENT",
                String.join(",", types));
        var typeSet = observer.queryForObject(
                "SELECT array_to_string(array_agg(DISTINCT policy_type ORDER BY policy_type), ',') "
                        + "FROM privacy_consent WHERE user_id = ?", String.class, USER_ID);
        assertEquals("AI_PROCESSING_NOTICE,PRIVACY_POLICY,USER_AGREEMENT", typeSet);
        var versions = observer.queryForObject(
                "SELECT array_to_string(array_agg(DISTINCT version ORDER BY version), ',') "
                        + "FROM privacy_consent WHERE user_id = ?", String.class, USER_ID);
        assertEquals("v1", versions);
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void secondAcceptIsIdempotentStillThreeRowsAndReleases() throws Exception {
        assertEquals(0, code(postAccept()));
        assertEquals(0, code(postAccept()));
        assertEquals(3, rows());
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void drainingRejectsWithZeroWrites() throws Exception {
        gate.apply(new Control(2, State.DRAINING, SCOPE), FreezeEvidence.missing());
        assertEquals(503, code(postAccept()));
        assertEquals(0, rows());
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void invalidTokenIsHandledRetainingUnknown() throws Exception {
        var result = privacyMvc.perform(post("/design/v1/privacy-consents")).andReturn();
        assertNotEquals(0, code(result));
        assertEquals(0, rows());
        assertEquals(1, gate.snapshot().checkpoint().pending().size());
        for (Work work : gate.snapshot().checkpoint().pending().values()) {
            assertEquals("identity-provision", work.writer());
            assertEquals("privacy-consent-accept", work.operation());
            assertTrue(work.unknown());
        }
    }

    @Test void wiredPrivacyHandlerEntersGateWithExactWriterAndOperation() throws Exception {
        var port = mock(LegacyPlatformWritePort.class);
        var permit = mock(Permit.class);
        when(port.enter(any(), any(), any())).thenReturn(permit);
        var selected = new LegacyPlatformHttpWriteInterceptor(port, true);
        var handler = new HandlerMethod(privacyController,
                AppPrivacyController.class.getDeclaredMethod("acceptConsents", String.class));
        assertTrue(selected.preHandle(new org.springframework.mock.web.MockHttpServletRequest(),
                new org.springframework.mock.web.MockHttpServletResponse(), handler));
        var captor = ArgumentCaptor.forClass(String.class);
        verify(port, times(1)).enter(eq(Owner.B_LEGACY), eq("identity-provision"), captor.capture());
        assertEquals("privacy-consent-accept", captor.getValue());
    }

    @Test void bridgeFalseStillGuardsPrivacyHandler() throws Exception {
        configurationCase(Map.of("platform.legacy-b-write-control-directory", "synthetic-unused",
                "platform.legacy-design-bridge.enabled", "false"), true);
    }

    @Test void installedDefaultConfigurationGuardsPrivacyHandler() throws Exception {
        configurationCase(Map.of("platform.legacy-b-write-control-directory", "synthetic-unused"), true);
    }

    @Test void notInstalledLeavesPrivacyBehaviorWithoutFreezeEvidence() throws Exception {
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
            context.getBeansOfType(WebMvcConfigurer.class).values().forEach(value -> value.addInterceptors(registry));
            assertEquals(installed ? 1 : 0, registry.handlers().size());
            var selected = registry.handlers().isEmpty() ? null : (HandlerInterceptor) registry.handlers().get(0);
            var configuredMvc = MockMvcBuilders.standaloneSetup(privacyController)
                    .setControllerAdvice(new Object[]{new GlobalExceptionHandler("g2i", errorLogs)})
                    .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
                    .addInterceptors(selected == null ? new HandlerInterceptor[0] : new HandlerInterceptor[]{selected})
                    .build();
            assertEquals(installed ? 503 : 0, code(configuredMvc.perform(post("/design/v1/privacy-consents")
                    .header("Authorization", "Bearer " + validToken)).andReturn()));
            assertEquals(installed ? 0 : 3, rows());
            assertThrows(IllegalStateException.class,
                    () -> gate.apply(new Control(3, State.FROZEN, SCOPE), FreezeEvidence.missing()));
        }
    }

    @Test void nonTargetGetConsentsDoesNotWaitForCheckpoint() throws Exception {
        store.entered = new CountDownLatch(1);
        store.release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var blocked = pool.submit(() -> gate.enter(Owner.B_LEGACY, "identity-provision", "synthetic-block"));
            assertTrue(store.entered.await(3, TimeUnit.SECONDS));
            var response = pool.submit(() -> privacyMvc.perform(get("/design/v1/privacy-consents")
                    .header("Authorization", "Bearer " + validToken)).andReturn()).get(1, TimeUnit.SECONDS);
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
}
