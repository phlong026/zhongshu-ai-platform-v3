package cn.iocoder.yudao.server.split;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.framework.web.core.handler.GlobalResponseBodyHandler;
import cn.iocoder.yudao.module.franchise.content.controller.admin.ContentScriptController;
import cn.iocoder.yudao.module.franchise.content.controller.admin.ContentTaskController;
import cn.iocoder.yudao.module.franchise.content.content.ContentQuotaService;
import cn.iocoder.yudao.module.franchise.content.content.ContentScriptService;
import cn.iocoder.yudao.module.franchise.content.content.ContentTaskService;
import cn.iocoder.yudao.module.franchise.content.content.ContentTaskStore;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentTaskDO;
import cn.iocoder.yudao.module.franchise.content.provider.AsrPort;
import cn.iocoder.yudao.module.franchise.content.provider.ContentProviderProperties;
import cn.iocoder.yudao.module.franchise.content.provider.LinkParsePort;
import cn.iocoder.yudao.module.franchise.content.provider.OralVideoPort;
import cn.iocoder.yudao.module.franchise.content.provider.ScriptRewritePort;
import cn.iocoder.yudao.module.franchise.content.provider.VideoGenerationPort;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.command.dal.CommandEventMapper;
import cn.iocoder.yudao.module.platform.command.CommandLogImpl;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
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
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * G2n 类别11 content-jobs（方案甲）：真实 MVC + 真实 PG（franchise源码迁移）验证
 * 任务创建面 T1/S1/S2/S3 接入共用停写门禁（writer=content-jobs）。
 *
 * <p>承载分层（合同§5，c31⑥批准）：T1 真实链（真实 ContentTaskService/Store/Quota/
 * FranchiseCommands+pf_command_event 审计，命令 mapper 照 G2b 手工 MP 工厂先例）；
 * S1-S3 门禁语义经 ContentScriptService 替身（provider 外呼替身）。ContentTaskJob sweep
 * 三段为登记豁免（不在本套件）；运营面14入口归 franchise-http 后续批（不接）。
 * content/设计域生产代码零改动。
 */
class LegacyPlatformContentJobsInterceptorTest {
    private static final String SCOPE = "a".repeat(64);
    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();

    private static final long ORG_ID = 3100L;
    private static final long USER_ID = 7001L;
    private static final String IDEM_KEY = "g2n-idem-001";

    private final ApiErrorLogCommonApi errorLogs = mock(ApiErrorLogCommonApi.class);
    private final ContentScriptService scriptService = mock(ContentScriptService.class);
    private JdbcTemplate observer;
    private LegacyPlatformWriteGate gate;
    private LegacyPlatformWalletSessionInterceptorTest.MemoryStore store;
    private MockMvc taskMvc;
    private MockMvc scriptMvc;
    private ContentTaskController taskController;
    private ContentScriptController scriptController;

    @BeforeAll static void startDatabase() {
        PG.start();
        var dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        // 版本序逐字执行源码迁移：701（pf_command_event 命令审计）→812/813（content 域）。
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/pf/V20261022.701__pf_command_event.sql"),
                new ClassPathResource("db/migration/franchise/V20261102.812__fr_content_core.sql"),
                new ClassPathResource("db/migration/franchise/V20261102.813__fr_content_persona_video.sql"))
                .execute(dataSource);
        new cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils(new WebProperties());
    }

    @AfterAll static void stopDatabase() { PG.stop(); }

    @AfterEach void securityCleanup() { SecurityContextHolder.clearContext(); }

    @BeforeEach void fixture() throws Exception {
        var dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        observer = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        observer.execute("TRUNCATE fr_content_task, fr_content_script, fr_content_draft, fr_content_publication, "
                + "fr_content_provider_call, fr_content_ai_usage, fr_content_persona, fr_content_video_draft, "
                + "fr_content_course_progress, pf_command_event");
        store = new LegacyPlatformWalletSessionInterceptorTest.MemoryStore();
        gate = new LegacyPlatformWriteGate("g2n", SCOPE, Set.of(), Set.of("content-jobs"), store);
        gate.apply(new Control(1, State.OPEN, SCOPE), FreezeEvidence.missing());

        // T1 真实链：命令 mapper 照 G2b 手工 MP 工厂先例（ContentTaskStore/Quota 为纯 JDBC 无 mapper）。
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(CommandEventMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        SqlSessionFactory sessionFactory = factory.getObject();
        var session = new SqlSessionTemplate(sessionFactory);
        var commandLog = new CommandLogImpl();
        ReflectionTestUtils.setField(commandLog, "commandEventMapper", session.getMapper(CommandEventMapper.class));
        var commands = new FranchiseCommands(commandLog);
        var quota = new ContentQuotaService(new JdbcTemplate(dataSource), new ContentProviderProperties());
        var taskStore = new ContentTaskStore(new JdbcTemplate(dataSource));
        var taskService = new ContentTaskService(taskStore, quota,
                mock(LinkParsePort.class), mock(AsrPort.class), mock(ScriptRewritePort.class),
                mock(OralVideoPort.class), mock(VideoGenerationPort.class),
                mock(AssetAccessPort.class), commands, new ContentProviderProperties(), mock(cn.iocoder.yudao.module.franchise.api.OrgPointPort.class));

        var snapshot = new PostSnapshot(5111L, USER_ID, ORG_ID, "HEADQUARTERS", "boss", null, true, Set.of());
        CurrentPost currentPost = () -> Optional.of(snapshot);

        taskController = new ContentTaskController();
        ReflectionTestUtils.setField(taskController, "currentPost", currentPost);
        ReflectionTestUtils.setField(taskController, "tasks", taskService);
        ReflectionTestUtils.setField(taskController, "drafts",
                mock(cn.iocoder.yudao.module.franchise.content.content.ContentDraftService.class));
        ReflectionTestUtils.setField(taskController, "taskMapper",
                mock(cn.iocoder.yudao.module.franchise.content.dal.FrContentTaskMapper.class));
        ReflectionTestUtils.setField(taskController, "video", mock(VideoGenerationPort.class));

        scriptController = new ContentScriptController();
        ReflectionTestUtils.setField(scriptController, "currentPost", currentPost);
        ReflectionTestUtils.setField(scriptController, "scripts", scriptService);
        // scriptMapper 为脚本历史查询面（本套件不触）；替身即可。
        ReflectionTestUtils.setField(scriptController, "scriptMapper",
                mock(cn.iocoder.yudao.module.franchise.content.dal.FrContentScriptMapper.class));

        taskMvc = build(taskController);
        scriptMvc = build(scriptController);
    }

    private MockMvc build(Object controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new Object[]{new GlobalExceptionHandler("g2n", errorLogs),
                        new GlobalResponseBodyHandler()})
                .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
                .addFilters(new cn.iocoder.yudao.framework.web.core.filter.CacheRequestBodyFilter())
                .addInterceptors(new LegacyPlatformHttpWriteInterceptor(gate, true))
                .build();
    }

    private MvcResult verb(MockMvc mvc, String method, String url, String json) throws Exception {
        var request = "PUT".equals(method) ? put(url) : post(url);
        if (json != null) request.contentType(MediaType.APPLICATION_JSON).content(json);
        request.header("Idempotency-Key", IDEM_KEY);
        return mvc.perform(request).andReturn();
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
            assertEquals("content-jobs", work.writer());
            assertEquals(operation, work.operation());
            assertTrue(work.unknown());
        }
    }

    // ---------- DRAINING：任务域4入口 503 零写 ----------

    @Test void drainingRejectsAllFourTaskEntriesWithoutAnyWrite() throws Exception {
        gate.apply(new Control(2, State.DRAINING, SCOPE), FreezeEvidence.missing());
        assertEquals(503, code(verb(taskMvc, "POST", "/franchise/content/tasks",
                "{\"kind\":\"digital\",\"voice\":\"synthetic-voice\",\"text\":\"synthetic-text\","
                        + "\"avatar\":\"https://cdn.example.com/a.png\"}")));
        assertEquals(503, code(verb(scriptMvc, "POST", "/franchise/content/links/parse",
                "{\"url\":\"https://example.com/video/123\"}")));
        assertEquals(503, code(verb(scriptMvc, "POST", "/franchise/content/scripts/extract",
                "{\"assetId\":9001}")));
        assertEquals(503, code(verb(scriptMvc, "POST", "/franchise/content/scripts/optimize",
                "{\"text\":\"synthetic\"}")));
        // 零写：任务/配额/审计全零。
        assertEquals(0, rows("fr_content_task"));
        assertEquals(0, rows("fr_content_ai_usage"));
        assertEquals(0, rows("pf_command_event"));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    // ---------- J1 同型：T1 OPEN 真实创建链深断言（c31⑥真实链+配额真实表+幂等键） ----------

    @Test void openCreateTaskWritesRealQueuedRowWithQuotaAndCommandAudit() throws Exception {
        assertEquals(0, code(verb(taskMvc, "POST", "/franchise/content/tasks",
                "{\"kind\":\"digital\",\"voice\":\"synthetic-voice\",\"text\":\"一二三四五六七八九十\","
                        + "\"avatar\":\"https://cdn.example.com/a.png\"}")));
        // fr_content_task 行级：QUEUED + 任务域字段。
        var task = observer.queryForMap("SELECT company_id, author_user_id, kind, status "
                + "FROM fr_content_task");
        assertEquals(ORG_ID, ((Number) task.get("company_id")).longValue());
        assertEquals(USER_ID, ((Number) task.get("author_user_id")).longValue());
        assertEquals("digital", task.get("kind"));
        assertEquals("QUEUED", task.get("status"));
        // fr_content_ai_usage 配额真实表行级：DIGITAL 科目当日 reserve。
        var usage = observer.queryForMap("SELECT org_id, service, count FROM fr_content_ai_usage");
        assertEquals(ORG_ID, ((Number) usage.get("org_id")).longValue());
        assertTrue(((Number) usage.get("count")).intValue() > 0);
        // pf_command_event 命令审计行级：content-task-create + 幂等键（FranchiseCommands.record
        // 封装 actor 域前缀 franchise:{userId}:{key}——红期实值核出）。
        var command = observer.queryForMap("SELECT aggregate_type, command, idempotency_key "
                + "FROM pf_command_event");
        assertEquals("fr_content_task", command.get("aggregate_type"));
        assertEquals("content-task-create", command.get("command"));
        assertEquals("franchise:" + USER_ID + ":" + IDEM_KEY, command.get("idempotency_key"));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());

        // 幂等重放：同 Idempotency-Key 第二次 → 零新行（返回既有任务）。
        assertEquals(0, code(verb(taskMvc, "POST", "/franchise/content/tasks",
                "{\"kind\":\"digital\",\"voice\":\"synthetic-voice\",\"text\":\"一二三四五六七八九十\","
                        + "\"avatar\":\"https://cdn.example.com/a.png\"}")));
        assertEquals(1, rows("fr_content_task"));
        assertEquals(1, rows("pf_command_event"));
    }

    @Test void createTaskInvalidKindIsHandledRetainingUnknownPending() throws Exception {
        var result = verb(taskMvc, "POST", "/franchise/content/tasks", "{\"kind\":\"bogus\"}");
        assertEquals(1_114_000_001, code(result)); // INPUT_INVALID（真实业务校验，源码精确值）
        assertEquals(0, rows("fr_content_task"));
        assertEquals(0, rows("fr_content_ai_usage"));
        unknownPending("content-task-create");
    }

    // ---------- S1-S3 OPEN：门禁语义经 service 替身（c31⑥层级；真实写链=T1 同源任务化） ----------

    @Test void openScriptEntriesPassGateAndReleaseWithoutPending() throws Exception {
        when(scriptService.parseLink(any(), anyString(), anyString()))
                .thenReturn(new FrContentTaskDO().setId(9991L).setStatus("SUCCEEDED"));
        when(scriptService.extract(any(), any(), any(), any(), any(), anyString()))
                .thenReturn(new FrContentTaskDO().setId(9992L).setStatus("SUCCEEDED"));
        when(scriptService.optimize(any(), any(), anyString()))
                .thenReturn(new FrContentTaskDO().setId(9993L).setStatus("SUCCEEDED"));
        assertEquals(0, code(verb(scriptMvc, "POST", "/franchise/content/links/parse",
                "{\"url\":\"https://example.com/video/123\"}")));
        assertEquals(0, code(verb(scriptMvc, "POST", "/franchise/content/scripts/extract",
                "{\"assetId\":9001}")));
        assertEquals(0, code(verb(scriptMvc, "POST", "/franchise/content/scripts/optimize",
                "{\"text\":\"synthetic\"}")));
        // 替身承载：无真实 fr_content_task 写（S1-S3 真实任务化写链与 T1 同源，由本类 T1 锁定）。
        assertEquals(0, rows("fr_content_task"));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    // ---------- 白名单合同：4 入口精确 writer/operation ----------

    @Test void wiredContentJobHandlersEnterGateWithExactWriterAndOperation() throws Exception {
        var port = mock(LegacyPlatformWritePort.class);
        var permit = mock(Permit.class);
        when(port.enter(any(), any(), any())).thenReturn(permit);
        var selected = new LegacyPlatformHttpWriteInterceptor(port, true);
        var methods = List.of(
                ContentTaskController.class.getDeclaredMethod("create", Map.class, String.class),
                ContentScriptController.class.getDeclaredMethod("parseLink", Map.class, String.class),
                ContentScriptController.class.getDeclaredMethod("extract", Map.class, String.class),
                ContentScriptController.class.getDeclaredMethod("optimize", Map.class, String.class));
        assertEquals(4, methods.size());
        for (var method : methods) {
            var handler = new HandlerMethod(
                    method.getDeclaringClass() == ContentTaskController.class ? taskController : scriptController,
                    method);
            assertTrue(selected.preHandle(new org.springframework.mock.web.MockHttpServletRequest(),
                    new org.springframework.mock.web.MockHttpServletResponse(), handler), method.getName());
        }
        var captor = ArgumentCaptor.forClass(String.class);
        verify(port, times(4)).enter(eq(Owner.B_LEGACY), eq("content-jobs"), captor.capture());
        assertEquals(Set.of("content-task-create", "content-link-parse",
                "content-script-extract", "content-script-optimize"), Set.copyOf(captor.getAllValues()));
    }

    // ---------- 装配矩阵（T1 代表） ----------

    @Test void bridgeFalseStillGuardsContentJobHandlers() throws Exception {
        configurationCase(Map.of("platform.legacy-b-write-control-directory", "synthetic-unused",
                "platform.legacy-design-bridge.enabled", "false"), true);
    }

    @Test void installedDefaultConfigurationGuardsContentJobHandlers() throws Exception {
        configurationCase(Map.of("platform.legacy-b-write-control-directory", "synthetic-unused"), true);
    }

    @Test void notInstalledLeavesContentJobBehaviorWithoutFreezeEvidence() throws Exception {
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
            var configuredMvc = MockMvcBuilders.standaloneSetup(taskController)
                    .setControllerAdvice(new Object[]{new GlobalExceptionHandler("g2n", errorLogs)})
                    .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
                    .addInterceptors(selected == null ? new HandlerInterceptor[0] : new HandlerInterceptor[]{selected})
                    .build();
            assertEquals(installed ? 503 : 0, code(verb(configuredMvc, "POST", "/franchise/content/tasks",
                    "{\"kind\":\"digital\",\"voice\":\"v\",\"text\":\"text\","
                            + "\"avatar\":\"https://cdn.example.com/a.png\"}")));
            // 装配：T1 拒绝零写；未装配：真实链执行 QUEUED 行落库。
            assertEquals(installed ? 0 : 1, rows("fr_content_task"));
            assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
            assertThrows(IllegalStateException.class,
                    () -> gate.apply(new Control(3, State.FROZEN, SCOPE), FreezeEvidence.missing()));
        }
    }

    // ---------- 非目标读直通（GET /franchise/content/tasks 列表纯读） ----------

    @Test void nonTargetTaskListDoesNotWaitForContentJobsCheckpoint() throws Exception {
        store.entered = new CountDownLatch(1);
        store.release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var blocked = pool.submit(() -> gate.enter(Owner.B_LEGACY, "content-jobs", "synthetic-block"));
            assertTrue(store.entered.await(3, TimeUnit.SECONDS));
            var response = pool.submit(() -> {
                try {
                    return taskMvc.perform(get("/franchise/content/tasks")).andReturn();
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
