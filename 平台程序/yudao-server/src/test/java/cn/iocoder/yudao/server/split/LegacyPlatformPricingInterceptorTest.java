package cn.iocoder.yudao.server.split;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.framework.web.core.handler.GlobalResponseBodyHandler;
import cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils;
import cn.iocoder.yudao.module.commerce.controller.admin.GenerationPriceRuleAdminController;
import cn.iocoder.yudao.module.commerce.controller.admin.UsagePriceRuleAdminController;
import cn.iocoder.yudao.module.commerce.pricing.PriceRuleService;
import cn.iocoder.yudao.module.commerce.pricing.UsagePointPriceService;
import cn.iocoder.yudao.module.infra.zhongshu.api.PointLedgerPort;
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

import static cn.iocoder.yudao.server.split.LegacyPlatformWritePort.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * G2f+（类别4后续批扩展）：真实 MVC + 真实 PG 验证定价规则两控制器 7 写入口
 * 接入共用停写门禁（writer=commerce-wallet，operation清单已登记 writer-coverage.json）。
 * commerce 模块生产代码零改动；U4 成功路径依赖 service_usage_charge 夹具（306 真实迁移表）。
 */
class LegacyPlatformPricingInterceptorTest {
    private static final String SCOPE = "e".repeat(64);
    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    private static final long OPERATOR = 1L;

    private final ApiErrorLogCommonApi errorLogs = mock(ApiErrorLogCommonApi.class);
    private final PointLedgerPort ledgerPort = mock(PointLedgerPort.class);
    private final AtomicLong auditRecords = new AtomicLong();
    private JdbcTemplate observer;
    private LegacyPlatformWriteGate gate;
    private LegacyPlatformWalletSessionInterceptorTest.MemoryStore store;
    private MockMvc generationMvc;
    private MockMvc usageMvc;
    private GenerationPriceRuleAdminController generationAdmin;
    private UsagePriceRuleAdminController usageAdmin;
    private UsagePointPriceService usagePrices;
    private final AtomicInteger commitsWithPermit = new AtomicInteger();
    private volatile CountDownLatch commitEntered;
    private volatile CountDownLatch commitRelease;

    @BeforeAll static void startDatabase() {
        PG.start();
        var dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        // 版本序逐字执行：identity 101（守卫/account）→ commerce 301（generation_price_rule）→
        // 305（resolution列）→ 306（service_usage_price_rule + service_usage_charge）。
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/identity/V20260905.101__p2a_identity_access.sql"),
                new ClassPathResource("db/migration/commerce/V20260905.301__p4a_pricing_points.sql"),
                new ClassPathResource("db/migration/commerce/V20260929.305__generation_price_resolution.sql"),
                new ClassPathResource("db/migration/commerce/V20260930.306__usage_point_pricing.sql"),
                new ClassPathResource("db/migration/commerce/V20261003.308__prompt_reservation.sql"),
                new ClassPathResource("db/migration/commerce/V20261003.309__prompt_call_receipt.sql"))
                .execute(dataSource);
        new WebFrameworkUtils(new WebProperties());
    }

    @AfterAll static void stopDatabase() { PG.stop(); }

    @AfterEach void securityCleanup() { SecurityContextHolder.clearContext(); }

    @BeforeEach void fixture() {
        loginAsOperator(OPERATOR);
        var dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        observer = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        observer.execute("TRUNCATE generation_price_rule, service_usage_price_rule, service_usage_charge, "
                + "design_point_account, design_point_ledger, account");
        observer.update("INSERT INTO account (id, status) VALUES (?, 'ACTIVE')", 7777L);
        store = new LegacyPlatformWalletSessionInterceptorTest.MemoryStore();
        gate = new LegacyPlatformWriteGate("g2fp", SCOPE, Set.of(), Set.of("legacy-session", "commerce-wallet"), store);
        gate.apply(new Control(1, State.OPEN, SCOPE), FreezeEvidence.missing());
        new WebFrameworkUtils(new WebProperties());
        auditRecords.set(0);
        commitEntered = null;
        commitRelease = null;

        var priceRules = new PriceRuleService(dataSource);
        // UsagePointPriceService 走程序化事务模板 → commit 可经代理计数；PriceRuleService 生产为
        // @Transactional（standalone 不生效=自动提交），commit 不可观测 → Generation 断言不含计数。
        usagePrices = new UsagePointPriceService(dataSource, counting(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource)), ledgerPort);
        var generationAudit = new cn.iocoder.yudao.module.infra.zhongshu.audit.AuditPort() {
            @Override public long record(cn.iocoder.yudao.module.infra.zhongshu.audit.AuditEventMessage message) {
                return auditRecords.incrementAndGet();
            }
        };
        var usageAudit = new cn.iocoder.yudao.module.infra.zhongshu.audit.AuditPort() {
            @Override public long record(cn.iocoder.yudao.module.infra.zhongshu.audit.AuditEventMessage message) {
                return auditRecords.incrementAndGet();
            }
        };
        generationAdmin = new GenerationPriceRuleAdminController(
                new JdbcTemplate(dataSource), priceRules, generationAudit);
        ReflectionTestUtils.setField(generationAdmin, "generationCountMax", 4);
        usageAdmin = new UsagePriceRuleAdminController(usagePrices, usageAudit);

        var interceptor = new LegacyPlatformHttpWriteInterceptor(gate, true);
        generationMvc = MockMvcBuilders.standaloneSetup(generationAdmin,
                        new LegacyPlatformWalletSessionInterceptorTest.AController())
                .setControllerAdvice(new Object[]{new GlobalExceptionHandler("g2fp", errorLogs),
                        new GlobalResponseBodyHandler()})
                .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
                .addFilters(new cn.iocoder.yudao.framework.web.core.filter.CacheRequestBodyFilter())
                .addInterceptors(interceptor)
                .build();
        usageMvc = MockMvcBuilders.standaloneSetup(usageAdmin)
                .setControllerAdvice(new Object[]{new GlobalExceptionHandler("g2fp", errorLogs),
                        new GlobalResponseBodyHandler()})
                .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
                .addFilters(new cn.iocoder.yudao.framework.web.core.filter.CacheRequestBodyFilter())
                .addInterceptors(new LegacyPlatformHttpWriteInterceptor(gate, true))
                .build();
    }

    /** 真实事务管理器代理：物理提交前可挂门闩；commit 按 pending 非空计数。 */
    private PlatformTransactionManagerForProbe counting(org.springframework.transaction.PlatformTransactionManager delegate) {
        return (PlatformTransactionManagerForProbe) java.lang.reflect.Proxy.newProxyInstance(
                PlatformTransactionManagerForProbe.class.getClassLoader(),
                new Class<?>[]{PlatformTransactionManagerForProbe.class},
                (proxy, method, args) -> {
                    boolean physicalBoundary = method.getName().equals("commit")
                            && args != null && args.length == 1
                            && args[0] instanceof org.springframework.transaction.TransactionStatus status
                            && status.isNewTransaction();
                    if (commitEntered != null && physicalBoundary) {
                        commitEntered.countDown();
                        try {
                            if (!commitRelease.await(5, TimeUnit.SECONDS))
                                throw new IllegalStateException("synthetic commit timeout");
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(failure);
                        }
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

    private interface PlatformTransactionManagerForProbe extends org.springframework.transaction.PlatformTransactionManager { }

    private static void loginAsOperator(long userId) {
        var loginUser = new LoginUser();
        loginUser.setId(userId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null));
    }

    private MvcResult post(String url, String json) throws Exception {
        return generationMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post(url).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(json)).andReturn();
    }

    private MvcResult postUsage(String url, String json) throws Exception {
        return usageMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post(url).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(json)).andReturn();
    }

    private int rows(String table) {
        return observer.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private MvcResult verb(MockMvc target, String method, String url, String json) throws Exception {
        var request = switch (method) {
            case "PATCH" -> patch(url);
            case "DELETE" -> delete(url);
            default -> org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url);
        };
        if (json != null) request.contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json);
        return target.perform(request).andReturn();
    }

    private int code(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString()).get("code").intValue();
    }

    private String createGenerationRule(String stage, String resolution) throws Exception {
        var result = post("/design/v1/generation-price-rules",
                "{\"stage\":\"" + stage + "\",\"resolution\":\"" + resolution + "\",\"unitPointCost\":10,"
                        + "\"minCount\":1,\"maxCount\":2,\"effectiveAt\":\"2026-11-11T00:00:00Z\"}");
        assertEquals(0, code(result));
        return JSON.readTree(result.getResponse().getContentAsString()).get("data").get("ruleId").asText();
    }

    private String createUsageRule() throws Exception {
        var result = postUsage("/design/v1/usage-price-rules",
                "{\"product\":\"AI_PROMPT\",\"pointCost\":3,\"effectiveAt\":\"2026-11-11T00:00:00Z\"}");
        assertEquals(0, code(result));
        return JSON.readTree(result.getResponse().getContentAsString()).get("data").get("ruleId").asText();
    }

    private void unknownPending(String operation) {
        assertEquals(1, gate.snapshot().checkpoint().pending().size());
        for (Work work : gate.snapshot().checkpoint().pending().values()) {
            assertEquals("commerce-wallet", work.writer());
            assertEquals(operation, work.operation());
            assertTrue(work.unknown());
        }
    }

    // ---------- DRAINING：7入口逐一拒绝，全部表零写 ----------

    @Test void drainingRejectsAllSevenPricingEntriesWithoutAnyWrite() throws Exception {
        gate.apply(new Control(2, State.DRAINING, SCOPE), FreezeEvidence.missing());
        var gen = "{\"stage\":\"FLAT\",\"resolution\":\"2K\",\"unitPointCost\":10,"
                + "\"minCount\":1,\"maxCount\":2,\"effectiveAt\":\"2026-11-11T00:00:00Z\"}";
        var usage = "{\"product\":\"AI_PROMPT\",\"pointCost\":3,\"effectiveAt\":\"2026-11-11T00:00:00Z\"}";
        assertEquals(503, code(post("/design/v1/generation-price-rules", gen)));
        assertEquals(503, code(verb(generationMvc, "PATCH", "/design/v1/generation-price-rules/9001/retire",
                "{\"reason\":\"x\"}")));
        assertEquals(503, code(verb(generationMvc, "DELETE", "/design/v1/generation-price-rules/9001", null)));
        assertEquals(503, code(postUsage("/design/v1/usage-price-rules", usage)));
        assertEquals(503, code(verb(usageMvc, "PATCH", "/design/v1/usage-price-rules/9002/retire",
                "{\"reason\":\"x\"}")));
        assertEquals(503, code(verb(usageMvc, "DELETE", "/design/v1/usage-price-rules/9002", null)));
        assertEquals(503, code(verb(usageMvc, "POST", "/design/v1/usage-price-rules/prompt-reconciliations/job-x",
                "{\"supplierCalled\":true,\"evidence\":\"e\"}")));
        assertEquals(0, rows("generation_price_rule"));
        assertEquals(0, rows("service_usage_price_rule"));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    // ---------- Generation 深断言 ----------

    @Test void openGenerationCreateWritesRealRowAndAudit() throws Exception {
        assertEquals(0, code(post("/design/v1/generation-price-rules",
                "{\"stage\":\"flat\",\"resolution\":\"2k\",\"unitPointCost\":10,"
                        + "\"minCount\":1,\"maxCount\":2,\"effectiveAt\":\"2026-11-11T00:00:00Z\"}")));
        var row = observer.queryForMap("SELECT * FROM generation_price_rule");
        assertEquals("FLAT", row.get("stage"));
        assertEquals("2K", row.get("resolution"));
        assertEquals("ACTIVE", row.get("status"));
        assertEquals(10L, ((Number) row.get("unit_point_cost")).longValue());
        // 生产@Transactional在standalone被忽略=自动提交，commit计数不可观测；permit收口以pending空证明。
        assertEquals(1, auditRecords.get());
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void generationCreateInvalidMaxCountIsHandledRetainingUnknown() throws Exception {
        // maxCount=5 超过 generationCountMax=4：handled 400 → UNKNOWN 保留（宁多不漏）。
        assertEquals(400, code(post("/design/v1/generation-price-rules",
                "{\"stage\":\"FLAT\",\"resolution\":\"2K\",\"unitPointCost\":10,"
                        + "\"minCount\":1,\"maxCount\":5,\"effectiveAt\":\"2026-11-11T00:00:00Z\"}")));
        assertEquals(0, rows("generation_price_rule"));
        unknownPending("generation-price-create");
    }

    @Test void openGenerationRetireChainRetiresRealRow() throws Exception {
        String ruleId = createGenerationRule("FLAT", "2K");
        assertEquals(0, code(verb(generationMvc, "PATCH",
                "/design/v1/generation-price-rules/" + ruleId + "/retire", "{\"reason\":\"调价\"}")));
        assertEquals("RETIRED", observer.queryForObject(
                "SELECT status FROM generation_price_rule WHERE id = ?", String.class, Long.parseLong(ruleId)));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void generationSecondRetireOfRetiredRuleIsRefusedNormallyWithoutUnknown() throws Exception {
        // 二次retire：RETIRED规则再停用→0行→data false→正常收口无UNKNOWN（与G3 nonexistent-UNKNOWN型区分）。
        String ruleId = createGenerationRule("FLAT", "2K");
        assertEquals(0, code(verb(generationMvc, "PATCH",
                "/design/v1/generation-price-rules/" + ruleId + "/retire", "{\"reason\":\"首次\"}")));
        var replay = verb(generationMvc, "PATCH",
                "/design/v1/generation-price-rules/" + ruleId + "/retire", "{\"reason\":\"重复\"}");
        assertEquals(0, code(replay));
        assertFalse(JSON.readTree(replay.getResponse().getContentAsString()).get("data").asBoolean());
        assertEquals("RETIRED", observer.queryForObject(
                "SELECT status FROM generation_price_rule WHERE id = ?", String.class, Long.parseLong(ruleId)));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void openGenerationDeleteChainSoftDeletesRealRow() throws Exception {
        String ruleId = createGenerationRule("FLAT", "2K");
        assertEquals(0, code(verb(generationMvc, "PATCH",
                "/design/v1/generation-price-rules/" + ruleId + "/retire", "{\"reason\":\"调价\"}")));
        assertEquals(0, code(verb(generationMvc, "DELETE",
                "/design/v1/generation-price-rules/" + ruleId, null)));
        assertEquals(Boolean.TRUE, observer.queryForObject(
                "SELECT deleted FROM generation_price_rule WHERE id = ?", Boolean.class, Long.parseLong(ruleId)));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void generationDeleteActiveRuleIsRefusedNormallyWithoutUnknown() throws Exception {
        // 被拒分支 return-false 正常收口：生效中规则删除→0行→data false，无 UNKNOWN（源码核实形）。
        String ruleId = createGenerationRule("FLAT", "2K");
        var result = verb(generationMvc, "DELETE", "/design/v1/generation-price-rules/" + ruleId, null);
        assertEquals(0, code(result));
        assertFalse(JSON.readTree(result.getResponse().getContentAsString()).get("data").asBoolean());
        assertEquals("ACTIVE", observer.queryForObject(
                "SELECT status FROM generation_price_rule WHERE id = ?", String.class, Long.parseLong(ruleId)));
        assertEquals(Boolean.FALSE, observer.queryForObject(
                "SELECT deleted FROM generation_price_rule WHERE id = ?", Boolean.class, Long.parseLong(ruleId)));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void generationDeleteNonexistentRetainsUnknownPending() throws Exception {
        // nonexistent 规则：queryForMap 抛 EmptyResultDataAccessException → 500 → UNKNOWN 保留（sameMakerReview 同型）。
        assertEquals(500, code(verb(generationMvc, "DELETE", "/design/v1/generation-price-rules/9009", null)));
        unknownPending("generation-price-delete");
    }

    // ---------- Usage 深断言 ----------

    @Test void openUsageCreateWritesRealRowAndAudit() throws Exception {
        assertEquals(0, code(postUsage("/design/v1/usage-price-rules",
                "{\"product\":\"AI_PROMPT\",\"pointCost\":3,\"effectiveAt\":\"2026-11-11T00:00:00Z\"}")));
        var row = observer.queryForMap("SELECT * FROM service_usage_price_rule");
        assertEquals("AI_PROMPT", row.get("product"));
        assertEquals("ACTIVE", row.get("status"));
        assertEquals(3L, ((Number) row.get("point_cost")).longValue());
        // Usage createRule/retire 为自动提交（无事务包装），commit计数不可观测；permit收口以pending空证明。
        assertEquals(1, auditRecords.get());
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void openUsageRetireChainRetiresRealRow() throws Exception {
        String ruleId = createUsageRule();
        assertEquals(0, code(verb(usageMvc, "PATCH",
                "/design/v1/usage-price-rules/" + ruleId + "/retire", "{\"reason\":\"下线\"}")));
        assertEquals("RETIRED", observer.queryForObject(
                "SELECT status FROM service_usage_price_rule WHERE id = ?", String.class, Long.parseLong(ruleId)));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void usageSecondRetireOfRetiredRuleIsRefusedNormallyWithoutUnknown() throws Exception {
        // 二次retire：RETIRED规则再停用→0行→data false→正常收口无UNKNOWN。
        String ruleId = createUsageRule();
        assertEquals(0, code(verb(usageMvc, "PATCH",
                "/design/v1/usage-price-rules/" + ruleId + "/retire", "{\"reason\":\"首次\"}")));
        var replay = verb(usageMvc, "PATCH",
                "/design/v1/usage-price-rules/" + ruleId + "/retire", "{\"reason\":\"重复\"}");
        assertEquals(0, code(replay));
        assertFalse(JSON.readTree(replay.getResponse().getContentAsString()).get("data").asBoolean());
        assertEquals("RETIRED", observer.queryForObject(
                "SELECT status FROM service_usage_price_rule WHERE id = ?", String.class, Long.parseLong(ruleId)));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void usageDeleteActiveRuleIsRefusedNormallyWithoutUnknown() throws Exception {
        String ruleId = createUsageRule();
        var result = verb(usageMvc, "DELETE", "/design/v1/usage-price-rules/" + ruleId, null);
        assertEquals(0, code(result));
        assertFalse(JSON.readTree(result.getResponse().getContentAsString()).get("data").asBoolean());
        assertEquals("ACTIVE", observer.queryForObject(
                "SELECT status FROM service_usage_price_rule WHERE id = ?", String.class, Long.parseLong(ruleId)));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    // ---------- U4 prompt 对账（charge 夹具 306 真实迁移表） ----------

    @Test void openPromptReconcileExecutesConsumeReserve() throws Exception {
        observer.update("INSERT INTO service_usage_charge (id, product, user_id, biz_type, biz_id, "
                        + "price_rule_id, price_rule_version, point_cost, state, reservation_token) "
                        + "VALUES (9002, 'AI_PROMPT', 7777, 'ai_job', 'job-1', 9003, 1, 5, 'PENDING', 12345)");
        assertEquals(0, code(verb(usageMvc, "POST",
                "/design/v1/usage-price-rules/prompt-reconciliations/job-1",
                "{\"supplierCalled\":true,\"evidence\":\"渠道工单#1\"}")));
        assertEquals("CHARGED", observer.queryForObject(
                "SELECT state FROM service_usage_charge WHERE biz_id = 'job-1'", String.class));
        verify(ledgerPort).consumeReserve(eq(7777L), eq(5L), eq("AI_PROMPT_DEBIT"), eq("ai_job"),
                eq("job-1"), any());
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void promptReconcileMissingEvidenceRetainsUnknownPending() throws Exception {
        observer.update("INSERT INTO service_usage_charge (id, product, user_id, biz_type, biz_id, "
                        + "price_rule_id, price_rule_version, point_cost, state, reservation_token) "
                        + "VALUES (9002, 'AI_PROMPT', 7777, 'ai_job', 'job-1', 9003, 1, 5, 'PENDING', 12345)");
        var result = verb(usageMvc, "POST", "/design/v1/usage-price-rules/prompt-reconciliations/job-1",
                "{\"supplierCalled\":true,\"evidence\":\"\"}");
        assertNotEquals(0, code(result));
        assertEquals(0, auditRecords.get());
        unknownPending("prompt-reconcile");
    }

    @Test void promptReconcileNonexistentChargeRetainsUnknownPending() throws Exception {
        var result = verb(usageMvc, "POST", "/design/v1/usage-price-rules/prompt-reconciliations/job-x",
                "{\"supplierCalled\":true,\"evidence\":\"e\"}");
        assertEquals(500, code(result));
        unknownPending("prompt-reconcile");
    }

    // ---------- 白名单合同：7入口精确 writer/operation ----------

    @Test void wiredPricingHandlersEnterGateWithExactWriterAndOperation() throws Exception {
        var port = mock(LegacyPlatformWritePort.class);
        var permit = mock(Permit.class);
        when(port.enter(any(), any(), any())).thenReturn(permit);
        var selected = new LegacyPlatformHttpWriteInterceptor(port, true);
        var genCreate = GenerationPriceRuleAdminController.class.getDeclaredMethod("createRule",
                GenerationPriceRuleAdminController.CreateRuleRequest.class);
        var genRetire = GenerationPriceRuleAdminController.class.getDeclaredMethod("retireRule", String.class, Map.class);
        var genDelete = GenerationPriceRuleAdminController.class.getDeclaredMethod("deleteRule", String.class);
        var usageCreate = UsagePriceRuleAdminController.class.getDeclaredMethod("create",
                UsagePriceRuleAdminController.CreateRule.class);
        var usageRetire = UsagePriceRuleAdminController.class.getDeclaredMethod("retire", long.class, Map.class);
        var usageDelete = UsagePriceRuleAdminController.class.getDeclaredMethod("delete", long.class);
        var usageReconcile = UsagePriceRuleAdminController.class.getDeclaredMethod("reconcilePrompt",
                String.class, UsagePriceRuleAdminController.PromptDecision.class);
        record HandlerCall(Object controller, java.lang.reflect.Method method) { }
        var calls = List.of(new HandlerCall(generationAdmin, genCreate),
                new HandlerCall(generationAdmin, genRetire), new HandlerCall(generationAdmin, genDelete),
                new HandlerCall(usageAdmin, usageCreate), new HandlerCall(usageAdmin, usageRetire),
                new HandlerCall(usageAdmin, usageDelete), new HandlerCall(usageAdmin, usageReconcile));
        for (var call : calls) {
            var handler = new HandlerMethod(call.controller(), call.method());
            assertTrue(selected.preHandle(new org.springframework.mock.web.MockHttpServletRequest(),
                    new org.springframework.mock.web.MockHttpServletResponse(), handler));
        }
        var captor = ArgumentCaptor.forClass(String.class);
        verify(port, times(7)).enter(eq(Owner.B_LEGACY), eq("commerce-wallet"), captor.capture());
        assertEquals(Set.of("generation-price-create", "generation-price-retire", "generation-price-delete",
                "usage-price-create", "usage-price-retire", "usage-price-delete", "prompt-reconcile"),
                Set.copyOf(captor.getAllValues()));
    }

    // ---------- 非目标读直通 ----------

    @Test void nonTargetRulePageDoesNotWaitForWalletCheckpoint() throws Exception {
        store.entered = new CountDownLatch(1);
        store.release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var blocked = pool.submit(() -> gate.enter(Owner.B_LEGACY, "commerce-wallet", "synthetic-block"));
            assertTrue(store.entered.await(3, TimeUnit.SECONDS));
            var response = pool.submit(() -> generationMvc.perform(
                    get("/design/v1/generation-price-rules?stage=FLAT")).andReturn()).get(1, TimeUnit.SECONDS);
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

    // ---------- 装配矩阵（Generation create 代表） ----------

    @Test void bridgeFalseStillGuardsPricingHandlers() throws Exception {
        configurationCase(Map.of("platform.legacy-b-write-control-directory", "synthetic-unused",
                "platform.legacy-design-bridge.enabled", "false"), true);
    }

    @Test void installedDefaultConfigurationGuardsPricingHandlers() throws Exception {
        configurationCase(Map.of("platform.legacy-b-write-control-directory", "synthetic-unused"), true);
    }

    @Test void notInstalledLeavesPricingBehaviorWithoutFreezeEvidence() throws Exception {
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
            var genMvc = MockMvcBuilders.standaloneSetup(generationAdmin)
                    .setControllerAdvice(new Object[]{new GlobalExceptionHandler("g2fp", errorLogs)})
                    .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
                    .addInterceptors(selected == null ? new HandlerInterceptor[0] : new HandlerInterceptor[]{selected})
                    .build();
            var gen = "{\"stage\":\"FLAT\",\"resolution\":\"2K\",\"unitPointCost\":10,"
                    + "\"minCount\":1,\"maxCount\":2,\"effectiveAt\":\"2026-11-11T00:00:00Z\"}";
            assertEquals(installed ? 503 : 0, code(genMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/design/v1/generation-price-rules")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(gen)).andReturn()));
            assertEquals(installed ? 0 : 1, rows("generation_price_rule"));
            assertThrows(IllegalStateException.class,
                    () -> gate.apply(new Control(3, State.FROZEN, SCOPE), FreezeEvidence.missing()));
        }
    }
}
