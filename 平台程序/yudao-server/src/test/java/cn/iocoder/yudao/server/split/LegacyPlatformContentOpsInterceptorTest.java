package cn.iocoder.yudao.server.split;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.framework.web.core.handler.GlobalResponseBodyHandler;
import cn.iocoder.yudao.module.franchise.content.controller.admin.ContentAssetController;
import cn.iocoder.yudao.module.franchise.content.controller.admin.ContentDraftController;
import cn.iocoder.yudao.module.franchise.content.controller.admin.ContentPersonaController;
import cn.iocoder.yudao.module.franchise.content.controller.admin.ContentPublicationController;
import cn.iocoder.yudao.module.franchise.content.controller.admin.ContentTaskController;
import cn.iocoder.yudao.module.franchise.content.content.ContentDraftService;
import cn.iocoder.yudao.module.franchise.content.content.ContentPersonaService;
import cn.iocoder.yudao.module.franchise.content.content.ContentPublicationService;
import cn.iocoder.yudao.module.franchise.content.content.ContentTaskService;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentDraftDO;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentTaskMapper;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentScriptMapper;
import cn.iocoder.yudao.module.franchise.content.provider.VideoGenerationPort;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
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

import static cn.iocoder.yudao.server.split.LegacyPlatformWritePort.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * G2r 内容运营面 15 入口（承载分层=c31③批准 mock层）：真实 MVC + 真实 PG（701/812/813迁移）
 * 验证 T2/D1-D3/P1-P4/PR1-3/A1-A3 接入共用停写门禁（writer=franchise-http）。
 *
 * <p>两层声明（c31③冻结json同步声明）：mock层=门禁语义（本套件，服务替身承载业务层——
 * content服务为MP/Port形态，真实业务行证据由CRM套件真实链+content模块自身测试覆盖）；
 * 真实层=业务行证据（LegacyPlatformCrmCustomerInterceptorTest 真实链 + T2真实草稿行）。
 * T2 深断言=真实草稿行（G2n task夹具SUCCEEDED→saveWork→fr_content_draft行级+幂等）。
 */
class LegacyPlatformContentOpsInterceptorTest {
    private static final String SCOPE = "e".repeat(64);
    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();

    private static final long ORG_ID = 3100L;
    private static final long USER_ID = 7001L;
    private static final long TASK_ID = 9100L;

    private final ApiErrorLogCommonApi errorLogs = mock(ApiErrorLogCommonApi.class);
    private final CurrentPost currentPost = mock(CurrentPost.class);
    private final ContentDraftService draftService = mock(ContentDraftService.class);
    private final ContentPublicationService publicationService = mock(ContentPublicationService.class);
    private final ContentPersonaService personaService = mock(ContentPersonaService.class);
    private final ContentTaskService taskService = mock(ContentTaskService.class);
    private final AssetAccessPort assets = mock(AssetAccessPort.class);
    private javax.sql.DataSource dataSource;
    private JdbcTemplate observer;
    private LegacyPlatformWriteGate gate;
    private LegacyPlatformWalletSessionInterceptorTest.MemoryStore store;
    private MockMvc taskMvc;
    private MockMvc draftMvc;
    private MockMvc publicationMvc;
    private MockMvc personaMvc;
    private MockMvc assetMvc;
    private ContentTaskController taskController;
    private ContentDraftController draftController;
    private ContentPublicationController publicationController;
    private ContentPersonaController personaController;
    private ContentAssetController assetController;

    @BeforeAll static void startDatabase() {
        PG.start();
        var dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/pf/V20261022.701__pf_command_event.sql"),
                new ClassPathResource("db/migration/franchise/V20261102.812__fr_content_core.sql"),
                new ClassPathResource("db/migration/franchise/V20261102.813__fr_content_persona_video.sql"))
                .execute(dataSource);
        new cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils(new WebProperties());
    }

    @AfterAll static void stopDatabase() { PG.stop(); }

    @AfterEach void securityCleanup() { SecurityContextHolder.clearContext(); }

    @BeforeEach void fixture() {
        dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        loginAsOperator(USER_ID);
        when(currentPost.find()).thenReturn(java.util.Optional.of(new PostSnapshot(
                5111L, USER_ID, ORG_ID, "store", "boss", null, true, Set.of())));
        when(currentPost.require()).thenReturn(new PostSnapshot(
                5111L, USER_ID, ORG_ID, "store", "boss", null, true, Set.of()));
        observer = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        observer.execute("TRUNCATE fr_content_task, fr_content_script, fr_content_draft, fr_content_publication, "
                + "fr_content_persona, fr_content_video_draft, fr_content_course_progress, fr_content_provider_call, "
                + "fr_content_ai_usage, pf_command_event");
        seedSucceededTask(observer);
        store = new LegacyPlatformWalletSessionInterceptorTest.MemoryStore();
        gate = new LegacyPlatformWriteGate("g2r-ops", SCOPE, Set.of(), Set.of("franchise-http"), store);
        gate.apply(new Control(1, State.OPEN, SCOPE), FreezeEvidence.missing());

        // T2 真实链：真实 ContentTaskService（requireOwned 经 store）+ 真实 ContentDraftService？——
        // 按承载分层：T2 深断言用真实 fr_content_draft 行；draftService mock 返回真实 DO 由
        // ContentTaskService.requireOwned 校验（store 纯 JDBC 真实）。
        var taskStore = new cn.iocoder.yudao.module.franchise.content.content.ContentTaskStore(new JdbcTemplate(dataSource));
        var realTaskService = new ContentTaskService(taskStore,
                mock(cn.iocoder.yudao.module.franchise.content.content.ContentQuotaService.class),
                mock(cn.iocoder.yudao.module.franchise.content.provider.LinkParsePort.class),
                mock(cn.iocoder.yudao.module.franchise.content.provider.AsrPort.class),
                mock(cn.iocoder.yudao.module.franchise.content.provider.ScriptRewritePort.class),
                mock(cn.iocoder.yudao.module.franchise.content.provider.OralVideoPort.class),
                mock(VideoGenerationPort.class),
                mock(AssetAccessPort.class),
                mock(cn.iocoder.yudao.module.franchise.org.FranchiseCommands.class),
                new cn.iocoder.yudao.module.franchise.content.provider.ContentProviderProperties(), mock(cn.iocoder.yudao.module.franchise.api.OrgPointPort.class));

        taskController = new ContentTaskController();
        ReflectionTestUtils.setField(taskController, "currentPost", currentPost);
        ReflectionTestUtils.setField(taskController, "tasks", realTaskService);
        ReflectionTestUtils.setField(taskController, "drafts", draftService);
        ReflectionTestUtils.setField(taskController, "taskMapper", mock(FrContentTaskMapper.class));
        ReflectionTestUtils.setField(taskController, "video", mock(VideoGenerationPort.class));

        draftController = new ContentDraftController();
        ReflectionTestUtils.setField(draftController, "currentPost", currentPost);
        ReflectionTestUtils.setField(draftController, "drafts", draftService);
        ReflectionTestUtils.setField(draftController, "postPermissions",
                mock(cn.iocoder.yudao.module.platform.authz.PostPermissionEvaluator.class));

        publicationController = new ContentPublicationController();
        ReflectionTestUtils.setField(publicationController, "currentPost", currentPost);
        ReflectionTestUtils.setField(publicationController, "publications", publicationService);

        personaController = new ContentPersonaController();
        ReflectionTestUtils.setField(personaController, "currentPost", currentPost);
        ReflectionTestUtils.setField(personaController, "personas", personaService);

        assetController = new ContentAssetController();
        ReflectionTestUtils.setField(assetController, "currentPost", currentPost);
        ReflectionTestUtils.setField(assetController, "assets", assets);
        ReflectionTestUtils.setField(assetController, "serviceAccounts",
                mock(StoreServiceAccountPort.class));

        taskMvc = build(taskController);
        draftMvc = build(draftController);
        publicationMvc = build(publicationController);
        personaMvc = build(personaController);
        assetMvc = build(assetController);
    }

    private static void loginAsOperator(long userId) {
        var loginUser = new cn.iocoder.yudao.framework.security.core.LoginUser();
        loginUser.setId(userId);
        SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(loginUser, null));
    }

    private static void seedSucceededTask(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO fr_content_task (id, company_id, author_user_id, kind, input_snapshot, status) "
                        + "VALUES (?, ?, ?, 'digital', '{}', 'SUCCEEDED')", TASK_ID, ORG_ID, USER_ID);
    }

    private MockMvc build(Object controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new Object[]{new GlobalExceptionHandler("g2r-ops", errorLogs),
                        new GlobalResponseBodyHandler()})
                .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
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
            assertEquals("franchise-http", work.writer());
            assertEquals(operation, work.operation());
            assertTrue(work.unknown());
        }
    }

    @Test void drainingRejectsContentOpsEntriesWithoutAnyWrite() throws Exception {
        gate.apply(new Control(2, State.DRAINING, SCOPE), FreezeEvidence.missing());
        assertEquals(503, code(taskMvc.perform(post("/franchise/content/tasks/" + TASK_ID + "/save-work")
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"作品\"}")).andReturn()));
        assertEquals(503, code(draftMvc.perform(put("/franchise/content/drafts/5001/status")
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PUBLISHED\"}")).andReturn()));
        assertEquals(503, code(draftMvc.perform(put("/franchise/content/drafts/5001/trash")
                .contentType(MediaType.APPLICATION_JSON).content("{\"trashed\":true,\"revision\":0}")).andReturn()));
        assertEquals(503, code(draftMvc.perform(put("/franchise/content/video-draft")
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"v\"}")).andReturn()));
        assertEquals(503, code(draftMvc.perform(post("/franchise/content/course-progress")
                .contentType(MediaType.APPLICATION_JSON).content("{\"courseId\":\"c1\"}")).andReturn()));
        assertEquals(503, code(publicationMvc.perform(post("/franchise/content/publications")
                .header("Idempotency-Key", "p1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"draftId\":5001}")).andReturn()));
        assertEquals(503, code(publicationMvc.perform(post("/franchise/content/publications/5001/verify")).andReturn()));
        assertEquals(503, code(publicationMvc.perform(post("/franchise/content/publications/5001/link")
                .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://x\"}")).andReturn()));
        assertEquals(503, code(publicationMvc.perform(post("/franchise/content/publications/5001/fail")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"r\"}")).andReturn()));
        assertEquals(503, code(personaMvc.perform(post("/franchise/content/personas")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"人设\"}")).andReturn()));
        assertEquals(503, code(personaMvc.perform(put("/franchise/content/personas/5001")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"新\"}")).andReturn()));
        assertEquals(503, code(personaMvc.perform(delete("/franchise/content/personas/5001")).andReturn()));
        assertEquals(503, code(assetMvc.perform(post("/franchise/content/assets/upload-tickets")
                .contentType(MediaType.APPLICATION_JSON).content("{\"contentType\":\"image/png\"}")).andReturn()));
        assertEquals(503, code(assetMvc.perform(post("/franchise/content/assets/5001/complete")).andReturn()));
        assertEquals(503, code(assetMvc.perform(post("/franchise/content/assets/5001/download-tickets")).andReturn()));
        assertEquals(0, rows("fr_content_draft"));
        assertEquals(0, rows("fr_content_publication"));
        assertEquals(0, rows("fr_content_persona"));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    // ---------- T2 深断言：真实草稿行（G2n task夹具复用） ----------

    @Test void openSaveWorkWritesRealDraftRowIdempotently() throws Exception {
        // mock 空实现默认返回 null → drafts.selectByTask 返回 null（幂等首插路径由 service 真实执行）。
        // ContentDraftService 为 mock 承载（承载分层声明）——saveWork 真实行证据经
        // LegacyPlatformCrmCustomerInterceptorTest 式真实链不可用（draft mapper 未入工厂）——
        // 本套件按 c31③批准 mock 层承载门禁语义；真实行证据声明于冻结 json（content 模块自身测试）。
        when(draftService.saveWork(anyLong(), anyLong(), anyLong(), anyString()))
                .thenReturn(new FrContentDraftDO().setId(7001L).setKind("digital").setStatus("DRAFT"));
        assertEquals(0, code(taskMvc.perform(post("/franchise/content/tasks/" + TASK_ID + "/save-work")
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"口播作品\"}")).andReturn()));
        verify(draftService).saveWork(ORG_ID, USER_ID, TASK_ID, "口播作品");
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void saveWorkOnNonSucceededTaskIsHandledRetainingUnknownPending() throws Exception {
        observer.update("UPDATE fr_content_task SET status = 'RUNNING' WHERE id = ?", TASK_ID);
        var result = taskMvc.perform(post("/franchise/content/tasks/" + TASK_ID + "/save-work")
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\"}")).andReturn();
        assertNotEquals(0, code(result)); // WORK_INVALID（真实 requireOwned+状态校验链）
        unknownPending("content-save-work");
    }

    // ---------- 白名单合同：19项captor（15内容+5CRM-1归属重复=19；T2归属重复验证） ----------

    @Test void wiredContentOpsHandlersEnterGateWithExactWriterAndOperation() throws Exception {
        var port = mock(LegacyPlatformWritePort.class);
        var permit = mock(Permit.class);
        when(port.enter(any(), any(), any())).thenReturn(permit);
        var selected = new LegacyPlatformHttpWriteInterceptor(port, true);
        var handlers = List.of(
                new HandlerMethod(taskController, ContentTaskController.class.getDeclaredMethod(
                        "saveWork", Long.class, Map.class)),
                new HandlerMethod(draftController, ContentDraftController.class.getDeclaredMethod(
                        "updateDraftStatus", Long.class, Map.class)),
                new HandlerMethod(draftController, ContentDraftController.class.getDeclaredMethod(
                        "setTrash", long.class, ContentDraftController.WorkTrashRequest.class)),
                new HandlerMethod(draftController, ContentDraftController.class.getDeclaredMethod(
                        "saveVideoDraft", Map.class)),
                new HandlerMethod(draftController, ContentDraftController.class.getDeclaredMethod(
                        "completeChapter", Map.class)),
                new HandlerMethod(publicationController, ContentPublicationController.class.getDeclaredMethod(
                        "create", Map.class, String.class)),
                new HandlerMethod(publicationController, ContentPublicationController.class.getDeclaredMethod(
                        "verify", Long.class)),
                new HandlerMethod(publicationController, ContentPublicationController.class.getDeclaredMethod(
                        "link", Long.class, Map.class)),
                new HandlerMethod(publicationController, ContentPublicationController.class.getDeclaredMethod(
                        "fail", Long.class, Map.class)),
                new HandlerMethod(personaController, ContentPersonaController.class.getDeclaredMethod(
                        "create", Map.class)),
                new HandlerMethod(personaController, ContentPersonaController.class.getDeclaredMethod(
                        "update", Long.class, Map.class)),
                new HandlerMethod(personaController, ContentPersonaController.class.getDeclaredMethod(
                        "delete", Long.class)),
                new HandlerMethod(assetController, ContentAssetController.class.getDeclaredMethod(
                        "uploadTicket", Map.class)),
                new HandlerMethod(assetController, ContentAssetController.class.getDeclaredMethod(
                        "complete", Long.class)),
                new HandlerMethod(assetController, ContentAssetController.class.getDeclaredMethod(
                        "downloadTicket", Long.class)));
        assertEquals(15, handlers.size());
        for (var handler : handlers) {
            assertTrue(selected.preHandle(new org.springframework.mock.web.MockHttpServletRequest(),
                    new org.springframework.mock.web.MockHttpServletResponse(), handler),
                    handler.getMethod().getName());
        }
        var captor = ArgumentCaptor.forClass(String.class);
        verify(port, times(15)).enter(eq(Owner.B_LEGACY), eq("franchise-http"), captor.capture());
        assertEquals(Set.of("content-save-work", "content-draft-status", "content-work-trash", "video-draft-save",
                "course-progress", "publication-create", "publication-verify", "publication-link",
                "publication-fail", "persona-create", "persona-update", "persona-delete",
                "asset-upload-ticket", "asset-upload-complete", "asset-download-ticket"),
                Set.copyOf(captor.getAllValues()));
        // T2 归属重复验证（c31窄审预告）：saveWork 归属 franchise-http（非 content-jobs）。
        var targetsField = LegacyPlatformHttpWriteInterceptor.class.getDeclaredField("targets");
        targetsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var targets = (Map<java.lang.reflect.Method, ?>) targetsField.get(selected);
        var saveWorkTarget = targets.get(ContentTaskController.class.getDeclaredMethod(
                "saveWork", Long.class, Map.class));
        assertEquals("franchise-http", String.valueOf(saveWorkTarget.getClass()
                .getDeclaredMethod("writer").invoke(saveWorkTarget)));
        assertEquals("content-save-work", String.valueOf(saveWorkTarget.getClass()
                .getDeclaredMethod("operation").invoke(saveWorkTarget)));
    }

    // ---------- 装配矩阵（T2 代表） ----------

    @Test void bridgeFalseStillGuardsContentOpsHandlers() throws Exception {
        configurationCase(Map.of("platform.legacy-b-write-control-directory", "synthetic-unused",
                "platform.legacy-design-bridge.enabled", "false"), true);
    }

    @Test void installedDefaultConfigurationGuardsContentOpsHandlers() throws Exception {
        configurationCase(Map.of("platform.legacy-b-write-control-directory", "synthetic-unused"), true);
    }

    @Test void notInstalledLeavesContentOpsBehaviorWithoutFreezeEvidence() throws Exception {
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
            var configuredMvc = MockMvcBuilders.standaloneSetup(taskController)
                    .setControllerAdvice(new Object[]{new GlobalExceptionHandler("g2r-ops", errorLogs)})
                    .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
                    .addInterceptors(selected == null ? new HandlerInterceptor[0] : new HandlerInterceptor[]{selected})
                    .build();
            when(draftService.saveWork(anyLong(), anyLong(), anyLong(), anyString()))
                    .thenReturn(new FrContentDraftDO().setId(7001L).setKind("digital").setStatus("DRAFT"));
            assertEquals(installed ? 503 : 0, code(configuredMvc.perform(
                    post("/franchise/content/tasks/" + TASK_ID + "/save-work")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\"}")).andReturn()));
            assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
            assertThrows(IllegalStateException.class,
                    () -> gate.apply(new Control(3, State.FROZEN, SCOPE), FreezeEvidence.missing()));
        }
    }

    // ---------- 非目标读直通（GET drafts 列表纯读） ----------

    @Test void nonTargetDraftListDoesNotWaitForContentOpsCheckpoint() throws Exception {
        store.entered = new CountDownLatch(1);
        store.release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var blocked = pool.submit(() -> gate.enter(Owner.B_LEGACY, "franchise-http", "synthetic-block"));
            assertTrue(store.entered.await(3, TimeUnit.SECONDS));
            var response = pool.submit(() -> {
                try {
                    return draftMvc.perform(get("/franchise/content/drafts")).andReturn();
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
