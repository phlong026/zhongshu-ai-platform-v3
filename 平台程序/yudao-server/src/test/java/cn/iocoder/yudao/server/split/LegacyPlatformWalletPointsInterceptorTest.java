package cn.iocoder.yudao.server.split;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.framework.web.core.handler.GlobalResponseBodyHandler;
import cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils;
import cn.iocoder.yudao.module.commerce.controller.admin.PointAdminController;
import cn.iocoder.yudao.module.commerce.controller.app.AppPointController;
import cn.iocoder.yudao.module.commerce.adjustment.ManualPointAdjustmentService;
import cn.iocoder.yudao.module.commerce.points.PointAccountService;
import cn.iocoder.yudao.module.infra.zhongshu.api.AccountStatePort;
import cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort;
import cn.iocoder.yudao.module.infra.zhongshu.audit.AuditPort;
import cn.iocoder.yudao.module.identity.account.JdbcAccountStateAdapter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static cn.iocoder.yudao.server.split.LegacyPlatformWritePort.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * G2f commerce-wallet：真实 MVC + 真实 PG（identity/commerce 源码迁移）验证钱包 3 写入口
 * 接入共用停写门禁（writer=commerce-wallet）。commerce 模块生产代码零改动；
 * W1 写GET旁路已经独立探针真实复现（/tmp/zs-p3-g2f-bypass-probe-q5n8w2xe）。
 */
class LegacyPlatformWalletPointsInterceptorTest {
    private static final String SCOPE = "e".repeat(64);
    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    private static final long TARGET_USER = 7777L;

    private final ApiErrorLogCommonApi errorLogs = mock(ApiErrorLogCommonApi.class);
    private final AuditPort auditPort = mock(AuditPort.class);
    private JdbcTemplate observer;
    private LegacyPlatformWriteGate gate;
    private LegacyPlatformWalletSessionInterceptorTest.MemoryStore store;
    private MockMvc appMvc;
    private MockMvc adminMvc;
    private AppPointController pointController;
    private PointAdminController pointAdmin;
    private final AtomicInteger commitsWithPermit = new AtomicInteger();
    private final AtomicInteger rollbacksWithPermit = new AtomicInteger();
    private volatile CountDownLatch commitEntered;
    private volatile CountDownLatch commitRelease;

    @BeforeAll static void startDatabase() {
        PG.start();
        var dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        // 按版本序逐字执行：identity 101（account 守卫表）→ commerce 301（点账户/流水/调点单）→ 310（request_key）。
        // V20261003.310 与 xpay V20261005.310 日期前缀不同，归一化元组不同，无 Flyway 冲突。
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/identity/V20260905.101__p2a_identity_access.sql"),
                new ClassPathResource("db/migration/commerce/V20260905.301__p4a_pricing_points.sql"),
                new ClassPathResource("db/migration/commerce/V20261003.310__manual_adjustment_request_key.sql"))
                .execute(dataSource);
        new WebFrameworkUtils(new WebProperties());
    }

    @AfterAll static void stopDatabase() { PG.stop(); }

    @BeforeEach void fixture() {
        SecurityContextHolder.clearContext();
        var dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        observer = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        observer.execute("TRUNCATE design_point_account, design_point_ledger, manual_point_adjustment, account");
        observer.update("INSERT INTO account (id, status) VALUES (?, 'ACTIVE')", TARGET_USER);
        store = new LegacyPlatformWalletSessionInterceptorTest.MemoryStore();
        gate = new LegacyPlatformWriteGate("g2f", SCOPE, Set.of(), Set.of("legacy-session", "commerce-wallet"), store);
        gate.apply(new Control(1, State.OPEN, SCOPE), FreezeEvidence.missing());
        var transactions = counting(new DataSourceTransactionManager(dataSource));
        var points = new PointAccountService(dataSource, transactions);
        // 真实守卫适配器：MANUAL_CREDIT 走 identity account ACTIVE 校验（生产注入形态）。
        AccountStatePort guard = new JdbcAccountStateAdapter(dataSource);
        ReflectionTestUtils.setField(points, "accountStatePort", guard);
        var adjustments = new ManualPointAdjustmentService(dataSource, transactions, points, auditPort);

        pointController = new AppPointController();
        ReflectionTestUtils.setField(pointController, "pointAccountService", points);
        IdentitySessionPort stub = token -> Optional.of(
                new IdentitySessionPort.SessionContext(TARGET_USER, "synthetic-appid", "wallet-openid", false));
        ReflectionTestUtils.setField(pointController, "identitySessionPort", stub);
        ReflectionTestUtils.setField(pointController, "jdbcTemplate", new JdbcTemplate(dataSource));
        pointAdmin = new PointAdminController();
        ReflectionTestUtils.setField(pointAdmin, "pointAccountService", points);
        ReflectionTestUtils.setField(pointAdmin, "manualPointAdjustmentService", adjustments);

        // admin 与 app 控制器存在同路由 GET（/design/v1/point-ledger），生产靠 /admin-api 与 /app-api
        // 前缀分离；standalone 无前缀，拆双 MockMvc 规避歧义映射。
        appMvc = MockMvcBuilders.standaloneSetup(pointController,
                        new LegacyPlatformWalletSessionInterceptorTest.AController())
                .setControllerAdvice(new Object[]{new GlobalExceptionHandler("g2f", errorLogs), new GlobalResponseBodyHandler()})
                .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
                .addFilters(new cn.iocoder.yudao.framework.web.core.filter.CacheRequestBodyFilter())
                .addInterceptors(new LegacyPlatformHttpWriteInterceptor(gate, true))
                .build();
        adminMvc = MockMvcBuilders.standaloneSetup(pointAdmin)
                .setControllerAdvice(new Object[]{new GlobalExceptionHandler("g2f", errorLogs), new GlobalResponseBodyHandler()})
                .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
                .addFilters(new cn.iocoder.yudao.framework.web.core.filter.CacheRequestBodyFilter())
                .addInterceptors(new LegacyPlatformHttpWriteInterceptor(gate, true))
                .build();
    }

    @AfterEach void securityCleanup() { SecurityContextHolder.clearContext(); }

    /** 真实事务管理器代理：物理提交前可挂门闩；commit/rollback 按 pending 非空计数（G2d/E 口径）。 */
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

    /** 管理端身份：PointAdminController 经 SecurityFrameworkUtils 取操作员。 */
    private static void loginAsOperator(long userId) {
        var loginUser = new LoginUser();
        loginUser.setId(userId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null));
    }

    private MvcResult verb(String method, String url, String json) throws Exception {
        var request = switch (method) {
            case "PATCH" -> patch(url);
            case "DELETE" -> delete(url);
            default -> org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url);
        };
        if (json != null) request.contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json);
        return adminMvc.perform(request).andReturn();
    }

    private int code(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString()).get("code").intValue();
    }

    private int rows(String table) {
        return observer.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    // ---------- DRAINING：3入口逐一拒绝，全部表零写（per-entry 门禁覆盖） ----------

    @Test void drainingRejectsAllThreeWalletEntriesWithoutAnyWrite() throws Exception {
        gate.apply(new Control(2, State.DRAINING, SCOPE), FreezeEvidence.missing());
        assertEquals(503, code(appMvc.perform(get("/design/v1/point-account")
                .header("Authorization", "Bearer bogus")).andReturn()));
        assertEquals(503, code(verb("POST", "/design/v1/manual-point-adjustments",
                "{\"targetUserId\":7777,\"delta\":5,\"reason\":\"x\",\"requestKey\":\"k1\"}")));
        assertEquals(503, code(verb("POST", "/design/v1/manual-point-adjustments/9001/review-decisions",
                "{\"approve\":true,\"comment\":\"x\"}")));
        assertEquals(0, rows("design_point_account"));
        assertEquals(0, rows("manual_point_adjustment"));
        assertEquals(0, rows("design_point_ledger"));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    // ---------- 深断言 ----------

    @Test void openGetPointAccountWritesAccountRowForFirstTimeUser() throws Exception {
        assertEquals(0, rows("design_point_account"));
        var result = appMvc.perform(get("/design/v1/point-account")
                .header("Authorization", "Bearer probe-token")).andReturn();
        assertEquals(0, code(result));
        assertEquals(0, JSON.readTree(result.getResponse().getContentAsString())
                .get("data").get("availablePoints").intValue());
        assertEquals(1, rows("design_point_account"));
        // 幂等：二次 GET 不再新增
        appMvc.perform(get("/design/v1/point-account").header("Authorization", "Bearer probe-token")).andReturn();
        assertEquals(1, rows("design_point_account"));
        assertEquals(1, commitsWithPermit.get());
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void getPointAccountResponseFailureRetainsUnknownPending() throws Exception {
        // 代表性共用边界：写GET真实建行后响应证据缺失 → UNKNOWN 保留（宁多记不漏记）。
        var noAdvice = MockMvcBuilders.standaloneSetup(pointController)
                .setControllerAdvice(new Object[]{new GlobalExceptionHandler("g2f", errorLogs)})
                .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
                .addInterceptors(new LegacyPlatformHttpWriteInterceptor(gate, true))
                .build();
        assertEquals(0, code(noAdvice.perform(get("/design/v1/point-account")
                .header("Authorization", "Bearer probe-token")).andReturn()));
        assertEquals(1, rows("design_point_account"));
        assertEquals(1, gate.snapshot().checkpoint().pending().size());
        for (Work work : gate.snapshot().checkpoint().pending().values()) {
            assertEquals("commerce-wallet", work.writer());
            assertEquals("ensure-point-account", work.operation());
            assertTrue(work.unknown());
        }
    }

    @Test void openCreateAdjustmentExecutesRealLedgerAndAudit() throws Exception {
        loginAsOperator(1L);
        var result = verb("POST", "/design/v1/manual-point-adjustments",
                "{\"targetUserId\":7777,\"delta\":50,\"reason\":\"补偿\",\"requestKey\":\"synthetic-key-1\"}");
        assertEquals(0, code(result));
        assertEquals("EXECUTED", JSON.readTree(result.getResponse().getContentAsString())
                .get("data").get("status").asText());
        assertEquals(1, rows("manual_point_adjustment"));
        var adjustment = observer.queryForMap("SELECT * FROM manual_point_adjustment");
        assertEquals("EXECUTED", adjustment.get("status"));
        assertEquals(50L, ((Number) adjustment.get("delta")).longValue());
        assertNotNull(adjustment.get("ledger_id"));
        assertEquals(1, rows("design_point_ledger"));
        var ledger = observer.queryForMap("SELECT * FROM design_point_ledger");
        assertEquals("MANUAL_CREDIT", ledger.get("type"));
        assertEquals(50L, ((Number) ledger.get("delta")).longValue());
        assertEquals(50L, observer.queryForObject(
                "SELECT available_points FROM design_point_account WHERE user_id = ?", Long.class, TARGET_USER));
        verify(auditPort, times(1)).record(any());
        assertEquals(1, commitsWithPermit.get());
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void adjustmentRequestKeyReplayDoesNotDuplicate() throws Exception {
        // c31 附加要求：requestKey 幂等重放不重复（replayedAdjustment 既有路径）。
        loginAsOperator(1L);
        var body = "{\"targetUserId\":7777,\"delta\":5,\"reason\":\"补偿\",\"requestKey\":\"replay-key\"}";
        var first = verb("POST", "/design/v1/manual-point-adjustments", body);
        assertEquals(0, code(first));
        String firstId = JSON.readTree(first.getResponse().getContentAsString())
                .get("data").get("adjustmentId").asText();
        var replay = verb("POST", "/design/v1/manual-point-adjustments", body);
        assertEquals(0, code(replay));
        String replayId = JSON.readTree(replay.getResponse().getContentAsString())
                .get("data").get("adjustmentId").asText();
        assertEquals(firstId, replayId);
        assertEquals(1, rows("manual_point_adjustment"));
        assertEquals(1, rows("design_point_ledger"));
        assertEquals(5L, observer.queryForObject(
                "SELECT available_points FROM design_point_account WHERE user_id = ?", Long.class, TARGET_USER));
        // 重放请求的丢失插入在 permit 下回滚（DuplicateKey → tx 回滚 → 既有结果重读），不产生第二行。
        assertEquals(1, commitsWithPermit.get());
        assertEquals(1, rollbacksWithPermit.get());
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void openReviewApproveExecutesLedger() throws Exception {
        // 制单（SUBMITTED，maker=999）经服务级夹具建立；复核人 1000 ≠ 999 审批通过。
        var adjustments = (ManualPointAdjustmentService) org.springframework.test.util.ReflectionTestUtils
                .getField(pointAdmin, "manualPointAdjustmentService");
        long adjustmentId = adjustments.submit(TARGET_USER, 8, "复核链路", 999L);
        loginAsOperator(1000L);
        var result = verb("POST", "/design/v1/manual-point-adjustments/" + adjustmentId + "/review-decisions",
                "{\"approve\":true,\"comment\":\"同意\"}");
        assertEquals(0, code(result));
        assertEquals("EXECUTED", observer.queryForObject(
                "SELECT status FROM manual_point_adjustment WHERE id = ?", String.class, adjustmentId));
        assertEquals("MANUAL_CREDIT", observer.queryForObject(
                "SELECT type FROM design_point_ledger WHERE biz_id = ?",
                String.class, String.valueOf(adjustmentId)));
        assertEquals(8L, observer.queryForObject(
                "SELECT available_points FROM design_point_account WHERE user_id = ?", Long.class, TARGET_USER));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void sameMakerReviewIsHandledFailureRetainingUnknownPending() throws Exception {
        long adjustmentId = ((ManualPointAdjustmentService) org.springframework.test.util.ReflectionTestUtils
                .getField(pointAdmin, "manualPointAdjustmentService")).submit(TARGET_USER, 8, "复核链路", 999L);
        loginAsOperator(999L); // 复核人 = 制单人
        var result = verb("POST", "/design/v1/manual-point-adjustments/" + adjustmentId + "/review-decisions",
                "{\"approve\":true,\"comment\":\"self\"}");
        assertEquals(200, result.getResponse().getStatus());
        assertNotEquals(0, code(result));
        assertEquals("SUBMITTED", observer.queryForObject(
                "SELECT status FROM manual_point_adjustment WHERE id = ?", String.class, adjustmentId));
        assertEquals(0, rows("design_point_ledger"));
        assertEquals(1, gate.snapshot().checkpoint().pending().size());
        for (Work work : gate.snapshot().checkpoint().pending().values()) {
            assertEquals("commerce-wallet", work.writer());
            assertEquals("manual-adjustment-review", work.operation());
            assertTrue(work.unknown());
        }
    }

    // ---------- 提交前切 DRAINING / 非目标A ----------

    @Test void drainingBeforeCommitRetainsFirstPermitAndRejectsSecondWalletRequest() throws Exception {
        loginAsOperator(1L);
        commitEntered = new CountDownLatch(1);
        commitRelease = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            // SecurityContext 是 ThreadLocal：登录须发生在池内线程，否则 getLoginUserId() 拆箱 NPE。
            var first = pool.submit(() -> {
                loginAsOperator(1L);
                return verb("POST", "/design/v1/manual-point-adjustments",
                        "{\"targetUserId\":7777,\"delta\":3,\"reason\":\"x\",\"requestKey\":\"latch-key\"}");
            });
            assertTrue(commitEntered.await(3, TimeUnit.SECONDS));
            gate.apply(new Control(2, State.DRAINING, SCOPE), FreezeEvidence.missing());
            assertEquals(1, gate.snapshot().checkpoint().pending().size());
            assertEquals(503, code(verb("POST", "/design/v1/manual-point-adjustments",
                    "{\"targetUserId\":7777,\"delta\":3,\"reason\":\"x\",\"requestKey\":\"latch-key-2\"}")));
            commitRelease.countDown();
            assertEquals(0, code(first.get(3, TimeUnit.SECONDS)));
            assertEquals(1, rows("manual_point_adjustment"));
            assertEquals(1, commitsWithPermit.get());
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

    @Test void nonTargetLedgerGetDoesNotWaitForWalletCheckpoint() throws Exception {
        store.entered = new CountDownLatch(1);
        store.release = new CountDownLatch(1);
        var probeMvc = MockMvcBuilders.standaloneSetup(pointController)
                .setControllerAdvice(new Object[]{new GlobalExceptionHandler("g2f", errorLogs), new GlobalResponseBodyHandler()})
                .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
                .addInterceptors(new LegacyPlatformHttpWriteInterceptor(gate, true))
                .build();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var blocked = pool.submit(() -> gate.enter(Owner.B_LEGACY, "commerce-wallet", "synthetic-block"));
            assertTrue(store.entered.await(3, TimeUnit.SECONDS));
            // point-ledger GET 是纯读非目标：不等 B checkpoint。
            var response = pool.submit(() -> probeMvc.perform(get("/design/v1/point-ledger")
                    .header("Authorization", "Bearer probe-token")).andReturn()).get(1, TimeUnit.SECONDS);
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

    // ---------- 白名单合同：3入口精确 writer/operation ----------

    @Test void wiredWalletHandlersEnterGateWithExactWriterAndOperation() throws Exception {
        var port = mock(LegacyPlatformWritePort.class);
        var permit = mock(Permit.class);
        when(port.enter(any(), any(), any())).thenReturn(permit);
        var selected = new LegacyPlatformHttpWriteInterceptor(port, true);
        var handlerGet = new HandlerMethod(pointController,
                AppPointController.class.getDeclaredMethod("getPointAccount", String.class));
        var handlerAdjust = new HandlerMethod(pointAdmin,
                PointAdminController.class.getDeclaredMethod("createAdjustment", Map.class));
        var handlerReview = new HandlerMethod(pointAdmin,
                PointAdminController.class.getDeclaredMethod("reviewAdjustment", String.class, Map.class));
        for (var handler : List.of(handlerGet, handlerAdjust, handlerReview)) {
            assertTrue(selected.preHandle(new org.springframework.mock.web.MockHttpServletRequest(),
                    new org.springframework.mock.web.MockHttpServletResponse(), handler));
        }
        var captor = ArgumentCaptor.forClass(String.class);
        verify(port, times(3)).enter(eq(Owner.B_LEGACY), eq("commerce-wallet"), captor.capture());
        assertEquals(Set.of("ensure-point-account", "manual-point-adjustment", "manual-adjustment-review"),
                Set.copyOf(captor.getAllValues()));
    }

    // ---------- 装配矩阵（W2 admin + W1 app 代表） ----------

    @Test void bridgeFalseStillGuardsWalletHandlers() throws Exception {
        configurationCase(Map.of("platform.legacy-b-write-control-directory", "synthetic-unused",
                "platform.legacy-design-bridge.enabled", "false"), true);
    }

    @Test void installedDefaultConfigurationGuardsWalletHandlers() throws Exception {
        configurationCase(Map.of("platform.legacy-b-write-control-directory", "synthetic-unused"), true);
    }

    @Test void notInstalledLeavesWalletBehaviorWithoutFreezeEvidence() throws Exception {
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
            HandlerInterceptor[] interceptors = selected == null ? new HandlerInterceptor[0] : new HandlerInterceptor[]{selected};
            var appMvc = MockMvcBuilders.standaloneSetup(pointController)
                    .setControllerAdvice(new Object[]{new GlobalExceptionHandler("g2f", errorLogs)})
                    .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
                    .addInterceptors(interceptors)
                    .build();
            var adminMvc = MockMvcBuilders.standaloneSetup(pointAdmin)
                    .setControllerAdvice(new Object[]{new GlobalExceptionHandler("g2f", errorLogs)})
                    .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
                    .addInterceptors(interceptors)
                    .build();
            // bridge=false 只豁免钱包会话；commerce-wallet 入口无论开关一律受控。
            // 管理端请求取操作员来自 SecurityContext，未装配分支需真实登录身份。
            loginAsOperator(1L);
            assertEquals(installed ? 503 : 0, code(appMvc.perform(
                    get("/design/v1/point-account").header("Authorization", "Bearer probe-token")).andReturn()));
            assertEquals(installed ? 503 : 0, code(adminMvc.perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/design/v1/manual-point-adjustments")
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content("{\"targetUserId\":7777,\"delta\":1,\"reason\":\"x\",\"requestKey\":\"cfg-key\"}")).andReturn()));
            if (installed) {
                assertEquals(0, rows("design_point_account"));
                assertEquals(0, rows("manual_point_adjustment"));
            } else {
                assertEquals(1, rows("design_point_account"));
                assertEquals(1, rows("manual_point_adjustment"));
            }
            assertThrows(IllegalStateException.class,
                    () -> gate.apply(new Control(3, State.FROZEN, SCOPE), FreezeEvidence.missing()));
        }
    }
}
