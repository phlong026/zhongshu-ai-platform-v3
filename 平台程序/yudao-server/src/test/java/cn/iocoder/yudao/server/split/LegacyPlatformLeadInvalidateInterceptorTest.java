package cn.iocoder.yudao.server.split;

import cn.iocoder.yudao.framework.datapermission.core.db.DataPermissionRuleHandler;
import cn.iocoder.yudao.framework.datapermission.core.rule.DataPermissionRuleFactoryImpl;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.framework.web.core.handler.GlobalResponseBodyHandler;
import cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils;
import cn.iocoder.yudao.module.franchise.crm.CrmOrgScope;
import cn.iocoder.yudao.module.franchise.crm.LeadService;
import cn.iocoder.yudao.module.franchise.crm.controller.admin.LeadController;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerMapper;
import cn.iocoder.yudao.module.franchise.crm.dal.LeadMapper;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.authz.OrgScopeCustomizer;
import cn.iocoder.yudao.module.platform.authz.OrgScopeRule;
import cn.iocoder.yudao.module.platform.command.CommandLogImpl;
import cn.iocoder.yudao.module.platform.command.dal.CommandEventMapper;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.DataPermissionInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
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
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.*;
import static cn.iocoder.yudao.module.franchise.crm.enums.FranchiseCrmErrorCodes.LEAD_LINK_TARGET_FORBIDDEN;
import static cn.iocoder.yudao.module.franchise.crm.enums.FranchiseCrmErrorCodes.LEAD_NOT_FOUND;
import static cn.iocoder.yudao.module.franchise.org.FranchiseRoles.*;
import static cn.iocoder.yudao.server.split.LegacyPlatformWritePort.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Real MVC, MyBatis organization filtering, PostgreSQL CAS and command audit; no login/channel integration. */
class LegacyPlatformLeadInvalidateInterceptorTest {
    private static final String SCOPE = "e".repeat(64);
    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final PostSnapshot REGISTRAR = new PostSnapshot(21, 11, 31, "FRANCHISE", SALES, 41L, false, Set.of());
    private JdbcTemplate jdbc;
    private JdbcTemplate observer;
    private LegacyPlatformWriteGate gate;
    private LegacyPlatformWalletSessionInterceptorTest.MemoryStore store;
    private LeadController controller;
    private final AtomicReference<PostSnapshot> current = new AtomicReference<>(REGISTRAR);
    private final AtomicInteger commitsWithPermit = new AtomicInteger();
    private final AtomicInteger rollbacksWithPermit = new AtomicInteger();
    private final AtomicInteger completionCalls = new AtomicInteger();
    private final AtomicReference<Exception> completionException = new AtomicReference<>();
    private final ApiErrorLogCommonApi errorLogs = mock(ApiErrorLogCommonApi.class);
    private volatile CountDownLatch commitEntered;
    private volatile CountDownLatch commitRelease;

    @BeforeAll static void startDatabase() {
        PG.start();
        // Execute the two unchanged source migrations, not a second hand-written production schema.
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/pf/V20261022.701__pf_command_event.sql"),
                new ClassPathResource("db/migration/franchise/V20261022.802__fr_crm.sql"))
                .execute(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
    }

    @AfterAll static void stopDatabase() { PG.stop(); }

    @BeforeEach void fixture() throws Exception {
        var dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        observer = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        jdbc.execute("DROP TRIGGER IF EXISTS synthetic_audit_failure ON pf_command_event");
        jdbc.execute("TRUNCATE fr_lead, fr_customer_follow, fr_customer_scheme, fr_customer, pf_command_event");
        jdbc.update("""
                INSERT INTO fr_lead(id, company_id, team_id, name, phone, need, source, status,
                                    registrar_user_id, registrar_post_id)
                VALUES (101, 31, 41, 'synthetic-lead', 'synthetic-phone', 'synthetic-need', 'synthetic',
                        'LINK_REQUIRED', 11, 21)
                """);
        // 同手机号原客户，owner 是登记人本人；link 的第二对象权限仍须独立校验。
        jdbc.update("""
                INSERT INTO fr_customer(id, company_id, team_id, owner_user_id, name, phone, source, status)
                VALUES (9001, 31, 41, 11, 'synthetic-customer', 'synthetic-phone', 'synthetic', 'REGISTERED')
                """);
        store = new LegacyPlatformWalletSessionInterceptorTest.MemoryStore();
        gate = new LegacyPlatformWriteGate("g2b", SCOPE, Set.of(), Set.of("franchise-http"), store);
        gate.apply(new Control(1, State.OPEN, SCOPE), FreezeEvidence.missing());
        new WebFrameworkUtils(new WebProperties());
        CurrentPost identity = () -> Optional.ofNullable(current.get());
        var providers = new DefaultListableBeanFactory();
        var plugins = new MybatisPlusInterceptor();
        providers.registerSingleton("crmOrgScope", new CrmOrgScope());
        providers.registerSingleton("mybatisPlusInterceptor", plugins);
        var orgScope = new OrgScopeRule(identity, providers.getBeanProvider(OrgScopeCustomizer.class),
                providers.getBeanProvider(MybatisPlusInterceptor.class));
        plugins.addInnerInterceptor(new DataPermissionInterceptor(
                new DataPermissionRuleHandler(new DataPermissionRuleFactoryImpl(List.of(orgScope)))));
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(LeadMapper.class);
        configuration.addMapper(CustomerMapper.class);
        configuration.addMapper(CommandEventMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setPlugins(plugins);
        var session = new SqlSessionTemplate(java.util.Objects.requireNonNull(factory.getObject()));
        var commandLog = new CommandLogImpl();
        ReflectionTestUtils.setField(commandLog, "commandEventMapper", session.getMapper(CommandEventMapper.class));
        var service = new LeadService(session.getMapper(LeadMapper.class), session.getMapper(CustomerMapper.class),
                new FranchiseCommands(commandLog));
        var transactions = new TransactionInterceptor();
        transactions.setTransactionManager(new DataSourceTransactionManager(dataSource));
        transactions.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxy = new ProxyFactory(service);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(transactions);
        proxy.addAdvice((MethodInterceptor) invocation -> {
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
            return invocation.proceed();
        });
        controller = new LeadController((LeadService) proxy.getProxy(), identity);
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
                        ? new Object[]{new GlobalExceptionHandler("g2b", errorLogs), new GlobalResponseBodyHandler()}
                        : new Object[]{new GlobalExceptionHandler("g2b", errorLogs)})
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

    private MvcResult invalidate(MockMvc mvc, long revision, String note, String key) throws Exception {
        var request = post("/franchise/leads/101/invalid").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("revision", revision, "note", note)))
                .header("Host", "legacy-direct.invalid");
        if (key != null) request.header(LeadController.IDEMPOTENCY_HEADER, key);
        return mvc.perform(request).andReturn();
    }

    private MvcResult invalidate(MockMvc mvc) throws Exception { return invalidate(mvc, 1, "synthetic-note", null); }

    private MvcResult link(MockMvc mvc, long revision, String key) throws Exception {
        var request = post("/franchise/leads/101/link").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("revision", revision)))
                .header("Host", "legacy-direct.invalid");
        if (key != null) request.header(LeadController.IDEMPOTENCY_HEADER, key);
        return mvc.perform(request).andReturn();
    }

    private int code(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString()).get("code").intValue();
    }

    private Map<String, Object> lead() { return observer.queryForMap("SELECT * FROM fr_lead WHERE id=101"); }

    private void rows(String status, long revision, int audits) {
        var row = lead();
        assertEquals(status, row.get("status"));
        assertEquals(revision, row.get("revision"));
        assertEquals(audits, observer.queryForObject("SELECT count(*) FROM pf_command_event", Integer.class));
    }

    private void unknownPending() { unknownPending("lead-invalidate", 1); }

    private void unknownPending(String operation, int count) {
        assertEquals(count, gate.snapshot().checkpoint().pending().size());
        for (Work work : gate.snapshot().checkpoint().pending().values()) {
            assertEquals("franchise-http", work.writer());
            assertEquals(operation, work.operation());
            assertTrue(work.unknown());
        }
    }

    @Test void drainingRejectsBeforeLeadCasOrAudit() throws Exception {
        var before = lead();
        gate.apply(new Control(2, State.DRAINING, SCOPE), FreezeEvidence.missing());
        assertEquals(503, code(invalidate(mvc())));
        assertEquals(before, lead());
        rows("LINK_REQUIRED", 1, 0);
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void registrarCommitsCasAndRealAuditBeforeReleasingPermit() throws Exception {
        assertEquals(0, code(invalidate(mvc())));
        rows("INVALID", 2, 1);
        var event = observer.queryForMap("SELECT * FROM pf_command_event");
        assertEquals("lead-invalid", event.get("command"));
        assertEquals(101L, event.get("aggregate_id"));
        assertEquals(2L, event.get("revision"));
        assertEquals(31L, event.get("org_id"));
        assertEquals(11L, event.get("actor_user_id"));
        assertEquals(1, commitsWithPermit.get());
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void managerCanInvalidateManagedTeam() throws Exception {
        current.set(new PostSnapshot(22, 12, 31, "FRANCHISE", SALES_LEAD, 41L, true, Set.of(41L)));
        assertEquals(0, code(invalidate(mvc())));
        rows("INVALID", 2, 1);
        assertEquals(1, commitsWithPermit.get());
        assertEquals(12L, observer.queryForObject("SELECT actor_user_id FROM pf_command_event", Long.class));
    }

    @Test void sameOrganizationPeerCannotInvalidateAndHandledFailureRetainsPending() throws Exception {
        current.set(new PostSnapshot(23, 13, 31, "FRANCHISE", SALES, 41L, false, Set.of()));
        var before = lead();
        var result = invalidate(mvc());
        assertEquals(200, result.getResponse().getStatus());
        assertEquals(LEAD_NOT_FOUND.getCode(), code(result));
        assertEquals(before, lead());
        rows("LINK_REQUIRED", 1, 0);
        assertEquals(1, completionCalls.get());
        assertNull(completionException.get());
        unknownPending();
    }

    @Test void realOrganizationFilterRejectsEvenSameUserInAnotherOrganization() throws Exception {
        current.set(new PostSnapshot(24, 11, 32, "FRANCHISE", BOSS, null, true, Set.of()));
        assertEquals(LEAD_NOT_FOUND.getCode(), code(invalidate(mvc())));
        rows("LINK_REQUIRED", 1, 0);
        unknownPending();
    }

    @Test void staleRevisionCannotWriteAndRetainsUnknownPending() throws Exception {
        var before = lead();
        assertEquals(STATE_VERSION_CONFLICT.getCode(), code(invalidate(mvc(), 2, "synthetic-note", null)));
        assertEquals(before, lead());
        rows("LINK_REQUIRED", 1, 0);
        assertEquals(1, rollbacksWithPermit.get());
        unknownPending();
    }

    @Test void realIdempotencyReplayDoesNotRepeatCasOrAudit() throws Exception {
        var mvc = mvc();
        assertEquals(0, code(invalidate(mvc, 1, "synthetic-note", "synthetic-idempotency")));
        var first = lead();
        assertEquals(0, code(invalidate(mvc, 1, "synthetic-note", "synthetic-idempotency")));
        assertEquals(first, lead());
        rows("INVALID", 2, 1);
        assertEquals(2, commitsWithPermit.get());
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void changedReplayPayloadIsRejectedWithoutClearingPending() throws Exception {
        var mvc = mvc();
        assertEquals(0, code(invalidate(mvc, 1, "synthetic-note", "synthetic-idempotency")));
        assertEquals(IDEMPOTENCY_KEY_REUSED.getCode(), code(invalidate(mvc, 1, "changed", "synthetic-idempotency")));
        rows("INVALID", 2, 1);
        unknownPending();
    }

    private void failAuditWrites() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION synthetic_reject_audit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'synthetic audit failure' USING ERRCODE='23505'; END $$
                """);
        jdbc.execute("CREATE TRIGGER synthetic_audit_failure BEFORE INSERT ON pf_command_event "
                + "FOR EACH ROW EXECUTE FUNCTION synthetic_reject_audit()");
    }

    @Test void databaseAuditFailureRollsBackLeadAndRetainsPending() throws Exception {
        failAuditWrites();
        var before = lead();
        assertEquals(STATE_VERSION_CONFLICT.getCode(), code(invalidate(mvc())));
        assertEquals(before, lead());
        rows("LINK_REQUIRED", 1, 0);
        assertEquals(1, rollbacksWithPermit.get());
        unknownPending();
    }

    @Test void drainingBeforeCommitRetainsFirstPermitAndRejectsSecondRequest() throws Exception {
        commitEntered = new CountDownLatch(1);
        commitRelease = new CountDownLatch(1);
        var mvc = mvc();
        var pool = Executors.newSingleThreadExecutor();
        try {
            var first = pool.submit(() -> invalidate(mvc));
            assertTrue(commitEntered.await(3, TimeUnit.SECONDS));
            rows("LINK_REQUIRED", 1, 0);
            gate.apply(new Control(2, State.DRAINING, SCOPE), FreezeEvidence.missing());
            assertEquals(1, gate.snapshot().checkpoint().pending().size());
            assertEquals(503, code(invalidate(mvc)));
            rows("LINK_REQUIRED", 1, 0);
            commitRelease.countDown();
            assertEquals(0, code(first.get(3, TimeUnit.SECONDS)));
            rows("INVALID", 2, 1);
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

    @Test void missingCommonResultEvidenceCannotClearCommittedLeadPending() throws Exception {
        assertEquals(0, code(invalidate(mvc(interceptor(), false, null))));
        rows("INVALID", 2, 1);
        unknownPending();
    }

    @Test void serializationFailureAfterCommitRetainsPendingAndBuildsErrorLog() throws Exception {
        var module = new com.fasterxml.jackson.databind.module.SimpleModule();
        module.addSerializer(String.class, new com.fasterxml.jackson.databind.JsonSerializer<String>() {
            @Override public void serialize(String value, com.fasterxml.jackson.core.JsonGenerator output,
                                            com.fasterxml.jackson.databind.SerializerProvider provider) throws IOException {
                if (value.equals("synthetic-lead")) throw new IOException("synthetic serialization failure");
                output.writeString(value);
            }
        });
        invalidate(mvc(interceptor(), true, new MappingJackson2HttpMessageConverter(JSON.copy().registerModule(module))));
        rows("INVALID", 2, 1);
        verify(errorLogs).createApiErrorLogAsync(any());
        unknownPending();
    }

    // ---------- G2c LeadController.link 接线：双对象权限、CAS/幂等、审计事务、DRAINING、提交前切换 ----------

    private static final PostSnapshot MANAGER = new PostSnapshot(22, 12, 31, "FRANCHISE", SALES_LEAD, 41L, true, Set.of(41L));

    @Test void registrarCannotLinkOwnLeadWithoutLeadHandleAuthority() throws Exception {
        // 登记人是同手机号原客户 owner，但 link 考核的是 lead 经办权，两个对象权限互相独立。
        var before = lead();
        var result = link(mvc(), 1, null);
        assertEquals(200, result.getResponse().getStatus());
        assertEquals(LEAD_NOT_FOUND.getCode(), code(result));
        assertEquals(before, lead());
        rows("LINK_REQUIRED", 1, 0);
        assertEquals(1, completionCalls.get());
        assertNull(completionException.get());
        unknownPending("lead-link", 1);
    }

    @Test void managerLinksLeadToInScopeCustomerWithCasAndAudit() throws Exception {
        current.set(MANAGER);
        assertEquals(0, code(link(mvc(), 1, null)));
        rows("CONVERTED", 2, 1);
        assertEquals("LINKED", lead().get("conversion"));
        assertEquals(9001L, lead().get("customer_id"));
        var event = observer.queryForMap("SELECT * FROM pf_command_event");
        assertEquals("lead-link", event.get("command"));
        assertEquals(101L, event.get("aggregate_id"));
        assertEquals(2L, event.get("revision"));
        assertEquals(31L, event.get("org_id"));
        assertEquals(12L, event.get("actor_user_id"));
        assertEquals(1, commitsWithPermit.get());
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void leadHandleableButCustomerOutOfScopeCannotLink() throws Exception {
        // 同组织 lead 可办理，不代表目标 customer 也在管理范围（第二对象独立校验）。
        current.set(MANAGER);
        jdbc.update("UPDATE fr_customer SET team_id = 42 WHERE id = 9001");
        var before = lead();
        assertEquals(LEAD_LINK_TARGET_FORBIDDEN.getCode(), code(link(mvc(), 1, null)));
        assertEquals(before, lead());
        rows("LINK_REQUIRED", 1, 0);
        assertEquals(1, rollbacksWithPermit.get());
        unknownPending("lead-link", 1);
    }

    @Test void bossCanLinkLeadAndCrossTeamCustomer() throws Exception {
        current.set(new PostSnapshot(25, 14, 31, "FRANCHISE", BOSS, null, true, Set.of()));
        jdbc.update("UPDATE fr_customer SET team_id = 42 WHERE id = 9001");
        assertEquals(0, code(link(mvc(), 1, null)));
        rows("CONVERTED", 2, 1);
        assertEquals(1, commitsWithPermit.get());
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void crossOrganizationUserCannotLink() throws Exception {
        current.set(new PostSnapshot(24, 11, 32, "FRANCHISE", BOSS, null, true, Set.of()));
        assertEquals(LEAD_NOT_FOUND.getCode(), code(link(mvc(), 1, null)));
        rows("LINK_REQUIRED", 1, 0);
        unknownPending("lead-link", 1);
    }

    @Test void staleRevisionCannotLinkAndRetainsUnknownPending() throws Exception {
        current.set(MANAGER);
        var before = lead();
        assertEquals(STATE_VERSION_CONFLICT.getCode(), code(link(mvc(), 2, null)));
        assertEquals(before, lead());
        rows("LINK_REQUIRED", 1, 0);
        assertEquals(1, rollbacksWithPermit.get());
        unknownPending("lead-link", 1);
    }

    @Test void linkIdempotentReplayDoesNotRepeatCasOrAudit() throws Exception {
        current.set(MANAGER);
        var mvc = mvc();
        assertEquals(0, code(link(mvc, 1, "synthetic-link-key")));
        var first = lead();
        assertEquals(0, code(link(mvc, 1, "synthetic-link-key")));
        assertEquals(first, lead());
        rows("CONVERTED", 2, 1);
        assertEquals(2, commitsWithPermit.get());
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void changedLinkReplayPayloadIsRejectedWithoutClearingPending() throws Exception {
        current.set(MANAGER);
        var mvc = mvc();
        assertEquals(0, code(link(mvc, 1, "synthetic-link-key")));
        assertEquals(IDEMPOTENCY_KEY_REUSED.getCode(), code(link(mvc, 2, "synthetic-link-key")));
        rows("CONVERTED", 2, 1);
        unknownPending("lead-link", 1);
    }

    @Test void linkDatabaseAuditFailureRollsBackLeadAndRetainsPending() throws Exception {
        current.set(MANAGER);
        failAuditWrites();
        var before = lead();
        assertEquals(STATE_VERSION_CONFLICT.getCode(), code(link(mvc(), 1, null)));
        assertEquals(before, lead());
        rows("LINK_REQUIRED", 1, 0);
        assertEquals(1, rollbacksWithPermit.get());
        unknownPending("lead-link", 1);
    }

    @Test void drainingRejectsBeforeLinkCasOrAudit() throws Exception {
        current.set(MANAGER);
        var before = lead();
        gate.apply(new Control(2, State.DRAINING, SCOPE), FreezeEvidence.missing());
        assertEquals(503, code(link(mvc(), 1, null)));
        assertEquals(before, lead());
        rows("LINK_REQUIRED", 1, 0);
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void drainingBeforeCommitRetainsFirstLinkPermitAndRejectsSecondLinkRequest() throws Exception {
        current.set(MANAGER);
        commitEntered = new CountDownLatch(1);
        commitRelease = new CountDownLatch(1);
        var mvc = mvc();
        var pool = Executors.newSingleThreadExecutor();
        try {
            var first = pool.submit(() -> link(mvc, 1, null));
            assertTrue(commitEntered.await(3, TimeUnit.SECONDS));
            rows("LINK_REQUIRED", 1, 0);
            gate.apply(new Control(2, State.DRAINING, SCOPE), FreezeEvidence.missing());
            assertEquals(1, gate.snapshot().checkpoint().pending().size());
            assertEquals(503, code(link(mvc, 1, null)));
            rows("LINK_REQUIRED", 1, 0);
            commitRelease.countDown();
            assertEquals(0, code(first.get(3, TimeUnit.SECONDS)));
            rows("CONVERTED", 2, 1);
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

    @Test void bridgeFalseStillGuardsTheRealLeadHandler() throws Exception {
        configurationCase(Map.of("platform.legacy-b-write-control-directory", "synthetic-unused",
                "platform.legacy-design-bridge.enabled", "false"), true);
    }

    @Test void notInstalledLeavesLeadBehaviorWithoutFreezeEvidence() throws Exception {
        configurationCase(Map.of(), false);
    }

    @Test void installedDefaultConfigurationGuardsLead() throws Exception {
        configurationCase(Map.of("platform.legacy-b-write-control-directory", "synthetic-unused"), true);
    }

    @Test void bridgeFalseStillGuardsTheLinkHandler() throws Exception {
        // bridge 开关只豁免钱包会话；link 是 CRM 入口，没有 bridge 装配条件，仍必须受门禁控制。
        configurationCase(Map.of("platform.legacy-b-write-control-directory", "synthetic-unused",
                "platform.legacy-design-bridge.enabled", "false"), true, true);
    }

    @Test void notInstalledLeavesLinkBehaviorWithoutFreezeEvidence() throws Exception {
        // link 不像 invalidate 有登记人豁免，直达业务层时需要经办权岗位。
        current.set(MANAGER);
        configurationCase(Map.of(), false, true);
    }

    private void configurationCase(Map<String, Object> properties, boolean installed) throws Exception {
        configurationCase(properties, installed, false);
    }

    private void configurationCase(Map<String, Object> properties, boolean installed, boolean linking) throws Exception {
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
            var result = linking ? link(configuredMvc, 1, null) : invalidate(configuredMvc);
            assertEquals(installed ? 503 : 0, code(result));
            rows(installed ? "LINK_REQUIRED" : linking ? "CONVERTED" : "INVALID", installed ? 1 : 2, installed ? 0 : 1);
            assertThrows(IllegalStateException.class,
                    () -> gate.apply(new Control(3, State.FROZEN, SCOPE), FreezeEvidence.missing()));
        }
    }

    @Test void nonTargetARequestDoesNotWaitForBCheckpoint() throws Exception {
        store.entered = new CountDownLatch(1);
        store.release = new CountDownLatch(1);
        var mvc = mvc();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var blocked = pool.submit(() -> gate.enter(Owner.B_LEGACY, "franchise-http", "synthetic-block"));
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

    @Test void unlistedRealLeadMethodsDoNotEnterGate() throws Exception {
        // G2r 更新：capture 已接线（franchise-http/lead-capture）——白名单外负例集收缩为 pending
        // （纯读，维持不收口）；capture 进门断言由 LegacyPlatformCrmCustomerInterceptorTest captor 锁定。
        var port = mock(LegacyPlatformWritePort.class);
        var selected = new LegacyPlatformHttpWriteInterceptor(port, true);
        for (var method : LeadController.class.getDeclaredMethods()) {
            if (!Set.of("pending").contains(method.getName())) continue;
            assertTrue(selected.preHandle(new org.springframework.mock.web.MockHttpServletRequest(),
                    new org.springframework.mock.web.MockHttpServletResponse(), new HandlerMethod(controller, method)));
        }
        verifyNoInteractions(port);
    }
}
