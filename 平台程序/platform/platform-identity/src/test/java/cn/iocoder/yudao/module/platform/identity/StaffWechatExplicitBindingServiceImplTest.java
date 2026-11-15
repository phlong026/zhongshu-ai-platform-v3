package cn.iocoder.yudao.module.platform.identity;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.platform.identity.dal.WechatBindingDO;
import cn.iocoder.yudao.module.platform.identity.dal.WechatBindingMapper;
import cn.iocoder.yudao.module.platform.identity.wechat.WechatStaffClient;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import cn.iocoder.yudao.module.platform.org.dal.StaffPostDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static cn.iocoder.yudao.module.platform.identity.enums.PlatformIdentityErrorCodes.WECHAT_ALREADY_BOUND_OTHER;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StaffWechatExplicitBindingServiceImplTest {

    private static final String APPID = "wx-test";
    private static final long USER_ID = 101L;

    @Mock private WechatStaffClient wechat;
    @Mock private WechatBindingMapper bindingMapper;
    @Mock private StaffAccountDirectory accounts;
    @Mock private StaffPostService staffPostService;
    @Mock private StaffWechatBindingCommitService commitService;

    private StaffWechatExplicitBindingServiceImpl service;

    @BeforeEach
    void setUp() {
        PlatformWechatProperties properties = new PlatformWechatProperties();
        PlatformWechatProperties.App app = new PlatformWechatProperties.App();
        app.setAppid(APPID);
        app.setSecret("test-secret");
        properties.setApps(List.of(app));
        service = new StaffWechatExplicitBindingServiceImpl();
        ReflectionTestUtils.setField(service, "properties", properties);
        ReflectionTestUtils.setField(service, "wechat", wechat);
        ReflectionTestUtils.setField(service, "bindingMapper", bindingMapper);
        ReflectionTestUtils.setField(service, "accounts", accounts);
        ReflectionTestUtils.setField(service, "staffPostService", staffPostService);
        ReflectionTestUtils.setField(service, "commitService", commitService);

        when(accounts.find(USER_ID)).thenReturn(Optional.of(
                new StaffAccountDirectory.StaffAccount(USER_ID, "13800000000", true)));
        StaffPostDO post = new StaffPostDO().setId(7L).setUserId(USER_ID).setOrgId(8L);
        when(staffPostService.listActivePosts(USER_ID)).thenReturn(List.of(post));
        when(staffPostService.findActivePost(USER_ID, post.getId())).thenReturn(Optional.of(
                new PostSnapshot(post.getId(), USER_ID, post.getOrgId(), "FRANCHISE", "OWNER", null, true, Set.of())));
        when(wechat.openid(APPID, "login-code")).thenReturn("openid-1");
    }

    @Test
    void bindsWechatOnlyToAuthenticatedUser() {
        service.bind(APPID, "login-code", USER_ID);

        verify(commitService).commit(APPID, "openid-1", USER_ID, "13800000000", 7L);
    }

    @Test
    void sameUserBindingIsIdempotent() {
        service.bind(APPID, "login-code", USER_ID);

        verify(commitService).commit(APPID, "openid-1", USER_ID, "13800000000", 7L);
    }

    @Test
    void rejectsWechatAlreadyBoundToAnotherUser() {
        doThrow(new ServiceException(WECHAT_ALREADY_BOUND_OTHER.getCode(), WECHAT_ALREADY_BOUND_OTHER.getMsg()))
                .when(commitService).commit(APPID, "openid-1", USER_ID, "13800000000", 7L);

        assertThatThrownBy(() -> service.bind(APPID, "login-code", USER_ID))
                .isInstanceOfSatisfying(ServiceException.class,
                        error -> org.assertj.core.api.Assertions.assertThat(error.getCode())
                                .isEqualTo(WECHAT_ALREADY_BOUND_OTHER.getCode()));
        verify(commitService).commit(APPID, "openid-1", USER_ID, "13800000000", 7L);
    }

    @Test
    void concurrentSameUserBindingIsIdempotent() {
        doThrow(new DuplicateKeyException("unique conflict"))
                .when(commitService).commit(APPID, "openid-1", USER_ID, "13800000000", 7L);
        when(bindingMapper.selectActive(APPID, "openid-1"))
                .thenReturn(new WechatBindingDO().setUserId(USER_ID));

        service.bind(APPID, "login-code", USER_ID);
    }
}
