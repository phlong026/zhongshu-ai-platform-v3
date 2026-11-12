package cn.iocoder.yudao.server.split;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.framework.web.core.handler.GlobalResponseBodyHandler;
import cn.iocoder.yudao.module.identity.controller.app.AppDataSubjectRequestController;
import cn.iocoder.yudao.module.identity.privacy.PrivacyService;
import cn.iocoder.yudao.server.controller.admin.AdminDataDeleteController;
import cn.iocoder.yudao.server.controller.admin.PrivacyAdminController;
import cn.iocoder.yudao.server.controller.app.AppPrivacyLifecycleController;
import cn.iocoder.yudao.server.privacy.PrivacyLifecycleService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.method.HandlerMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static cn.iocoder.yudao.server.split.LegacyPlatformWritePort.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * G2o 类别12 outbox-export-privacy（c31裁决：①②④⑤⑥批准+③改判PL1/PL2均接线）：
 * 真实 MVC + 真实 PG（platform/identity源码迁移）验证 7 写入口接入共用停写门禁。
 *
 * <p>承载分层（合同§5 v0.1⑤）：AD1 真实链（audit-event分支物理删除行级）+DS1 真实链
 * （identity PrivacyService(DataSource)单参轻量，account夹具+幂等重放）；
 * AD2/PA1/PL1/PL2 门禁语义 mock/替身承载——**PA1擦除流真实行业务覆盖引用既有
 * PrivacyLifecycleTest（真实PG Testcontainers：decide CLOSE_ACCOUNT全链+409守卫分支+
 * 并发死锁）**，覆盖边界=decision端点HTTP层门禁语义（本套件锁定）。W1/W2/W3 sweep为
 * 登记豁免不在本套件。privacy/identity/infra生产代码零改动。
 */
class LegacyPlatformOutboxPrivacyInterceptorTest {
    private static final String SCOPE = "b".repeat(64);
    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();

    private static final long ORG_USER = 7L;

    private final ApiErrorLogCommonApi errorLogs = mock(ApiErrorLogCommonApi.class);
    private final PrivacyLifecycleService lifecycle = mock(PrivacyLifecycleService.class);
    private final cn.iocoder.yudao.module.identity.privacy.PrivacyService appPrivacy =
            mock(cn.iocoder.yudao.module.identity.privacy.PrivacyService.class);
    private JdbcTemplate observer;
    private DataSource dataSource;
    private cn.iocoder.yudao.module.identity.session.UserSessionService sessions;
    private String bearerToken;
    private LegacyPlatformWriteGate gate;
    private LegacyPlatformWalletSessionInterceptorTest.MemoryStore store;
    private MockMvc adminDeleteMvc;
    private MockMvc privacyAdminMvc;
    private MockMvc appSubjectMvc;
    private MockMvc appLifecycleMvc;
    private AdminDataDeleteController adminDeleteController;
    private PrivacyAdminController privacyAdminController;
    private AppDataSubjectRequestController appSubjectController;
    private AppPrivacyLifecycleController appLifecycleController;

    @BeforeAll static void startDatabase() {
        PG.start();
        var dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        // 版本序逐字执行源码迁移：platform 001/002/003（audit_event/outbox_event/dispatcher_lease）
        // →identity 101（account）→design 206/213（data_subject_request/privacy_consent，G2i 夹具序列先例）。
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/platform/V20260905.001__zhongshu_baseline.sql"),
                new ClassPathResource("db/migration/platform/V20260905.002__zhongshu_baseline_probe_index.sql"),
                new ClassPathResource("db/migration/platform/V20260905.003__p1c_platform_tables.sql"),
                new ClassPathResource("db/migration/identity/V20260905.101__p2a_identity_access.sql"),
                new ClassPathResource("db/migration/design/V20260905.206__p7b_budget_message_privacy.sql"),
                new ClassPathResource("db/migration/identity/V20260909.102__independent_refresh_expiry.sql"),
                new ClassPathResource("db/migration/design/V20260913.213__privacy_request_execution.sql"))
                .execute(dataSource);
        new cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils(new WebProperties());
    }

    @AfterAll static void stopDatabase() { PG.stop(); }

    @BeforeEach void fixture() {
        // AD1 deleteInternal 读 SecurityFrameworkUtils.getLoginUserId（SecurityContextHolder，拆箱）。
        var loginUser = new cn.iocoder.yudao.framework.security.core.LoginUser();
        loginUser.setId(7001L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null));
        dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        observer = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        observer.execute("TRUNCATE audit_event, outbox_event, dispatcher_lease, data_subject_request, "
                + "privacy_consent, user_session, design_access_grant, account");
        // DS1/PL 真实链夹具：ACTIVE 账号（PrivacyService FOR UPDATE 校验）+ 真实会话
        // （AppPrivacyLifecycleController.user() 经 UserSessionService.validateAccessToken）。
        observer.update("INSERT INTO account (id, status) VALUES (?, 'ACTIVE')", ORG_USER);
        var transactions = new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
        sessions = new cn.iocoder.yudao.module.identity.session.UserSessionService(dataSource, transactions);
        bearerToken = sessions.issue(ORG_USER, "synthetic-appid", "g2o-openid", false, null).accessToken();
        store = new LegacyPlatformWalletSessionInterceptorTest.MemoryStore();
        gate = new LegacyPlatformWriteGate("g2o", SCOPE, Set.of(), Set.of("outbox-export-privacy"), store);
        gate.apply(new Control(1, State.OPEN, SCOPE), FreezeEvidence.missing());

        // AD1 真实链：controller dataSource 字段真实注入（audit-event 分支 JdbcTemplate 直写）；
        // auditPort 替身（审计写面）+ 真实 userSessionService（account 分支 revoke 链，G2d 模式）。
        adminDeleteController = new AdminDataDeleteController();
        ReflectionTestUtils.setField(adminDeleteController, "dataSource", dataSource);
        ReflectionTestUtils.setField(adminDeleteController, "auditPort",
                mock(cn.iocoder.yudao.module.infra.zhongshu.audit.AuditPort.class));
        ReflectionTestUtils.setField(adminDeleteController, "userSessionService", sessions);

        // PA1：PrivacyLifecycleService 替身（擦除流真实行业务覆盖=既有 PrivacyLifecycleTest，
        // 合同§5 v0.1⑤登记）；decision 端点门禁语义由本套件锁定。
        privacyAdminController = new PrivacyAdminController(lifecycle);

        // DS1 真实链：identity PrivacyService(DataSource) 单参轻量真实构造；
        // 会话校验经真实 UserSessionService（sessionService 字段，G2d 模式）。
        appSubjectController = new AppDataSubjectRequestController();
        ReflectionTestUtils.setField(appSubjectController, "privacyService",
                new cn.iocoder.yudao.module.identity.privacy.PrivacyService(dataSource));
        ReflectionTestUtils.setField(appSubjectController, "sessionService", sessions);

        // PL1/PL2：lifecycle 替身（门禁语义承载；取回行为真实覆盖=PrivacyLifecycleTest）；
        // 真实 UserSessionService（user() 校验链，G2d 模式）。
        appLifecycleController = new AppPrivacyLifecycleController(
                mock(cn.iocoder.yudao.module.identity.privacy.PrivacyService.class),
                lifecycle, sessions);

        adminDeleteMvc = build(adminDeleteController);
        privacyAdminMvc = build(privacyAdminController);
        appSubjectMvc = build(appSubjectController);
        appLifecycleMvc = build(appLifecycleController);
    }

    private MockMvc build(Object controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new Object[]{new GlobalExceptionHandler("g2o", errorLogs),
                        new GlobalResponseBodyHandler()})
                .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON),
                        new org.springframework.http.converter.ByteArrayHttpMessageConverter())
                .addFilters(new cn.iocoder.yudao.framework.web.core.filter.CacheRequestBodyFilter())
                .addInterceptors(new LegacyPlatformHttpWriteInterceptor(gate, true))
                .build();
    }

    private int code(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString()).get("code").intValue();
    }

    private int rows(String table) {
        Integer n = observer.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }

    private void unknownPending(String operation) {
        assertEquals(1, gate.snapshot().checkpoint().pending().size());
        for (Work work : gate.snapshot().checkpoint().pending().values()) {
            assertEquals("outbox-export-privacy", work.writer());
            assertEquals(operation, work.operation());
            assertTrue(work.unknown());
        }
    }

    // ---------- DRAINING：7入口 503 零写（PL2 CHANNEL_ACK 形态同理 503） ----------

    @Test void drainingRejectsAllSevenPrivacyOutboxEntriesWithoutAnyWrite() throws Exception {
        gate.apply(new Control(2, State.DRAINING, SCOPE), FreezeEvidence.missing());
        assertEquals(503, code(adminDeleteMvc.perform(
                delete("/design/v1/admin-data/audit-event/9001")).andReturn()));
        assertEquals(503, code(adminDeleteMvc.perform(post("/design/v1/admin-data/batch-deletes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"audit-event\",\"ids\":[9001]}")).andReturn()));
        assertEquals(503, code(privacyAdminMvc.perform(post("/design/v1/privacy-requests/9001/decision")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"approve\":true,\"reason\":\"synthetic\"}")).andReturn()));
        assertEquals(503, code(appSubjectMvc.perform(post("/design/v1/data-export-requests")
                .header("Authorization", "Bearer " + bearerToken)).andReturn()));
        assertEquals(503, code(appSubjectMvc.perform(post("/design/v1/account-closure-requests")
                .header("Authorization", "Bearer " + bearerToken)).andReturn()));
        assertEquals(503, code(appLifecycleMvc.perform(post("/design/v1/privacy-requests/9001/download-tickets")
                .header("Authorization", "Bearer " + bearerToken)).andReturn()));
        // yudao 惯例：preHandle ServiceException 经 advice → HTTP 200 + code 503。
        assertEquals(503, code(appLifecycleMvc.perform(get("/design/v1/privacy-requests/9001/content")
                .param("ticket", "g2o-ticket")
                .header("Authorization", "Bearer " + bearerToken)).andReturn()));
        // 零写：审计/请求/账号全零变化。
        assertEquals(0, rows("audit_event"));
        assertEquals(0, rows("data_subject_request"));
        assertEquals(1, rows("account"));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    // ---------- AD1 OPEN 真实链深断言（audit-event 物理删除） ----------

    @Test void openAdminDeletePhysicallyRemovesAuditEventRow() throws Exception {
        observer.update("INSERT INTO audit_event (id, event_type, actor_type, action, biz_type, biz_id, result) "
                + "VALUES (9001, 'SYNTHETIC', 'ADMIN', 'DELETE', 'synthetic', '9001', 'SUCCESS')");
        assertEquals(0, code(adminDeleteMvc.perform(
                delete("/design/v1/admin-data/audit-event/9001")).andReturn()));
        // 物理删除行级断言（deleteInternal audit-event 分支 DELETE 直写）。
        assertEquals(0, rows("audit_event"));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void adminDeleteUnsupportedTypeIsHandledRetainingUnknownPending() throws Exception {
        var result = adminDeleteMvc.perform(
                delete("/design/v1/admin-data/not-a-type/9001")).andReturn();
        // yudao 惯例：业务异常 HTTP 200 + CommonResult.code=500（defaultExceptionHandler）。
        assertEquals(500, code(result));
        assertEquals(0, rows("audit_event"));
        unknownPending("admin-data-delete");
    }

    // ---------- DS1 OPEN 真实链深断言（PENDING 行 + 幂等重放零重复） ----------

    @Test void openDataExportRequestCreatesPendingRowIdempotently() throws Exception {
        assertEquals(0, code(appSubjectMvc.perform(post("/design/v1/data-export-requests")
                .header("Authorization", "Bearer " + bearerToken)).andReturn()));
        var request = observer.queryForMap(
                "SELECT user_id, request_type, status FROM data_subject_request");
        assertEquals(ORG_USER, ((Number) request.get("user_id")).longValue());
        assertEquals("EXPORT", request.get("request_type"));
        assertEquals("PENDING", request.get("status"));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
        // 幂等重放：同类型未完结 → 返回既有请求零新行（createSubjectRequest 幂等语义）。
        assertEquals(0, code(appSubjectMvc.perform(post("/design/v1/data-export-requests")
                .header("Authorization", "Bearer " + bearerToken)).andReturn()));
        assertEquals(1, rows("data_subject_request"));
    }

    // ---------- PL2 CHANNEL_ACK 行为断言（有意 5xx ACK 释放+warn，G2h 机制） ----------

    @Test void pl2DownloadChannelAckReleasesOnSuccessAndEscapesToUnknownOnException() throws Exception {
        // 正向：mock 正常返回 → 200 ResponseEntity（byte[] 需 ByteArray converter，build() 已对齐生产）。
        when(lifecycle.download(anyLong(), anyLong(), anyString()))
                .thenReturn(new byte[]{1});
        var ok = appLifecycleMvc.perform(get("/design/v1/privacy-requests/9001/content")
                .param("ticket", "g2o-ticket")
                .header("Authorization", "Bearer " + bearerToken)).andReturn();
        assertEquals(200, ok.getResponse().getStatus());
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
        // 反向：service 替身抛异常逃逸 → advice 产 CommonResult(code=500) → 判别器 escaped → UNKNOWN 保留。
        when(lifecycle.download(anyLong(), anyLong(), anyString()))
                .thenThrow(new RuntimeException("synthetic download failure"));
        var failed = appLifecycleMvc.perform(get("/design/v1/privacy-requests/9001/content")
                .param("ticket", "g2o-ticket")
                .header("Authorization", "Bearer " + bearerToken)).andReturn();
        assertEquals(500, code(failed));
        unknownPending("privacy-download");
    }

    // ---------- 白名单合同：7 入口精确 writer/operation（PL2 CHANNEL_ACK Evidence） ----------

    @Test void wiredPrivacyOutboxHandlersEnterGateWithExactWriterAndOperation() throws Exception {
        var port = mock(LegacyPlatformWritePort.class);
        var permit = mock(Permit.class);
        when(port.enter(any(), any(), any())).thenReturn(permit);
        var selected = new LegacyPlatformHttpWriteInterceptor(port, true);
        var handlers = List.of(
                new HandlerMethod(adminDeleteController, AdminDataDeleteController.class.getDeclaredMethod(
                        "deleteOne", String.class, String.class)),
                new HandlerMethod(adminDeleteController, AdminDataDeleteController.class.getDeclaredMethod(
                        "deleteBatch", Map.class)),
                new HandlerMethod(privacyAdminController, PrivacyAdminController.class.getDeclaredMethod(
                        "decide", long.class, PrivacyAdminController.Decision.class)),
                new HandlerMethod(appSubjectController, AppDataSubjectRequestController.class.getDeclaredMethod(
                        "createDataExportRequest", String.class)),
                new HandlerMethod(appSubjectController, AppDataSubjectRequestController.class.getDeclaredMethod(
                        "createAccountClosureRequest", String.class)),
                new HandlerMethod(appLifecycleController, AppPrivacyLifecycleController.class.getDeclaredMethod(
                        "ticket", long.class, String.class)),
                new HandlerMethod(appLifecycleController, AppPrivacyLifecycleController.class.getDeclaredMethod(
                        "download", long.class, String.class, String.class)));
        assertEquals(7, handlers.size());
        for (var handler : handlers) {
            assertTrue(selected.preHandle(new org.springframework.mock.web.MockHttpServletRequest(),
                    new org.springframework.mock.web.MockHttpServletResponse(), handler),
                    handler.getMethod().getName());
        }
        var captor = ArgumentCaptor.forClass(String.class);
        verify(port, times(7)).enter(eq(Owner.B_LEGACY), eq("outbox-export-privacy"), captor.capture());
        assertEquals(Set.of("admin-data-delete", "admin-data-delete-batch",
                "privacy-request-decision", "data-export-request", "account-closure-request",
                "privacy-download-ticket", "privacy-download"), Set.copyOf(captor.getAllValues()));
        // PL2 CHANNEL_ACK Evidence 反射核验（WriteTarget 内部合同，行为断言由 PL2 双向用例锁定）。
        var targetsField = LegacyPlatformHttpWriteInterceptor.class.getDeclaredField("targets");
        targetsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var targets = (Map<java.lang.reflect.Method, ?>) targetsField.get(selected);
        var downloadTarget = targets.get(handlers.get(6).getMethod());
        var evidenceMethod = downloadTarget.getClass().getDeclaredMethod("evidence");
        evidenceMethod.setAccessible(true);
        assertEquals("CHANNEL_ACK", String.valueOf(evidenceMethod.invoke(downloadTarget)));
    }

    // ---------- 装配矩阵（AD1 代表） ----------

    @Test void bridgeFalseStillGuardsPrivacyOutboxHandlers() throws Exception {
        configurationCase(Map.of("platform.legacy-b-write-control-directory", "synthetic-unused",
                "platform.legacy-design-bridge.enabled", "false"), true);
    }

    @Test void installedDefaultConfigurationGuardsPrivacyOutboxHandlers() throws Exception {
        configurationCase(Map.of("platform.legacy-b-write-control-directory", "synthetic-unused"), true);
    }

    @Test void notInstalledLeavesPrivacyOutboxBehaviorWithoutFreezeEvidence() throws Exception {
        configurationCase(Map.of(), false);
    }

    private void configurationCase(Map<String, Object> properties, boolean installed) throws Exception {
        gate.apply(new Control(2, State.DRAINING, SCOPE), FreezeEvidence.missing());
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new org.springframework.core.env.MapPropertySource("synthetic", properties));
            context.registerBean(LegacyPlatformWriteGate.class, () -> gate);
            context.register(LegacyPlatformHttpWriteWebConfiguration.class);
            context.refresh();
            var registry = new LegacyPlatformWalletSessionInterceptorTest.TestRegistry();
            context.getBeansOfType(WebMvcConfigurer.class).values().forEach(value -> value.addInterceptors(registry));
            assertEquals(installed ? 1 : 0, registry.handlers().size());
            var selected = registry.handlers().isEmpty() ? null : (HandlerInterceptor) registry.handlers().get(0);
            var configuredMvc = MockMvcBuilders.standaloneSetup(adminDeleteController)
                    .setControllerAdvice(new Object[]{new GlobalExceptionHandler("g2o", errorLogs)})
                    .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON),
                        new org.springframework.http.converter.ByteArrayHttpMessageConverter())
                    .addInterceptors(selected == null ? new HandlerInterceptor[0] : new HandlerInterceptor[]{selected})
                    .build();
            assertEquals(installed ? 503 : 0, code(configuredMvc.perform(
                    delete("/design/v1/admin-data/audit-event/9001")).andReturn()));
            // 装配：AD1 拒绝零写；未装配：audit-event 不存在行 DELETE 返回 false 仍 code 0（原行为）。
            assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
            assertThrows(IllegalStateException.class,
                    () -> gate.apply(new Control(3, State.FROZEN, SCOPE), FreezeEvidence.missing()));
        }
    }

    // ---------- 非目标读直通（GET /design/v1/privacy-requests 列表纯读） ----------

    @Test void nonTargetPrivacyListDoesNotWaitForOutboxPrivacyCheckpoint() throws Exception {
        store.entered = new CountDownLatch(1);
        store.release = new CountDownLatch(1);
        when(lifecycle.pageRequests(anyInt(), anyInt()))
                .thenReturn(new cn.iocoder.yudao.framework.common.pojo.PageResult<>(List.of(), 0L));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var blocked = pool.submit(() -> gate.enter(Owner.B_LEGACY, "outbox-export-privacy", "synthetic-block"));
            assertTrue(store.entered.await(3, TimeUnit.SECONDS));
            var response = pool.submit(() -> {
                try {
                    return privacyAdminMvc.perform(get("/design/v1/privacy-requests")).andReturn();
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }).get(2, TimeUnit.SECONDS);
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
