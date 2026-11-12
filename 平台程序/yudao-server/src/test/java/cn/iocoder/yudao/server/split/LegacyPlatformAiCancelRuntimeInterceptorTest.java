package cn.iocoder.yudao.server.split;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.framework.web.core.handler.GlobalResponseBodyHandler;
import cn.iocoder.yudao.module.aiorchestration.controller.app.AppAiJobController;
import cn.iocoder.yudao.module.aiorchestration.controller.internal.AiJobInternalController;
import cn.iocoder.yudao.module.aiorchestration.controller.internal.InternalSignatureVerifier;
import cn.iocoder.yudao.module.aiorchestration.controller.internal.vo.InternalClaimReqVO;
import cn.iocoder.yudao.module.aiorchestration.job.AiJobOrchestrationService;
import cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static cn.iocoder.yudao.server.split.LegacyPlatformWritePort.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyByte;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * G2m 类别9/10 ai-cancel-runtime（方案B）：真实 MVC + 真实 PG（ai-orchestration 源码迁移）验证
 * J1 /claims（Runtime 新领取门禁）与 C1 cancellation-requests（用户取消写门禁）接入共用停写门禁。
 *
 * <p>承载分层（合同§5，c31⑤批准）：J1 真实 claim SQL 链（真实 ai_job 夹具行 + 真实
 * AiJobOrchestrationService），InternalSignatureVerifier 替身（pass-through）；C1 真实取消链 +
 * IdentitySessionPort 替身（default 方法链须显式 stub，G2l 教训）。J2-J8 豁免面与 worker 收口轮
 * 不在本套件（登记制，G2j 判例）。ai-orchestration/commerce/identity 生产代码零改动。
 */
class LegacyPlatformAiCancelRuntimeInterceptorTest {
    private static final String SCOPE = "9".repeat(64);
    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();

    private static final long JOB_ID = 8601L;
    private static final long CANCEL_JOB_ID = 8602L;
    private static final long OWNER_ID = 7001L;

    private final ApiErrorLogCommonApi errorLogs = mock(ApiErrorLogCommonApi.class);
    private final InternalSignatureVerifier signatureVerifier = mock(InternalSignatureVerifier.class);
    private final IdentitySessionPort identitySessionPort = mock(IdentitySessionPort.class);
    private JdbcTemplate observer;
    private LegacyPlatformWriteGate gate;
    private LegacyPlatformWalletSessionInterceptorTest.MemoryStore store;
    private MockMvc internalMvc;
    private MockMvc appMvc;
    private AiJobInternalController internalController;
    private AppAiJobController appController;
    private AiJobOrchestrationService orchestration;

    @BeforeAll static void startDatabase() {
        PG.start();
        var dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        // 版本序逐字执行 ai-orchestration 源码迁移（不含 940：B 闭包外，与旧融合库一致）。
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/ai-orchestration/V20260905.401__p4b_ai_job.sql"),
                new ClassPathResource("db/migration/ai-orchestration/V20260905.402__p4c_task_charge.sql"),
                new ClassPathResource("db/migration/ai-orchestration/V20260913.403__runtime_completion_and_validation.sql"),
                new ClassPathResource("db/migration/ai-orchestration/V20261010.404__job_prompt_record.sql"),
                new ClassPathResource("db/migration/ai-orchestration/V20261012.510__runtime_call_trace.sql"))
                .execute(dataSource);
        // 402b/403b 含 dollar-quote DO 块（populator 无法切分，G2d 106 同型）：
        // 整文件原内容经 pg JDBC 单次 execute（后端解析，$$ quoting 由后端处理）。
        var mixedScript = new JdbcTemplate(dataSource);
        for (String path : new String[] {
                "db/migration/ai-orchestration/V20261003.402__prompt_pricing_version.sql",
                "db/migration/ai-orchestration/V20261003.403__job_request_scope.sql"}) {
            try (var input = new ClassPathResource(path).getInputStream()) {
                mixedScript.execute(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception failure) {
                throw new IllegalStateException(path + " migration failed", failure);
            }
        }
        new cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils(new WebProperties());
    }

    @AfterAll static void stopDatabase() { PG.stop(); }

    @AfterEach void securityCleanup() { SecurityContextHolder.clearContext(); }

    @BeforeEach void fixture() {
        var dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        observer = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        observer.execute("TRUNCATE ai_job, ai_job_attempt, ai_job_result, ai_job_settlement");
        seedAiJobs(observer);
        store = new LegacyPlatformWalletSessionInterceptorTest.MemoryStore();
        gate = new LegacyPlatformWriteGate("g2m", SCOPE, Set.of(), Set.of("ai-cancel-runtime"), store);
        gate.apply(new Control(1, State.OPEN, SCOPE), FreezeEvidence.missing());

        var transactions = new DataSourceTransactionManager(dataSource);
        orchestration = new AiJobOrchestrationService(dataSource, transactions,
                mock(cn.iocoder.yudao.module.infra.zhongshu.event.ReliableEventPort.class),
                mock(cn.iocoder.yudao.module.infra.zhongshu.api.QuarantineObjectPort.class),
                mock(cn.iocoder.yudao.module.infra.zhongshu.api.ContentScanPort.class));

        // J1：真实 orchestration（claim SQL 链）+ 签名替身 pass-through（c31⑤批准层级）。
        // settlementService 字段不注入（J1 路径不触计费；豁免面 J3/J4 不在本套件）。
        internalController = new AiJobInternalController();
        ReflectionTestUtils.setField(internalController, "orchestrationService", orchestration);
        ReflectionTestUtils.setField(internalController, "signatureVerifier", signatureVerifier);
        ReflectionTestUtils.setField(internalController, "providerCode", "g2m-test");
        when(signatureVerifier.verify(anyString(), anyString(), anyString(), anyString(), any())).thenReturn(true);

        // C1：真实 orchestration（取消链）+ IdentitySessionPort 替身（default 方法链显式 stub）。
        appController = new AppAiJobController();
        ReflectionTestUtils.setField(appController, "orchestrationService", orchestration);
        ReflectionTestUtils.setField(appController, "identitySessionPort", identitySessionPort);
        when(identitySessionPort.requireUnrestricted("g2m-bearer")).thenReturn(
                new IdentitySessionPort.SessionContext(OWNER_ID, "synthetic-appid", "synthetic-openid", false));

        internalMvc = build(internalController);
        appMvc = build(appController);
    }

    /** 夹具任务：8601=QUEUED（J1 领取深断言）；8602=RUNNING 持有效租约（C1 取消深断言，owner=7001）。 */
    private static void seedAiJobs(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO ai_job (id, user_id, project_ref, phase, status, requested_count, "
                        + "output_prefix, tenant_id) VALUES (?, ?, 'synthetic-project', 'FLAT', 'QUEUED', 2, "
                        + "'synthetic/outputs', 0)", JOB_ID, OWNER_ID);
        jdbc.update("INSERT INTO ai_job (id, user_id, project_ref, phase, status, requested_count, "
                        + "output_prefix, claimed_by, claim_expires_at, tenant_id) VALUES (?, ?, "
                        + "'synthetic-project', 'FLAT', 'RUNNING', 2, 'synthetic/outputs', 'synthetic-worker', "
                        + "now() + interval '5 minutes', 0)", CANCEL_JOB_ID, OWNER_ID);
    }

    private MockMvc build(Object controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new Object[]{new GlobalExceptionHandler("g2m", errorLogs),
                        new GlobalResponseBodyHandler()})
                .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
                .addFilters(new cn.iocoder.yudao.framework.web.core.filter.CacheRequestBodyFilter())
                .addInterceptors(new LegacyPlatformHttpWriteInterceptor(gate, true))
                .build();
    }

    private MvcResult claim(MockMvc mvc) throws Exception {
        // 生产 Runtime 形态：携带签名头（verifier 替身 pass-through 校验）。
        return mvc.perform(post("/internal-api/design/v1/ai-jobs/claims")
                .header("X-ZS-Timestamp", "g2m-timestamp")
                .header("X-ZS-Signature", "g2m-signature")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workerId\":\"g2m-worker\",\"maxJobs\":1}")).andReturn();
    }

    private MvcResult cancel(MockMvc mvc, long jobId) throws Exception {
        return mvc.perform(post("/design/v1/ai-jobs/" + jobId + "/cancellation-requests")
                .header("Authorization", "Bearer g2m-bearer")).andReturn();
    }

    private int code(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString()).get("code").intValue();
    }

    private void unknownPending(String operation) {
        assertEquals(1, gate.snapshot().checkpoint().pending().size());
        for (Work work : gate.snapshot().checkpoint().pending().values()) {
            assertEquals("ai-cancel-runtime", work.writer());
            assertEquals(operation, work.operation());
            assertTrue(work.unknown());
        }
    }

    // ---------- DRAINING：J1/C1 503 零写（c31 窄审核点①前置） ----------

    @Test void drainingRejectsClaimAndCancellationWithoutAnyWrite() throws Exception {
        gate.apply(new Control(2, State.DRAINING, SCOPE), FreezeEvidence.missing());
        assertEquals(503, code(claim(internalMvc)));
        assertEquals(503, code(cancel(appMvc, CANCEL_JOB_ID)));
        // 零写：任务行数与状态保持夹具原值。
        assertEquals(2, observer.queryForObject("SELECT COUNT(*) FROM ai_job", Integer.class));
        assertEquals("QUEUED", observer.queryForObject(
                "SELECT status FROM ai_job WHERE id = ?", String.class, JOB_ID));
        assertEquals("RUNNING", observer.queryForObject(
                "SELECT status FROM ai_job WHERE id = ?", String.class, CANCEL_JOB_ID));
        assertEquals(0, observer.queryForObject("SELECT COUNT(*) FROM ai_job_attempt", Integer.class));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    // ---------- J1 OPEN：真实 claim SQL 链深断言（c31 窄审核点①） ----------

    @Test void openClaimTransitionsQueuedJobToRunningWithLeaseAndFencingToken() throws Exception {
        assertEquals(0, code(claim(internalMvc)));
        // ai_job 行级：QUEUED→RUNNING + claimed_by + 租约 + fencing_token 递增。
        var job = observer.queryForMap("SELECT status, claimed_by, claim_expires_at, fencing_token "
                + "FROM ai_job WHERE id = ?", JOB_ID);
        assertEquals("RUNNING", job.get("status"));
        assertEquals("g2m-worker", job.get("claimed_by"));
        assertNotNull(job.get("claim_expires_at"));
        assertEquals(1L, ((Number) job.get("fencing_token")).longValue());
        // ai_job_attempt 行级：首次尝试注册（worker@provider + fencing）。
        var attempt = observer.queryForMap("SELECT attempt_no, worker_id, fencing_token "
                + "FROM ai_job_attempt WHERE job_id = ?", JOB_ID);
        assertEquals(1, ((Number) attempt.get("attempt_no")).intValue());
        assertEquals("g2m-worker@g2m-test", attempt.get("worker_id"));
        assertEquals(1L, ((Number) attempt.get("fencing_token")).longValue());
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    // ---------- C1 OPEN：真实取消链深断言（c31 窄审核点①） ----------

    @Test void openCancellationWritesCancelRequestedWithDecisionSeq() throws Exception {
        assertEquals(0, code(cancel(appMvc, CANCEL_JOB_ID)));
        var job = observer.queryForMap("SELECT status, cancel_seq, decision_seq, cancel_requested_at "
                + "FROM ai_job WHERE id = ?", CANCEL_JOB_ID);
        assertEquals("CANCEL_REQUESTED", job.get("status"));
        assertEquals(1L, ((Number) job.get("cancel_seq")).longValue());
        assertEquals(1L, ((Number) job.get("decision_seq")).longValue());
        assertNotNull(job.get("cancel_requested_at"));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void cancellationForNonOwnerIsHandledRetainingUnknownPending() throws Exception {
        when(identitySessionPort.requireUnrestricted("g2m-bearer")).thenReturn(
                new IdentitySessionPort.SessionContext(9999L, "synthetic-appid", "synthetic-openid", false));
        var result = cancel(appMvc, CANCEL_JOB_ID);
        assertEquals(ZhongshuErrorCodeConstants.RESOURCE_FORBIDDEN.getCode(), code(result));
        // 取消未发生（真实 owner 校验在 service 内先拒）。
        assertEquals("RUNNING", observer.queryForObject(
                "SELECT status FROM ai_job WHERE id = ?", String.class, CANCEL_JOB_ID));
        assertEquals(0L, ((Number) observer.queryForMap(
                "SELECT decision_seq FROM ai_job WHERE id = ?", CANCEL_JOB_ID).get("decision_seq")).longValue());
        unknownPending("job-cancel-request");
    }

    // ---------- 白名单合同：2 入口精确 writer/operation（c31 窄审核点②） ----------

    @Test void wiredAiCancelHandlersEnterGateWithExactWriterAndOperation() throws Exception {
        var port = mock(LegacyPlatformWritePort.class);
        var permit = mock(Permit.class);
        when(port.enter(any(), any(), any())).thenReturn(permit);
        var selected = new LegacyPlatformHttpWriteInterceptor(port, true);
        var calls = List.<HandlerMethod>of(
                new HandlerMethod(internalController, AiJobInternalController.class.getDeclaredMethod(
                        "claimJobs", HttpServletRequest.class, InternalClaimReqVO.class)),
                new HandlerMethod(appController, AppAiJobController.class.getDeclaredMethod(
                        "requestCancellation", String.class, String.class)));
        assertEquals(2, calls.size());
        for (var handler : calls) {
            assertTrue(selected.preHandle(new org.springframework.mock.web.MockHttpServletRequest(),
                    new org.springframework.mock.web.MockHttpServletResponse(), handler),
                    handler.getMethod().getName());
        }
        var captor = ArgumentCaptor.forClass(String.class);
        verify(port, times(2)).enter(eq(Owner.B_LEGACY), eq("ai-cancel-runtime"), captor.capture());
        assertEquals(Set.of("job-claim", "job-cancel-request"), Set.copyOf(captor.getAllValues()));
    }

    // ---------- 装配矩阵（J1 代表） ----------

    @Test void bridgeFalseStillGuardsAiCancelHandlers() throws Exception {
        configurationCase(Map.of("platform.legacy-b-write-control-directory", "synthetic-unused",
                "platform.legacy-design-bridge.enabled", "false"), true);
    }

    @Test void installedDefaultConfigurationGuardsAiCancelHandlers() throws Exception {
        configurationCase(Map.of("platform.legacy-b-write-control-directory", "synthetic-unused"), true);
    }

    @Test void notInstalledLeavesAiCancelBehaviorWithoutFreezeEvidence() throws Exception {
        configurationCase(Map.of(), false);
    }

    private void configurationCase(Map<String, Object> properties, boolean installed) throws Exception {
        gate.apply(new Control(2, State.DRAINING, SCOPE), FreezeEvidence.missing());
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("synthetic", properties));
            context.registerBean(LegacyPlatformWriteGate.class, () -> gate);
            context.register(LegacyPlatformHttpWriteWebConfiguration.class);
            context.refresh();
            var registry = new LegacyPlatformWalletSessionInterceptorTest.TestRegistry();
            context.getBeansOfType(WebMvcConfigurer.class).values().forEach(value -> value.addInterceptors(registry));
            assertEquals(installed ? 1 : 0, registry.handlers().size());
            var selected = registry.handlers().isEmpty() ? null : (HandlerInterceptor) registry.handlers().get(0);
            var configuredMvc = MockMvcBuilders.standaloneSetup(internalController)
                    .setControllerAdvice(new Object[]{new GlobalExceptionHandler("g2m", errorLogs)})
                    .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
                    .addInterceptors(selected == null ? new HandlerInterceptor[0] : new HandlerInterceptor[]{selected})
                    .build();
            assertEquals(installed ? 503 : 0, code(claim(configuredMvc)));
            // 未装配分支：claim 真实执行（QUEUED→RUNNING）；装配分支：零写保持 QUEUED。
            assertEquals(installed ? "QUEUED" : "RUNNING", observer.queryForObject(
                    "SELECT status FROM ai_job WHERE id = ?", String.class, JOB_ID));
            assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
            assertThrows(IllegalStateException.class,
                    () -> gate.apply(new Control(3, State.FROZEN, SCOPE), FreezeEvidence.missing()));
        }
    }

    // ---------- 非目标读直通（GET /design/v1/ai-jobs/{id} 纯读，真实 getJobForUser 链） ----------

    @Test void nonTargetJobReadDoesNotWaitForAiCancelCheckpoint() throws Exception {
        store.entered = new CountDownLatch(1);
        store.release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var blocked = pool.submit(() -> gate.enter(Owner.B_LEGACY, "ai-cancel-runtime", "synthetic-block"));
            assertTrue(store.entered.await(3, TimeUnit.SECONDS));
            var response = pool.submit(() -> {
                try {
                    return appMvc.perform(get("/design/v1/ai-jobs/" + CANCEL_JOB_ID)
                            .header("Authorization", "Bearer g2m-bearer")).andReturn();
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }).get(2, TimeUnit.SECONDS);
            assertEquals(0, code(response));
            assertEquals(String.valueOf(CANCEL_JOB_ID), JSON.readTree(
                    response.getResponse().getContentAsString()).get("data").get("jobId").asText());
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
