package cn.iocoder.yudao.server.split;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.framework.web.core.handler.GlobalResponseBodyHandler;
import cn.iocoder.yudao.module.design.controller.admin.ExportAuditAdminController;
import cn.iocoder.yudao.module.design.asset.ObjectStoragePort;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.DeliveryPort;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.ExportFilters;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.ExportJobRequest;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.JdbcDeliveryPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static cn.iocoder.yudao.server.split.LegacyPlatformWritePort.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * G2q 导出任务管理4入口（G2p交叉核验触发子集批）：真实 MVC + 真实 PG（platform/identity源码
 * 迁移）验证 ExportAuditAdminController 4写入口接入共用停写门禁（writer=outbox-export-privacy
 * 导出域扩展）。E4 downloadExportFile=ResponseEntity<byte[]>走CHANNEL_ACK（G2o PL2同型）。
 *
 * <p>承载分层：E1-E4全真实链（JdbcDeliveryPort(DataSource)单参轻量真实构造——export_job/
 * one_time_delivery_ticket真实行；storage替身供E4文件流）。design/infra生产代码零改动。
 */
class LegacyPlatformExportJobInterceptorTest {
    private static final String SCOPE = "c".repeat(64);
    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();

    private static final long OPERATOR = 7001L;
    private static final String CSV_CONTENT = "synthetic-export-csv-content";
    private static final String CSV_SHA256; // CSV_CONTENT 的 SHA-256（夹具预置校验）
    static {
        try {
            CSV_SHA256 = java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(CSV_CONTENT.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private final ApiErrorLogCommonApi errorLogs = mock(ApiErrorLogCommonApi.class);
    private final ObjectStoragePort storage = mock(ObjectStoragePort.class);
    private JdbcTemplate observer;
    private LegacyPlatformWriteGate gate;
    private LegacyPlatformWalletSessionInterceptorTest.MemoryStore store;
    private MockMvc exportMvc;
    private ExportAuditAdminController exportController;
    private String bearerToken;

    @BeforeAll static void startDatabase() {
        PG.start();
        var dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        // 版本序逐字执行源码迁移：platform 003（export_job/one_time_delivery_ticket）→identity 101（account）。
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/platform/V20260905.003__p1c_platform_tables.sql"),
                new ClassPathResource("db/migration/identity/V20260905.101__p2a_identity_access.sql"),
                new ClassPathResource("db/migration/identity/V20260909.102__independent_refresh_expiry.sql"),
                new ClassPathResource("db/migration/platform/V20261002.006__export_job_cancel.sql"))
                .execute(dataSource);
        new cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils(new WebProperties());
    }

    @AfterAll static void stopDatabase() { PG.stop(); }

    @AfterEach void securityCleanup() { SecurityContextHolder.clearContext(); }

    @BeforeEach void fixture() {
        var loginUser = new cn.iocoder.yudao.framework.security.core.LoginUser();
        loginUser.setId(OPERATOR);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null));
        var dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        observer = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        observer.execute("TRUNCATE export_job, one_time_delivery_ticket, one_time_download_ticket, account");
        observer.update("INSERT INTO account (id, status) VALUES (?, 'ACTIVE')", OPERATOR);
        store = new LegacyPlatformWalletSessionInterceptorTest.MemoryStore();
        gate = new LegacyPlatformWriteGate("g2q", SCOPE, Set.of(), Set.of("outbox-export-privacy"), store);
        gate.apply(new Control(1, State.OPEN, SCOPE), FreezeEvidence.missing());

        // E1-E4 全真实链：JdbcDeliveryPort(DataSource) 单参轻量真实构造。
        exportController = new ExportAuditAdminController();
        ReflectionTestUtils.setField(exportController, "deliveryPort",
                new JdbcDeliveryPort(dataSource));
        ReflectionTestUtils.setField(exportController, "dataSource", dataSource);
        ReflectionTestUtils.setField(exportController, "storage", storage);

        // E4 真实链会话：真实 UserSessionService 签发（SecurityFrameworkUtils + consume owner 双校验）。
        var transactions = new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
        var sessions = new cn.iocoder.yudao.module.identity.session.UserSessionService(dataSource, transactions);
        bearerToken = sessions.issue(OPERATOR, "synthetic-appid", "g2q-openid", false, null).accessToken();

        exportMvc = build(exportController);
    }

    private MockMvc build(Object controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new Object[]{new GlobalExceptionHandler("g2q", errorLogs),
                        new GlobalResponseBodyHandler()})
                .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON),
                        new ByteArrayHttpMessageConverter())
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

    // ---------- DRAINING：4入口 503 零写 ----------

    @Test void drainingRejectsAllFourExportEntriesWithoutAnyWrite() throws Exception {
        gate.apply(new Control(2, State.DRAINING, SCOPE), FreezeEvidence.missing());
        assertEquals(503, code(exportMvc.perform(post("/design/v1/export-jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"jobType\":\"AUDIT_EVENTS\"}")).andReturn()));
        assertEquals(503, code(exportMvc.perform(post("/design/v1/export-jobs/9001/cancellation")).andReturn()));
        assertEquals(503, code(exportMvc.perform(post("/design/v1/export-jobs/9001/download-tickets")).andReturn()));
        // E4 yudao 惯例：preHandle ServiceException 经 advice → HTTP 200 + code 503。
        assertEquals(503, code(exportMvc.perform(get("/design/v1/export-jobs/9001/content")
                .param("ticket", "g2q-ticket")
                .header("Authorization", "Bearer " + bearerToken)).andReturn()));
        // 零写。
        assertEquals(0, rows("export_job"));
        assertEquals(0, rows("one_time_delivery_ticket"));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    // ---------- OPEN：真实链深断言 ----------

    @Test void openCreateExportJobWritesRealPendingRow() throws Exception {
        assertEquals(0, code(exportMvc.perform(post("/design/v1/export-jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"jobType\":\"AUDIT_EVENTS\"}")).andReturn()));
        var job = observer.queryForMap("SELECT job_type, requester_type, requester_user_id, status "
                + "FROM export_job");
        assertEquals("AUDIT_EVENTS", job.get("job_type"));
        assertEquals("ADMIN", job.get("requester_type"));
        assertEquals(OPERATOR, ((Number) job.get("requester_user_id")).longValue());
        assertEquals("PENDING", job.get("status"));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void openCancelMarksPendingRowAndReleases() throws Exception {
        seedJob(9001L, "PENDING", null, null);
        assertEquals(0, code(exportMvc.perform(post("/design/v1/export-jobs/9001/cancellation")).andReturn()));
        assertEquals("true", observer.queryForObject(
                "SELECT cancel_requested::text FROM export_job WHERE id = 9001", String.class));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void cancelNonOwnedJobIsHandledRetainingUnknownPending() throws Exception {
        seedJob(9002L, "PENDING", null, null);
        // 本人校验：requester_user_id 与当前操作者不符 → AccessDenied（requireOwnedJob）。
        observer.update("UPDATE export_job SET requester_user_id = 9999 WHERE id = 9002");
        var result = exportMvc.perform(post("/design/v1/export-jobs/9002/cancellation")).andReturn();
        // AccessDeniedException → advice 专分支 → HTTP 200 + code 403（G2o 同款实证）。
        assertEquals(403, code(result));
        assertEquals("false", observer.queryForObject(
                "SELECT cancel_requested::text FROM export_job WHERE id = 9002", String.class));
        unknownPending("export-job-cancel");
    }

    @Test void openDownloadTicketIssuesRealTicketRowForCompletedJob() throws Exception {
        seedJob(9003L, "COMPLETED", "synthetic-file-key", CSV_SHA256);
        assertEquals(0, code(exportMvc.perform(post("/design/v1/export-jobs/9003/download-tickets")).andReturn()));
        // one_time_delivery_ticket 真实行级：票据签发（purpose/biz_ref/owner；表存 token_hash 非明文）。
        var ticket = observer.queryForMap(
                "SELECT purpose, biz_ref, owner_user_id FROM one_time_download_ticket");
        assertEquals("EXPORT_FILE", ticket.get("purpose"));
        assertEquals("9003", ticket.get("biz_ref"));
        assertEquals(OPERATOR, ((Number) ticket.get("owner_user_id")).longValue());
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    // ---------- E4 CHANNEL_ACK 双向断言（G2o PL2 同型） ----------

    @Test void e4DownloadChannelAckReleasesOnSuccessAndEscapesToUnknownOnConsumptionFailure() throws Exception {
        seedJob(9003L, "COMPLETED", "synthetic-file-key", CSV_SHA256);
        // 正向：票据真实签发（表存 token_hash 非明文——token 从 E3 真实响应体读取）+ storage 替身
        // 返回匹配内容 → 消费成功 → 200 CSV ResponseEntity → 释放。
        var ticketResult = exportMvc.perform(post("/design/v1/export-jobs/9003/download-tickets")).andReturn();
        assertEquals(0, code(ticketResult));
        var ticketToken = JSON.readTree(ticketResult.getResponse().getContentAsString())
                .get("data").get("ticket").asText();
        when(storage.getObject("synthetic-file-key"))
                .thenReturn(new ByteArrayInputStream(CSV_CONTENT.getBytes(StandardCharsets.UTF_8)));
        var ok = exportMvc.perform(get("/design/v1/export-jobs/9003/content")
                .param("ticket", ticketToken)
                .header("Authorization", "Bearer " + bearerToken)).andReturn();
        assertEquals(200, ok.getResponse().getStatus());
        assertEquals(CSV_CONTENT, ok.getResponse().getContentAsString());
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());

        // 反向：票据二次消费（已消费）→ AccessDenied 逃逸 → advice 产 CommonResult(code=500) →
        // 判别器 escaped → UNKNOWN 保留（宁多不漏）。
        var replay = exportMvc.perform(get("/design/v1/export-jobs/9003/content")
                .param("ticket", ticketToken)
                .header("Authorization", "Bearer " + bearerToken)).andReturn();
        assertEquals(403, code(replay));
        unknownPending("export-download");
    }

    private static void seedJob(long id, String status, String fileKey, String sha) {
        observerStatic().update("INSERT INTO export_job (id, job_type, requester_type, requester_user_id, "
                        + "status, file_asset_id, file_sha256, expires_at) VALUES (?, 'AUDIT_EVENTS', 'ADMIN', ?, ?, ?, ?, "
                        + "now() + interval '1 day')",
                id, OPERATOR, status, fileKey, sha);
    }

    private static JdbcTemplate observerStatic() {
        return new JdbcTemplate(new DriverManagerDataSource(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
    }

    // ---------- 白名单合同：4 入口精确 writer/operation（E4 CHANNEL_ACK Evidence 反射核验） ----------

    @Test void wiredExportHandlersEnterGateWithExactWriterAndOperation() throws Exception {
        var port = mock(LegacyPlatformWritePort.class);
        var permit = mock(Permit.class);
        when(port.enter(any(), any(), any())).thenReturn(permit);
        var selected = new LegacyPlatformHttpWriteInterceptor(port, true);
        var handlers = List.of(
                new HandlerMethod(exportController, ExportAuditAdminController.class.getDeclaredMethod(
                        "createExportJob", Map.class)),
                new HandlerMethod(exportController, ExportAuditAdminController.class.getDeclaredMethod(
                        "cancelExportJob", String.class)),
                new HandlerMethod(exportController, ExportAuditAdminController.class.getDeclaredMethod(
                        "createDownloadTicket", String.class)),
                new HandlerMethod(exportController, ExportAuditAdminController.class.getDeclaredMethod(
                        "downloadExportFile", String.class, String.class)));
        assertEquals(4, handlers.size());
        for (var handler : handlers) {
            assertTrue(selected.preHandle(new org.springframework.mock.web.MockHttpServletRequest(),
                    new org.springframework.mock.web.MockHttpServletResponse(), handler),
                    handler.getMethod().getName());
        }
        var captor = ArgumentCaptor.forClass(String.class);
        verify(port, times(4)).enter(eq(Owner.B_LEGACY), eq("outbox-export-privacy"), captor.capture());
        assertEquals(Set.of("export-job-create", "export-job-cancel", "export-download-ticket",
                "export-download"), Set.copyOf(captor.getAllValues()));
        // E4 CHANNEL_ACK Evidence 反射核验。
        var targetsField = LegacyPlatformHttpWriteInterceptor.class.getDeclaredField("targets");
        targetsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var targets = (Map<java.lang.reflect.Method, ?>) targetsField.get(selected);
        var evidenceMethod = targets.get(handlers.get(3).getMethod()).getClass().getDeclaredMethod("evidence");
        evidenceMethod.setAccessible(true);
        assertEquals("CHANNEL_ACK", String.valueOf(evidenceMethod.invoke(targets.get(handlers.get(3).getMethod()))));
    }

    // ---------- 装配矩阵（E1 代表） ----------

    @Test void bridgeFalseStillGuardsExportHandlers() throws Exception {
        configurationCase(Map.of("platform.legacy-b-write-control-directory", "synthetic-unused",
                "platform.legacy-design-bridge.enabled", "false"), true);
    }

    @Test void installedDefaultConfigurationGuardsExportHandlers() throws Exception {
        configurationCase(Map.of("platform.legacy-b-write-control-directory", "synthetic-unused"), true);
    }

    @Test void notInstalledLeavesExportBehaviorWithoutFreezeEvidence() throws Exception {
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
            var configuredMvc = MockMvcBuilders.standaloneSetup(exportController)
                    .setControllerAdvice(new Object[]{new GlobalExceptionHandler("g2q", errorLogs)})
                    .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON),
                            new ByteArrayHttpMessageConverter())
                    .addInterceptors(selected == null ? new HandlerInterceptor[0] : new HandlerInterceptor[]{selected})
                    .build();
            assertEquals(installed ? 503 : 0, code(configuredMvc.perform(post("/design/v1/export-jobs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"jobType\":\"AUDIT_EVENTS\"}")).andReturn()));
            // 装配：E1 拒绝零写；未装配：真实链 PENDING 行落库。
            assertEquals(installed ? 0 : 1, rows("export_job"));
            assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
            assertThrows(IllegalStateException.class,
                    () -> gate.apply(new Control(3, State.FROZEN, SCOPE), FreezeEvidence.missing()));
        }
    }

    // ---------- 非目标读直通（GET /design/v1/export-jobs 列表纯读） ----------

    @Test void nonTargetExportListDoesNotWaitForExportCheckpoint() throws Exception {
        store.entered = new CountDownLatch(1);
        store.release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var blocked = pool.submit(() -> gate.enter(Owner.B_LEGACY, "outbox-export-privacy", "synthetic-block"));
            assertTrue(store.entered.await(3, TimeUnit.SECONDS));
            var response = pool.submit(() -> {
                try {
                    return exportMvc.perform(get("/design/v1/export-jobs")).andReturn();
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
