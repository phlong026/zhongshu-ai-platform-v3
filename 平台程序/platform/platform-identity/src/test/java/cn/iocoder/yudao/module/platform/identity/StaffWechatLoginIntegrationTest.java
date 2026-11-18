package cn.iocoder.yudao.module.platform.identity;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.platform.identity.StaffAccountDirectory.StaffAccount;
import cn.iocoder.yudao.module.platform.identity.dal.WechatBindingDO;
import cn.iocoder.yudao.module.platform.identity.dal.WechatBindingEventDO;
import cn.iocoder.yudao.module.platform.identity.dal.WechatBindingEventMapper;
import cn.iocoder.yudao.module.platform.identity.dal.WechatBindingMapper;
import cn.iocoder.yudao.module.platform.identity.wechat.WechatStaffClient;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import cn.iocoder.yudao.module.platform.org.dal.StaffPostDO;
import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.AfterEach;
import jakarta.annotation.Resource;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static cn.iocoder.yudao.module.platform.identity.enums.PlatformIdentityErrorCodes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@Import({StaffWechatLoginServiceImpl.class, StaffWechatBindingServiceImpl.class, StaffWechatBindingCommitService.class, StaffWechatLoginIntegrationTest.Config.class})
@TestPropertySource(properties = {
        "platform.identity.wechat.apps[0].appid=wx-staff",
        "platform.identity.wechat.apps[0].secret=unused-in-this-test"
})
class StaffWechatLoginIntegrationTest extends PlatformPgIntegrationTest {

    private static final String APP = "wx-staff";
    private static final AtomicLong IDS = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @TestConfiguration
    @EnableConfigurationProperties(PlatformWechatProperties.class)
    static class Config {
    }

    @Resource private JdbcTemplate jdbc;
    @Resource
    private StaffWechatLoginService loginService;
    @Resource
    private WechatBindingMapper bindingMapper;
    @Resource
    private WechatBindingEventMapper eventMapper;

    @MockitoBean
    private WechatStaffClient wechat;
    @MockitoBean
    private StaffAccountDirectory accounts;
    @MockitoBean
    private StaffPostService staffPostService;
    @MockitoBean
    private TokenIssuer tokenIssuer;

    private String openid;
    private long userId;
    private String mobile;

    @BeforeEach
    void setUp() {
        FranchiseAccountAuthServiceIntegrationTest.createSystemUsersIfAbsent(jdbc);
        TenantContextHolder.setTenantId(1L);
        openid = "openid-" + IDS.incrementAndGet();
        userId = IDS.incrementAndGet();
        mobile = "139" + String.format("%08d", IDS.incrementAndGet() % 100_000_000);
        when(wechat.openid(APP, "login-code")).thenReturn(openid);
        when(wechat.phoneNumber(APP, "phone-code")).thenReturn(mobile);
        when(accounts.findByMobile(anyString())).thenReturn(List.of());
        when(accounts.find(anyLong())).thenReturn(Optional.empty());
        when(staffPostService.listActivePosts(anyLong())).thenReturn(List.of());
        when(tokenIssuer.issue(anyLong())).thenAnswer(inv -> new TokenIssuer.IssuedToken(
                "access-" + inv.getArgument(0), "refresh-" + inv.getArgument(0), LocalDateTime.now().plusHours(2)));
    }

    @Test
    void unknownAppIsRejectedBeforeCallingWechat() {
        assertError(() -> loginService.login("wx-other", "login-code", null), WECHAT_APP_NOT_ALLOWED);
        verifyNoInteractions(wechat);
    }

    @Test
    void firstLoginWithoutPhoneAsksForThePhoneNumber() {
        WechatLoginResult result = loginService.login(APP, "login-code", null);

        assertThat(result.needPhone()).isTrue();
        assertThat(result.token()).isNull();
        assertThat(activeBinding()).isNull();
    }

    @Test
    void firstLoginWithPhoneOfAnEnrolledStaffBindsAndLogsIn() {
        enrolledStaff(userId, mobile);

        WechatLoginResult result = loginService.login(APP, "login-code", "phone-code");

        assertThat(result.needPhone()).isFalse();
        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.token().accessToken()).isEqualTo("access-" + userId);
        WechatBindingDO binding = activeBinding();
        assertThat(binding.getUserId()).isEqualTo(userId);
        assertThat(binding.getMobileHash()).hasSize(64).doesNotContain(mobile);
        assertThat(events()).extracting(WechatBindingEventDO::getEventType, WechatBindingEventDO::getOrgId)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("BOUND", 501L),
                        org.assertj.core.groups.Tuple.tuple("BOUND", 502L));
        verify(tokenIssuer, never()).revokeAll(anyLong());
    }

    @Test
    void laterLoginsUseTheBindingWithoutAskingForThePhone() {
        enrolledStaff(userId, mobile);
        loginService.login(APP, "login-code", "phone-code");

        WechatLoginResult again = loginService.login(APP, "login-code", null);

        assertThat(again.userId()).isEqualTo(userId);
        verify(wechat, times(1)).phoneNumber(APP, "phone-code");
    }

    @Test
    void phoneWithoutAnEnrolledActiveStaffIsRejected() {
        assertError(() -> loginService.login(APP, "login-code", "phone-code"), STAFF_NOT_ENROLLED);

        // 有账号但没有有效任职
        when(accounts.findByMobile(mobile)).thenReturn(List.of(new StaffAccount(userId, mobile, true)));
        assertError(() -> loginService.login(APP, "login-code", "phone-code"), STAFF_NOT_ENROLLED);

        // 有任职但账号已停用
        when(accounts.findByMobile(mobile)).thenReturn(List.of(new StaffAccount(userId, mobile, false)));
        when(staffPostService.listActivePosts(userId)).thenReturn(List.of(new StaffPostDO().setId(1L)));
        assertError(() -> loginService.login(APP, "login-code", "phone-code"), STAFF_NOT_ENROLLED);

        assertThat(activeBinding()).isNull();
    }

    @Test
    void phoneSharedByTwoEnrolledStaffIsRejected() {
        long other = IDS.incrementAndGet();
        when(accounts.findByMobile(mobile)).thenReturn(List.of(
                new StaffAccount(userId, mobile, true), new StaffAccount(other, mobile, true)));
        when(staffPostService.listActivePosts(anyLong())).thenReturn(List.of(new StaffPostDO().setId(1L)));

        assertError(() -> loginService.login(APP, "login-code", "phone-code"), STAFF_PHONE_AMBIGUOUS);
        assertThat(activeBinding()).isNull();
    }

    @Test
    void changedMobileInvalidatesTheBinding() {
        enrolledStaff(userId, mobile);
        loginService.login(APP, "login-code", "phone-code");
        when(accounts.find(userId)).thenReturn(Optional.of(new StaffAccount(userId, "13000000000", true)));

        WechatLoginResult result = loginService.login(APP, "login-code", null);

        assertThat(result.needPhone()).isTrue();
        assertThat(activeBinding()).isNull();
        assertThat(lastBinding().getRevokeReason()).isEqualTo(WechatBindingDO.REASON_MOBILE_CHANGED);
        assertThat(events()).filteredOn(e -> "REVOKED".equals(e.getEventType()))
                .extracting(WechatBindingEventDO::getReason).containsOnly(WechatBindingDO.REASON_MOBILE_CHANGED).hasSize(2);
        verify(tokenIssuer).revokeAll(userId); // 旧微信已签发的令牌一并吊销
    }

    @Test
    void disabledAccountInvalidatesTheBinding() {
        enrolledStaff(userId, mobile);
        loginService.login(APP, "login-code", "phone-code");
        when(accounts.find(userId)).thenReturn(Optional.of(new StaffAccount(userId, mobile, false)));

        assertThat(loginService.login(APP, "login-code", null).needPhone()).isTrue();
        assertThat(lastBinding().getRevokeReason()).isEqualTo(WechatBindingDO.REASON_USER_DISABLED);
        verify(tokenIssuer).revokeAll(userId);
    }

    @Test
    void staffWhoLostAllPostsCannotLogInButKeepsTheBinding() {
        enrolledStaff(userId, mobile);
        loginService.login(APP, "login-code", "phone-code");
        when(staffPostService.listActivePosts(userId)).thenReturn(List.of());

        assertError(() -> loginService.login(APP, "login-code", null), STAFF_NOT_ENROLLED);
        assertThat(activeBinding()).isNotNull();
    }

    @Test
    void bindingANewWechatReplacesTheStaffsOldBinding() {
        enrolledStaff(userId, mobile);
        loginService.login(APP, "login-code", "phone-code");
        String oldOpenid = openid;
        String newOpenid = "openid-new-" + IDS.incrementAndGet();
        when(wechat.openid(APP, "new-login-code")).thenReturn(newOpenid);

        loginService.login(APP, "new-login-code", "phone-code");

        assertThat(bindingMapper.selectActive(APP, oldOpenid)).isNull();
        WechatBindingDO current = bindingMapper.selectActive(APP, newOpenid);
        assertThat(current.getUserId()).isEqualTo(userId);
        assertThat(eventMapper.selectList(WechatBindingEventDO::getBindingId, current.getId()))
                .extracting(WechatBindingEventDO::getEventType).containsOnly("REBOUND");
        verify(tokenIssuer, never()).revokeAll(anyLong()); // 本人换微信，不吊销已有会话
    }

    @Test
    void concurrentFirstPhoneLoginsBindOnlyOnceAndBothSucceed() throws Exception {
        enrolledStaff(userId, mobile);
        var ready = new java.util.concurrent.CountDownLatch(2);
        when(wechat.phoneNumber(APP, "concurrent-phone")).thenAnswer(invocation -> {
            ready.countDown();
            if (!ready.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Second login did not reach phone exchange");
            return mobile;
        });
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<WechatLoginResult> login = () -> {
                TenantContextHolder.setTenantId(1L);
                try { return loginService.login(APP, "login-code", "concurrent-phone"); }
                finally { TenantContextHolder.clear(); }
            };
            var first=pool.submit(login);var second=pool.submit(login);
            assertThat(first.get(15, java.util.concurrent.TimeUnit.SECONDS).userId()).isEqualTo(userId);
            assertThat(second.get(15, java.util.concurrent.TimeUnit.SECONDS).userId()).isEqualTo(userId);
            assertThat(bindingMapper.selectList(WechatBindingDO::getUserId, userId)).hasSize(1);
            assertThat(events()).hasSize(2); // 每个组织一条首次绑定审计，不发生重复换绑。
        } finally { pool.shutdownNow(); }
    }

    @Test
    void bindingEntitiesAreTenantIgnored() {
        assertThat(WechatBindingDO.class.isAnnotationPresent(TenantIgnore.class)).isTrue();
        assertThat(WechatBindingEventDO.class.isAnnotationPresent(TenantIgnore.class)).isTrue();
    }

    @AfterEach
    void clearTenant() { TenantContextHolder.clear(); }

    private void enrolledStaff(long id, String phone) {
        jdbc.update("INSERT INTO system_users(id,username,nickname,mobile,password,status,deleted,tenant_id) VALUES (?,?,?,?,'unused-wechat-test-hash',0,0,1)", id, "wechat-test-"+id, "微信测试", phone);
        StaffAccount account = new StaffAccount(id, phone, true);
        when(accounts.findByMobile(phone)).thenReturn(List.of(account));
        when(accounts.find(id)).thenReturn(Optional.of(account));
        when(staffPostService.listActivePosts(id)).thenReturn(List.of(new StaffPostDO().setId(1L).setUserId(id)));
        when(staffPostService.listOrgIdsEverHeld(id)).thenReturn(List.of(501L, 502L));
    }

    private List<WechatBindingEventDO> events() {
        return eventMapper.selectList(WechatBindingEventDO::getUserId, userId);
    }

    private WechatBindingDO activeBinding() {
        return bindingMapper.selectActive(APP, openid);
    }

    private WechatBindingDO lastBinding() {
        return bindingMapper.selectList(WechatBindingDO::getOpenid, openid).stream()
                .max((a, b) -> Long.compare(a.getId(), b.getId())).orElseThrow();
    }

    private static void assertError(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call).isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode()).isEqualTo(expected.getCode());
    }

}
