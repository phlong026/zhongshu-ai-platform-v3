package cn.iocoder.yudao.server.split;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.framework.web.core.handler.GlobalResponseBodyHandler;
import cn.iocoder.yudao.module.franchise.crm.CustomerService;
import cn.iocoder.yudao.module.franchise.crm.LeadService;
import cn.iocoder.yudao.module.franchise.crm.controller.admin.CustomerController;
import cn.iocoder.yudao.module.franchise.crm.controller.admin.LeadController;
import cn.iocoder.yudao.module.platform.command.dal.CommandEventMapper;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerFollowMapper;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerMapper;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerSchemeMapper;
import cn.iocoder.yudao.module.franchise.crm.dal.LeadMapper;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.command.CommandLogImpl;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import cn.iocoder.yudao.module.platform.org.dal.StaffPostDO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.extension.plugins.inner.DataPermissionInterceptor;
import cn.iocoder.yudao.framework.datapermission.core.db.DataPermissionRuleHandler;
import cn.iocoder.yudao.framework.datapermission.core.rule.DataPermissionRuleFactoryImpl;
import cn.iocoder.yudao.module.franchise.crm.CrmOrgScope;
import cn.iocoder.yudao.module.platform.authz.OrgScopeCustomizer;
import cn.iocoder.yudao.module.platform.authz.OrgScopeRule;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static cn.iocoder.yudao.server.split.LegacyPlatformWritePort.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * G2r CRM 客资/客户面 5 入口（真实链）：真实 MVC + 真实 PG（701/802 源码迁移）验证
 * capture/update/follow/saveScheme/transfer 接入共用停写门禁（writer=franchise-http）。
 *
 * <p>承载分层（合同§5，c31③批准）：G2b 真实 MP 手工工厂复用（四 mapper+OrgScopeRule+
 * CommandLogImpl+真实事务代理使 @Transactional 生效）；capture 三分支+并发红测（真实 MVC
 * 并行发起——c31④：防线在入口语义下成立）+多表回滚（synthetic audit trigger，G2b 模式）+
 * CU1 CAS revision 冲突+CU4 transfer 团队变更行级。
 */
class LegacyPlatformCrmCustomerInterceptorTest {
    private static final String SCOPE = "d".repeat(64);
    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();

    private static final String STATE_VERSION_CONFLICT_CODE = String.valueOf(
            cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.STATE_VERSION_CONFLICT.getCode());

    // G2b 夹具同构：登记人=SALES@公司31团队41。
    private static final PostSnapshot REGISTRAR =
            new PostSnapshot(21, 11, 31, "store", "sales", 41L, false, Set.of());
    private static final PostSnapshot BOSS =
            new PostSnapshot(25, 11, 31, "store", "boss", null, true, Set.of());

    private final ApiErrorLogCommonApi errorLogs = mock(ApiErrorLogCommonApi.class);
    private final AtomicReference<PostSnapshot> current = new AtomicReference<>(REGISTRAR);
    private JdbcTemplate observer;
    private LegacyPlatformWriteGate gate;
    private LegacyPlatformWalletSessionInterceptorTest.MemoryStore store;
    private MockMvc leadMvc;
    private MockMvc customerMvc;
    private LeadController leadController;
    private CustomerController customerController;

    @BeforeAll static void startDatabase() {
        PG.start();
        var dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        // 版本序逐字执行源码迁移（G2b 同款）：701（pf_command_event）→802（fr_lead/fr_customer/
        // fr_customer_follow/fr_customer_scheme，含 uk_fr_customer_phone UNIQUE(company_id,phone)）。
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/pf/V20261022.701__pf_command_event.sql"),
                new ClassPathResource("db/migration/franchise/V20261022.802__fr_crm.sql"),
                new ClassPathResource("db/migration/franchise/V20261102.807__fr_scheme_design_project.sql"),
                new ClassPathResource("db/migration/franchise/V20261102.809__fr_scheme_stage.sql"))
                .execute(dataSource);
        new cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils(new WebProperties());
    }

    @AfterAll static void stopDatabase() { PG.stop(); }

    @AfterEach void securityCleanup() { SecurityContextHolder.clearContext(); }

    @BeforeEach void fixture() {
        var dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        var jdbc = new JdbcTemplate(dataSource);
        observer = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        jdbc.execute("DROP TRIGGER IF EXISTS synthetic_audit_failure ON pf_command_event");
        observer.execute("TRUNCATE fr_lead, fr_customer_follow, fr_customer_scheme, fr_customer, pf_command_event");
        current.set(REGISTRAR);
        store = new LegacyPlatformWalletSessionInterceptorTest.MemoryStore();
        gate = new LegacyPlatformWriteGate("g2r", SCOPE, Set.of(), Set.of("franchise-http"), store);
        gate.apply(new Control(1, State.OPEN, SCOPE), FreezeEvidence.missing());

        // G2b 真实 MP 手工工厂复用（四 mapper + OrgScopeRule + CommandLogImpl）。
        CurrentPost identity = () -> Optional.ofNullable(current.get());
        var providers = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
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
        configuration.addMapper(CustomerFollowMapper.class);
        configuration.addMapper(CustomerSchemeMapper.class);
        configuration.addMapper(CommandEventMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        SqlSessionFactory sessionFactory;
        try {
            sessionFactory = factory.getObject();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
        var session = new SqlSessionTemplate(sessionFactory);
        var commandLog = new CommandLogImpl();
        ReflectionTestUtils.setField(commandLog, "commandEventMapper", session.getMapper(CommandEventMapper.class));
        var commands = new cn.iocoder.yudao.module.franchise.org.FranchiseCommands(commandLog);

        var staffPostService = mock(StaffPostService.class);
        when(staffPostService.listActivePosts(anyLong())).thenAnswer(inv -> {
            long userId = inv.getArgument(0);
            if (userId == 12L) { // 转交目标：公司31团队41的 SALES 任职
                return List.of(new StaffPostDO().setId(33L).setUserId(12L).setOrgId(31L)
                        .setTeamId(41L).setRoleCode("sales"));
            }
            return List.of();
        });
        var systemDirectory = mock(SystemDirectory.class);
        when(systemDirectory.userExists(anyLong())).thenReturn(true);
        // transfer salesPostOf：目标用户 12 须为在岗（active）人员。
        when(systemDirectory.usersByIds(any())).thenAnswer(inv -> {
            var ids = (java.util.Collection<Long>) inv.getArgument(0);
            return ids.contains(12L)
                    ? List.of(new SystemDirectory.UserBrief(12L, "接收人", "13600001235", true))
                    : List.of();
        });

        PlatformTransactionManager txManager = new DataSourceTransactionManager(dataSource);
        var leadService = new LeadService(session.getMapper(LeadMapper.class),
                session.getMapper(CustomerMapper.class), commands);
        var customerService = new CustomerService(session.getMapper(CustomerMapper.class),
                session.getMapper(CustomerFollowMapper.class), session.getMapper(CustomerSchemeMapper.class),
                staffPostService, systemDirectory, commands);
        // 真实事务代理：@Transactional 经代理生效（G2b 模式）。
        var leadProxy = new ProxyFactory(leadService);
        leadProxy.setProxyTargetClass(true);
        leadProxy.addAdvice(new TransactionInterceptor(txManager, new AnnotationTransactionAttributeSource()));
        var customerProxy = new ProxyFactory(customerService);
        customerProxy.setProxyTargetClass(true);
        customerProxy.addAdvice(new TransactionInterceptor(txManager, new AnnotationTransactionAttributeSource()));

        leadController = new LeadController((LeadService) leadProxy.getProxy(), identity);
        customerController = new CustomerController((CustomerService) customerProxy.getProxy(), identity);
        leadMvc = build(leadController);
        customerMvc = build(customerController);
    }

    private MockMvc build(Object controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new Object[]{new GlobalExceptionHandler("g2r-crm", errorLogs),
                        new GlobalResponseBodyHandler()})
                .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
                .addFilters(new cn.iocoder.yudao.framework.web.core.filter.CacheRequestBodyFilter())
                .addInterceptors(new LegacyPlatformHttpWriteInterceptor(gate, true))
                .build();
    }

    private int code(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString()).get("code").asText().isEmpty()
                ? -1 : JSON.readTree(result.getResponse().getContentAsString()).get("code").intValue();
    }

    private int rows(String table) {
        Integer n = observer.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }

    private void unknownPending(String operation) {
        assertEquals(1, gate.snapshot().checkpoint().pending().size());
        for (Work work : gate.snapshot().checkpoint().pending().values()) {
            assertEquals("franchise-http", work.writer());
            assertEquals(operation, work.operation());
            assertTrue(work.unknown());
        }
    }

    // ---------- DRAINING：5入口 503 零写 ----------

    @Test void drainingRejectsAllFiveCrmEntriesWithoutAnyWrite() throws Exception {
        gate.apply(new Control(2, State.DRAINING, SCOPE), FreezeEvidence.missing());
        assertEquals(503, code(leadMvc.perform(post("/franchise/leads")
                .header("Idempotency-Key", "k1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"张三\",\"phone\":\"13800001111\",\"need\":\"need\",\"source\":\"source\"}"))
                .andReturn()));
        assertEquals(503, code(customerMvc.perform(put("/franchise/customers/9001")
                .header("Idempotency-Key", "k2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"新名\",\"revision\":1}")).andReturn()));
        assertEquals(503, code(customerMvc.perform(post("/franchise/customers/9001/follows")
                .header("Idempotency-Key", "k3")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"跟进\"}")).andReturn()));
        assertEquals(503, code(customerMvc.perform(post("/franchise/customers/9001/schemes")
                .header("Idempotency-Key", "k4")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"BUDGET\",\"title\":\"方案\"}")).andReturn()));
        assertEquals(503, code(customerMvc.perform(post("/franchise/customers/9001/transfer")
                .header("Idempotency-Key", "k5")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetUserId\":12,\"reason\":\"离职\",\"revision\":1}")).andReturn()));
        assertEquals(0, rows("fr_lead"));
        assertEquals(0, rows("fr_customer"));
        assertEquals(0, rows("fr_customer_follow"));
        assertEquals(0, rows("fr_customer_scheme"));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    // ---------- C0 capture 三分支真实链 ----------

    @Test void captureNewPhoneCreatesCustomerAndLeadConvertedDirect() throws Exception {
        assertEquals(0, code(leadMvc.perform(post("/franchise/leads")
                .header("Idempotency-Key", "cap-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"张三\",\"phone\":\"13800001111\",\"need\":\"三居\",\"source\":\"walk-in\"}"))
                .andReturn()));
        // fr_customer 新行（companyId=31/teamId=41/owner=登记人）。
        var customer = observer.queryForMap(
                "SELECT company_id, team_id, owner_user_id, phone, status FROM fr_customer");
        assertEquals(31, ((Number) customer.get("company_id")).intValue());
        assertEquals(41, ((Number) customer.get("team_id")).intValue());
        assertEquals(11, ((Number) customer.get("owner_user_id")).intValue());
        assertEquals("13800001111", customer.get("phone"));
        // fr_lead 行：CONVERTED/direct + 关联客户。
        var lead = observer.queryForMap(
                "SELECT status, conversion, customer_id, registrar_user_id FROM fr_lead");
        assertEquals("CONVERTED", lead.get("status"));
        assertEquals("DIRECT", lead.get("conversion"));
        assertNotNull(lead.get("customer_id"));
        // 命令审计：customer-create。
        assertEquals(1, observer.queryForObject(
                "SELECT COUNT(*) FROM pf_command_event WHERE command = 'customer-create'", Integer.class));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void captureExistingPhoneOwnedBySelfConvertsWithoutNewCustomer() throws Exception {
        observer.update("INSERT INTO fr_customer(id, company_id, team_id, owner_user_id, name, phone, source, status) "
                + "VALUES (9001, 31, 41, 11, '老客户', '13800002222', 'synthetic', 'REGISTERED')");
        assertEquals(0, code(leadMvc.perform(post("/franchise/leads")
                .header("Idempotency-Key", "cap-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"老客\",\"phone\":\"13800002222\",\"need\":\"need\",\"source\":\"source\"}"))
                .andReturn()));
        // 零新客户行 + lead CONVERTED/existing 关联既有客户。
        assertEquals(1, rows("fr_customer"));
        var lead = observer.queryForMap("SELECT status, conversion, customer_id FROM fr_lead");
        assertEquals("CONVERTED", lead.get("status"));
        assertEquals("EXISTING", lead.get("conversion"));
        assertEquals(9001L, ((Number) lead.get("customer_id")).longValue());
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void captureConcurrentSamePhoneSecondRequestRetainsUnknownWithZeroDuplicateRows() throws Exception {
        // c31④：并发红测第二请求须经真实 MVC 并行发起（防线=uk_fr_customer_phone 入口语义下成立）。
        var pool = Executors.newFixedThreadPool(2);
        var firstEntered = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var winner = new AtomicReference<MvcResult>();
        var loser = new AtomicReference<MvcResult>();
        try {
            // 第一请求在审计事务边界挂起（G2b 门闩模式不可用于 capture——改用真并发：两请求同时发）。
            firstEntered.countDown();
            var f1 = pool.submit(() -> leadMvc.perform(post("/franchise/leads")
                    .header("Idempotency-Key", "cap-race-1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"并发甲\",\"phone\":\"13900001234\",\"need\":\"need\",\"source\":\"source\"}"))
                    .andReturn());
            var f2 = pool.submit(() -> leadMvc.perform(post("/franchise/leads")
                    .header("Idempotency-Key", "cap-race-2")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"并发乙\",\"phone\":\"13900001234\",\"need\":\"need\",\"source\":\"source\"}"))
                    .andReturn());
            var r1 = f1.get(10, TimeUnit.SECONDS);
            var r2 = f2.get(10, TimeUnit.SECONDS);
            releaseFirst.countDown();
            // 恰一成功一冲突（或双成功不可能——UNIQUE(company_id,phone)）。
            int code1 = code(r1);
            int code2 = code(r2);
            assertTrue((code1 == 0 && code2 != 0) || (code2 == 0 && code1 != 0),
                    "exactly-one-winner expected, got " + code1 + "/" + code2);
            winner.set(code1 == 0 ? r1 : r2);
            loser.set(code1 == 0 ? r2 : r1);
            assertEquals(1, rows("fr_customer"));
            assertEquals(1, rows("fr_lead"));
            // 败者经 handled 异常收口 → UNKNOWN 保留。
            unknownPending("lead-capture");
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }
    }

    @Test void captureAuditFailureRollsBackCustomerAndLeadAndRetainsPending() throws Exception {
        // synthetic audit trigger（G2b 模式）：pf_command_event INSERT 拒绝 → 多表整笔回滚。
        observer.execute("""
                CREATE OR REPLACE FUNCTION synthetic_reject_audit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'synthetic audit failure' USING ERRCODE='23505'; END $$
                """);
        observer.execute("CREATE TRIGGER synthetic_audit_failure BEFORE INSERT ON pf_command_event "
                + "FOR EACH ROW EXECUTE FUNCTION synthetic_reject_audit()");
        var result = leadMvc.perform(post("/franchise/leads")
                .header("Idempotency-Key", "cap-fail")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"回滚客\",\"phone\":\"13700001234\",\"need\":\"need\",\"source\":\"source\"}"))
                .andReturn();
        assertNotEquals(0, code(result));
        // 多表回滚：customer/lead/审计全零。
        assertEquals(0, rows("fr_customer"));
        assertEquals(0, rows("fr_lead"));
        assertEquals(0, rows("pf_command_event"));
        unknownPending("lead-capture");
        observer.execute("DROP TRIGGER IF EXISTS synthetic_audit_failure ON pf_command_event");
    }

    // ---------- CU1 CAS revision 冲突 ----------

    @Test void updateWithStaleRevisionIsHandledRetainingUnknownPending() throws Exception {
        seedCustomer(9001L, 11L, 1L);
        // revision=2 > 库内 1 → updateByRevision 0 行 → Revisions.requireUpdated 抛冲突。
        var result = customerMvc.perform(put("/franchise/customers/9001")
                .header("Idempotency-Key", "cu1-stale")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"新名\",\"wechat\":\"wx\",\"province\":\"p\",\"city\":\"c\","
                        + "\"summary\":\"s\",\"revision\":2}")).andReturn();
        assertNotEquals(0, code(result));
        assertEquals("旧名", observer.queryForObject(
                "SELECT name FROM fr_customer WHERE id = 9001", String.class));
        unknownPending("customer-update");
    }

    @Test void updateWithCurrentRevisionWritesNameAndAdvancesAudit() throws Exception {
        seedCustomer(9001L, 11L, 1L);
        assertEquals(0, code(customerMvc.perform(put("/franchise/customers/9001")
                .header("Idempotency-Key", "cu1-ok")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"新名\",\"wechat\":\"wx\",\"province\":\"p\",\"city\":\"c\","
                        + "\"summary\":\"s\",\"revision\":1}")).andReturn()));
        assertEquals("新名", observer.queryForObject(
                "SELECT name FROM fr_customer WHERE id = 9001", String.class));
        assertEquals(1, observer.queryForObject(
                "SELECT COUNT(*) FROM pf_command_event WHERE command = 'customer-update'", Integer.class));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    // ---------- CU2/CU3/CU4 真实行级 ----------

    @Test void followWritesFollowRowAndAdvancesRevision() throws Exception {
        seedCustomer(9001L, 11L, 1L);
        assertEquals(0, code(customerMvc.perform(post("/franchise/customers/9001/follows")
                .header("Idempotency-Key", "cu2-ok")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"电话跟进\",\"nextVisitDate\":\"2026-11-20\"}")).andReturn()));
        assertEquals(1, rows("fr_customer_follow"));
        assertEquals("电话跟进", observer.queryForObject(
                "SELECT note FROM fr_customer_follow WHERE customer_id = 9001", String.class));
        assertEquals(2L, ((Number) observer.queryForMap(
                "SELECT revision FROM fr_customer WHERE id = 9001").get("revision")).longValue());
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void saveSchemeWritesSchemeRow() throws Exception {
        seedCustomer(9001L, 11L, 1L);
        assertEquals(0, code(customerMvc.perform(post("/franchise/customers/9001/schemes")
                .header("Idempotency-Key", "cu3-ok")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"BUDGET\",\"title\":\"全屋预算\",\"payload\":\"{}\",\"width\":3.5,\"depth\":4.0,\"floors\":2,\"area\":100}")).andReturn()));
        var scheme = observer.queryForMap(
                "SELECT company_id, customer_id, kind, title FROM fr_customer_scheme");
        assertEquals(31, ((Number) scheme.get("company_id")).intValue());
        assertEquals(9001L, ((Number) scheme.get("customer_id")).longValue());
        assertEquals("BUDGET", scheme.get("kind"));
        assertEquals("全屋预算", scheme.get("title"));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void transferMovesOwnerAndTeamRowLevel() throws Exception {
        seedCustomer(9001L, 11L, 1L);
        current.set(BOSS); // transfer 须 boss（CustomerAccess.canTransfer）
        assertEquals(0, code(customerMvc.perform(post("/franchise/customers/9001/transfer")
                .header("Idempotency-Key", "cu4-ok")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetUserId\":12,\"reason\":\"离职转交\",\"revision\":1}")).andReturn()));
        // 行级：owner_user_id=12 + team_id 随接手人（33 岗位 team=41）。
        var customer = observer.queryForMap(
                "SELECT owner_user_id, team_id FROM fr_customer WHERE id = 9001");
        assertEquals(12, ((Number) customer.get("owner_user_id")).intValue());
        assertEquals(41, ((Number) customer.get("team_id")).intValue());
        assertEquals(1, observer.queryForObject(
                "SELECT COUNT(*) FROM pf_command_event WHERE command = 'customer-transfer'", Integer.class));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    private static void seedCustomer(long id, long owner, long revision) {
        observerStatic().update("INSERT INTO fr_customer(id, company_id, team_id, owner_user_id, name, phone, "
                        + "source, status, revision) VALUES (?, 31, 41, ?, '旧名', '13600001234', 'synthetic', "
                        + "'REGISTERED', ?)", id, owner, revision);
    }

    private static JdbcTemplate observerStatic() {
        return new JdbcTemplate(new DriverManagerDataSource(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
    }

    // ---------- 白名单合同：5 入口精确 writer/operation ----------

    @Test void wiredCrmHandlersEnterGateWithExactWriterAndOperation() throws Exception {
        var port = mock(LegacyPlatformWritePort.class);
        var permit = mock(Permit.class);
        when(port.enter(any(), any(), any())).thenReturn(permit);
        var selected = new LegacyPlatformHttpWriteInterceptor(port, true);
        var handlers = List.of(
                new HandlerMethod(leadController, LeadController.class.getDeclaredMethod("capture",
                        cn.iocoder.yudao.module.franchise.crm.LeadService.CaptureCommand.class, String.class)),
                new HandlerMethod(customerController, CustomerController.class.getDeclaredMethod("update",
                        long.class, CustomerController.UpdateReq.class, String.class)),
                new HandlerMethod(customerController, CustomerController.class.getDeclaredMethod("follow",
                        long.class, CustomerService.FollowCommand.class, String.class)),
                new HandlerMethod(customerController, CustomerController.class.getDeclaredMethod("saveScheme",
                        long.class, CustomerService.SchemeCommand.class, String.class)),
                new HandlerMethod(customerController, CustomerController.class.getDeclaredMethod("transfer",
                        long.class, CustomerController.TransferReq.class, String.class)));
        assertEquals(5, handlers.size());
        for (var handler : handlers) {
            assertTrue(selected.preHandle(new org.springframework.mock.web.MockHttpServletRequest(),
                    new org.springframework.mock.web.MockHttpServletResponse(), handler),
                    handler.getMethod().getName());
        }
        var captor = ArgumentCaptor.forClass(String.class);
        verify(port, times(5)).enter(eq(Owner.B_LEGACY), eq("franchise-http"), captor.capture());
        assertEquals(Set.of("lead-capture", "customer-update", "customer-follow",
                "customer-scheme", "customer-transfer"), Set.copyOf(captor.getAllValues()));
    }

    // ---------- 非目标读直通（GET pending 纯读） ----------

    @Test void nonTargetLeadPendingDoesNotWaitForCrmCheckpoint() throws Exception {
        store.entered = new CountDownLatch(1);
        store.release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var blocked = pool.submit(() -> gate.enter(Owner.B_LEGACY, "franchise-http", "synthetic-block"));
            assertTrue(store.entered.await(3, TimeUnit.SECONDS));
            var response = pool.submit(() -> {
                try {
                    return leadMvc.perform(get("/franchise/leads/pending")).andReturn();
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
