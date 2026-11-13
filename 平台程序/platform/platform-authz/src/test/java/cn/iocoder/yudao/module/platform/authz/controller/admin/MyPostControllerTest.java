package cn.iocoder.yudao.module.platform.authz.controller.admin;

import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import cn.iocoder.yudao.module.platform.org.dal.StaffPostDO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.platform.authz.enums.PlatformAuthzErrorCodes.POST_REQUIRED;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class MyPostControllerTest {

    private static final PostSnapshot SALES = new PostSnapshot(11L, 7L, 100L, "store", "sales", null, false, Set.of());

    @Mock private StaffPostService staffPostService;
    @Mock private CurrentPost currentPost;
    @Mock private SystemDirectory systemDirectory;
    @InjectMocks private MyPostController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        LoginUser user = new LoginUser();
        user.setId(7L);
        user.setUserType(UserTypeEnum.ADMIN.getValue());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void mineIncludesOrganizationNameAndOmitsPostsRevokedDuringListing() throws Exception {
        when(staffPostService.listActivePosts(7L)).thenReturn(List.of(
                new StaffPostDO().setId(11L), new StaffPostDO().setId(12L)));
        when(staffPostService.findActivePost(7L, 11L)).thenReturn(Optional.of(SALES));
        when(staffPostService.findActivePost(7L, 12L)).thenReturn(Optional.empty());
        lenient().when(systemDirectory.deptName(100L)).thenReturn("测试门店");

        mvc.perform(get("/platform/posts/mine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].orgName").value("测试门店"))
                .andExpect(jsonPath("$.data[0].postId").value(11));
    }

    @Test
    void currentContextUsesOnlySelectedRolesOrganizationCapabilities() throws Exception {
        lenient().when(currentPost.require()).thenReturn(SALES);
        lenient().when(systemDirectory.deptName(100L)).thenReturn("测试门店");
        lenient().when(systemDirectory.roleHasPermission("sales", "platform:org:query")).thenReturn(true);
        // 即使该账号兼老板，也不能把未选中的老板权限并入当前业务员会话。
        lenient().when(systemDirectory.roleHasPermission("boss", "platform:org:manage")).thenReturn(true);

        mvc.perform(get("/platform/posts/current-context").header("X-Post-Id", "11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.post.postId").value(11))
                .andExpect(jsonPath("$.data.post.orgName").value("测试门店"))
                .andExpect(jsonPath("$.data.permissions", hasSize(1)))
                .andExpect(jsonPath("$.data.permissions[0]").value("platform:org:query"));
    }

    @Test
    void currentContextRejectsMissingOrUnusableWorkIdentity() {
        lenient().when(currentPost.require()).thenThrow(exception(POST_REQUIRED));

        assertThatThrownBy(() -> mvc.perform(get("/platform/posts/current-context")))
                .hasRootCauseInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
    }
}
