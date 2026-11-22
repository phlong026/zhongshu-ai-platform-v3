package cn.iocoder.yudao.module.platform.identity;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.authz.controller.admin.StoreProfileController;
import cn.iocoder.yudao.module.platform.authz.controller.admin.StaffImportController;
import cn.iocoder.yudao.module.platform.authz.service.FranchiseOnboardService;
import cn.iocoder.yudao.module.platform.authz.service.StaffAccountOnboardService;
import cn.iocoder.yudao.module.platform.identity.dal.WechatBindingMapper;
import cn.iocoder.yudao.module.platform.identity.wechat.WechatStaffClient;
import cn.iocoder.yudao.module.platform.org.FranchiseAccountNaming;
import cn.iocoder.yudao.module.platform.org.OrgServiceImpl;
import cn.iocoder.yudao.module.platform.org.OrgService;
import cn.iocoder.yudao.module.platform.org.StaffImportService;
import cn.iocoder.yudao.module.platform.org.dal.OrgUnitDO;
import cn.iocoder.yudao.module.platform.org.dal.PostTemplateDO;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import cn.iocoder.yudao.module.platform.org.StaffInitialPasswordPolicy;
import cn.iocoder.yudao.module.platform.org.StaffPostServiceImpl;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import cn.iocoder.yudao.module.system.dal.dataobject.user.AdminUserDO;
import cn.iocoder.yudao.module.system.dal.mysql.user.AdminUserMapper;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import cn.iocoder.yudao.module.system.service.user.AdminUserServiceImpl;
import cn.iocoder.yudao.module.system.service.auth.AdminAuthServiceImpl;
import cn.iocoder.yudao.module.system.service.auth.AdminUserPasswordPolicy;
import cn.iocoder.yudao.module.system.service.logger.LoginLogService;
import cn.iocoder.yudao.module.system.controller.admin.user.vo.profile.UserProfileUpdatePasswordReqVO;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.config.Config;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static cn.iocoder.yudao.module.platform.identity.enums.PlatformIdentityErrorCodes.ACCOUNT_CREDENTIAL_INVALID;
import static cn.iocoder.yudao.module.platform.identity.enums.PlatformIdentityErrorCodes.ACCOUNT_LOGIN_LOCKED;
import static cn.iocoder.yudao.module.platform.identity.enums.PlatformIdentityErrorCodes.ACCOUNT_INITIAL_PASSWORD_EXPIRED;
import static cn.iocoder.yudao.module.platform.identity.enums.PlatformIdentityErrorCodes.STAFF_NOT_ENROLLED;
import static cn.iocoder.yudao.module.platform.identity.enums.PlatformIdentityErrorCodes.WECHAT_ALREADY_BOUND_OTHER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Import({FranchiseAccountAuthService.class, FranchiseAccountNaming.class, OrgServiceImpl.class,
        StaffPostServiceImpl.class, FranchiseOnboardService.class, StaffAccountOnboardService.class, StoreProfileController.class,
        StaffImportService.class, StaffImportController.class,
        StaffWechatBindingServiceImpl.class, StaffWechatExplicitBindingServiceImpl.class,
        StaffWechatBindingCommitService.class, StaffWechatLoginServiceImpl.class, StaffInitialPasswordPolicy.class,
        FranchiseAccountAuthServiceIntegrationTest.RedisConfig.class})
@TestPropertySource(properties = {
        "platform.legacy-design-bridge.enabled=false",
        "platform.identity.wechat.apps[0].appid=wx-franchise-flow",
        "platform.identity.wechat.apps[0].secret=unused-in-test"
})
class FranchiseAccountAuthServiceIntegrationTest extends PlatformPgIntegrationTest {

    private static final AtomicLong IDS = new AtomicLong(System.nanoTime() % 100_000_000L + 900_000_000L);
    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder(10);
    private static final GenericContainer<?> REDIS = cn.iocoder.yudao.module.platform.test.TestNativeService.container("redis:7-alpine")
            .withExposedPorts(6379);

    static {
        if (System.getProperty("platform.test.redis.address") == null) REDIS.start();
    }

    @TestConfiguration
    @EnableConfigurationProperties(PlatformWechatProperties.class)
    static class RedisConfig {
        @Bean(destroyMethod = "destroy")
        RedissonConnectionFactory accountLoginRedisConnectionFactory() {
            Config config = new Config();
            String externalRedis = System.getProperty("platform.test.redis.address");
            config.useSingleServer().setAddress(externalRedis == null ? "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379) : externalRedis);
            return new RedissonConnectionFactory(config);
        }

        @Bean
        StringRedisTemplate stringRedisTemplate(RedissonConnectionFactory factory) {
            StringRedisTemplate template = new StringRedisTemplate(factory);
            template.afterPropertiesSet();
            return template;
        }

        @Bean
        JdbcDirectory jdbcDirectory(JdbcTemplate jdbc) {
            return new JdbcDirectory(jdbc);
        }
    }

    @Resource private FranchiseAccountAuthService service;
    @Resource private StoreProfileController storeProfile;
    @MockitoBean private CurrentPost currentPost;
    @Resource private JdbcTemplate jdbc;
    @Resource private StringRedisTemplate redis;
    @Resource private FranchiseOnboardService franchiseOnboardService;
    @Resource private StaffAccountOnboardService staffAccountOnboardService;
    @Resource private StaffImportController staffImport;
    @Resource private OrgService orgService;
    @Resource private StaffWechatExplicitBindingService explicitBindingService;
    @Resource private StaffWechatLoginService wechatLoginService;
    @Resource private StaffWechatBindingService bindingService;
    @Resource private WechatBindingMapper bindingMapper;
    @Resource private StaffPostService staffPostService;
    @Resource private StaffInitialPasswordPolicy initialPasswords;
    @Resource private PlatformTransactionManager transactions;
    @Resource private SqlSessionFactory sqlSessionFactory;
    @Resource private JdbcDirectory directory;
    @MockitoBean private AdminUserService adminUserService;
    @MockitoBean private AdminUserMapper adminUserMapper;
    @MockitoBean private TokenIssuer tokenIssuer;
    @MockitoBean private WechatStaffClient wechat;

    private long tenantId;

    @BeforeEach
    void setUp() {
        tenantId = IDS.incrementAndGet();
        TenantContextHolder.setTenantId(tenantId);
        createSystemUsersIfAbsent(jdbc);
        jdbc.update("INSERT INTO pf_org_type(code,domain,name) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                "franchise-auth-test", "test", "加盟商登录测试");
        jdbc.update("INSERT INTO pf_post_template(role_code,domain,org_types,line,lead,inherits_role_code) "
                        + "VALUES (?,?,?,?,FALSE,NULL) ON CONFLICT DO NOTHING",
                "franchise-auth-owner", "test", "franchise-auth-test", "management");
        jdbc.update("INSERT INTO pf_org_type(code,domain,name) VALUES ('store','test','加盟门店') "
                + "ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO pf_post_template(role_code,domain,org_types,line,lead,inherits_role_code) VALUES "
                + "('boss','test','store,t-store,t-hq,headquarters','management',FALSE,NULL),"
                + "('journey-sales','test','store','sales',FALSE,NULL) ON CONFLICT (role_code) DO UPDATE "
                + "SET org_types=EXCLUDED.org_types");
        directory.roleIdsByCode.put("boss", 501L);
        directory.roleIdsByCode.put("journey-sales", 502L);
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();

        when(adminUserService.getUser(anyLong())).thenAnswer(invocation -> loadUser(invocation.getArgument(0)));
        when(adminUserService.isPasswordMatch(anyString(), anyString())).thenAnswer(invocation ->
                BCRYPT.matches(invocation.getArgument(0), invocation.getArgument(1)));
        when(adminUserMapper.updateById(any(AdminUserDO.class))).thenAnswer(invocation -> {
            AdminUserDO update = invocation.getArgument(0);
            return jdbc.update("UPDATE system_users SET password=? WHERE id=? AND tenant_id=?",
                    update.getPassword(), update.getId(), TenantContextHolder.getRequiredTenantId());
        });
        when(tokenIssuer.issue(anyLong())).thenAnswer(invocation -> new TokenIssuer.IssuedToken(
                "access-" + invocation.getArgument(0), "refresh", LocalDateTime.now().plusHours(1)));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void matchesExactFirstAndExcludesOtherTenantDisabledUserRevokedPost() {
        for (int i = 0; i < 22; i++) {
            long userId = IDS.incrementAndGet();
            Account account = createAccount(tenantId, userId, "hjmz-jxnc-gaoxin-" + String.format("%02d", i), 0,
                    "ACTIVE", false);
            addAlias(account, "jxnc-gaoxin");
        }
        createAccount(tenantId, IDS.incrementAndGet(), "jxnc-gaoxin", 0, "ACTIVE", false);
        Account disabled = createAccount(tenantId, IDS.incrementAndGet(), "hjmz-jxnc-gaoxin-disabled", 1,
                "ACTIVE", false);
        addAlias(disabled, "jxnc-gaoxin");
        Account revoked = createAccount(tenantId, IDS.incrementAndGet(), "hjmz-jxnc-gaoxin-revoked", 0,
                "REVOKED", false);
        addAlias(revoked, "jxnc-gaoxin");
        Account otherTenant = createAccount(tenantId + 1, IDS.incrementAndGet(), "hjmz-jxnc-gaoxin-other", 0,
                "ACTIVE", false);
        addAlias(otherTenant, "jxnc-gaoxin");

        FranchiseAccountAuthService.MatchResult result = service.matches("JXNC-GAO");

        assertThat(result.items()).hasSize(20);
        assertThat(result.hasMore()).isTrue();
        assertThat(result.items()).extracting(FranchiseAccountAuthService.AccountMatch::accountId)
                .doesNotContain(disabled.accountId(), revoked.accountId(), otherTenant.accountId());
        assertCode(() -> service.login(otherTenant.accountId(), "Initial2026Pwd!", null),
                ACCOUNT_CREDENTIAL_INVALID.getCode());
    }

    @Test
    void findsCurrentStoreAndOwnerNamesAfterAnEditWithoutChangingTheLogin() {
        Account account = createAccount(tenantId, IDS.incrementAndGet(), "hjmz-profile-rename", 0, "ACTIVE", false);
        jdbc.update("UPDATE pf_franchise_account SET org_name=?, owner_name=? WHERE tenant_id=? AND account_id=?",
                "青松建房中心", "方明", tenantId, account.accountId());
        for (String query : List.of("青松建房中心", "方明", "hjmz-profile-rename")) {
            assertThat(service.matches(query).items()).extracting(FranchiseAccountAuthService.AccountMatch::accountId)
                    .containsExactly(account.accountId());
        }
    }

    @Test
    void retriesDuplicateStoreNameWithOriginalVersionAndFindsTheSavedAccount() {
        // system_dept is a shim here; the operations module owns pf_org_profile in the full application.
        jdbc.execute("ALTER TABLE system_dept ADD COLUMN IF NOT EXISTS update_time timestamp");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS pf_org_profile (tenant_id bigint, org_id bigint, address varchar(300),
                    version bigint, updated_by bigint, updated_at timestamptz, PRIMARY KEY(tenant_id,org_id))
                """);
        String login = "hjmz-profile-retry", address = "南宁市青秀区青松路8号";
        Account account = createAccount(tenantId, IDS.incrementAndGet(), login, 0, "ACTIVE", false);
        Account other = createAccount(tenantId, IDS.incrementAndGet(), "hjmz-other", 0, "ACTIVE", false);
        for (Account row : List.of(account, other)) {
            jdbc.update("""
                    INSERT INTO system_dept(id,tenant_id,name)
                    SELECT org_id,tenant_id,org_name FROM pf_franchise_account WHERE account_id=?
                    """, row.accountId());
        }
        when(currentPost.require()).thenReturn(new PostSnapshot(account.postId(), account.userId(), account.orgId(),
                "store", "boss", null, false, Set.of()));
        var before = storeProfile.get().getData();
        String taken = jdbc.queryForObject("SELECT name FROM system_dept WHERE id=?", String.class, other.orgId());
        jdbc.update("UPDATE pf_franchise_account SET org_name=? WHERE account_id=?", "　"+taken+"　", other.accountId());
        assertThat(service.matches(taken).items()).extracting(FranchiseAccountAuthService.AccountMatch::accountId)
                .containsExactly(other.accountId());
        var duplicate = new StoreProfileController.Update(before.orgId(), before.version(), "　"+taken+"　", before.ownerName(), address);
        assertCode(() -> storeProfile.update(duplicate), 1_101_000_025);
        // A retired department can still reserve its account name through the unique index.
        jdbc.update("UPDATE system_dept SET deleted=1 WHERE id=?", other.orgId());
        assertCode(() -> storeProfile.update(duplicate), 1_101_000_025);
        assertThat(storeProfile.get().getData()).isEqualTo(before);

        var update = new StoreProfileController.Update(before.orgId(), before.version(),
                "　青松建房（南宁店）　", before.ownerName(), address);
        var saved = storeProfile.update(update).getData();
        assertThat(saved.address()).isEqualTo(address);
        assertThat(saved.version()).isEqualTo(before.version() + 1);
        assertThat(saved.name()).isEqualTo("青松建房（南宁店）");
        for (String query : List.of(saved.name(), "　"+saved.name()+"　", login)) {
            assertThat(service.matches(query).items()).extracting(FranchiseAccountAuthService.AccountMatch::accountId)
                    .containsExactly(account.accountId());
        }
        assertCode(() -> storeProfile.update(duplicate), 409);
        assertThat(storeProfile.get().getData()).isEqualTo(saved);
        addAlias(other, "青松 建房");
        for (String partial : List.of("青松 建房", "hjmz%", "hjmz_", "h")) {
            assertThat(service.matches(partial).items()).isEmpty();
        }
        var single = new StoreProfileController.Update(before.orgId(), saved.version(), "青", before.ownerName(), address);
        storeProfile.update(single);
        assertThat(service.matches("青").items()).extracting(FranchiseAccountAuthService.AccountMatch::accountId)
                .containsExactly(account.accountId());
    }

    @Test
    void formalExactWinsThenAliasExactCanReturnMultipleBeforePrefixSearch() {
        Account base = createAccount(tenantId, IDS.incrementAndGet(), "hjmz-jxnc-gaoxin", 0, "ACTIVE", false);
        addAlias(base, "jxnc-gaoxin");
        Account owner = createAccount(tenantId, IDS.incrementAndGet(), "hjmz-jxnc-gaoxin-lizhong", 0,
                "ACTIVE", false);
        addAlias(owner, "jxnc-gaoxin");
        // 无效正式账号不得抑制同名的有效简写候选。
        createAccount(tenantId, IDS.incrementAndGet(), "jxnc-gaoxin", 1, "ACTIVE", false);

        FranchiseAccountAuthService.MatchResult formal = service.matches("hjmz-jxnc-gaoxin");
        assertThat(formal.hasMore()).isFalse();
        assertThat(formal.items()).extracting(FranchiseAccountAuthService.AccountMatch::accountId)
                .containsExactly(base.accountId());

        FranchiseAccountAuthService.MatchResult aliases = service.matches("jxnc-gaoxin");
        assertThat(aliases.hasMore()).isFalse();
        assertThat(aliases.items()).extracting(FranchiseAccountAuthService.AccountMatch::accountId)
                .containsExactlyInAnyOrder(base.accountId(), owner.accountId());
    }

    @Test
    void initialPasswordNeverIssuesTokenUntilStrongPasswordIsSavedWithCostTen() {
        Account account = createAccount(tenantId, IDS.incrementAndGet(), "hjmz-jxnc-gaoxin-lizhong", 0,
                "ACTIVE", true);

        FranchiseAccountAuthService.LoginResult initial = service.login(account.accountId(), "Initial2026Pwd!", null);
        assertThat(initial.requirePasswordChange()).isTrue();
        assertThat(initial.token()).isNull();
        verifyNoInteractions(tokenIssuer);

        FranchiseAccountAuthService.LoginResult changed = service.login(
                account.accountId(), "Initial2026Pwd!", "Changed2026Pwd");
        assertThat(changed.requirePasswordChange()).isFalse();
        assertThat(changed.userId()).isEqualTo(account.userId());
        assertThat(changed.token()).isNotNull();
        String saved = jdbc.queryForObject("SELECT password FROM system_users WHERE id=?", String.class, account.userId());
        assertThat(saved).startsWith("$2a$10$");
        assertThat(BCRYPT.matches("Changed2026Pwd", saved)).isTrue();
        assertThat(jdbc.queryForObject("SELECT require_change FROM pf_staff_password_policy "
                + "WHERE tenant_id=? AND user_id=?", Boolean.class, tenantId, account.userId())).isFalse();
    }

    @Test
    void concurrentFirstChangeAcrossTwoStoresAllowsOnlyOneInitialPassword() throws Exception {
        long userId = IDS.incrementAndGet();
        Account first = createAccount(tenantId, userId, "hjmz-jxnc-gaoxin-lizhong", 0, "ACTIVE", true);
        Account second = createAccountForExistingUser(tenantId, userId, "hjmz-jxnc-honggutan-lizhong", "李忠二店");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            futures.add(pool.submit(() -> concurrentChange(first.accountId(), "Changed2026One", ready, start)));
            futures.add(pool.submit(() -> concurrentChange(second.accountId(), "Changed2026Two", ready, start)));
            ready.await();
            start.countDown();
            assertThat(List.of(futures.get(0).get(), futures.get(1).get()))
                    .containsExactlyInAnyOrder(0, ACCOUNT_CREDENTIAL_INVALID.getCode());
            String saved = jdbc.queryForObject("SELECT password FROM system_users WHERE id=?", String.class, userId);
            assertThat(BCRYPT.matches("Changed2026One", saved) || BCRYPT.matches("Changed2026Two", saved)).isTrue();
            verify(tokenIssuer, times(1)).issue(userId);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void failureBudgetIsSharedAcrossAliasesButIndependentBetweenUsers() {
        long lockedUser = IDS.incrementAndGet();
        Account first = createAccount(tenantId, lockedUser, "hjmz-jxnc-gaoxin-lock", 0, "ACTIVE", false);
        Account second = createAccountForExistingUser(tenantId, lockedUser, "hjmz-jxnc-honggutan-lock", "锁定二店");
        Account independent = createAccount(tenantId, IDS.incrementAndGet(), "hjmz-jxnc-qingshan-ok", 0,
                "ACTIVE", false);

        for (int i = 0; i < 5; i++) {
            String accountId = i % 2 == 0 ? first.accountId() : second.accountId();
            assertCode(() -> service.login(accountId, "wrong-password", null), ACCOUNT_CREDENTIAL_INVALID.getCode());
        }
        assertCode(() -> service.login(first.accountId(), "Initial2026Pwd!", null), ACCOUNT_LOGIN_LOCKED.getCode());
        assertThat(service.login(independent.accountId(), "Initial2026Pwd!", null).token()).isNotNull();
    }

    @Test
    void successfulPasswordLoginClearsFailureBudget() {
        Account account = createAccount(tenantId, IDS.incrementAndGet(), "hjmz-jxnc-xihu-clear", 0,
                "ACTIVE", false);
        for (int i = 0; i < 4; i++) {
            assertCode(() -> service.login(account.accountId(), "wrong-password", null),
                    ACCOUNT_CREDENTIAL_INVALID.getCode());
        }
        service.login(account.accountId(), "Initial2026Pwd!", null);
        for (int i = 0; i < 4; i++) {
            assertCode(() -> service.login(account.accountId(), "wrong-password", null),
                    ACCOUNT_CREDENTIAL_INVALID.getCode());
        }
        assertThatCode(() -> service.login(account.accountId(), "Initial2026Pwd!", null))
                .doesNotThrowAnyException();
    }

    @Test
    void headquartersOpensBossWhoBindsWechatThenBossOpensIndependentEmployeeAccount() {
        FranchiseOnboardService.Result boss = franchiseOnboardService.onboard(
                "合家美宅", "江西省", "南昌市", "高新区", "李忠", "13800108881",
                null, null, "贯通测试", 9L);

        FranchiseAccountAuthService.LoginResult bossInitial = service.login(
                boss.accountId(), boss.initialPassword(), null);
        assertThat(bossInitial.requirePasswordChange()).isTrue();
        assertThat(bossInitial.token()).isNull();
        FranchiseAccountAuthService.LoginResult bossLogin = service.login(
                boss.accountId(), boss.initialPassword(), "BossSecure2026");
        assertThat(bossLogin.userId()).isEqualTo(boss.adminUserId());
        assertThat(bossLogin.preferredPostId()).isEqualTo(boss.postId());

        when(wechat.openid("wx-franchise-flow", "boss-bind-code")).thenReturn("openid-boss-flow");
        explicitBindingService.bind("wx-franchise-flow", "boss-bind-code", boss.adminUserId());
        when(wechat.openid("wx-franchise-flow", "boss-login-code")).thenReturn("openid-boss-flow");
        WechatLoginResult wechatLogin = wechatLoginService.login(
                "wx-franchise-flow", "boss-login-code", null);
        assertThat(wechatLogin.userId()).isEqualTo(boss.adminUserId());
        var bossPost = staffPostService.findActivePost(wechatLogin.userId(), boss.postId()).orElseThrow();
        assertThat(bossPost.orgId()).isEqualTo(boss.orgId());
        assertThat(bossPost.roleCode()).isEqualTo("boss");

        StaffAccountOnboardService.Result employee = staffAccountOnboardService.open(
                boss.orgId(), null, "张三", "13900108881", "journey-sales", null,
                boss.adminUserId(), boss.postId());
        assertThat(employee.userId()).isNotEqualTo(boss.adminUserId());
        assertThat(employee.orgName()).isEqualTo(boss.orgName());
        assertThat(employee.loginName()).isEqualTo("zhangsan");
        assertThat(employee.initialPassword()).hasSize(16).isNotEqualTo("zhangsan123");
        assertCode(() -> service.login(employee.accountId(), "zhangsan123", "Attacker2026Pwd"),
                ACCOUNT_CREDENTIAL_INVALID.getCode());
        FranchiseAccountAuthService.LoginResult employeeInitial = service.login(
                employee.accountId(), employee.initialPassword(), null);
        assertThat(employeeInitial.requirePasswordChange()).isTrue();
        assertThat(employeeInitial.token()).isNull();
        FranchiseAccountAuthService.LoginResult employeeLogin = service.login(
                employee.accountId(), employee.initialPassword(), "654321");
        assertThat(employeeLogin.userId()).isEqualTo(employee.userId());
        assertThat(employeeLogin.preferredPostId()).isEqualTo(employee.postId());
        var employeePost = staffPostService.findActivePost(employee.userId(), employee.postId()).orElseThrow();
        assertThat(employeePost.orgId()).isEqualTo(boss.orgId());
        assertThat(employeePost.roleCode()).isEqualTo("journey-sales");
        String savedPassword = loadUser(employee.userId()).getPassword();
        when(wechat.openid("wx-franchise-flow", "employee-bind-code")).thenReturn("openid-employee-flow");
        explicitBindingService.bind("wx-franchise-flow", "employee-bind-code", employee.userId());
        when(wechat.openid("wx-franchise-flow", "employee-login-code")).thenReturn("openid-employee-flow");
        assertThat(wechatLoginService.login("wx-franchise-flow", "employee-login-code", null).userId()).isEqualTo(employee.userId());
        assertThat(service.login(employee.accountId(), "654321", null).userId()).isEqualTo(employee.userId());
        var reused = staffAccountOnboardService.open(boss.orgId(), employee.userId(), "张三", "13900108881", "journey-sales", null, boss.adminUserId(), boss.postId());
        assertThat(reused.accountId()).isEqualTo(employee.accountId());assertThat(reused.initialPassword()).isNull();
        assertThat(loadUser(employee.userId()).getPassword()).isEqualTo(savedPassword);
    }

    @Test
    void importAndSingleOpeningDeliverTheSameLoginChannelsAndInitialPasswordPolicy() {
        var store = importStore("批量店", "13800107001");
        long usersBefore = tenantUserCount();
        var req = importRequest(store.boss().orgId(), "张三", "13900107001");
        var preview = staffImport.precheck(req).getData();
        assertThat(preview.rows().get(0).status()).isEqualTo("OK");
        assertThat(preview.rows().get(0).account()).isNull();
        assertThat(tenantUserCount()).isEqualTo(usersBefore);
        assertThat(service.matches("zhangsan").items()).isEmpty();

        var imported = staffImport.submit(req).getData().rows().get(0);
        var single = staffAccountOnboardService.open(store.boss().orgId(), null, "张三", "13900107002",
                "journey-sales", store.teamId(), store.boss().adminUserId(), store.boss().postId());
        assertThat(imported.status()).isEqualTo("CREATED");
        assertThat(imported.userId()).isNotNull();
        var account = imported.account();
        assertThat(account.loginName()).isEqualTo("zhangsan");
        assertThat(single.loginName()).isEqualTo("zhangsan2");
        assertThat(single.userId()).isNotEqualTo(imported.userId());
        assertThat(account.aliases()).contains("zhangsan", "zs");
        assertThat(account.initialPassword()).hasSize(16).isNotEqualTo("zhangsan123");
        assertThat(BCRYPT.matches(account.initialPassword(), loadUser(imported.userId()).getPassword())).isTrue();
        assertThat(service.matches(account.loginName()).items())
                .extracting(FranchiseAccountAuthService.AccountMatch::accountId).contains(account.accountId());
        assertThat(service.login(account.accountId(), account.initialPassword(), null).requirePasswordChange()).isTrue();
        assertThat(service.login(single.accountId(), single.initialPassword(), null).requirePasswordChange()).isTrue();
        var post = staffPostService.findActivePost(imported.userId(), imported.postId()).orElseThrow();
        assertThat(post.orgId()).isEqualTo(store.boss().orgId());
        assertThat(post.teamId()).isEqualTo(store.teamId());
        assertThat(post.roleCode()).isEqualTo("journey-sales");
        assertThat(jdbc.queryForObject("SELECT initial_password_expires_at BETWEEN now()+INTERVAL '47 hours' "
                + "AND now()+INTERVAL '49 hours' FROM pf_staff_password_policy WHERE tenant_id=? AND user_id=?",
                Boolean.class, tenantId, imported.userId())).isTrue();
        assertThat(service.login(account.accountId(), account.initialPassword(), "ImportPersonal2026").userId())
                .isEqualTo(imported.userId());
        when(wechat.openid("wx-franchise-flow", "import-wechat")).thenReturn("import-openid");
        when(wechat.phoneNumber("wx-franchise-flow", "import-phone")).thenReturn(req.rows().get(0).mobile());
        assertThat(wechatLoginService.login("wx-franchise-flow", "import-wechat", "import-phone").userId())
                .isEqualTo(imported.userId());
        assertThat(service.login(account.accountId(), "ImportPersonal2026", null).userId()).isEqualTo(imported.userId());
    }

    @Test
    void retryAndAdditionalPostDoNotReissuePasswordOrExtendActivationDeadline() {
        var store = importStore("重试店", "13800107003");
        var req = importRequest(store.boss().orgId(), "王五", "13900107003");
        var first = staffImport.submit(req).getData().rows().get(0);
        String hash = loadUser(first.userId()).getPassword();
        var expires = jdbc.queryForObject("SELECT initial_password_expires_at FROM pf_staff_password_policy "
                + "WHERE tenant_id=? AND user_id=?", java.time.OffsetDateTime.class, tenantId, first.userId());
        var retry = staffImport.submit(req).getData();
        assertThat(retry.skipped()).isEqualTo(1);
        assertThat(retry.created()).isZero();
        assertThat(retry.rows().get(0).account().accountId()).isEqualTo(first.account().accountId());
        assertThat(retry.rows().get(0).account().initialPassword()).isNull();
        assertThat(retry.rows().get(0).account().reusedUser()).isTrue();
        jdbc.update("INSERT INTO pf_post_template(role_code,domain,org_types,line,lead) VALUES "
                + "('import-finance','test','store','finance',FALSE) ON CONFLICT DO NOTHING");
        directory.roleIdsByCode.put("import-finance", 503L);
        var extra = staffImport.submit(new StaffImportController.ImportReq(store.boss().orgId(), List.of(
                new StaffImportController.ImportRowReq(2, "王五", "13900107003", "import-finance", "业务一组")))).getData();
        assertThat(extra.rows().get(0).userId()).isEqualTo(first.userId());
        assertThat(extra.rows().get(0).account().initialPassword()).isNull();
        assertThat(loadUser(first.userId()).getPassword()).isEqualTo(hash);
        assertThat(jdbc.queryForObject("SELECT require_change FROM pf_staff_password_policy WHERE tenant_id=? AND user_id=?",
                Boolean.class, tenantId, first.userId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT initial_password_expires_at FROM pf_staff_password_policy "
                + "WHERE tenant_id=? AND user_id=?", java.time.OffsetDateTime.class, tenantId, first.userId())).isEqualTo(expires);
        assertThat(tenantUserCount()).isEqualTo(2);
        assertThat(first.toString()).doesNotContain(first.account().initialPassword());
    }

    @Test
    void legacyImportedPostGetsAliasesWithoutChangingExistingUserAndCrossStoreReuseStaysScoped() {
        var firstStore = importStore("旧导入店", "13800107004");
        long oldPost = staffPostService.createStaffInOrg(firstStore.boss().orgId(), "原姓名", "13900107004",
                "journey-sales", firstStore.teamId(), firstStore.boss().adminUserId());
        long userId = staffPostService.getPost(oldPost).orElseThrow().getUserId();
        String hash = loadUser(userId).getPassword();
        var first = staffImport.submit(importRequest(firstStore.boss().orgId(), "导入新姓名", "13900107004"))
                .getData().rows().get(0);
        assertThat(first.status()).isEqualTo("SKIPPED");
        assertThat(first.postId()).isEqualTo(oldPost);
        assertThat(first.account().name()).isEqualTo("原姓名");
        assertThat(first.account().reusedUser()).isTrue();
        assertThat(first.account().initialPassword()).isNull();
        assertThat(service.login(first.account().accountId(), "LegacySecure2026", null).userId()).isEqualTo(userId);
        var secondStore = importStore("跨店复用", "13800107005");
        var cross = staffImport.submit(importRequest(secondStore.boss().orgId(), "原姓名", "13900107004"))
                .getData().rows().get(0);
        assertThat(cross.userId()).isEqualTo(userId);
        assertThat(cross.account().accountId()).isNotEqualTo(first.account().accountId());
        assertThat(cross.account().orgName()).isEqualTo(secondStore.boss().orgName());
        assertThat(cross.account().initialPassword()).isNull();
        assertThat(loadUser(userId).getPassword()).isEqualTo(hash);
        assertThat(staffPostService.listActivePosts(userId)).extracting(p -> p.getOrgId())
                .containsExactlyInAnyOrder(firstStore.boss().orgId(), secondStore.boss().orgId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pf_staff_password_policy WHERE tenant_id=? AND user_id=?",
                Integer.class, tenantId, userId)).isZero();
        assertCode(() -> staffImport.submit(importRequest(firstStore.boss().orgId(), "越权", "13900107006")),
                cn.iocoder.yudao.module.platform.authz.enums.PlatformAuthzErrorCodes.ORG_SCOPE_FORBIDDEN.getCode());
        assertThat(directory.userIdByMobile("13900107006")).isNull();
    }

    @Test
    void skippedButDisabledUserFailsInsteadOfPretendingCredentialsWereDelivered() {
        var store = importStore("停用复核", "13800107006");
        long post = staffPostService.createStaffInOrg(store.boss().orgId(), "停用员工", "13900107007",
                "journey-sales", store.teamId(), store.boss().adminUserId());
        long user = staffPostService.getPost(post).orElseThrow().getUserId();
        directory.setUserActive(user, false);
        var result = staffImport.submit(importRequest(store.boss().orgId(), "停用员工", "13900107007")).getData();
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.skipped()).isZero();
        assertThat(result.rows().get(0).account()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pf_franchise_account WHERE tenant_id=? AND user_id=?",
                Integer.class, tenantId, user)).isZero();
    }

    @Test
    void realOnboardingRollbackKeepsOtherRowsCommittedAndDoesNotLeakFailedUserOrCredentials() {
        var store = importStore("逐行事务", "13800107007");
        long missingTeam = IDS.incrementAndGet();
        OrgService staleTeams = mock(OrgService.class);
        when(staleTeams.getUnit(store.boss().orgId())).thenReturn(orgService.getUnit(store.boss().orgId()));
        when(staleTeams.listTeams(store.boss().orgId())).thenReturn(List.of(
                new OrgUnitDO().setDeptId(store.teamId()), new OrgUnitDO().setDeptId(missingTeam)));
        when(staleTeams.listPostTemplates(store.boss().orgId()))
                .thenReturn(List.of(new PostTemplateDO().setRoleCode("journey-sales")));
        directory.deptNames.put(missingTeam, "已删除组");
        directory.deptTenants.put(missingTeam, tenantId);
        var controller = new StaffImportController(currentPost,
                new StaffImportService(staffPostService, staleTeams, directory), staffAccountOnboardService);
        var result = controller.submit(new StaffImportController.ImportReq(store.boss().orgId(), List.of(
                new StaffImportController.ImportRowReq(1, "先成功", "13900107008", "journey-sales", "业务一组"),
                new StaffImportController.ImportRowReq(2, "须回滚", "13900107009", "journey-sales", "已删除组"),
                new StaffImportController.ImportRowReq(3, "后成功", "13900107010", "journey-sales", "业务一组"))))
                .getData();
        assertThat(result.created()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.rows().get(1).code())
                .isEqualTo(cn.iocoder.yudao.module.platform.org.enums.PlatformOrgErrorCodes.TEAM_NOT_IN_ORG.getCode());
        assertThat(result.rows().get(1).account()).isNull();
        assertThat(directory.userIdByMobile("13900107009")).isNull();
        assertThat(tenantUserCount()).isEqualTo(3);
        for (var row : List.of(result.rows().get(0), result.rows().get(2))) {
            assertThat(service.login(row.account().accountId(), row.account().initialPassword(), null)
                    .requirePasswordChange()).isTrue();
            assertThat(result.toString()).doesNotContain(row.account().initialPassword());
        }
    }

    @Test
    void concurrentImportOfSamePhoneCreatesOneUserAndOnlyOneInitialCredential() throws Exception {
        var store = importStore("并发导入", "13800107008");
        var request = importRequest(store.boss().orgId(), "并发员工", "13900107011");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            java.util.concurrent.Callable<StaffImportService.ImportResult> call = () -> {
                TenantContextHolder.setTenantId(tenantId);
                try { start.await(); return staffImport.submit(request).getData(); }
                finally { TenantContextHolder.clear(); }
            };
            var a = pool.submit(call); var b = pool.submit(call); start.countDown();
            var x = a.get(); var y = b.get();
            assertThat(x.failed() + y.failed()).isZero();
            assertThat(x.rows().get(0).userId()).isEqualTo(y.rows().get(0).userId());
            assertThat(x.rows().get(0).postId()).isEqualTo(y.rows().get(0).postId());
            assertThat(x.rows().get(0).account().accountId()).isEqualTo(y.rows().get(0).account().accountId());
            assertThat((x.rows().get(0).account().initialPassword() == null)
                    != (y.rows().get(0).account().initialPassword() == null)).isTrue();
            assertThat(tenantUserCount()).isEqualTo(2);
        } finally { pool.shutdownNow(); }
    }

    private record ImportStore(FranchiseOnboardService.Result boss, long teamId) { }

    private ImportStore importStore(String area, String phone) {
        var boss = franchiseOnboardService.onboard("合家美宅", "江西", "南昌", area, "老板", phone,
                null, null, "", 9L);
        long team = orgService.createTeam(boss.orgId(), null, "业务一组", "sales");
        when(currentPost.require()).thenReturn(staffPostService.findActivePost(boss.adminUserId(), boss.postId()).orElseThrow());
        return new ImportStore(boss, team);
    }

    private StaffImportController.ImportReq importRequest(long org, String name, String phone) {
        return new StaffImportController.ImportReq(org, List.of(
                new StaffImportController.ImportRowReq(1, name, phone, "journey-sales", "业务一组")));
    }

    private long tenantUserCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM system_users WHERE tenant_id=?", Long.class, tenantId);
    }

    @Test
    void concurrentSameNamesAcrossStoresHaveUniqueAccountsAndMatchingPasswords() throws Exception {
        var first = franchiseOnboardService.onboard("合家美宅", "江西", "南昌", "东湖", "甲", "13800107880", null, null, "", 9L);
        var second = franchiseOnboardService.onboard("合家美宅", "江西", "南昌", "西湖", "乙", "13800107881", null, null, "", 9L);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try {
            List<Future<StaffAccountOnboardService.Result>> futures = new ArrayList<>();
            for (int n = 0; n < 2; n++) {
                final int index = n;
                futures.add(pool.submit(() -> {
                    TenantContextHolder.setTenantId(tenantId);
                    try {
                        ready.countDown(); start.await(); var boss = index == 0 ? first : second;
                        return staffAccountOnboardService.open(boss.orgId(), null, "李丽", "1390010788" + index, "journey-sales", null, boss.adminUserId(), boss.postId());
                    } finally { TenantContextHolder.clear(); }
                }));
            }
            ready.await(); start.countDown();var results = List.of(futures.get(0).get(), futures.get(1).get());
            assertThat(results).extracting(StaffAccountOnboardService.Result::loginName).containsExactlyInAnyOrder("lili", "lili2");
            for (var result : results) {
                assertThat(result.initialPassword()).hasSize(16).isNotEqualTo(result.loginName() + "123");
                assertThat(BCRYPT.matches(result.initialPassword(), loadUser(result.userId()).getPassword())).isTrue();
                assertThat(service.login(result.accountId(), result.initialPassword(), null).requirePasswordChange()).isTrue();
            }
            var collision = staffAccountOnboardService.open(first.orgId(), null, "lili2", "13900107882", "journey-sales", null, first.adminUserId(), first.postId());
            assertThat(collision.loginName()).isEqualTo("lili22");
            assertThat(collision.initialPassword()).hasSize(16).isNotEqualTo("lili22123");
            assertThat(results).extracting(StaffAccountOnboardService.Result::initialPassword).doesNotHaveDuplicates();
            assertThat(service.matches("李丽").items()).hasSize(2);
        } finally { pool.shutdownNow(); }
    }

    @Test
    void bossAndEmployeeCanFirstUseWechatPhoneWithoutPasswordOrManualBinding() {
        var boss = franchiseOnboardService.onboard("合家美宅", "江西", "南昌", "自动识别店", "老板", "13800107891", null, null, "", 9L);
        var employee = staffAccountOnboardService.open(boss.orgId(), null, "李丽", "13900107891", "journey-sales", null, boss.adminUserId(), boss.postId());
        for (var account : List.of(
                new Object[]{boss.accountId(), boss.adminUserId(), "13800107891", boss.initialPassword()},
                new Object[]{employee.accountId(), employee.userId(), "13900107891", employee.initialPassword()})) {
            String id=(String)account[0], phone=(String)account[2], initial=(String)account[3]; long user=(Long)account[1];
            String code="auto-phone-"+user, openid="auto-openid-"+user;
            when(wechat.openid("wx-franchise-flow", code)).thenReturn(openid);
            when(wechat.phoneNumber("wx-franchise-flow", code)).thenReturn(phone);
            String passwordBefore=loadUser(user).getPassword();
            assertThat(wechatLoginService.login("wx-franchise-flow", code, null).needPhone()).isTrue();
            var first=wechatLoginService.login("wx-franchise-flow", code, code);
            assertThat(first.userId()).isEqualTo(user);assertThat(first.token()).isNotNull();
            assertThat(bindingMapper.selectActive("wx-franchise-flow", openid).getUserId()).isEqualTo(user);
            assertThat(loadUser(user).getPassword()).isNotEqualTo(passwordBefore);
            assertThat(BCRYPT.matches(initial, loadUser(user).getPassword())).isFalse();
            String retiredPassword=loadUser(user).getPassword();
            assertThat(wechatLoginService.login("wx-franchise-flow", code, null).userId()).isEqualTo(user);
            verify(wechat,times(1)).phoneNumber("wx-franchise-flow", code);
            assertThat(loadUser(user).getPassword()).isEqualTo(retiredPassword);
            assertCode(() -> service.login(id, initial, null), ACCOUNT_CREDENTIAL_INVALID.getCode());
            assertCode(() -> service.login(id, initial, "123456"), ACCOUNT_CREDENTIAL_INVALID.getCode());
            assertThat(wechatLoginService.login("wx-franchise-flow", code, null).userId()).isEqualTo(user);
        }
    }

    @Test
    void expiredInitialPasswordCannotActivateEvenWithANewPassword() {
        Account account = createAccount(tenantId, IDS.incrementAndGet(), "expiry-check", 0, "ACTIVE", true);
        assertThat(jdbc.queryForObject("SELECT initial_password_expires_at BETWEEN now()+INTERVAL '47 hours' "
                + "AND now()+INTERVAL '49 hours' FROM pf_staff_password_policy WHERE tenant_id=? AND user_id=?",
                Boolean.class, tenantId, account.userId())).isTrue();
        jdbc.update("UPDATE pf_staff_password_policy SET initial_password_expires_at=now()-INTERVAL '1 second' "
                + "WHERE tenant_id=? AND user_id=?", tenantId, account.userId());
        assertCode(() -> service.login(account.accountId(), "Initial2026Pwd!", null), ACCOUNT_INITIAL_PASSWORD_EXPIRED.getCode());
        assertCode(() -> service.login(account.accountId(), "Initial2026Pwd!", "Replacement2026"), ACCOUNT_INITIAL_PASSWORD_EXPIRED.getCode());
        assertThat(BCRYPT.matches("Initial2026Pwd!", loadUser(account.userId()).getPassword())).isTrue();
        jdbc.update("UPDATE system_users SET password='!retired-initial-password' WHERE id=?", account.userId());
        assertCode(() -> service.login(account.accountId(), "Initial2026Pwd!", null), ACCOUNT_CREDENTIAL_INVALID.getCode());
        assertThat(BCRYPT.matches("Initial2026Pwd!", loadUser(account.userId()).getPassword())).isFalse();
        verifyNoInteractions(tokenIssuer);
    }

    @Test
    void systemUsernamePasswordEntryCannotBypassFirstChangePolicy() {
        Account account = createAccount(tenantId, IDS.incrementAndGet(), "system-entry-check", 0, "ACTIVE", true);
        AdminAuthServiceImpl webAuth = new AdminAuthServiceImpl();
        ReflectionTestUtils.setField(webAuth, "userService", adminUserService);
        ReflectionTestUtils.setField(webAuth, "loginLogService", mock(LoginLogService.class));
        ReflectionTestUtils.setField(webAuth, "passwordPolicies", passwordPolicies().getBeanProvider(AdminUserPasswordPolicy.class));
        when(adminUserService.getUserByUsername("staff-user")).thenAnswer(inv -> loadUser(account.userId()));
        assertThatThrownBy(() -> webAuth.authenticate("staff-user", "Initial2026Pwd!"))
                .isInstanceOf(ServiceException.class).hasMessageContaining("首次改密");
        service.login(account.accountId(), "Initial2026Pwd!", "Chosen2026Pwd");
        assertThat(webAuth.authenticate("staff-user", "Chosen2026Pwd").getId()).isEqualTo(account.userId());
        jdbc.update("UPDATE system_users SET password='!retired-initial-password' WHERE id=?", account.userId());
        assertThatThrownBy(() -> webAuth.authenticate("staff-user", "Initial2026Pwd!"))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void systemPasswordMatchCannotRaceWechatRetirementIntoATokenEligibleUser() {
        Account account = createAccount(tenantId, IDS.incrementAndGet(), "system-race-check", 0, "ACTIVE", true);
        AdminAuthServiceImpl webAuth = new AdminAuthServiceImpl();
        ReflectionTestUtils.setField(webAuth, "userService", adminUserService);
        ReflectionTestUtils.setField(webAuth, "loginLogService", mock(LoginLogService.class));
        ReflectionTestUtils.setField(webAuth, "passwordPolicies", passwordPolicies().getBeanProvider(AdminUserPasswordPolicy.class));
        when(adminUserService.getUserByUsername("racing-staff")).thenAnswer(inv -> loadUser(account.userId()));
        String initialHash = loadUser(account.userId()).getPassword();
        when(adminUserService.isPasswordMatch("Initial2026Pwd!", initialHash)).thenAnswer(inv -> {
            boolean matched = BCRYPT.matches("Initial2026Pwd!", initialHash);
            initialPasswords.retireAfterWechatLogin(account.userId());
            return matched;
        });
        assertThatThrownBy(() -> webAuth.authenticate("racing-staff", "Initial2026Pwd!"))
                .isInstanceOf(ServiceException.class);
        assertThat(jdbc.queryForObject("SELECT require_change FROM pf_staff_password_policy WHERE tenant_id=? AND user_id=?",
                Boolean.class, tenantId, account.userId())).isFalse();
        assertThat(loadUser(account.userId()).getPassword()).isNotEqualTo(initialHash);
        verifyNoInteractions(tokenIssuer);
    }

    @Test
    void passwordLockReadsFreshHashEvenWhenMybatisSessionContainsTheOldSnapshot() {
        Account account = createAccount(tenantId, IDS.incrementAndGet(), "mybatis-password-cache", 0, "ACTIVE", true);
        var configuration = sqlSessionFactory.getConfiguration();
        if (!configuration.hasMapper(AdminUserMapper.class)) configuration.addMapper(AdminUserMapper.class);
        String readId = "audit.passwordSnapshot" + IDS.incrementAndGet();
        var result = new org.apache.ibatis.mapping.ResultMap.Builder(configuration, readId + "Map", String.class, List.of()).build();
        var sql = new org.apache.ibatis.builder.StaticSqlSource(configuration,
                "SELECT password FROM system_users WHERE id=" + account.userId());
        configuration.addMappedStatement(new org.apache.ibatis.mapping.MappedStatement.Builder(configuration, readId,
                sql, org.apache.ibatis.mapping.SqlCommandType.SELECT).resultMaps(List.of(result)).build());
        try (var session = sqlSessionFactory.openSession(false)) {
            String oldHash = session.selectOne(readId);
            String changedHash = BCRYPT.encode("Concurrent2026");
            jdbc.update("UPDATE system_users SET password=? WHERE id=?", changedHash, account.userId());
            // The repeated read really is stale; the production lock query must bypass/clear that session cache.
            assertThat((String) session.selectOne(readId)).isEqualTo(oldHash);
            String lockedHash = session.getMapper(AdminUserMapper.class).selectPasswordForUpdate(account.userId());
            assertThat(lockedHash).isEqualTo(changedHash);
            assertThat(BCRYPT.matches("Initial2026Pwd!", lockedHash)).isFalse();
            assertThat((String) session.selectOne(readId)).isEqualTo(changedHash);
            session.rollback();
        }
    }

    @Test
    void verifiedResetRestoresExpiredPasswordLoginAndFailedProfileChangeKeepsPolicy() {
        Account account = createAccount(tenantId, IDS.incrementAndGet(), "reset-expired-check", 0, "ACTIVE", true);
        jdbc.update("UPDATE pf_staff_password_policy SET initial_password_expires_at=now()-INTERVAL '1 day' "
                + "WHERE tenant_id=? AND user_id=?", tenantId, account.userId());
        AdminUserServiceImpl resetter = new AdminUserServiceImpl();
        ReflectionTestUtils.setField(resetter, "userMapper", adminUserMapper);
        ReflectionTestUtils.setField(resetter, "passwordEncoder", BCRYPT);
        ReflectionTestUtils.setField(resetter, "passwordPolicies", passwordPolicies().getBeanProvider(AdminUserPasswordPolicy.class));
        when(adminUserMapper.selectById(account.userId())).thenAnswer(inv -> loadUser(account.userId()));
        when(adminUserMapper.selectPasswordForUpdate(account.userId())).thenAnswer(inv -> loadUser(account.userId()).getPassword());
        var transaction = new TransactionTemplate(transactions);
        var wrongOld = new UserProfileUpdatePasswordReqVO().setOldPassword("wrong-old").setNewPassword("Chosen2026Pwd");
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> resetter.updateUserPassword(account.userId(), wrongOld)))
                .isInstanceOf(ServiceException.class);
        assertThat(jdbc.queryForObject("SELECT require_change FROM pf_staff_password_policy WHERE tenant_id=? AND user_id=?",
                Boolean.class, tenantId, account.userId())).isTrue();
        verify(adminUserMapper).selectPasswordForUpdate(account.userId());

        // This is the same password write called after SMS verification or an authorized system-admin reset.
        transaction.executeWithoutResult(status -> resetter.updateUserPassword(account.userId(), "Verified2026Pwd"));
        assertThat(jdbc.queryForObject("SELECT require_change FROM pf_staff_password_policy WHERE tenant_id=? AND user_id=?",
                Boolean.class, tenantId, account.userId())).isFalse();
        assertThatCode(() -> initialPasswords.validateLogin(loadUser(account.userId()))).doesNotThrowAnyException();
        assertThat(service.login(account.accountId(), "Verified2026Pwd", null).userId()).isEqualTo(account.userId());
        assertCode(() -> service.login(account.accountId(), "Initial2026Pwd!", null), ACCOUNT_CREDENTIAL_INVALID.getCode());
    }

    @Test
    void existingWechatBindingRetiresLegacyInitialPasswordWithoutChangingChosenPasswords() {
        var boss = franchiseOnboardService.onboard("合家美宅", "江西", "南昌", "旧绑定安全", "老板", "13800107901", null, null, "", 9L);
        var employee = staffAccountOnboardService.open(boss.orgId(), null, "员工", "13900107901", "journey-sales", null, boss.adminUserId(), boss.postId());
        String predictable = employee.loginName() + "123";
        jdbc.update("UPDATE system_users SET password=? WHERE id=?", BCRYPT.encode(predictable), employee.userId());
        jdbc.update("UPDATE pf_staff_password_policy SET initial_password_expires_at=now()-INTERVAL '1 day' WHERE tenant_id=? AND user_id=?",
                tenantId, employee.userId());
        bindingService.bind("wx-franchise-flow", "legacy-bound-openid", employee.userId(), "13900107901");
        when(wechat.openid("wx-franchise-flow", "legacy-bound-code")).thenReturn("legacy-bound-openid");
        assertThat(wechatLoginService.login("wx-franchise-flow", "legacy-bound-code", null).userId()).isEqualTo(employee.userId());
        assertThat(BCRYPT.matches(predictable, loadUser(employee.userId()).getPassword())).isFalse();
        assertCode(() -> service.login(employee.accountId(), predictable, "Replacement2026"), ACCOUNT_CREDENTIAL_INVALID.getCode());
        String after = loadUser(employee.userId()).getPassword();
        assertThat(wechatLoginService.login("wx-franchise-flow", "legacy-bound-code", null).userId()).isEqualTo(employee.userId());
        assertThat(loadUser(employee.userId()).getPassword()).isEqualTo(after);
        verify(wechat, never()).phoneNumber(eq("wx-franchise-flow"), anyString());
    }

    @Test
    void expiryMigrationRetiresOnlyInitialStaffCredentialsIncludingStaffPromotedToBoss() throws Exception {
        String migration;
        try (var input = new ClassPathResource("db/migration/pf/V20261122.990__pf_initial_password_expiry.sql").getInputStream()) {
            migration = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            String originalSchema = connection.getSchema();
            String schema = "password_security_" + IDS.incrementAndGet();
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA " + schema);
                connection.setSchema(schema);
                statement.execute("CREATE TABLE pf_staff_password_policy(tenant_id BIGINT,user_id BIGINT,require_change BOOLEAN,PRIMARY KEY(tenant_id,user_id))");
                statement.execute("CREATE TABLE pf_franchise_account(tenant_id BIGINT,user_id BIGINT,account_type VARCHAR(8))");
                statement.execute("CREATE TABLE system_users(id BIGINT,tenant_id BIGINT,password VARCHAR(100))");
                statement.execute("INSERT INTO pf_staff_password_policy VALUES(1,1,true),(1,2,false),(1,3,true),(1,4,true),(2,1,true)");
                statement.execute("INSERT INTO pf_franchise_account VALUES(1,1,'STAFF'),(1,2,'STAFF'),(1,3,'BOSS'),(1,4,'STAFF'),(1,4,'BOSS'),(2,1,'BOSS')");
                statement.execute("INSERT INTO system_users VALUES(1,1,'legacy-1'),(2,1,'chosen-2'),(3,1,'random-boss-3'),(4,1,'legacy-promoted-4'),(1,2,'other-tenant-boss')");
                // Let PostgreSQL parse dollar-quoted blocks; ScriptUtils would split their internal semicolons.
                statement.execute(migration);
                Set<String> retired = new HashSet<>();
                try (var rows = statement.executeQuery("SELECT tenant_id,user_id FROM pf_staff_password_policy WHERE initial_password_expires_at < now()")) {
                    while (rows.next()) retired.add(rows.getLong(1) + ":" + rows.getLong(2));
                }
                assertThat(retired).containsExactlyInAnyOrder("1:1", "1:4");
                Map<String,String> passwords = new HashMap<>();
                try (var rows = statement.executeQuery("SELECT tenant_id,id,password FROM system_users")) {
                    while(rows.next()) passwords.put(rows.getLong(1)+":"+rows.getLong(2), rows.getString(3));
                }
                assertThat(passwords).containsEntry("1:1", "!retired-initial-password")
                        .containsEntry("1:4", "!retired-initial-password").containsEntry("1:2", "chosen-2")
                        .containsEntry("1:3", "random-boss-3").containsEntry("2:1", "other-tenant-boss");
                try (var rows = statement.executeQuery("SELECT require_change FROM pf_staff_password_policy WHERE tenant_id=1 AND user_id=2")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getBoolean(1)).isFalse();
                }
            } finally {
                connection.setSchema(originalSchema);
                try (var statement = connection.createStatement()) { statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); }
            }
            return null;
        });
    }

    private StaticListableBeanFactory passwordPolicies() {
        var beans = new StaticListableBeanFactory();
        beans.addBean("initialPasswords", initialPasswords);
        return beans;
    }

    @Test
    void accountSearchReturnsPersonRoleAndStructuredShortOrganization() {
        var account = createAccount(tenantId, IDS.incrementAndGet(), "peihuorong", 0, "ACTIVE", false);
        jdbc.update("UPDATE system_users SET nickname='裴火荣' WHERE id=?", account.userId());
        jdbc.update("UPDATE pf_staff_post SET role_code='boss' WHERE id=?", account.postId());
        jdbc.update("UPDATE pf_franchise_account SET area='高新区合家美宅',org_name='合家美宅-江西南昌-高新区合家美宅-裴火荣' WHERE account_id=?", account.accountId());
        var result = service.matches("peihuorong").items().get(0);
        assertThat(result.personName()).isEqualTo("裴火荣");assertThat(result.roleName()).isEqualTo("老板");
        assertThat(result.shortOrgName()).isEqualTo("合家美宅 · 江西南昌高新区");assertThat(result.accountId()).isEqualTo(account.accountId());
    }

    @Test
    void explicitWechatBindRejectsPostRevokedDuringWechatExchange() {
        FranchiseOnboardService.Result boss = franchiseOnboardService.onboard(
                "合家美宅", "江西", "南昌", "经开", "撤岗测试", "13800108882",
                null, null, "", 9L);
        when(wechat.openid("wx-franchise-flow", "revoke-during-code")).thenAnswer(invocation -> {
            jdbc.update("UPDATE pf_staff_post SET status='REVOKED',revoked_at=now(),revoked_by=9 WHERE id=?",
                    boss.postId());
            return "openid-revoked-flow";
        });

        assertThatThrownBy(() -> explicitBindingService.bind(
                "wx-franchise-flow", "revoke-during-code", boss.adminUserId()))
                .isInstanceOf(ServiceException.class);
        assertThat(bindingMapper.selectActive("wx-franchise-flow", "openid-revoked-flow")).isNull();
    }

    @Test
    void explicitWechatBindRejectsMobileChangedDuringWechatExchange() {
        FranchiseOnboardService.Result boss = franchiseOnboardService.onboard(
                "合家美宅", "江西", "南昌", "青山湖", "改号测试", "13800108883",
                null, null, "", 9L);
        when(wechat.openid("wx-franchise-flow", "mobile-during-code")).thenAnswer(invocation -> {
            jdbc.update("UPDATE system_users SET mobile='13800109999' WHERE id=?", boss.adminUserId());
            return "openid-mobile-flow";
        });

        assertCode(() -> explicitBindingService.bind(
                "wx-franchise-flow", "mobile-during-code", boss.adminUserId()), STAFF_NOT_ENROLLED.getCode());
        assertThat(bindingMapper.selectActive("wx-franchise-flow", "openid-mobile-flow")).isNull();
    }

    @Test
    void explicitWechatBindKeepsOtherUserConflictAndSameUserIdempotency() {
        FranchiseOnboardService.Result first = franchiseOnboardService.onboard(
                "合家美宅", "江西", "南昌", "西湖", "甲", "13800108884",
                null, null, "", 9L);
        FranchiseOnboardService.Result other = franchiseOnboardService.onboard(
                "合家美宅", "江西", "南昌", "东湖", "乙", "13800108885",
                null, null, "", 9L);
        bindingService.bind("wx-franchise-flow", "openid-occupied-flow", other.adminUserId(), "13800108885");
        when(wechat.openid("wx-franchise-flow", "occupied-code")).thenReturn("openid-occupied-flow");
        assertCode(() -> explicitBindingService.bind(
                "wx-franchise-flow", "occupied-code", first.adminUserId()), WECHAT_ALREADY_BOUND_OTHER.getCode());
        assertThat(bindingMapper.selectActive("wx-franchise-flow", "openid-occupied-flow").getUserId())
                .isEqualTo(other.adminUserId());

        when(wechat.openid("wx-franchise-flow", "idempotent-code")).thenReturn("openid-idempotent-flow");
        explicitBindingService.bind("wx-franchise-flow", "idempotent-code", first.adminUserId());
        explicitBindingService.bind("wx-franchise-flow", "idempotent-code", first.adminUserId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pf_wechat_binding "
                        + "WHERE appid=? AND openid=? AND status='ACTIVE'", Integer.class,
                "wx-franchise-flow", "openid-idempotent-flow")).isEqualTo(1);
    }

    private int concurrentChange(String accountId, String newPassword, CountDownLatch ready, CountDownLatch start) {
        TenantContextHolder.setTenantId(tenantId);
        try {
            ready.countDown();
            start.await();
            service.login(accountId, "Initial2026Pwd!", newPassword);
            return 0;
        } catch (ServiceException e) {
            return e.getCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } finally {
            TenantContextHolder.clear();
        }
    }

    private Account createAccount(long tenant, long userId, String loginName, int userStatus, String postStatus,
                                  boolean requireChange) {
        jdbc.update("INSERT INTO system_users(id,username,password,nickname,status,deleted,tenant_id) "
                        + "VALUES (?,?,?,?,?,0,?)",
                userId, "u" + userId, BCRYPT.encode("Initial2026Pwd!"), "测试用户", userStatus, tenant);
        Account account = createAccountForExistingUser(tenant, userId, loginName, "门店" + IDS.incrementAndGet());
        if (!"ACTIVE".equals(postStatus)) {
            jdbc.update("UPDATE pf_staff_post SET status='REVOKED',revoked_at=now() WHERE id=?", account.postId());
        }
        if (requireChange) {
            jdbc.update("INSERT INTO pf_staff_password_policy(tenant_id,user_id,require_change) VALUES (?,?,TRUE)",
                    tenant, userId);
        }
        return account;
    }

    private Account createAccountForExistingUser(long tenant, long userId, String loginName, String orgName) {
        long orgId = IDS.incrementAndGet();
        long postId = IDS.incrementAndGet();
        String accountId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO pf_org_unit(dept_id,kind,org_id,org_type,status) VALUES (?,'ORG',?,?,'ACTIVE')",
                orgId, orgId, "franchise-auth-test");
        jdbc.update("INSERT INTO pf_staff_post(id,user_id,org_id,role_code,status) VALUES (?,?,?,?,'ACTIVE')",
                postId, userId, orgId, "franchise-auth-owner");
        jdbc.update("INSERT INTO pf_franchise_account(account_id,tenant_id,org_id,user_id,login_name,org_name,"
                        + "owner_name,brand,province,city,area,area_pinyin,owner_pinyin,created_by) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                accountId, tenant, orgId, userId, loginName, orgName, "李忠", "合家美宅", "江西", "南昌", "高新",
                "gaoxin", "lizhong", 1L);
        return new Account(accountId, userId, orgId, postId);
    }

    private void addAlias(Account account, String alias) {
        jdbc.update("INSERT INTO pf_franchise_account_alias(tenant_id,account_id,alias) VALUES (?,?,?)",
                jdbc.queryForObject("SELECT tenant_id FROM pf_franchise_account WHERE account_id=?", Long.class,
                        account.accountId()), account.accountId(), alias);
    }

    private AdminUserDO loadUser(long userId) {
        List<AdminUserDO> users = jdbc.query("SELECT id,password,status,tenant_id FROM system_users WHERE id=? AND deleted=0",
                (rs, row) -> {
                    AdminUserDO user = new AdminUserDO().setId(rs.getLong(1)).setPassword(rs.getString(2))
                            .setStatus(rs.getInt(3));
                    user.setTenantId(rs.getLong(4));
                    return user;
                }, userId);
        return users.isEmpty() ? null : users.get(0);
    }

    static void createSystemUsersIfAbsent(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS system_users (
                  id BIGINT PRIMARY KEY, username VARCHAR(30) NOT NULL, password VARCHAR(100) NOT NULL,
                  nickname VARCHAR(30) NOT NULL, mobile VARCHAR(11), dept_id BIGINT,
                  status SMALLINT NOT NULL DEFAULT 0,
                  deleted SMALLINT NOT NULL DEFAULT 0, tenant_id BIGINT NOT NULL DEFAULT 0)
                """);
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, int expected) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ServiceException.class,
                error -> assertThat(error.getCode()).isEqualTo(expected));
    }

    static final class JdbcDirectory implements SystemDirectory, StaffAccountDirectory {
        private final JdbcTemplate jdbc;
        private final AtomicLong deptIds = IDS;
        private final AtomicLong userIds = IDS;
        private final Map<Long, Long> deptParents = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<Long, String> deptNames = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<Long, Long> deptTenants = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<Long, Set<Long>> userRoles = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<String, Long> roleIdsByCode = new java.util.concurrent.ConcurrentHashMap<>();

        JdbcDirectory(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public long createDept(Long parentDeptId, String name) {
            long id = deptIds.incrementAndGet();
            deptParents.put(id, parentDeptId == null ? 0L : parentDeptId);
            deptNames.put(id, name);
            deptTenants.put(id, TenantContextHolder.getRequiredTenantId());
            return id;
        }

        @Override
        public Set<Long> deptAndDescendants(long deptId) {
            Set<Long> result = new HashSet<>();
            result.add(deptId);
            boolean added;
            do {
                added = false;
                for (Map.Entry<Long, Long> entry : deptParents.entrySet()) {
                    if (result.contains(entry.getValue()) && result.add(entry.getKey())) added = true;
                }
            } while (added);
            return result;
        }

        @Override public Long roleIdByCode(String roleCode) { return roleIdsByCode.get(roleCode); }
        @Override public boolean roleHasPermission(String roleCode, String permission) { return false; }

        @Override
        public boolean userExists(long userId) {
            return count("SELECT COUNT(*) FROM system_users WHERE id=? AND tenant_id=? AND deleted=0",
                    userId, TenantContextHolder.getRequiredTenantId()) == 1;
        }

        @Override
        public boolean userUsable(long userId) {
            return count("SELECT COUNT(*) FROM system_users WHERE id=? AND tenant_id=? AND deleted=0 AND status=0",
                    userId, TenantContextHolder.getRequiredTenantId()) == 1;
        }

        @Override public Set<Long> userRoleIds(long userId) {
            return new HashSet<>(userRoles.getOrDefault(userId, Set.of()));
        }
        @Override public void replaceUserRoles(long userId, Set<Long> roleIds) {
            userRoles.put(userId, new HashSet<>(roleIds));
        }

        @Override
        public Long userIdByMobile(String mobile) {
            List<Long> ids = jdbc.queryForList("SELECT id FROM system_users WHERE tenant_id=? AND mobile=? AND deleted=0",
                    Long.class, TenantContextHolder.getRequiredTenantId(), mobile);
            return ids.isEmpty() ? null : ids.get(0);
        }

        @Override public long createUser(String name, String mobile, Long deptId) {
            return createUserWithPassword(name, mobile, deptId, "LegacySecure2026");
        }

        @Override
        public long createUserWithPassword(String name, String mobile, Long deptId, String initialPassword) {
            long id;
            do {
                id = userIds.incrementAndGet();
            } while (count("SELECT COUNT(*) FROM system_users WHERE id=?", id) != 0);
            jdbc.update("INSERT INTO system_users(id,username,password,nickname,mobile,dept_id,status,deleted,tenant_id) "
                            + "VALUES (?,?,?,?,?,?,0,0,?)",
                    id, mobile, BCRYPT.encode(initialPassword), name, mobile, deptId,
                    TenantContextHolder.getRequiredTenantId());
            return id;
        }

        @Override
        public List<UserBrief> usersByIds(Collection<Long> requestedIds) {
            List<UserBrief> users = new ArrayList<>();
            for (Long id : requestedIds) {
                jdbc.query("SELECT nickname,mobile,status FROM system_users WHERE id=? AND tenant_id=? AND deleted=0",
                        rs -> {
                            if (rs.next()) users.add(new UserBrief(id, rs.getString(1), rs.getString(2), rs.getInt(3) == 0));
                            return null;
                        }, id, TenantContextHolder.getRequiredTenantId());
            }
            return users;
        }

        @Override
        public void setUserActive(long userId, boolean active) {
            jdbc.update("UPDATE system_users SET status=? WHERE id=? AND tenant_id=?",
                    active ? 0 : 1, userId, TenantContextHolder.getRequiredTenantId());
        }

        @Override public String deptName(Long deptId) {
            return deptId == null || !TenantContextHolder.getRequiredTenantId().equals(deptTenants.get(deptId))
                    ? "" : deptNames.getOrDefault(deptId, "");
        }

        @Override
        public List<Long> findRootDeptIdsByName(String name) {
            return deptParents.entrySet().stream().filter(entry -> entry.getValue() == 0L)
                    .filter(entry -> TenantContextHolder.getRequiredTenantId().equals(deptTenants.get(entry.getKey())))
                    .map(Map.Entry::getKey).filter(id -> name.equals(deptNames.get(id))).toList();
        }

        @Override
        public Optional<StaffAccount> find(long userId) {
            return usersByIds(List.of(userId)).stream()
                    .map(user -> new StaffAccount(user.userId(), user.mobile(), user.active())).findFirst();
        }

        @Override
        public List<StaffAccount> findByMobile(String mobile) {
            Long id = userIdByMobile(mobile);
            return id == null ? List.of() : find(id).stream().toList();
        }

        private int count(String sql, Object... args) {
            Integer value = jdbc.queryForObject(sql, Integer.class, args);
            return value == null ? 0 : value;
        }
    }

    private record Account(String accountId, long userId, long orgId, long postId) {}
}
