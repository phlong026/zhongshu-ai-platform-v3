package cn.iocoder.yudao.module.platform.authz;

import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.common.exception.ErrorCode;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import cn.iocoder.yudao.module.platform.org.dal.StaffPostDO;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static cn.iocoder.yudao.module.platform.authz.enums.PlatformAuthzErrorCodes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class RequestCurrentPostTest {

    private static final long USER = 7L;
    private static final PostSnapshot SALES = new PostSnapshot(11L, USER, 100L, "store", "sales", null, false, Set.of());
    private static final PostSnapshot FINANCE = new PostSnapshot(12L, USER, 100L, "store", "finance", null, false, Set.of());

    private final StaffPostService staffPostService = mock(StaffPostService.class);
    private final RequestCurrentPost currentPost = new RequestCurrentPost(staffPostService);
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(staffPostService.findActivePost(anyLong(), anyLong())).thenReturn(Optional.empty());
        when(staffPostService.findActivePost(USER, 11L)).thenReturn(Optional.of(SALES));
        when(staffPostService.findActivePost(USER, 12L)).thenReturn(Optional.of(FINANCE));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    @Test
    void headerSelectsOneOfTheUsersActivePosts() {
        login(USER, UserTypeEnum.ADMIN);
        request.addHeader(CurrentPost.HEADER, "12");

        assertThat(currentPost.find()).contains(FINANCE);
    }

    @Test
    void headerPointingToSomeoneElsesOrRevokedPostIsRejected() {
        login(USER, UserTypeEnum.ADMIN);
        request.addHeader(CurrentPost.HEADER, "99");

        assertError(currentPost::find, POST_NOT_USABLE);
    }

    @Test
    void malformedHeaderIsRejected() {
        login(USER, UserTypeEnum.ADMIN);
        request.addHeader(CurrentPost.HEADER, "12 OR 1=1");

        assertError(currentPost::find, POST_HEADER_INVALID);
    }

    @Test
    void withoutHeaderASinglePostIsUsedAutomatically() {
        login(USER, UserTypeEnum.ADMIN);
        when(staffPostService.listActivePosts(USER)).thenReturn(List.of(post(11L)));

        assertThat(currentPost.find()).contains(SALES);
    }

    @Test
    void withoutHeaderSeveralPostsRequireAnExplicitChoice() {
        login(USER, UserTypeEnum.ADMIN);
        when(staffPostService.listActivePosts(USER)).thenReturn(List.of(post(11L), post(12L)));

        assertError(currentPost::find, POST_SELECTION_REQUIRED);
    }

    @Test
    void userWithoutPostsHasNoContextAndRequireFails() {
        login(USER, UserTypeEnum.ADMIN);
        when(staffPostService.listActivePosts(USER)).thenReturn(List.of());

        assertThat(currentPost.find()).isEmpty();
        assertError(currentPost::require, POST_REQUIRED);
    }

    @Test
    void anonymousOrMemberUsersHaveNoPostContext() {
        assertThat(currentPost.find()).isEmpty();

        login(USER, UserTypeEnum.MEMBER);
        request.addHeader(CurrentPost.HEADER, "11");
        assertThat(currentPost.find()).isEmpty();
        verify(staffPostService, never()).findActivePost(anyLong(), anyLong());
    }

    @Test
    void resolutionIsCachedForTheRequest() {
        login(USER, UserTypeEnum.ADMIN);
        request.addHeader(CurrentPost.HEADER, "11");

        currentPost.find();
        currentPost.find();

        verify(staffPostService, times(1)).findActivePost(USER, 11L);
    }

    @Test
    void outsideARequestThereIsNoPostContext() {
        RequestContextHolder.resetRequestAttributes();
        login(USER, UserTypeEnum.ADMIN);

        assertThat(currentPost.find()).isEmpty();
    }

    private void login(long userId, UserTypeEnum type) {
        LoginUser user = new LoginUser();
        user.setId(userId);
        user.setUserType(type.getValue());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, Collections.emptyList()));
    }

    private static StaffPostDO post(long id) {
        return new StaffPostDO().setId(id).setUserId(USER).setStatus(StaffPostDO.STATUS_ACTIVE);
    }

    private static void assertError(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call).isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode()).isEqualTo(expected.getCode());
    }

}
