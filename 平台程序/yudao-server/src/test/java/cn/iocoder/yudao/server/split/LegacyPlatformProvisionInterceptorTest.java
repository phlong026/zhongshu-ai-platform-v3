package cn.iocoder.yudao.server.split;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.framework.web.core.handler.GlobalResponseBodyHandler;
import cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils;
import cn.iocoder.yudao.module.identity.account.AccountLoginService;
import cn.iocoder.yudao.module.identity.account.AccountProfileService;
import cn.iocoder.yudao.module.identity.activation.ActivationRequestService;
import cn.iocoder.yudao.module.identity.controller.admin.AccessCodeAdminController;
import cn.iocoder.yudao.module.identity.controller.admin.AccessCodeBatchAdminController;
import cn.iocoder.yudao.module.identity.controller.admin.AccessGrantAdminController;
import cn.iocoder.yudao.module.identity.controller.admin.AccountAdminController;
import cn.iocoder.yudao.module.identity.controller.admin.ActivationRequestAdminController;
import cn.iocoder.yudao.module.identity.controller.app.AppAccessCodeRedemptionController;
import cn.iocoder.yudao.module.identity.controller.app.AppActivationRequestController;
import cn.iocoder.yudao.module.identity.controller.app.AppAuthController;
import cn.iocoder.yudao.module.identity.controller.app.AppProfileController;
import cn.iocoder.yudao.module.identity.accesscode.AccessCodeCipher;
import cn.iocoder.yudao.module.identity.accesscode.AccessCodeRedemptionService;
import cn.iocoder.yudao.module.identity.accesscode.AccessCodeService;
import cn.iocoder.yudao.module.identity.accesscode.AccessGrantService;
import cn.iocoder.yudao.module.identity.session.UserSessionService;
import cn.iocoder.yudao.module.identity.wechat.WechatIdentityPort;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.DeliveryPort;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.IssuedTicket;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.TicketConsumption;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static cn.iocoder.yudao.server.split.LegacyPlatformWritePort.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * G2e identity-provision：真实 MVC + 真实 PG（identity 源码迁移）验证供给链 15 写入口
 * 接入共用停写门禁（writer=identity-provision）。业务服务零改动；
 * design_activation_request 夹具 DDL 为漂移债务复现（源码迁移链无该表，合同 §6 方案 a，c31 已批）。
 */
class LegacyPlatformProvisionInterceptorTest {
    private static final String SCOPE = "e".repeat(64);
    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    private static final String APPID = "synthetic-appid";
    private static final String OPENID = "synthetic-openid";
    private static final long ACCOUNT_ID = 5001L;

    private JdbcTemplate jdbc;
    private JdbcTemplate observer;
    private LegacyPlatformWriteGate gate;
    private LegacyPlatformWalletSessionInterceptorTest.MemoryStore store;
    private UserSessionService sessions;
    private AccessCodeService accessCodes;
    private AccessCodeRedemptionService redemptions;
    private AppProfileController profileController;
    private AccountAdminController accountAdmin;
    private final AtomicInteger commitsWithPermit = new AtomicInteger();
    private final AtomicInteger rollbacksWithPermit = new AtomicInteger();
    private final ApiErrorLogCommonApi errorLogs = mock(ApiErrorLogCommonApi.class);
    private final DeliveryPort deliveryPort = mock(DeliveryPort.class);
    private volatile CountDownLatch commitEntered;
    private volatile CountDownLatch commitRelease;
    private MockMvc mvc;

    @BeforeAll static void startDatabase() throws Exception {
        PG.start();
        var dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/identity/V20260905.101__p2a_identity_access.sql"),
                new ClassPathResource("db/migration/identity/V20260909.102__independent_refresh_expiry.sql"),
                new ClassPathResource("db/migration/identity/V20261010.107__redemption_account_binding.sql"),
                new ClassPathResource("db/migration/identity/V20261010.108__access_code_binding_audit.sql"),
                new ClassPathResource("db/migration/identity/V20261016.109__wechat_identity_reregister_after_account_delete.sql"),
                new ClassPathResource("db/migration/identity/V20261102.101__account_external_key.sql"))
                .execute(dataSource);
        try (var input = new ClassPathResource("db/migration/identity/V20260913.106__privacy_management_permission.sql")
                .getInputStream()) {
            new JdbcTemplate(dataSource).execute(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        // 漂移债务复现（合同 §6 方案a，c31已批）：design_activation_request 在源码迁移链中无 DDL，
        // 列集从 ActivationRequestService INSERT/UPDATE 与 AppProfileController 绑定 UPDATE 的 Java 写集推导。
        // 非生产 schema——将来补 forward DDL 后必须删除本夹具并切回源码迁移。
        new JdbcTemplate(dataSource).execute("""
                CREATE TABLE IF NOT EXISTS design_activation_request (
                    id            BIGINT       NOT NULL,
                    account_id    BIGINT       NOT NULL,
                    appid         VARCHAR(32)  NOT NULL,
                    openid        VARCHAR(64)  NOT NULL,
                    company_name  VARCHAR(120) NOT NULL,
                    contact_name  VARCHAR(60)  NOT NULL,
                    contact_phone VARCHAR(30)  NOT NULL,
                    status        VARCHAR(16)  NOT NULL,
                    reject_reason VARCHAR(200) NULL,
                    note          VARCHAR(200) NULL,
                    handled_by    VARCHAR(64)  NULL,
                    handled_at    TIMESTAMPTZ  NULL,
                    tenant_id     BIGINT       NOT NULL DEFAULT 0,
                    creator       VARCHAR(64)  NULL DEFAULT '',
                    create_time   TIMESTAMPTZ  NOT NULL DEFAULT now(),
                    updater       VARCHAR(64)  NULL DEFAULT '',
                    update_time   TIMESTAMPTZ  NOT NULL DEFAULT now(),
                    deleted       BOOLEAN      NOT NULL DEFAULT FALSE,
                    CONSTRAINT pk_design_activation_request PRIMARY KEY (id),
                    CONSTRAINT ck_design_activation_request_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'))
                )""");
    }

    @AfterAll static void stopDatabase() { PG.stop(); }

    @BeforeEach void fixture() throws Exception {
        var dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        observer = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        jdbc.execute("TRUNCATE account, wechat_identity, user_session, design_access_grant, "
                + "design_access_code, design_access_code_batch, access_code_redemption, "
                + "access_code_binding_audit, design_activation_request");
        jdbc.update("INSERT INTO account (id, status) VALUES (?, 'ACTIVE')", ACCOUNT_ID);
        jdbc.update("INSERT INTO design_access_grant (id, account_id, status) VALUES (6001, ?, 'ACTIVE')", ACCOUNT_ID);
        jdbc.update("INSERT INTO wechat_identity (id, appid, openid, unionid, account_id) VALUES (7001, ?, ?, 'synthetic-unionid', ?)",
                APPID, OPENID, ACCOUNT_ID);
        store = new LegacyPlatformWalletSessionInterceptorTest.MemoryStore();
        gate = new LegacyPlatformWriteGate("g2e", SCOPE, Set.of(), Set.of("legacy-session", "identity-provision"), store);
        gate.apply(new Control(1, State.OPEN, SCOPE), FreezeEvidence.missing());
        new WebFrameworkUtils(new WebProperties());
        var transactions = counting(new DataSourceTransactionManager(dataSource));
        sessions = new UserSessionService(dataSource, transactions);
        WechatIdentityPort stubWechat = (appid, code) -> switch (code) {
            case "synthetic-code" -> new WechatIdentityPort.WechatSession(OPENID, "synthetic-unionid");
            case "fresh-code" -> new WechatIdentityPort.WechatSession("fresh-openid", "fresh-unionid");
            default -> new WechatIdentityPort.WechatSession("other-openid", null);
        };
        var logins = new AccountLoginService(dataSource, transactions, stubWechat, sessions);
        var cipher = AccessCodeCipher.forTesting("synthetic-pepper-0123456789abcdef0123456789abcdef", "v1",
                Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        accessCodes = new AccessCodeService(dataSource, transactions, cipher, deliveryPort);
        redemptions = new AccessCodeRedemptionService(dataSource, transactions, cipher, logins);
        var grants = new AccessGrantService(dataSource, transactions);
        var activations = new ActivationRequestService(dataSource, transactions);
        var profiles = new AccountProfileService(dataSource,
                mock(cn.iocoder.yudao.module.infra.zhongshu.api.ProfileAvatarPort.class));
        var rateLimiter = mock(cn.iocoder.yudao.framework.ratelimiter.core.VerifiedAccountRateLimiter.class);

        var auth = new AppAuthController();
        ReflectionTestUtils.setField(auth, "accountLoginService", logins);
        ReflectionTestUtils.setField(auth, "sessionService", sessions);
        ReflectionTestUtils.setField(auth, "wechatAppid", APPID);
        var redemption = new AppAccessCodeRedemptionController();
        ReflectionTestUtils.setField(redemption, "redemptionService", redemptions);
        ReflectionTestUtils.setField(redemption, "sessionService", sessions);
        ReflectionTestUtils.setField(redemption, "verifiedAccountRateLimiter", rateLimiter);
        ReflectionTestUtils.setField(redemption, "accessGrantService",
                new AccessGrantService(dataSource, transactions));
        var activationApp = new AppActivationRequestController();
        ReflectionTestUtils.setField(activationApp, "activationRequestService", activations);
        ReflectionTestUtils.setField(activationApp, "sessionService", sessions);
        ReflectionTestUtils.setField(activationApp, "verifiedAccountRateLimiter", rateLimiter);
        var profile = new AppProfileController();
        ReflectionTestUtils.setField(profile, "sessionService", sessions);
        ReflectionTestUtils.setField(profile, "accountLoginService", logins);
        ReflectionTestUtils.setField(profile, "accountProfileService", profiles);
        ReflectionTestUtils.setField(profile, "dataSource", dataSource);
        ReflectionTestUtils.setField(profile, "accessCodeService", accessCodes);
        profileController = profile;
        var accessCodeAdmin = new AccessCodeAdminController();
        ReflectionTestUtils.setField(accessCodeAdmin, "accessCodeService", accessCodes);
        var batchAdmin = new AccessCodeBatchAdminController();
        ReflectionTestUtils.setField(batchAdmin, "accessCodeService", accessCodes);
        var grantAdmin = new AccessGrantAdminController();
        ReflectionTestUtils.setField(grantAdmin, "accessGrantService", grants);
        var accountAdmin = new AccountAdminController();
        ReflectionTestUtils.setField(accountAdmin, "dataSource", dataSource);
        ReflectionTestUtils.setField(accountAdmin, "accessGrantService", grants);
        this.accountAdmin = accountAdmin;
        var activationAdmin = new ActivationRequestAdminController();
        ReflectionTestUtils.setField(activationAdmin, "activationRequestService", activations);

        mvc = MockMvcBuilders.standaloneSetup(auth, redemption, activationApp, profile,
                        accessCodeAdmin, batchAdmin, grantAdmin, accountAdmin, activationAdmin,
                        new LegacyPlatformWalletSessionInterceptorTest.AController())
                .setControllerAdvice(new Object[]{new GlobalExceptionHandler("g2e", errorLogs), new GlobalResponseBodyHandler()})
                .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
                .addFilters(new cn.iocoder.yudao.framework.web.core.filter.CacheRequestBodyFilter())
                .addInterceptors(new LegacyPlatformHttpWriteInterceptor(gate, true))
                .build();
    }

    /** 真实事务管理器代理：物理提交前可挂门闩；commit/rollback 按 pending 非空计数（G2d 口径）。 */
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

    private String token() {
        return sessions.issue(ACCOUNT_ID, APPID, OPENID, false, null).accessToken();
    }

    private MvcResult post(String url, String token, String json) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post(url).contentType(MediaType.APPLICATION_JSON).content(json);
        if (token != null) request.header("Authorization", "Bearer " + token);
        return mvc.perform(request).andReturn();
    }

    private MvcResult verb(String method, String url, String json) throws Exception {
        return verb(method, url, json, null);
    }

    private MvcResult verb(String method, String url, String json, String token) throws Exception {
        var request = switch (method) {
            case "PUT" -> put(url);
            case "PATCH" -> patch(url);
            case "DELETE" -> delete(url);
            default -> org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url);
        };
        if (json != null) request.contentType(MediaType.APPLICATION_JSON).content(json);
        if (token != null) request.header("Authorization", "Bearer " + token);
        return mvc.perform(request).andReturn();
    }

    private int code(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString()).get("code").intValue();
    }

    private int rows(String table) {
        return observer.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private long soleCodeId(long batchId) {
        return observer.queryForObject("SELECT id FROM design_access_code WHERE batch_id = ?", Long.class, batchId);
    }

    /** 授权码批次（INLINE 返回明文；TICKET 走票据），服务级夹具。 */
    private AccessCodeService.BatchCreateResult mintBatch(String mode, int quantity) {
        return accessCodes.createBatch(quantity, cn.iocoder.yudao.module.identity.enums.DeliveryModeEnum.of(mode).getMode(),
                30, "synthetic", "501");
    }

    // ---------- DRAINING：15入口逐一拒绝，全部表零写（per-entry 门禁覆盖） ----------

    @Test void drainingRejectsAllFifteenProvisionEntriesWithoutAnyWrite() throws Exception {
        var batch = mintBatch("INLINE", 1);
        long codeId = soleCodeId(batch.batchId());
        jdbc.update("UPDATE design_access_code SET status = 'CONSUMED', consumed_at = now() WHERE id = ?", codeId);
        jdbc.update("INSERT INTO access_code_redemption (id, code_id, account_id, appid, openid, "
                        + "company_name, contact_name, contact_phone) VALUES (8001, ?, ?, ?, ?, 'c', 'n', 'p')",
                codeId, ACCOUNT_ID, APPID, OPENID);
        gate.apply(new Control(2, State.DRAINING, SCOPE), FreezeEvidence.missing());
        var token = token();
        assertEquals(503, code(post("/design/v1/access-code-redemptions", token,
                "{\"accessCode\":\"ZSX\",\"companyName\":\"c\",\"contactName\":\"n\",\"contactPhone\":\"p\"}")));
        assertEquals(503, code(post("/design/v1/activation-requests", token,
                "{\"companyName\":\"c\",\"contactName\":\"n\",\"contactPhone\":\"p\"}")));
        assertEquals(503, code(verb("PUT", "/design/v1/profile/binding",
                "{\"companyName\":\"c\",\"contactName\":\"n\",\"contactPhone\":\"p\"}")));
        assertEquals(503, code(verb("PATCH", "/design/v1/profile/preferences", "{\"messagePush\":true}")));
        assertEquals(503, code(verb("PATCH", "/design/v1/profile", "{\"nickname\":\"新昵称\"}")));
        assertEquals(503, code(verb("PATCH", "/design/v1/access-codes/" + codeId, "{}")));
        assertEquals(503, code(verb("POST", "/design/v1/access-codes/" + codeId + "/copy", null)));
        assertEquals(503, code(verb("PUT", "/design/v1/access-codes/" + codeId + "/binding",
                "{\"companyName\":\"c\",\"contactName\":\"n\",\"contactPhone\":\"p\"}")));
        assertEquals(503, code(verb("DELETE", "/design/v1/access-codes/" + codeId, null)));
        assertEquals(503, code(verb("POST", "/design/v1/access-code-batches",
                "{\"quantity\":2,\"deliveryMode\":\"INLINE\",\"validityDays\":30,\"purposeNote\":\"x\"}")));
        assertEquals(503, code(verb("POST", "/design/v1/access-code-batches/" + batch.batchId() + "/delivery-tickets", null)));
        assertEquals(503, code(verb("POST", "/design/v1/access-code-batches/" + batch.batchId() + "/delivery-exports",
                "{\"ticket\":\"t\"}")));
        assertEquals(503, code(verb("POST", "/design/v1/access-grants/6001/revocations", null)));
        assertEquals(503, code(verb("PATCH", "/design/v1/accounts/" + ACCOUNT_ID, null)));
        assertEquals(503, code(verb("POST", "/design/v1/activation-requests/9001/decision",
                "{\"approve\":false,\"reason\":\"x\"}")));
        // 全部供给表零写：只有夹具行与 token() 直接签发的会话
        assertEquals(1, rows("design_access_code"));
        assertEquals(1, rows("design_access_code_batch"));
        assertEquals(1, rows("access_code_redemption"));
        assertEquals(1, rows("design_access_grant"));
        assertEquals(1, rows("account"));
        assertEquals(0, rows("design_activation_request"));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    // ---------- 深断言（c31附加条件：真实行持久而非仅permit计数） ----------

    @Test void openRedeemSuppliesGrantAndConsumesCodeWithRealRows() throws Exception {
        // 夹具 grant 先撤销：兑换正是受限账号获得 grant 的供给路径。
        jdbc.update("UPDATE design_access_grant SET status = 'REVOKED' WHERE id = 6001");
        var batch = mintBatch("INLINE", 1);
        String plaintext = batch.oneTimeCodes().get(0);
        var result = post("/design/v1/access-code-redemptions", token(),
                "{\"accessCode\":\"" + plaintext + "\",\"companyName\":\"甲公司\",\"contactName\":\"张三\",\"contactPhone\":\"13800000000\"}");
        assertEquals(0, code(result));
        assertEquals("CONSUMED", observer.queryForObject(
                "SELECT status FROM design_access_code WHERE batch_id = ?", String.class, batch.batchId()));
        assertEquals(1, rows("access_code_redemption"));
        var redemption = observer.queryForMap("SELECT * FROM access_code_redemption");
        assertEquals(ACCOUNT_ID, ((Number) redemption.get("account_id")).longValue());
        assertEquals("甲公司", redemption.get("company_name"));
        // 6001 已撤销仍在表（逻辑保留）+ 兑换新 grant：恰一条 ACTIVE
        assertEquals(2, rows("design_access_grant"));
        assertEquals(1, observer.queryForObject(
                "SELECT count(*) FROM design_access_grant WHERE status = 'ACTIVE' AND deleted = FALSE", Integer.class));
        assertEquals(1, commitsWithPermit.get());
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void openCreateBatchIssuesRealBatchAndCodeRows() throws Exception {
        var result = verb("POST", "/design/v1/access-code-batches",
                "{\"quantity\":2,\"deliveryMode\":\"INLINE\",\"validityDays\":30,\"purposeNote\":\"synthetic\"}");
        assertEquals(0, code(result));
        assertEquals(1, rows("design_access_code_batch"));
        assertEquals(2, rows("design_access_code"));
        assertEquals(2, JSON.readTree(result.getResponse().getContentAsString()).get("data").get("oneTimeCodes").size());
        assertEquals(1, commitsWithPermit.get());
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void openDisableAccountSingleTxUpdatesAccountAndRevokesGrants() throws Exception {
        assertEquals(0, code(verb("PATCH", "/design/v1/accounts/" + ACCOUNT_ID, null)));
        assertEquals("DISABLED", observer.queryForObject(
                "SELECT status FROM account WHERE id = ?", String.class, ACCOUNT_ID));
        assertEquals("REVOKED", observer.queryForObject(
                "SELECT status FROM design_access_grant WHERE id = 6001", String.class));
        assertEquals(1, commitsWithPermit.get());
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void openApproveActivationSuppliesRealGrant() throws Exception {
        // 经真实受限账号 A2 提交建立 PENDING 申请，再走 D10 审批通过。
        var freshToken = freshRestrictedToken();
        assertEquals(0, code(post("/design/v1/activation-requests", freshToken,
                "{\"companyName\":\"甲公司\",\"contactName\":\"张三\",\"contactPhone\":\"13800000000\"}")));
        assertEquals("PENDING", observer.queryForObject(
                "SELECT status FROM design_activation_request", String.class));
        long requestId = observer.queryForObject("SELECT id FROM design_activation_request", Long.class);
        assertEquals(0, code(verb("POST", "/design/v1/activation-requests/" + requestId + "/decision",
                "{\"approve\":true,\"reason\":\"同意\"}")));
        assertEquals("APPROVED", observer.queryForObject(
                "SELECT status FROM design_activation_request", String.class));
        assertNotNull(observer.queryForObject(
                "SELECT handled_at FROM design_activation_request", java.sql.Timestamp.class));
        assertEquals(2, rows("design_access_grant"));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    // ---------- 其余入口 OPEN 成功路径（每入口真实行为） ----------

    /** 开通申请链路仅对无 grant 的受限账号开放；经真实 fresh 登录取受限会话。 */
    private String freshRestrictedToken() throws Exception {
        var login = post("/design/v1/auth/wechat-login", null, "{\"code\":\"fresh-code\"}");
        assertEquals(0, code(login));
        return JSON.readTree(login.getResponse().getContentAsString()).get("data").get("accessToken").asText();
    }

    @Test void openSubmitActivationCreatesRealPendingRow() throws Exception {
        assertEquals(0, code(post("/design/v1/activation-requests", freshRestrictedToken(),
                "{\"companyName\":\"甲公司\",\"contactName\":\"张三\",\"contactPhone\":\"13800000000\"}")));
        var row = observer.queryForMap("SELECT * FROM design_activation_request");
        assertEquals("PENDING", row.get("status"));
        assertEquals(APPID, row.get("appid"));
        long freshAccount = observer.queryForObject(
                "SELECT account_id FROM wechat_identity WHERE openid = 'fresh-openid'", Long.class);
        assertEquals(freshAccount, ((Number) row.get("account_id")).longValue());
        // 两次 permit 收口：fresh 登录（legacy-session）+ 开通申请（identity-provision）
        assertEquals(2, commitsWithPermit.get());
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void openUpdateBindingWritesRedemptionContactAndAudit() throws Exception {
        var batch = mintBatch("INLINE", 1);
        redemptions.redeem(APPID, OPENID, null, batch.oneTimeCodes().get(0),
                new AccessCodeRedemptionService.RedemptionBinding("旧公司", "旧联系人", "13800000000"));
        assertEquals(0, code(verb("PUT", "/design/v1/profile/binding",
                "{\"companyName\":\"新公司\",\"contactName\":\"新联系人\",\"contactPhone\":\"13900000000\"}", token())));
        assertEquals("新公司", observer.queryForObject(
                "SELECT company_name FROM access_code_redemption", String.class));
        assertEquals(1, rows("access_code_binding_audit"));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void openUpdatePreferencesWritesWhitelistedAccountJson() throws Exception {
        assertEquals(0, code(verb("PATCH", "/design/v1/profile/preferences",
                "{\"messagePush\":true,\"evilKey\":\"drop\"}", token())));
        var preferences = observer.queryForObject(
                "SELECT preferences::text FROM account WHERE id = ?", String.class, ACCOUNT_ID);
        assertTrue(preferences.contains("messagePush"));
        assertFalse(preferences.contains("evilKey"));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void openUpdateProfileWritesNickname() throws Exception {
        assertEquals(0, code(verb("PATCH", "/design/v1/profile", "{\"nickname\":\"新昵称\"}", token())));
        assertEquals("新昵称", observer.queryForObject(
                "SELECT nickname FROM account WHERE id = ?", String.class, ACCOUNT_ID));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void openDisableAccessCodeDisablesRealRow() throws Exception {
        var batch = mintBatch("INLINE", 1);
        long codeId = soleCodeId(batch.batchId());
        assertEquals(0, code(verb("PATCH", "/design/v1/access-codes/" + codeId, "{}")));
        assertEquals("DISABLED", observer.queryForObject(
                "SELECT status FROM design_access_code WHERE id = ?", String.class, codeId));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void openCopyAccessCodeReturnsPlaintextAndMarksExposure() throws Exception {
        var batch = mintBatch("INLINE", 1);
        long codeId = soleCodeId(batch.batchId());
        var result = verb("POST", "/design/v1/access-codes/" + codeId + "/copy", null);
        assertEquals(0, code(result));
        String plaintext = JSON.readTree(result.getResponse().getContentAsString()).get("data").asText();
        assertTrue(plaintext.startsWith("ZS"));
        assertNotNull(observer.queryForObject(
                "SELECT secret_exposed_at FROM design_access_code WHERE id = ?", java.sql.Timestamp.class, codeId));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void openAdminUpdateBindingWritesAuditRow() throws Exception {
        var batch = mintBatch("INLINE", 1);
        long codeId = soleCodeId(batch.batchId());
        jdbc.update("INSERT INTO access_code_redemption (id, code_id, account_id, appid, openid, "
                        + "company_name, contact_name, contact_phone) VALUES (8002, ?, ?, ?, ?, 'c', 'n', 'p')",
                codeId, ACCOUNT_ID, APPID, OPENID);
        assertEquals(0, code(verb("PUT", "/design/v1/access-codes/" + codeId + "/binding",
                "{\"companyName\":\"修正公司\",\"contactName\":\"修正人\",\"contactPhone\":\"13700000000\"}")));
        assertEquals("修正公司", observer.queryForObject(
                "SELECT company_name FROM access_code_redemption WHERE code_id = ?", String.class, codeId));
        assertEquals(1, rows("access_code_binding_audit"));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void openDeleteUnusedCodeSoftDeletesRealRow() throws Exception {
        var batch = mintBatch("INLINE", 1);
        long codeId = soleCodeId(batch.batchId());
        assertEquals(0, code(verb("DELETE", "/design/v1/access-codes/" + codeId, null)));
        assertEquals(Boolean.TRUE, observer.queryForObject(
                "SELECT deleted FROM design_access_code WHERE id = ?", Boolean.class, codeId));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void openCreateDeliveryTicketReturnsOneTimeToken() throws Exception {
        var batch = mintBatch("TICKET", 2);
        when(deliveryPort.issueDeliveryTicket(any(), any(), any(), anyLong()))
                .thenReturn(IssuedTicket.builder().token("synthetic-ticket").ticketId(1L)
                        .expiresAt(java.time.Instant.now().plusSeconds(600)).build());
        var result = verb("POST", "/design/v1/access-code-batches/" + batch.batchId() + "/delivery-tickets", null);
        assertEquals(0, code(result));
        assertNotNull(JSON.readTree(result.getResponse().getContentAsString()).get("data").get("ticket"));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void openExportByTicketDeliversCodesAndReplayFails() throws Exception {
        var batch = mintBatch("TICKET", 2);
        when(deliveryPort.issueDeliveryTicket(any(), any(), any(), anyLong()))
                .thenReturn(IssuedTicket.builder().token("synthetic-ticket").ticketId(1L)
                        .expiresAt(java.time.Instant.now().plusSeconds(600)).build());
        assertEquals(0, code(verb("POST", "/design/v1/access-code-batches/" + batch.batchId() + "/delivery-tickets", null)));
        var firstConsumption = mock(TicketConsumption.class);
        when(firstConsumption.getOutcome()).thenReturn(TicketConsumption.Outcome.CONSUMED_NOW);
        when(firstConsumption.getBizRef()).thenReturn(String.valueOf(batch.batchId()));
        when(firstConsumption.getPurpose()).thenReturn("ACCESS_CODE_BATCH");
        var replayConsumption = mock(TicketConsumption.class);
        when(replayConsumption.getOutcome()).thenReturn(TicketConsumption.Outcome.ALREADY_CONSUMED);
        when(deliveryPort.consumeDeliveryTicket(eq("synthetic-ticket"), any()))
                .thenReturn(firstConsumption, replayConsumption);
        var result = verb("POST", "/design/v1/access-code-batches/" + batch.batchId() + "/delivery-exports",
                "{\"ticket\":\"synthetic-ticket\"}");
        assertEquals(0, code(result));
        assertNotNull(JSON.readTree(result.getResponse().getContentAsString()).get("data"));
        // 同票据重放必失败（原子消费，第二次返回 ALREADY_CONSUMED → 业务错误码），
        // 处理后异常 → permit 转 UNKNOWN 保留（宁多记不漏记）。
        assertNotEquals(0, code(verb("POST", "/design/v1/access-code-batches/" + batch.batchId() + "/delivery-exports",
                "{\"ticket\":\"synthetic-ticket\"}")));
        assertEquals(1, gate.snapshot().checkpoint().pending().size());
        for (Work work : gate.snapshot().checkpoint().pending().values()) {
            assertEquals("identity-provision", work.writer());
            assertEquals("export-by-ticket", work.operation());
            assertTrue(work.unknown());
        }
    }

    @Test void openRevokeGrantRevokesRealRow() throws Exception {
        assertEquals(0, code(verb("POST", "/design/v1/access-grants/6001/revocations", null)));
        assertEquals("REVOKED", observer.queryForObject(
                "SELECT status FROM design_access_grant WHERE id = 6001", String.class));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    // ---------- c31 前置2：首次登录三行落库（G2d 未覆盖路径，本批补断言） ----------

    @Test void freshWechatFirstLoginCreatesAccountIdentityAndSession() throws Exception {
        int accountsBefore = rows("account");
        int identitiesBefore = rows("wechat_identity");
        int sessionsBefore = rows("user_session");
        var result = post("/design/v1/auth/wechat-login", null, "{\"code\":\"fresh-code\"}");
        assertEquals(0, code(result));
        // 三行真实落库：account + wechat_identity + user_session
        assertEquals(accountsBefore + 1, rows("account"));
        assertEquals(identitiesBefore + 1, rows("wechat_identity"));
        assertEquals(sessionsBefore + 1, rows("user_session"));
        long freshAccount = observer.queryForObject(
                "SELECT account_id FROM wechat_identity WHERE openid = 'fresh-openid'", Long.class);
        // 新微信无 grant → 受限会话
        assertEquals(Boolean.TRUE, observer.queryForObject(
                "SELECT restricted FROM user_session WHERE account_id = ?", Boolean.class, freshAccount));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    // ---------- 处理后异常 UNKNOWN（代表） / 提交前切 DRAINING / 非目标A ----------

    @Test void redeemUnknownCodeIsHandledFailureRetainingUnknownPending() throws Exception {
        var result = post("/design/v1/access-code-redemptions", token(),
                "{\"accessCode\":\"ZSNOTEXISTENT0000000000000\",\"companyName\":\"c\",\"contactName\":\"n\",\"contactPhone\":\"p\"}");
        assertEquals(200, result.getResponse().getStatus());
        assertNotEquals(0, code(result));
        assertEquals(0, rows("access_code_redemption"));
        assertEquals(1, gate.snapshot().checkpoint().pending().size());
        for (Work work : gate.snapshot().checkpoint().pending().values()) {
            assertEquals("identity-provision", work.writer());
            assertEquals("redeem-access-code", work.operation());
            assertTrue(work.unknown());
        }
    }

    @Test void drainingBeforeCommitRetainsFirstPermitAndRejectsSecondProvisionRequest() throws Exception {
        commitEntered = new CountDownLatch(1);
        commitRelease = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var first = pool.submit(() -> verb("PATCH", "/design/v1/accounts/" + ACCOUNT_ID, null));
            assertTrue(commitEntered.await(3, TimeUnit.SECONDS));
            gate.apply(new Control(2, State.DRAINING, SCOPE), FreezeEvidence.missing());
            assertEquals(1, gate.snapshot().checkpoint().pending().size());
            assertEquals(503, code(verb("PATCH", "/design/v1/accounts/" + ACCOUNT_ID, null)));
            commitRelease.countDown();
            assertEquals(0, code(first.get(3, TimeUnit.SECONDS)));
            assertEquals("DISABLED", observer.queryForObject(
                    "SELECT status FROM account WHERE id = ?", String.class, ACCOUNT_ID));
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

    @Test void nonTargetARequestDoesNotWaitForProvisionCheckpoint() throws Exception {
        store.entered = new CountDownLatch(1);
        store.release = new CountDownLatch(1);
        var probeMvc = MockMvcBuilders.standaloneSetup(new LegacyPlatformWalletSessionInterceptorTest.AController())
                .setControllerAdvice(new Object[]{new GlobalExceptionHandler("g2e", errorLogs), new GlobalResponseBodyHandler()})
                .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
                .addInterceptors(new LegacyPlatformHttpWriteInterceptor(gate, true))
                .build();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var blocked = pool.submit(() -> gate.enter(Owner.B_LEGACY, "identity-provision", "synthetic-block"));
            assertTrue(store.entered.await(3, TimeUnit.SECONDS));
            var response = pool.submit(() -> probeMvc.perform(get("/app-api/design/probe")).andReturn()).get(1, TimeUnit.SECONDS);
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

    // ---------- 白名单合同：15入口精确 writer/operation ----------

    @Test void wiredProvisionHandlersEnterGateWithExactWriterAndOperation() throws Exception {
        var port = mock(LegacyPlatformWritePort.class);
        var permit = mock(Permit.class);
        when(port.enter(any(), any(), any())).thenReturn(permit);
        var selected = new LegacyPlatformHttpWriteInterceptor(port, true);
        var redemption = new AppAccessCodeRedemptionController();
        var activationApp = new AppActivationRequestController();
        var profile = new AppProfileController();
        var accessCodeAdmin = new AccessCodeAdminController();
        var batchAdmin = new AccessCodeBatchAdminController();
        var grantAdmin = new AccessGrantAdminController();
        var accountAdmin = new AccountAdminController();
        var activationAdmin = new ActivationRequestAdminController();
        record Call(Object controller, Class<?> type, String method, Class<?>... params) { }
        var calls = List.of(
                new Call(redemption, AppAccessCodeRedemptionController.class, "redeem",
                        cn.iocoder.yudao.module.identity.controller.app.vo.AppAccessCodeRedeemReqVO.class, String.class),
                new Call(activationApp, AppActivationRequestController.class, "submit",
                        cn.iocoder.yudao.module.identity.controller.app.vo.AppActivationRequestSubmitReqVO.class, String.class),
                new Call(profile, AppProfileController.class, "updateBinding",
                        cn.iocoder.yudao.module.identity.controller.app.vo.AppProfileBindingUpdateReqVO.class, String.class),
                new Call(profile, AppProfileController.class, "updatePreferences", Map.class, String.class),
                new Call(profile, AppProfileController.class, "updateProfile",
                        cn.iocoder.yudao.module.identity.controller.app.vo.AppProfileUpdateReqVO.class, String.class),
                new Call(accessCodeAdmin, AccessCodeAdminController.class, "disableAccessCode", Long.class, Map.class),
                new Call(accessCodeAdmin, AccessCodeAdminController.class, "copyAccessCode", Long.class),
                new Call(accessCodeAdmin, AccessCodeAdminController.class, "updateBinding", Long.class,
                        cn.iocoder.yudao.module.identity.controller.admin.vo.AdminAccessCodeBindingUpdateReqVO.class),
                new Call(accessCodeAdmin, AccessCodeAdminController.class, "deleteAccessCode", Long.class),
                new Call(batchAdmin, AccessCodeBatchAdminController.class, "createBatch",
                        cn.iocoder.yudao.module.identity.controller.admin.vo.AdminAccessCodeBatchCreateReqVO.class),
                new Call(batchAdmin, AccessCodeBatchAdminController.class, "createDeliveryTicket", Long.class),
                new Call(batchAdmin, AccessCodeBatchAdminController.class, "exportByTicket", Long.class, Map.class),
                new Call(grantAdmin, AccessGrantAdminController.class, "revokeAccessGrant", Long.class),
                new Call(accountAdmin, AccountAdminController.class, "disableAccount", String.class),
                new Call(activationAdmin, ActivationRequestAdminController.class, "decide", Long.class,
                        cn.iocoder.yudao.module.identity.controller.admin.vo.AdminActivationDecisionReqVO.class));
        for (var call : calls) {
            var handler = new HandlerMethod(call.controller(), call.type().getDeclaredMethod(call.method(), call.params()));
            assertTrue(selected.preHandle(new org.springframework.mock.web.MockHttpServletRequest(),
                    new org.springframework.mock.web.MockHttpServletResponse(), handler), call.method());
        }
        var captor = ArgumentCaptor.forClass(String.class);
        verify(port, times(15)).enter(eq(Owner.B_LEGACY), eq("identity-provision"), captor.capture());
        assertEquals(Set.of("redeem-access-code", "submit-activation", "update-binding", "update-preferences",
                "update-profile", "disable-access-code", "copy-access-code", "admin-update-binding",
                "delete-access-code", "create-code-batch", "create-delivery-ticket", "export-by-ticket",
                "revoke-grant", "disable-account", "decide-activation"), Set.copyOf(captor.getAllValues()));
    }

    // ---------- 装配矩阵（代表入口：D9 admin + A4 app；bridge=false 一律受控） ----------

    @Test void bridgeFalseStillGuardsProvisionHandlers() throws Exception {
        configurationCase(Map.of("platform.legacy-b-write-control-directory", "synthetic-unused",
                "platform.legacy-design-bridge.enabled", "false"), true);
    }

    @Test void installedDefaultConfigurationGuardsProvisionHandlers() throws Exception {
        configurationCase(Map.of("platform.legacy-b-write-control-directory", "synthetic-unused"), true);
    }

    @Test void notInstalledLeavesProvisionBehaviorWithoutFreezeEvidence() throws Exception {
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
            // fixture 真实控制器（字段已注入），未装配分支才能走通业务层。
            var adminMvc = MockMvcBuilders.standaloneSetup(accountAdmin)
                    .setControllerAdvice(new Object[]{new GlobalExceptionHandler("g2e", errorLogs)})
                    .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
                    .addInterceptors(interceptors)
                    .build();
            var profileMvc = MockMvcBuilders.standaloneSetup(profileController)
                    .setControllerAdvice(new Object[]{new GlobalExceptionHandler("g2e", errorLogs)})
                    .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
                    .addInterceptors(interceptors)
                    .build();
            // bridge=false 只豁免钱包会话；供给链入口无论开关一律受控。
            // app 请求先行：notInstalled 分支 admin patch 会真实停号，validateAccessToken
            // 联表 ACTIVE 即时降级，停号后的请求将 401 而非进入业务层。
            var token = token();
            assertEquals(installed ? 503 : 0, code(profileMvc.perform(
                    patch("/design/v1/profile/preferences").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"messagePush\":true}")
                            .header("Authorization", "Bearer " + token)).andReturn()));
            assertEquals(installed ? 503 : 0, code(adminMvc.perform(
                    patch("/design/v1/accounts/" + ACCOUNT_ID)).andReturn()));
            if (installed) {
                assertEquals("ACTIVE", accountStatus());
                assertNull(observer.queryForObject(
                        "SELECT preferences::text FROM account WHERE id = ?", String.class, ACCOUNT_ID));
            } else {
                assertEquals("DISABLED", accountStatus());
                assertTrue(observer.queryForObject(
                        "SELECT preferences::text FROM account WHERE id = ?", String.class, ACCOUNT_ID).contains("messagePush"));
            }
            assertThrows(IllegalStateException.class,
                    () -> gate.apply(new Control(3, State.FROZEN, SCOPE), FreezeEvidence.missing()));
        }
    }

    private String accountStatus() {
        return observer.queryForObject("SELECT status FROM account WHERE id = ?", String.class, ACCOUNT_ID);
    }
}
