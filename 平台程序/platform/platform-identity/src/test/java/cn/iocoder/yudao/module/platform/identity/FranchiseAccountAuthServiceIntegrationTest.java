package cn.iocoder.yudao.module.platform.identity;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.platform.authz.service.FranchiseOnboardService;
import cn.iocoder.yudao.module.platform.authz.service.StaffAccountOnboardService;
import cn.iocoder.yudao.module.platform.identity.dal.WechatBindingMapper;
import cn.iocoder.yudao.module.platform.identity.wechat.WechatStaffClient;
import cn.iocoder.yudao.module.platform.org.FranchiseAccountNaming;
import cn.iocoder.yudao.module.platform.org.OrgServiceImpl;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import cn.iocoder.yudao.module.platform.org.StaffPostServiceImpl;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import cn.iocoder.yudao.module.system.dal.dataobject.user.AdminUserDO;
import cn.iocoder.yudao.module.system.dal.mysql.user.AdminUserMapper;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
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
import static cn.iocoder.yudao.module.platform.identity.enums.PlatformIdentityErrorCodes.STAFF_NOT_ENROLLED;
import static cn.iocoder.yudao.module.platform.identity.enums.PlatformIdentityErrorCodes.WECHAT_ALREADY_BOUND_OTHER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Import({FranchiseAccountAuthService.class, FranchiseAccountNaming.class, OrgServiceImpl.class,
        StaffPostServiceImpl.class, FranchiseOnboardService.class, StaffAccountOnboardService.class,
        StaffWechatBindingServiceImpl.class, StaffWechatExplicitBindingServiceImpl.class,
        StaffWechatBindingCommitService.class, StaffWechatLoginServiceImpl.class,
        FranchiseAccountAuthServiceIntegrationTest.RedisConfig.class})
@TestPropertySource(properties = {
        "platform.identity.wechat.apps[0].appid=wx-franchise-flow",
        "platform.identity.wechat.apps[0].secret=unused-in-test"
})
class FranchiseAccountAuthServiceIntegrationTest extends PlatformPgIntegrationTest {

    private static final AtomicLong IDS = new AtomicLong(System.nanoTime() % 100_000_000L + 900_000_000L);
    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder(10);
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @TestConfiguration
    @EnableConfigurationProperties(PlatformWechatProperties.class)
    static class RedisConfig {
        @Bean(destroyMethod = "destroy")
        RedissonConnectionFactory accountLoginRedisConnectionFactory() {
            Config config = new Config();
            config.useSingleServer().setAddress("redis://127.0.0.1:" + REDIS.getMappedPort(6379));
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
    @Resource private JdbcTemplate jdbc;
    @Resource private StringRedisTemplate redis;
    @Resource private FranchiseOnboardService franchiseOnboardService;
    @Resource private StaffAccountOnboardService staffAccountOnboardService;
    @Resource private StaffWechatExplicitBindingService explicitBindingService;
    @Resource private StaffWechatLoginService wechatLoginService;
    @Resource private StaffWechatBindingService bindingService;
    @Resource private WechatBindingMapper bindingMapper;
    @Resource private StaffPostService staffPostService;
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
        createSystemUsersIfAbsent();
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
        FranchiseAccountAuthService.LoginResult employeeInitial = service.login(
                employee.accountId(), employee.initialPassword(), null);
        assertThat(employeeInitial.requirePasswordChange()).isTrue();
        assertThat(employeeInitial.token()).isNull();
        FranchiseAccountAuthService.LoginResult employeeLogin = service.login(
                employee.accountId(), employee.initialPassword(), "StaffSecure2026");
        assertThat(employeeLogin.userId()).isEqualTo(employee.userId());
        assertThat(employeeLogin.preferredPostId()).isEqualTo(employee.postId());
        var employeePost = staffPostService.findActivePost(employee.userId(), employee.postId()).orElseThrow();
        assertThat(employeePost.orgId()).isEqualTo(boss.orgId());
        assertThat(employeePost.roleCode()).isEqualTo("journey-sales");
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

    private void createSystemUsersIfAbsent() {
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
        private final AtomicLong deptIds = new AtomicLong(IDS.incrementAndGet());
        private final AtomicLong userIds = new AtomicLong(IDS.incrementAndGet());
        private final Map<Long, Long> deptParents = new HashMap<>();
        private final Map<Long, String> deptNames = new HashMap<>();
        private final Map<Long, Long> deptTenants = new HashMap<>();
        private final Map<Long, Set<Long>> userRoles = new HashMap<>();
        final Map<String, Long> roleIdsByCode = new HashMap<>();

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
