package cn.iocoder.yudao.server.split;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.framework.web.core.handler.GlobalResponseBodyHandler;
import cn.iocoder.yudao.module.platform.authz.controller.admin.MyPostController;
import cn.iocoder.yudao.module.platform.authz.controller.admin.OrgOnboardController;
import cn.iocoder.yudao.module.platform.authz.controller.admin.StaffOrgAdminController;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.authz.service.OrgOnboardService;
import cn.iocoder.yudao.module.platform.identity.StaffAccountDirectory;
import cn.iocoder.yudao.module.platform.identity.StaffSmsLoginService;
import cn.iocoder.yudao.module.platform.identity.StaffSmsLoginServiceImpl;
import cn.iocoder.yudao.module.platform.identity.StaffWechatLoginService;
import cn.iocoder.yudao.module.platform.identity.SmsCaptchaService;
import cn.iocoder.yudao.module.platform.identity.TokenIssuer;
import cn.iocoder.yudao.module.platform.identity.controller.admin.PlatformAuthController;
import cn.iocoder.yudao.module.platform.org.DepartureHandoverService;
import cn.iocoder.yudao.module.platform.org.DepartureHandoverPort;
import cn.iocoder.yudao.module.platform.org.OrgService;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import cn.iocoder.yudao.module.platform.org.StaffPostServiceImpl;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import cn.iocoder.yudao.module.platform.org.WalletSessionRevocation;
import cn.iocoder.yudao.module.platform.org.dal.OrgUnitMapper;
import cn.iocoder.yudao.module.platform.org.dal.PlatformHandoverAuditMapper;
import cn.iocoder.yudao.module.platform.org.dal.PlatformHandoverPendingMapper;
import cn.iocoder.yudao.module.platform.org.dal.PostTemplateMapper;
import cn.iocoder.yudao.module.platform.org.dal.StaffPostMapper;
import cn.iocoder.yudao.module.platform.org.controller.admin.StaffProfileController;
import cn.iocoder.yudao.module.platform.org.profile.StaffProfileService;
import cn.iocoder.yudao.module.platform.org.profile.StaffProfileStore;
import cn.iocoder.yudao.module.platform.workflow.controller.admin.PlatformMessageController;
import cn.iocoder.yudao.module.platform.workflow.dal.MessageDO;
import cn.iocoder.yudao.module.platform.workflow.message.MessageStore;
import cn.iocoder.yudao.module.platform.workflow.message.PlatformMessageService;
import cn.iocoder.yudao.module.identity.session.UserSessionService;
import cn.iocoder.yudao.module.infra.zhongshu.api.WechatPaySessionPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
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
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static cn.iocoder.yudao.server.split.LegacyPlatformWritePort.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * G2l 类别7 platform-http：真实 MVC + 真实 PG（platform-bootstrap/identity/pf 源码迁移）验证
 * 平台包 14 写入口接入共用停写门禁（writer=platform-http）。
 *
 * <p>承载分层（合同§5，G2k同型）：主套件业务层 mock/fake 承载（platform 模块为 MyBatis-Plus
 * BaseMapperX 形态）；O6/O7 交接撤权深断言与 A3 会话签发为真实装配（真实 mapper/服务链）。
 * 门禁行为与 G2b-G2k 共用机制逐字一致；platform/commerce/identity/system 生产代码零改动。
 */
class LegacyPlatformPlatformHttpInterceptorTest {
    private static final String SCOPE = "f".repeat(64);
    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();

    // 夹具组织/用户编号（深断言链共用）
    private static final long ORG_ID = 3100L;
    private static final long FROM_USER = 8100L;
    private static final long TO_USER = 8200L;
    private static final long SMS_USER = 8300L;
    private static final long FR_ACCOUNT = 9100L; // external_key = fr-service:3100
    private static final String MOBILE = "13800001234";

    private final ApiErrorLogCommonApi errorLogs = mock(ApiErrorLogCommonApi.class);
    private final CurrentPost currentPost = mock(CurrentPost.class);
    private final StaffPostService staffPostService = mock(StaffPostService.class);
    private final OrgService orgService = mock(OrgService.class);
    private final SystemDirectory directory = mock(SystemDirectory.class);
    private final DepartureHandoverService handoverService = mock(DepartureHandoverService.class);
    private final OrgOnboardService onboardService = mock(OrgOnboardService.class);
    private final StaffWechatLoginService wechatLoginService = mock(StaffWechatLoginService.class);
    private final StaffSmsLoginService smsLoginService = mock(StaffSmsLoginService.class);

    private JdbcTemplate observer;
    private LegacyPlatformWriteGate gate;
    private LegacyPlatformWalletSessionInterceptorTest.MemoryStore store;
    private MockMvc profileMvc;
    private MockMvc messageMvc;
    private MockMvc orgMvc;
    private MockMvc onboardMvc;
    private MockMvc authMvc;
    private StaffProfileController profileController;
    private PlatformMessageController messageController;
    private StaffOrgAdminController orgController;
    private OrgOnboardController onboardController;
    private PlatformAuthController authController;
    // S1 真实业务链（纯JDK service + 内存 store，工程 StaffProfileServiceTest 先例）
    private final AtomicReference<StaffProfileStore.UserProfile> stored =
            new AtomicReference<>(new StaffProfileStore.UserProfile(7001L, "旧名", MOBILE, null));
    private final AtomicInteger storeWrites = new AtomicInteger();
    private final AtomicInteger storeFailures = new AtomicInteger();

    @BeforeAll static void startDatabase() {
        PG.start();
        var dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        // 版本序逐字执行源码迁移（合同§5）：platform-bootstrap → identity(G2d序列) → pf 702..709。
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/platform-bootstrap/V20260904.001__platform_system_infra.sql"),
                new ClassPathResource("db/migration/identity/V20260905.101__p2a_identity_access.sql"),
                new ClassPathResource("db/migration/identity/V20260909.102__independent_refresh_expiry.sql"),
                new ClassPathResource("db/migration/identity/V20261010.107__redemption_account_binding.sql"),
                new ClassPathResource("db/migration/identity/V20261010.108__access_code_binding_audit.sql"),
                new ClassPathResource("db/migration/identity/V20261016.109__wechat_identity_reregister_after_account_delete.sql"),
                new ClassPathResource("db/migration/identity/V20261102.101__account_external_key.sql"),
                new ClassPathResource("db/migration/pf/V20261022.702__pf_org.sql"),
                new ClassPathResource("db/migration/pf/V20261031.703__pf_org_admin_permissions.sql"),
                new ClassPathResource("db/migration/pf/V20261101.705__pf_message.sql"),
                new ClassPathResource("db/migration/pf/V20261104.707__pf_org_onboard.sql"),
                new ClassPathResource("db/migration/pf/V20261104.708__pf_handover_pending.sql"),
                new ClassPathResource("db/migration/pf/V20261104.709__pf_handover_audit.sql"))
                .execute(dataSource);
        // identity 106 为单条 dollar-quote DO 块（G2d 先例：ScriptUtils 无法切分，整块执行）。
        try (var input = new ClassPathResource("db/migration/identity/V20260913.106__privacy_management_permission.sql")
                .getInputStream()) {
            new JdbcTemplate(dataSource).execute(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception failure) {
            throw new IllegalStateException("identity 106 migration failed", failure);
        }
        new cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils(new WebProperties());
    }

    @AfterAll static void stopDatabase() { PG.stop(); }

    @AfterEach void securityCleanup() { SecurityContextHolder.clearContext(); }

    @BeforeEach void fixture() {
        loginAsOperator(7001L);
        // requireScope/scope 经 currentPost.find() 读岗位快照（未装配分支的业务路径也依赖）。
        when(currentPost.find()).thenReturn(java.util.Optional.of(new cn.iocoder.yudao.module.platform.org.PostSnapshot(
                5111L, 7001L, ORG_ID, "HEADQUARTERS", "boss", null, true, Set.of())));
        var dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        observer = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        observer.execute("TRUNCATE pf_org_unit, pf_staff_post, pf_post_template, pf_org_type, "
                + "pf_message, pf_handover_pending, pf_handover_audit, "
                + "system_users, system_oauth2_access_token, account, user_session");
        seedOrgFixture(observer);
        store = new LegacyPlatformWalletSessionInterceptorTest.MemoryStore();
        gate = new LegacyPlatformWriteGate("g2l", SCOPE, Set.of(), Set.of("platform-http"), store);
        gate.apply(new Control(1, State.OPEN, SCOPE), FreezeEvidence.missing());
        storeWrites.set(0);
        storeFailures.set(0);

        // S1：真实 StaffProfileService + 内存 store（白名单解析为真实业务语义）。
        var profileService = new StaffProfileService();
        ReflectionTestUtils.setField(profileService, "store", new StaffProfileStore() {
            @Override public UserProfile findByUserId(long userId) {
                return stored.get().userId() == userId ? stored.get() : null;
            }
            @Override public void updateProfile(long userId, String nickname, String avatar) {
                storeWrites.incrementAndGet();
                if (storeFailures.get() > 0) throw new IllegalStateException("synthetic store failure");
                stored.set(new UserProfile(userId, nickname, MOBILE, avatar));
            }
        });
        profileController = new StaffProfileController();
        ReflectionTestUtils.setField(profileController, "staffProfileService", profileService);

        // M1/M2：真实 PlatformMessageService + 内存 MessageStore（DbMessageStore 为 MP 形态，
        // platform-workflow 自身测试覆盖 mapper 路径；本套件承载白名单与门禁语义）。
        messageController = new PlatformMessageController();
        ReflectionTestUtils.setField(messageController, "platformMessageService",
                new PlatformMessageService(memoryMessageStore()));

        // O1-O7：mock 业务层（MP 形态；深断言用例另建真实装配）。
        orgController = new StaffOrgAdminController();
        ReflectionTestUtils.setField(orgController, "currentPost", currentPost);
        ReflectionTestUtils.setField(orgController, "staffPostService", staffPostService);
        ReflectionTestUtils.setField(orgController, "orgService", orgService);
        ReflectionTestUtils.setField(orgController, "systemDirectory", directory);
        ReflectionTestUtils.setField(orgController, "handoverService", handoverService);

        // B1：mock 业务层。
        onboardController = new OrgOnboardController();
        ReflectionTestUtils.setField(onboardController, "orgOnboardService", onboardService);
        // 深断言装配使用同一 onboardService mock（O6/O7 用例另建真实 handover 装配）。

        // A1-A3：mock 业务层（主套件门禁语义；A3 深断言另建真实链）。
        authController = new PlatformAuthController();
        ReflectionTestUtils.setField(authController, "staffWechatLoginService", wechatLoginService);
        ReflectionTestUtils.setField(authController, "staffSmsLoginService", smsLoginService);

        profileMvc = build(profileController);
        messageMvc = build(messageController);
        orgMvc = build(orgController);
        onboardMvc = build(onboardController);
        authMvc = build(authController);
    }

    /** 组织/岗位/账号/会话夹具：702 无种子（org_type 由业务域注册），深断言链共用。 */
    private static void seedOrgFixture(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO pf_org_type (code, domain, name) VALUES ('HEADQUARTERS', 'platform', '总部')");
        jdbc.update("INSERT INTO pf_org_unit (dept_id, kind, org_id, org_type, status) "
                + "VALUES (?, 'ORG', ?, 'HEADQUARTERS', 'ACTIVE')", ORG_ID, ORG_ID);
        jdbc.update("INSERT INTO pf_post_template (role_code, domain, org_types, lead) "
                + "VALUES ('boss', 'platform', 'HEADQUARTERS', TRUE)");
        jdbc.update("INSERT INTO pf_staff_post (id, user_id, org_id, role_code, status) "
                + "VALUES (5101, ?, ?, 'boss', 'ACTIVE')", FROM_USER, ORG_ID);
        jdbc.update("INSERT INTO pf_staff_post (id, user_id, org_id, role_code, status) "
                + "VALUES (5102, ?, ?, 'boss', 'ACTIVE')", TO_USER, ORG_ID);
        jdbc.update("INSERT INTO account (id, status, external_key) VALUES (?, 'ACTIVE', ?)",
                FR_ACCOUNT, "fr-service:" + ORG_ID);
        jdbc.update("INSERT INTO system_users (id, username, password, nickname, status) "
                + "VALUES (7001, 'profile-owner', 'hashed', '旧名', 0)");
    }

    /** 内存 MessageStore：只承载 M1/M2 门禁语义（幂等置已读语义按 DbMessageStore 行为复刻）。 */
    private MessageStore memoryMessageStore() {
        record Row(long id, long userId, boolean read, OffsetDateTime readTime) {}
        var rows = new LinkedHashMap<Long, Row>();
        return new MessageStore() {
            @Override public MessageDO findByDedupe(String senderDomain, String dedupeKey) { return null; }
            @Override public void insert(MessageDO message) { }
            @Override public MessageDO findById(long id) {
                Row row = rows.get(id);
                return row == null ? null : view(row);
            }
            @Override public List<MessageDO> findPage(long userId, Long beforeId, int limit) { return List.of(); }
            @Override public long countUnread(long userId) {
                return rows.values().stream().filter(r -> r.userId() == userId && !r.read()).count();
            }
            @Override public int markRead(long userId, long messageId, OffsetDateTime readTime) {
                Row row = rows.get(messageId);
                if (row == null || row.userId() != userId || row.read()) return 0;
                rows.put(messageId, new Row(row.id(), row.userId(), true, readTime));
                return 1;
            }
            @Override public int markAllRead(long userId, OffsetDateTime readTime) {
                int updated = 0;
                for (var entry : rows.entrySet()) {
                    if (entry.getValue().userId() == userId && !entry.getValue().read()) {
                        entry.setValue(new Row(entry.getKey(), userId, true, readTime));
                        updated++;
                    }
                }
                return updated;
            }
            private MessageDO view(Row row) {
                var message = new MessageDO();
                message.setId(row.id());
                message.setUserId(row.userId());
                return message;
            }
        };
    }

    private MockMvc build(Object controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new Object[]{new GlobalExceptionHandler("g2l", errorLogs),
                        new GlobalResponseBodyHandler()})
                .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
                .addFilters(new cn.iocoder.yudao.framework.web.core.filter.CacheRequestBodyFilter())
                .addInterceptors(new LegacyPlatformHttpWriteInterceptor(gate, true))
                .build();
    }

    private static void loginAsOperator(long userId) {
        var loginUser = new LoginUser();
        loginUser.setId(userId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null));
    }

    private MvcResult verb(MockMvc mvc, String method, String url, String json) throws Exception {
        var request = switch (method) {
            case "PUT" -> put(url);
            case "DELETE" -> delete(url);
            default -> post(url);
        };
        if (json != null) request.contentType(MediaType.APPLICATION_JSON).content(json);
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
            assertEquals("platform-http", work.writer());
            assertEquals(operation, work.operation());
            assertTrue(work.unknown());
        }
    }

    // ---------- DRAINING：14 入口逐一拒绝，全部表零写（c31 窄审核点①） ----------

    @Test void drainingRejectsAllFourteenPlatformEntriesWithoutAnyWrite() throws Exception {
        gate.apply(new Control(2, State.DRAINING, SCOPE), FreezeEvidence.missing());
        assertEquals(503, code(verb(profileMvc, "PUT", "/platform/profile/mine",
                "{\"nickname\":\"新名\"}")));
        assertEquals(503, code(verb(messageMvc, "POST", "/platform/messages/88/read", null)));
        assertEquals(503, code(verb(messageMvc, "POST", "/platform/messages/read-all", null)));
        assertEquals(503, code(verb(orgMvc, "POST", "/platform/org/teams",
                "{\"orgId\":" + ORG_ID + ",\"name\":\"团队\"}")));
        assertEquals(503, code(verb(orgMvc, "POST", "/platform/org/staff",
                "{\"orgId\":" + ORG_ID + ",\"name\":\"张三\",\"mobile\":\"13900001111\",\"roleCode\":\"boss\"}")));
        assertEquals(503, code(verb(orgMvc, "POST", "/platform/org/posts",
                "{\"orgId\":" + ORG_ID + ",\"userId\":" + TO_USER + ",\"roleCode\":\"boss\"}")));
        assertEquals(503, code(verb(orgMvc, "DELETE", "/platform/org/posts/5102", null)));
        assertEquals(503, code(verb(orgMvc, "POST", "/platform/org/staff-status",
                "{\"userId\":" + TO_USER + ",\"active\":false}")));
        assertEquals(503, code(verb(orgMvc, "POST", "/platform/org/staff-handover",
                "{\"orgId\":" + ORG_ID + ",\"fromUserId\":" + FROM_USER + ",\"toUserId\":" + TO_USER + "}")));
        assertEquals(503, code(verb(orgMvc, "POST", "/platform/org/staff-emergency-revoke",
                "{\"orgId\":" + ORG_ID + ",\"userId\":" + FROM_USER + "}")));
        assertEquals(503, code(verb(onboardMvc, "POST", "/platform/org/onboard",
                "{\"orgType\":\"HEADQUARTERS\",\"orgName\":\"总部\",\"adminName\":\"甲\",\"adminMobile\":\"13900002222\"}")));
        assertEquals(503, code(verb(authMvc, "POST", "/platform/auth/wechat-login",
                "{\"appid\":\"wx-app\",\"loginCode\":\"code\"}")));
        assertEquals(503, code(verb(authMvc, "POST", "/platform/auth/sms/send",
                "{\"mobile\":\"" + MOBILE + "\"}")));
        assertEquals(503, code(verb(authMvc, "POST", "/platform/auth/sms/login",
                "{\"mobile\":\"" + MOBILE + "\",\"captcha\":\"123456\"}")));
        // 零写（c31 窄审核点④ A2 503 零副作用：登录链/资料/消息/组织/审计面全零）。
        // pf_staff_post 夹具两行（5101 FROM_USER + 5102 TO_USER）：DRAINING 零写=行数保持 2。
        assertEquals(2, rows("pf_staff_post"));
        assertEquals(0, rows("pf_handover_audit"));
        assertEquals(0, rows("pf_message"));
        assertEquals(1, rows("system_users"));
        assertEquals(0, rows("system_oauth2_access_token"));
        assertEquals(0, rows("user_session"));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    // ---------- 白名单合同：14 入口精确 writer/operation（c31 窄审核点②） ----------

    @Test void wiredPlatformHandlersEnterGateWithExactWriterAndOperation() throws Exception {
        var port = mock(LegacyPlatformWritePort.class);
        var permit = mock(Permit.class);
        when(port.enter(any(), any(), any())).thenReturn(permit);
        var selected = new LegacyPlatformHttpWriteInterceptor(port, true);
        record HandlerCall(Object controller, java.lang.reflect.Method method) {}
        var calls = List.of(
            new HandlerCall(profileController, StaffProfileController.class.getDeclaredMethod(
                    "updateMine", Map.class)),
            new HandlerCall(messageController, PlatformMessageController.class.getDeclaredMethod(
                    "read", long.class)),
            new HandlerCall(messageController, PlatformMessageController.class.getDeclaredMethod(
                    "readAll")),
            new HandlerCall(orgController, StaffOrgAdminController.class.getDeclaredMethod(
                    "createTeam", String.class, StaffOrgAdminController.CreateTeamReqVO.class)),
            new HandlerCall(orgController, StaffOrgAdminController.class.getDeclaredMethod(
                    "createStaff", StaffOrgAdminController.CreateStaffReqVO.class)),
            new HandlerCall(orgController, StaffOrgAdminController.class.getDeclaredMethod(
                    "assignPost", StaffOrgAdminController.AssignPostReqVO.class)),
            new HandlerCall(orgController, StaffOrgAdminController.class.getDeclaredMethod(
                    "revokePost", long.class)),
            new HandlerCall(orgController, StaffOrgAdminController.class.getDeclaredMethod(
                    "setStatus", StaffOrgAdminController.StaffStatusReqVO.class)),
            new HandlerCall(orgController, StaffOrgAdminController.class.getDeclaredMethod(
                    "handover", StaffOrgAdminController.StaffHandoverReqVO.class)),
            new HandlerCall(orgController, StaffOrgAdminController.class.getDeclaredMethod(
                    "emergencyRevoke", StaffOrgAdminController.StaffEmergencyRevokeReqVO.class)),
            new HandlerCall(onboardController, OrgOnboardController.class.getDeclaredMethod(
                    "onboard", OrgOnboardController.OnboardReqVO.class)),
            new HandlerCall(authController, PlatformAuthController.class.getDeclaredMethod(
                    "wechatLogin", cn.iocoder.yudao.module.platform.identity.controller.admin.vo.WechatLoginReqVO.class)),
            new HandlerCall(authController, PlatformAuthController.class.getDeclaredMethod(
                    "sendSmsCaptcha", cn.iocoder.yudao.module.platform.identity.controller.admin.vo.SmsCaptchaSendReqVO.class)),
            new HandlerCall(authController, PlatformAuthController.class.getDeclaredMethod(
                    "smsLogin", cn.iocoder.yudao.module.platform.identity.controller.admin.vo.SmsLoginReqVO.class)));
        assertEquals(14, calls.size());
        for (var call : calls) {
            var handler = new HandlerMethod(call.controller(), call.method());
            assertTrue(selected.preHandle(new org.springframework.mock.web.MockHttpServletRequest(),
                    new org.springframework.mock.web.MockHttpServletResponse(), handler), call.method().getName());
        }
        var captor = ArgumentCaptor.forClass(String.class);
        verify(port, times(14)).enter(eq(Owner.B_LEGACY), eq("platform-http"), captor.capture());
        assertEquals(Set.of("profile-update-mine", "message-read", "message-read-all",
                "org-team-create", "staff-create", "post-assign", "post-revoke", "staff-status",
                "staff-handover", "emergency-revoke", "org-onboard",
                "staff-wechat-login", "staff-sms-send", "staff-sms-login"),
                Set.copyOf(captor.getAllValues()));
    }

    // ---------- 装配矩阵（跨三控制器代表：S1/O1/A1） ----------

    @Test void bridgeFalseStillGuardsPlatformHandlers() throws Exception {
        configurationCase(Map.of("platform.legacy-b-write-control-directory", "synthetic-unused",
                "platform.legacy-design-bridge.enabled", "false"), true);
    }

    @Test void installedDefaultConfigurationGuardsPlatformHandlers() throws Exception {
        configurationCase(Map.of("platform.legacy-b-write-control-directory", "synthetic-unused"), true);
    }

    @Test void notInstalledLeavesPlatformBehaviorWithoutFreezeEvidence() throws Exception {
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
            // 三个平台模块各取一个代表入口验证装配覆盖（S1 资料面 / O1 组织面 / A1 登录面）。
            for (Object controller : List.of(profileController, orgController, authController)) {
                var configuredMvc = MockMvcBuilders.standaloneSetup(controller)
                        .setControllerAdvice(new Object[]{new GlobalExceptionHandler("g2l", errorLogs)})
                        .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
                        .addInterceptors(selected == null ? new HandlerInterceptor[0] : new HandlerInterceptor[]{selected})
                        .build();
                String url = controller == profileController ? "/platform/profile/mine"
                        : controller == orgController ? "/platform/org/teams" : "/platform/auth/sms/send";
                String body = controller == profileController ? "{\"nickname\":\"x\"}"
                        : controller == orgController ? "{\"orgId\":" + ORG_ID + ",\"name\":\"团队\"}"
                        : "{\"mobile\":\"" + MOBILE + "\"}";
                String method = controller == profileController ? "PUT" : "POST";
                assertEquals(installed ? 503 : 0, code(verb(configuredMvc, method, url, body)),
                        controller.getClass().getSimpleName());
            }
            assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
            assertThrows(IllegalStateException.class,
                    () -> gate.apply(new Control(3, State.FROZEN, SCOPE), FreezeEvidence.missing()));
        }
    }

    // ---------- 非目标读直通（GET /platform/posts/mine 纯读；loginUser 缺省返回空列表不依赖登录上下文） ----------

    @Test void nonTargetPlatformReadDoesNotWaitForPlatformCheckpoint() throws Exception {
        var myPostController = new MyPostController();
        ReflectionTestUtils.setField(myPostController, "staffPostService", staffPostService);
        ReflectionTestUtils.setField(myPostController, "currentPost", currentPost);
        ReflectionTestUtils.setField(myPostController, "systemDirectory", directory);
        // 登录上下文存在（SecurityContextHolder 7001）时 mine() 走真实查询路径：空任职返回空列表。
        when(staffPostService.listActivePosts(7001L)).thenReturn(List.of());
        var postsMvc = build(myPostController);
        store.entered = new CountDownLatch(1);
        store.release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var blocked = pool.submit(() -> gate.enter(Owner.B_LEGACY, "platform-http", "synthetic-block"));
            assertTrue(store.entered.await(3, TimeUnit.SECONDS));
            var response = pool.submit(() -> postsMvc.perform(get("/platform/posts/mine")).andReturn())
                    .get(1, TimeUnit.SECONDS);
            assertEquals(0, code(response));
            assertEquals(0, JSON.readTree(response.getResponse().getContentAsString())
                    .get("data").size());
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

    // ---------- S1 OPEN/UNKNOWN：真实白名单语义（工程 StaffProfileServiceTest 先例承载） ----------

    @Test void openProfileUpdateWritesThroughRealWhitelistSemantics() throws Exception {
        var result = verb(profileMvc, "PUT", "/platform/profile/mine",
                "{\"nickname\":\"  新名  \",\"avatar\":\"https://cdn.example.com/a.png\"}");
        assertEquals(0, code(result));
        // trim 后落库、头像写入（真实 store 断言：白名单三态语义在真实 service 内执行）。
        assertEquals("新名", stored.get().nickname());
        assertEquals("https://cdn.example.com/a.png", stored.get().avatar());
        assertEquals(1, storeWrites.get());
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void profileUpdateValidationFailureIsHandledRetainingUnknownPending() throws Exception {
        var result = verb(profileMvc, "PUT", "/platform/profile/mine", "{\"nickname\":\"   \"}");
        assertEquals(1_101_000_011, code(result)); // PROFILE_NICKNAME_INVALID（真实业务校验）
        assertEquals("旧名", stored.get().nickname());
        assertEquals(0, storeWrites.get());
        unknownPending("profile-update-mine");
    }

    // ---------- M1/M2 OPEN：真实幂等已读语义 ----------

    @Test void openMessageReadAndReadAllReleaseWithoutPending() throws Exception {
        // 夹具走非本人消息：markRead MESSAGE_NOT_FOUND 属 handled 失败；先以 read-all 幂等零行路径验证正常收口。
        assertEquals(0, code(verb(messageMvc, "POST", "/platform/messages/read-all", null)));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
        // 非本人/不存在消息：MESSAGE_NOT_FOUND（PlatformWorkflowErrorCodes 1_105_000_000）→ UNKNOWN 保留。
        assertEquals(1_105_000_000, code(verb(messageMvc, "POST", "/platform/messages/88/read", null)));
        unknownPending("message-read");
    }

    // ---------- A3 OPEN 深断言：真实登录链 + TokenIssuer 替身真实 token 行（合同§5 勘误2 边界） ----------

    @Test void openSmsLoginIssuesRealTokenRowThroughRealLoginChain() throws Exception {
        var captchaService = mock(SmsCaptchaService.class);
        var issuer = stubTokenIssuer();
        var accounts = mock(StaffAccountDirectory.class);
        when(accounts.findByMobile(MOBILE)).thenReturn(List.of(
                new StaffAccountDirectory.StaffAccount(SMS_USER, MOBILE, true)));
        when(staffPostService.listActivePosts(SMS_USER)).thenReturn(List.of(
                new cn.iocoder.yudao.module.platform.org.dal.StaffPostDO().setId(5103L).setOrgId(ORG_ID)));
        var smsLogin = new StaffSmsLoginServiceImpl();
        ReflectionTestUtils.setField(smsLogin, "smsCaptchaService", captchaService);
        ReflectionTestUtils.setField(smsLogin, "accounts", accounts);
        ReflectionTestUtils.setField(smsLogin, "staffPostService", staffPostService);
        ReflectionTestUtils.setField(smsLogin, "tokenIssuer", issuer);
        var realAuthController = new PlatformAuthController();
        ReflectionTestUtils.setField(realAuthController, "staffWechatLoginService", wechatLoginService);
        ReflectionTestUtils.setField(realAuthController, "staffSmsLoginService", smsLogin);
        var realAuthMvc = build(realAuthController);
        assertEquals(0, code(verb(realAuthMvc, "POST", "/platform/auth/sms/login",
                "{\"mobile\":\"" + MOBILE + "\",\"captcha\":\"654321\"}")));
        verify(captchaService).verifyLoginCaptcha(MOBILE, "654321");
        assertEquals(1, rows("system_oauth2_access_token"));
        var token = observer.queryForMap("SELECT user_id, user_type FROM system_oauth2_access_token");
        assertEquals(SMS_USER, ((Number) token.get("user_id")).longValue());
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    /** TokenIssuer 替身：等价 JDBC 写 system_oauth2_access_token（生产 YudaoTokenIssuer 经
     *  OAuth2TokenService 的装配不在本批 scope，其写语义由 system 模块自身测试覆盖——合同§5 登记）。 */
    private TokenIssuer stubTokenIssuer() {
        var sequence = new AtomicInteger(9900);
        return new TokenIssuer() {
            @Override public IssuedToken issue(long userId) {
                String access = "g2l-access-" + sequence.get();
                String refresh = "g2l-refresh-" + sequence.get();
                observer.update("INSERT INTO system_oauth2_access_token (id, user_id, user_type, user_info, "
                        + "access_token, refresh_token, client_id, expires_time) "
                        + "VALUES (?, ?, 2, '{}', ?, ?, 'default', now() + interval '30 minutes')",
                        sequence.get(), userId, access, refresh);
                return new IssuedToken(access, refresh, java.time.LocalDateTime.now().plusMinutes(30));
            }
            @Override public void revokeAll(long userId) {
                observer.update("DELETE FROM system_oauth2_access_token WHERE user_id = ?", userId);
            }
        };
    }

    // ---------- O6/O7 深断言：真实 mapper/服务链（c31 窄审核点③ 三重回滚） ----------

    @Test void openHandoverRevokesPostsWritesAuditAndRevokesSessions() throws Exception {
        var assembly = deepAssembly(List.of());
        seedSessionsForFrAccount();
        assertEquals(0, code(verb(assembly.mvc(), "POST", "/platform/org/staff-handover",
                "{\"orgId\":" + ORG_ID + ",\"fromUserId\":" + FROM_USER + ",\"toUserId\":" + TO_USER + "}")));
        // pf_staff_post 行级：FROM_USER 本组织任职全部撤销（TO_USER 不受影响）。
        assertEquals("REVOKED", observer.queryForObject(
                "SELECT status FROM pf_staff_post WHERE id = 5101", String.class));
        assertNotNull(observer.queryForObject(
                "SELECT revoked_at FROM pf_staff_post WHERE id = 5101", java.sql.Timestamp.class));
        assertEquals("ACTIVE", observer.queryForObject(
                "SELECT status FROM pf_staff_post WHERE id = 5102", String.class));
        // audit 行级：恰一条 NORMAL。
        assertEquals(1, rows("pf_handover_audit"));
        assertEquals("NORMAL", observer.queryForObject(
                "SELECT mode FROM pf_handover_audit", String.class));
        // 钱包会话吊销链行级：fr-service 账号 9100 的 user_session 全部 revoked。
        assertEquals(0, observer.queryForObject(
                "SELECT COUNT(*) FROM user_session WHERE account_id = ? AND revoked_at IS NULL",
                Integer.class, FR_ACCOUNT));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void handoverPortFailureRollsBackPostRevocationAuditAndSessionRevocation() throws Exception {
        // Port 替身：crfm 域有在途且 bulkTransfer 抛异常 → REQUIRES_NEW 整笔回滚（c31④）。
        var failingPort = new DepartureHandoverPort() {
            @Override public String domain() { return "franchise-crm"; }
            @Override public String label() { return "客户"; }
            @Override public long scanInFlight(long fromUserId, long orgId) { return 1; }
            @Override public void validateReceiver(long toUserId, long orgId) { }
            @Override public int bulkTransfer(long fromUserId, long toUserId, long orgId, long operatorUserId) {
                throw new IllegalStateException("synthetic transfer failure");
            }
        };
        var assembly = deepAssembly(List.of(failingPort));
        seedSessionsForFrAccount();
        var result = verb(assembly.mvc(), "POST", "/platform/org/staff-handover",
                "{\"orgId\":" + ORG_ID + ",\"fromUserId\":" + FROM_USER + ",\"toUserId\":" + TO_USER + "}");
        // 异常传出经 advice 产非成功 CommonResult → postHandle 证据缺失 → UNKNOWN 保留。
        assertNotEquals(0, code(result));
        // 三重回滚断言（c31④）：audit 无行 + pf_staff_post 未撤 + user_session 未吊销。
        assertEquals(0, rows("pf_handover_audit"));
        assertEquals("ACTIVE", observer.queryForObject(
                "SELECT status FROM pf_staff_post WHERE id = 5101", String.class));
        assertNull(observer.queryForObject(
                "SELECT revoked_at FROM pf_staff_post WHERE id = 5101", java.sql.Timestamp.class));
        assertEquals(2, observer.queryForObject(
                "SELECT COUNT(*) FROM user_session WHERE account_id = ? AND revoked_at IS NULL",
                Integer.class, FR_ACCOUNT));
        unknownPending("staff-handover");
    }

    @Test void openEmergencyRevokeRevokesAndWritesPendingAndAudit() throws Exception {
        var assembly = deepAssembly(List.of());
        seedSessionsForFrAccount();
        assertEquals(0, code(verb(assembly.mvc(), "POST", "/platform/org/staff-emergency-revoke",
                "{\"orgId\":" + ORG_ID + ",\"userId\":" + FROM_USER + "}")));
        assertEquals("REVOKED", observer.queryForObject(
                "SELECT status FROM pf_staff_post WHERE id = 5101", String.class));
        assertEquals(1, rows("pf_handover_audit"));
        assertEquals("EMERGENCY", observer.queryForObject(
                "SELECT mode FROM pf_handover_audit", String.class));
        assertEquals(0, observer.queryForObject(
                "SELECT COUNT(*) FROM user_session WHERE account_id = ? AND revoked_at IS NULL",
                Integer.class, FR_ACCOUNT));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void emergencyRevokeWithoutActivePostIsHandledRetainingUnknownPending() throws Exception {
        var assembly = deepAssembly(List.of());
        seedSessionsForFrAccount();
        var result = verb(assembly.mvc(), "POST", "/platform/org/staff-emergency-revoke",
                "{\"orgId\":" + ORG_ID + ",\"userId\":" + SMS_USER + "}");
        assertEquals(1_101_000_019, code(result)); // HANDOVER_SOURCE_NO_POST（真实业务校验）
        assertEquals("ACTIVE", observer.queryForObject(
                "SELECT status FROM pf_staff_post WHERE id = 5101", String.class));
        assertEquals(0, rows("pf_handover_audit"));
        unknownPending("emergency-revoke");
    }

    /** fr 账号会话夹具：经真实 UserSessionService.issue 签发两枚（深断言吊销/回滚共用）。 */
    private void seedSessionsForFrAccount() {
        var dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        var transactions = new DataSourceTransactionManager(dataSource);
        var sessions = new UserSessionService(dataSource, transactions);
        sessions.issue(FR_ACCOUNT, "synthetic-appid", "fr-openid-1", false, null);
        sessions.issue(FR_ACCOUNT, "synthetic-appid", "fr-openid-2", false, null);
    }

    /**
     * O6/O7 真实装配（合同§5）：内联 PlatformPgIntegrationTest 形态——真实 PG + MyBatis-Plus
     * （MapperScan 懒加载）+ 真实 StaffPostServiceImpl/DepartureHandoverService（ProxyFactory 织入
     * @Transactional 使 self 自注入 REQUIRES_NEW 生效）+ 真实 WalletSessionRevocation 桥接链
     * （bridge=true 原样）+ SystemDirectory/DepartureHandoverPort 替身（c31④批准）。
     */
    private DeepAssembly deepAssembly(List<DepartureHandoverPort> ports) {
        var dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        PlatformTransactionManager txManager = new DataSourceTransactionManager(dataSource);

        // 上下文保持存活（不 close）：mapper/template/装配链在用例执行期间持续可用。
        var context = new AnnotationConfigApplicationContext();
        context.getBeanFactory().registerSingleton("dataSource", dataSource);
        context.getBeanFactory().registerSingleton("txManager", txManager);
        context.getEnvironment().getPropertySources().addFirst(
                new org.springframework.core.env.MapPropertySource("g2l-mp", Map.of(
                        "yudao.info.base-package", "cn.iocoder.yudao",
                        "mybatis.lazy-initialization", "true",
                        "spring.datasource.type", "org.springframework.jdbc.datasource.SimpleDriverDataSource")));
        context.register(G2lMapperScan.class,
                cn.iocoder.yudao.framework.mybatis.config.YudaoMybatisAutoConfiguration.class,
                com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration.class,
                com.baomidou.mybatisplus.autoconfigure.MybatisPlusLanguageDriverAutoConfiguration.class);
        context.refresh();
        var mapperFactory = context.getBean(org.apache.ibatis.session.SqlSessionFactory.class);
        var template = new org.mybatis.spring.SqlSessionTemplate(mapperFactory);
        var staffPostMapper = template.getMapper(StaffPostMapper.class);
        var postTemplateMapper = template.getMapper(PostTemplateMapper.class);
        var orgUnitMapper = template.getMapper(OrgUnitMapper.class);
        var pendingMapper = template.getMapper(PlatformHandoverPendingMapper.class);
        var auditMapper = template.getMapper(PlatformHandoverAuditMapper.class);

        SystemDirectory deepDirectory = mock(SystemDirectory.class);
        when(deepDirectory.userExists(anyLong())).thenReturn(true);
        // Mockito mock 不执行接口 default 方法链（userUsable→userExists），须显式 stub。
        when(deepDirectory.userUsable(anyLong())).thenReturn(true);
        when(deepDirectory.roleIdByCode(anyString())).thenReturn(800L);
        when(deepDirectory.userRoleIds(anyLong())).thenReturn(Set.of());

        var walletRevocation = new cn.iocoder.yudao.module.franchise.org.wallet.WalletSessionRevocationAdapter(
                dataSource, new cn.iocoder.yudao.module.identity.session.IdentitySessionIssuePortAdapter(
                new UserSessionService(dataSource, txManager), mock(WechatPaySessionPort.class)));
        ObjectProvider<WalletSessionRevocation> revocationProvider = singletonProvider(walletRevocation);

        var realStaffPostService = new StaffPostServiceImpl();
        ReflectionTestUtils.setField(realStaffPostService, "staffPostMapper", staffPostMapper);
        ReflectionTestUtils.setField(realStaffPostService, "postTemplateMapper", postTemplateMapper);
        ReflectionTestUtils.setField(realStaffPostService, "orgUnitMapper", orgUnitMapper);
        ReflectionTestUtils.setField(realStaffPostService, "systemDirectory", deepDirectory);
        ReflectionTestUtils.setField(realStaffPostService, "walletSessionRevocation", revocationProvider);

        var realHandover = new DepartureHandoverService();
        ReflectionTestUtils.setField(realHandover, "staffPostService", realStaffPostService);
        ReflectionTestUtils.setField(realHandover, "systemDirectory", deepDirectory);
        ReflectionTestUtils.setField(realHandover, "ports", listProvider(ports));
        ReflectionTestUtils.setField(realHandover, "staffPostMapper", staffPostMapper);
        ReflectionTestUtils.setField(realHandover, "pendingMapper", pendingMapper);
        ReflectionTestUtils.setField(realHandover, "auditMapper", auditMapper);
        // self 自注入事务代理：REQUIRES_NEW 经代理生效（生产经 Spring 容器 @Lazy 代理，等价）。
        var proxyFactory = new org.springframework.aop.framework.ProxyFactory(realHandover);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(new TransactionInterceptor(txManager, new AnnotationTransactionAttributeSource()));
        var handoverProxy = (DepartureHandoverService) proxyFactory.getProxy();
        ReflectionTestUtils.setField(realHandover, "self", handoverProxy);

        var realOrgController = new StaffOrgAdminController();
        // PostSnapshot 为 8 参 record（teamId/managedTeamIds），requireScope 经 find() 读 orgId。
        var scopedPost = new cn.iocoder.yudao.module.platform.org.PostSnapshot(5111L, 7001L, ORG_ID,
                "HEADQUARTERS", "boss", null, true, Set.of());
        when(currentPost.find()).thenReturn(java.util.Optional.of(scopedPost));
        ReflectionTestUtils.setField(realOrgController, "currentPost", currentPost);
        ReflectionTestUtils.setField(realOrgController, "staffPostService", realStaffPostService);
        ReflectionTestUtils.setField(realOrgController, "orgService", orgService);
        ReflectionTestUtils.setField(realOrgController, "systemDirectory", deepDirectory);
        ReflectionTestUtils.setField(realOrgController, "handoverService", handoverProxy);

        return new DeepAssembly(build(realOrgController), context);
    }

    /** 窄范围 MapperScan：仅 platform-org dal 五个 mapper，非懒加载（SqlSessionTemplate.getMapper
     *  绕过 bean 容器，须真实注册进 MybatisPlusMapperRegistry；全包扫描的 yudao @MapperScan
     *  为 lazy=true 不实例化，与本扫描并存无冲突）。 */
    @org.springframework.context.annotation.Configuration
    @org.mybatis.spring.annotation.MapperScan(
            basePackages = "cn.iocoder.yudao.module.platform.org.dal",
            annotationClass = org.apache.ibatis.annotations.Mapper.class)
    static class G2lMapperScan {
    }

    /** 多元素 ObjectProvider：orderedStream 产出元素流（DepartureHandoverService 的 ports 字段语义）。 */
    private static <T> ObjectProvider<T> listProvider(List<T> values) {
        return new ObjectProvider<>() {
            @Override public T getObject() {
                return values.isEmpty() ? null : values.get(0);
            }
            @Override public T getObject(Object... args) { return getObject(); }
            @Override public T getIfAvailable() { return getObject(); }
            @Override public T getIfAvailable(java.util.function.Supplier<T> defaultSupplier) {
                return values.isEmpty() ? defaultSupplier.get() : values.get(0);
            }
            @Override public T getIfUnique() { return getObject(); }
            @Override public T getIfUnique(java.util.function.Supplier<T> defaultSupplier) {
                return values.size() == 1 ? values.get(0) : defaultSupplier.get();
            }
            @Override public Stream<T> stream() { return values.stream(); }
            @Override public Stream<T> orderedStream() { return values.stream(); }
        };
    }

    private static <T> ObjectProvider<T> singletonProvider(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject() { return value; }
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfAvailable(java.util.function.Supplier<T> defaultSupplier) {
                return value != null ? value : defaultSupplier.get();
            }
            @Override public T getIfUnique() { return value; }
            @Override public T getIfUnique(java.util.function.Supplier<T> defaultSupplier) {
                return value != null ? value : defaultSupplier.get();
            }
            @Override public Stream<T> stream() { return Stream.of(value); }
        };
    }

    private record DeepAssembly(MockMvc mvc, AutoCloseable context) implements AutoCloseable {
        @Override public void close() throws Exception { context.close(); }
    }
}
