package cn.iocoder.yudao.server.split;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.framework.web.core.handler.GlobalResponseBodyHandler;
import cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.org.controller.admin.WalletSessionController;
import cn.iocoder.yudao.module.franchise.org.wallet.WalletSessionService;
import cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionIssuePort;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.command.CommandLog;
import cn.iocoder.yudao.module.platform.command.dal.CommandEventDO;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerInterceptor;
import org.testcontainers.containers.PostgreSQLContainer;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static cn.iocoder.yudao.server.split.LegacyPlatformWritePort.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class LegacyPlatformWalletSessionInterceptorTest {
    private static final String SCOPE = "d".repeat(64);
    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final ObjectMapper JSON = new ObjectMapper();
    private JdbcTemplate jdbc;
    private JdbcTemplate observer;
    private LegacyPlatformWriteGate gate;
    private MemoryStore store;
    private WalletSessionController controller;
    private final AtomicInteger exchanges = new AtomicInteger();
    private final AtomicInteger commitsWithPermit = new AtomicInteger();
    private final AtomicInteger rollbacksWithPermit = new AtomicInteger();
    private final AtomicReference<Exception> completionException = new AtomicReference<>();
    private final AtomicInteger completionCalls = new AtomicInteger();
    private volatile boolean failExchange;
    private volatile boolean failAudit;
    private volatile CountDownLatch exchangeEntered;
    private volatile CountDownLatch exchangeRelease;
    private volatile CountDownLatch commitEntered;
    private volatile CountDownLatch commitRelease;

    static final class MemoryStore implements StateStore {
        private Checkpoint saved;
        volatile CountDownLatch entered;
        volatile CountDownLatch release;
        @Override public Checkpoint load() { return saved; }
        @Override public void save(Checkpoint value) throws IOException {
            if (entered != null) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("synthetic persistence timeout");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IOException(failure);
                }
            }
            saved = value;
        }
    }

    @BeforeAll static void startDatabase() { PG.start(); }
    @AfterAll static void stopDatabase() { PG.stop(); }

    @BeforeEach void fixture() {
        new cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils(
                new cn.iocoder.yudao.framework.web.config.WebProperties());
        var dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        observer = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        jdbc.execute("CREATE TABLE IF NOT EXISTS synthetic_account(id bigint primary key)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS synthetic_session(id bigint primary key, account_id bigint not null)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS synthetic_audit(id bigint generated always as identity, account_id bigint not null)");
        jdbc.execute("TRUNCATE synthetic_account, synthetic_session, synthetic_audit");
        store = new MemoryStore();
        gate = new LegacyPlatformWriteGate("g2a", SCOPE, Set.of(), Set.of("franchise-http"), store);
        gate.apply(new Control(1, State.OPEN, SCOPE), FreezeEvidence.missing());
        var post = new PostSnapshot(11, 21, 31, "FRANCHISE", "OWNER", null, false, Set.of());
        var current = mock(CurrentPost.class);
        when(current.require()).thenReturn(post);
        var accounts = mock(StoreServiceAccountPort.class);
        when(accounts.requireServiceAccount(31)).thenAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) {
                    if (commitEntered != null) {
                        commitEntered.countDown();
                        await(commitRelease);
                    }
                }
                @Override public void afterCompletion(int status) {
                    if (!gate.snapshot().checkpoint().pending().isEmpty()) {
                        (status == STATUS_COMMITTED ? commitsWithPermit : rollbacksWithPermit).incrementAndGet();
                    }
                }
            });
            jdbc.update("INSERT INTO synthetic_account VALUES (41) ON CONFLICT DO NOTHING");
            return 41L;
        });
        var identity = mock(IdentitySessionIssuePort.class);
        when(identity.exchangePayerOpenid(anyString(), anyString())).thenAnswer(invocation -> {
            exchanges.incrementAndGet();
            if (exchangeEntered != null) {
                exchangeEntered.countDown();
                await(exchangeRelease);
            }
            if (failExchange) throw new IdentitySessionIssuePort.WechatCodeExchangeException(
                    IdentitySessionIssuePort.CodeFailureKind.SERVICE_UNAVAILABLE);
            return new IdentitySessionIssuePort.PayPayerOpenid("synthetic-payer");
        });
        when(identity.issueWalletSession(eq(41L), anyString(), anyString(), anyString(), eq(1800L)))
                .thenAnswer(invocation -> {
                    jdbc.update("INSERT INTO synthetic_session VALUES (51, 41)");
                    return new IdentitySessionIssuePort.IssuedWalletSession(51, "synthetic-session", 1800);
                });
        var log = mock(CommandLog.class);
        when(log.append(any(CommandEventDO.class))).thenAnswer(invocation -> {
            CommandEventDO event = invocation.getArgument(0);
            jdbc.update("INSERT INTO synthetic_audit(account_id) VALUES (?)", event.getAggregateId());
            if (failAudit) throw new cn.iocoder.yudao.framework.common.exception.ServiceException(503, "synthetic audit failure");
            return event;
        });
        var service = new WalletSessionService();
        ReflectionTestUtils.setField(service, "currentPost", current);
        ReflectionTestUtils.setField(service, "staffPostService", mock(StaffPostService.class));
        ReflectionTestUtils.setField(service, "storeServiceAccountPort", accounts);
        ReflectionTestUtils.setField(service, "identitySessionIssuePort", identity);
        ReflectionTestUtils.setField(service, "commands", new FranchiseCommands(log));
        ReflectionTestUtils.setField(service, "wechatAppId", "synthetic-app");
        var transactions = new TransactionInterceptor();
        transactions.setTransactionManager(new DataSourceTransactionManager(dataSource));
        transactions.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxy = new ProxyFactory(service);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(transactions);
        controller = new WalletSessionController();
        ReflectionTestUtils.setField(controller, "walletSessionService", proxy.getProxy());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("synthetic latch timeout");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    private HandlerInterceptor interceptor() {
        return new LegacyPlatformHttpWriteInterceptor(gate, true);
    }

    private MockMvc mvc(boolean responseAdvice) {
        return mvc(responseAdvice, interceptor(), null);
    }

    private MockMvc mvc(boolean responseAdvice, HandlerInterceptor selected,
                        org.springframework.http.converter.HttpMessageConverter<?> converter) {
        var errorLogs = mock(cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi.class);
        var builder = MockMvcBuilders.standaloneSetup(controller, new AController())
                .setControllerAdvice(responseAdvice
                        ? new Object[]{new GlobalExceptionHandler("g2a", errorLogs), new GlobalResponseBodyHandler()}
                        : new Object[]{new GlobalExceptionHandler("g2a", errorLogs)})
                .addInterceptors(selected == null ? new HandlerInterceptor[0] : new HandlerInterceptor[]{selected})
                .addInterceptors(new HandlerInterceptor() {
                    @Override public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                                          Object handler, Exception failure) {
                        completionException.set(failure);
                        completionCalls.incrementAndGet();
                    }
                });
        if (converter != null) builder.setMessageConverters(converter);
        return builder.build();
    }

    private MvcResult issue(MockMvc mvc) throws Exception {
        return mvc.perform(post("/platform/wallet/session").header("X-WX-Login-Code", "synthetic-code")
                .header("Host", "legacy-direct.invalid")).andReturn();
    }
    private int code(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString()).get("code").intValue();
    }
    private void rows(int accounts, int sessions, int audits) {
        assertEquals(accounts, observer.queryForObject("SELECT count(*) FROM synthetic_account", Integer.class));
        assertEquals(sessions, observer.queryForObject("SELECT count(*) FROM synthetic_session", Integer.class));
        assertEquals(audits, observer.queryForObject("SELECT count(*) FROM synthetic_audit", Integer.class));
    }
    private void unknownPending() {
        assertEquals(1, gate.snapshot().checkpoint().pending().size());
        assertTrue(gate.snapshot().checkpoint().pending().values().iterator().next().unknown());
    }

    @Test void drainingRejectsBeforeAccountExchangeSessionOrAudit() throws Exception {
        gate.apply(new Control(2, State.DRAINING, SCOPE), FreezeEvidence.missing());
        var result = issue(mvc(true));
        assertEquals(200, result.getResponse().getStatus());
        assertEquals(503, code(result));
        assertEquals(0, exchanges.get());
        rows(0, 0, 0);
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void normalReturnReleasesOnlyAfterActualCommit() throws Exception {
        assertEquals(0, code(issue(mvc(true))));
        rows(1, 1, 1);
        assertEquals(1, commitsWithPermit.get());
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void handledExchangeFailureReturnsHttp200ButKeepsUnknownPending() throws Exception {
        failExchange = true;
        var result = issue(mvc(true));
        assertEquals(200, result.getResponse().getStatus());
        assertNotEquals(0, code(result));
        assertEquals(1, completionCalls.get());
        assertNull(completionException.get());
        assertEquals(1, rollbacksWithPermit.get());
        rows(0, 0, 0);
        unknownPending();
    }

    @Test void handledFailureAfterSessionWriteRollsBackAllRowsAndKeepsPending() throws Exception {
        failAudit = true;
        var result = issue(mvc(true));
        assertEquals(200, result.getResponse().getStatus());
        assertEquals(503, code(result));
        assertEquals(1, completionCalls.get());
        assertNull(completionException.get());
        rows(0, 0, 0);
        unknownPending();
    }

    @Test void missingCommonResultEvidenceCannotReleaseEvenWhenHttp200() throws Exception {
        var result = issue(mvc(false));
        assertEquals(200, result.getResponse().getStatus());
        assertEquals(0, code(result));
        rows(1, 1, 1);
        unknownPending();
    }

    @Test void drainingDuringExchangeRejectsSecondRequestWhileOriginalCommits() throws Exception {
        exchangeEntered = new CountDownLatch(1);
        exchangeRelease = new CountDownLatch(1);
        var mvc = mvc(true);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var first = pool.submit(() -> issue(mvc));
            assertTrue(exchangeEntered.await(3, TimeUnit.SECONDS));
            rows(0, 0, 0);
            gate.apply(new Control(2, State.DRAINING, SCOPE), FreezeEvidence.missing());
            assertEquals(1, gate.snapshot().checkpoint().pending().size());
            assertEquals(503, code(issue(mvc)));
            assertEquals(1, exchanges.get());
            exchangeRelease.countDown();
            assertEquals(0, code(first.get(3, TimeUnit.SECONDS)));
            rows(1, 1, 1);
            assertEquals(1, commitsWithPermit.get());
            assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
        } finally {
            exchangeRelease.countDown();
            pool.shutdownNow();
        }
    }

    @Test void pendingSurvivesUntilCommitCallbackCompletes() throws Exception {
        commitEntered = new CountDownLatch(1);
        commitRelease = new CountDownLatch(1);
        var mvc = mvc(true);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var request = pool.submit(() -> issue(mvc));
            assertTrue(commitEntered.await(3, TimeUnit.SECONDS));
            rows(0, 0, 0);
            gate.apply(new Control(2, State.DRAINING, SCOPE), FreezeEvidence.missing());
            assertEquals(1, gate.snapshot().checkpoint().pending().size());
            commitRelease.countDown();
            assertEquals(0, code(request.get(3, TimeUnit.SECONDS)));
            rows(1, 1, 1);
            assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
        } finally {
            commitRelease.countDown();
            pool.shutdownNow();
        }
    }

    @Test void aHandlerDoesNotWaitForBCheckpointPersistence() throws Exception {
        store.entered = new CountDownLatch(1);
        store.release = new CountDownLatch(1);
        var mvc = mvc(true);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var blocked = pool.submit(() -> gate.enter(Owner.B_LEGACY, "franchise-http", "synthetic-block"));
            assertTrue(store.entered.await(3, TimeUnit.SECONDS));
            var result = pool.submit(() -> mvc.perform(get("/app-api/design/probe")).andReturn()).get(1, TimeUnit.SECONDS);
            assertEquals(0, code(result));
            store.release.countDown();
            blocked.get(3, TimeUnit.SECONDS).close();
        } finally {
            store.release.countDown();
            pool.shutdownNow();
        }
    }

    @Test void responseSerializationFailureAfterCommitKeepsPending() throws Exception {
        var module = new com.fasterxml.jackson.databind.module.SimpleModule();
        module.addSerializer(String.class, new com.fasterxml.jackson.databind.JsonSerializer<String>() {
            @Override public void serialize(String value, com.fasterxml.jackson.core.JsonGenerator output,
                                            com.fasterxml.jackson.databind.SerializerProvider provider) throws IOException {
                if (value.equals("synthetic-session")) throw new IOException("synthetic response failure");
                output.writeString(value);
            }
        });
        var converter = new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(
                JSON.copy().registerModule(module));
        issue(mvc(true, interceptor(), converter));
        rows(1, 1, 1);
        unknownPending();
    }

    @Test void notInstalledLeavesTheExistingBridgeBehaviorWithoutFreezeProof() throws Exception {
        configurationCase(Map.of(), false);
    }

    @Test void bridgeFalseDoesNotInstallTheLegacyInterceptor() throws Exception {
        configurationCase(Map.of("platform.legacy-b-write-control-directory", "synthetic-unused",
                "platform.legacy-design-bridge.enabled", "false"), false);
    }

    @Test void installedDefaultLegacyBridgeRegistersTheActualMvcInterceptor() throws Exception {
        configurationCase(Map.of("platform.legacy-b-write-control-directory", "synthetic-unused"), true);
    }

    private void configurationCase(Map<String, Object> properties, boolean installed) throws Exception {
        gate.apply(new Control(2, State.DRAINING, SCOPE), FreezeEvidence.missing());
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new org.springframework.core.env.MapPropertySource("synthetic", properties));
            context.registerBean(LegacyPlatformWriteGate.class, () -> gate);
            context.register(LegacyPlatformHttpWriteWebConfiguration.class);
            context.refresh();
            var registry = new TestRegistry();
            context.getBeansOfType(org.springframework.web.servlet.config.annotation.WebMvcConfigurer.class)
                    .values().forEach(configurer -> configurer.addInterceptors(registry));
            boolean registered = properties.containsKey("platform.legacy-b-write-control-directory");
            assertEquals(registered ? 1 : 0, registry.handlers().size());
            HandlerInterceptor selected = registered ? (HandlerInterceptor) registry.handlers().get(0) : null;
            assertEquals(installed ? 503 : 0, code(issue(mvc(true, selected, null))));
            rows(installed ? 0 : 1, installed ? 0 : 1, installed ? 0 : 1);
            assertThrows(IllegalStateException.class,
                    () -> gate.apply(new Control(3, State.FROZEN, SCOPE), FreezeEvidence.missing()));
        }
    }

    static class TestRegistry extends org.springframework.web.servlet.config.annotation.InterceptorRegistry {
        java.util.List<Object> handlers() { return getInterceptors(); }
    }

    @RestController
    static class AController {
        @GetMapping("/app-api/design/probe")
        public CommonResult<Map<String, String>> probe() { return CommonResult.success(Map.of("result", "A")); }
    }
}
