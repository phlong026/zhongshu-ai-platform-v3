package cn.iocoder.yudao.module.platform.authz.controller.admin;

import cn.iocoder.yudao.framework.test.core.util.AssertUtils;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.OrgService;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import cn.iocoder.yudao.module.platform.org.dal.StaffPostDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static cn.iocoder.yudao.module.platform.authz.enums.PlatformAuthzErrorCodes.ORG_SCOPE_FORBIDDEN;
import static cn.iocoder.yudao.module.platform.authz.enums.PlatformAuthzErrorCodes.POST_REQUIRED;
import static cn.iocoder.yudao.module.platform.org.enums.PlatformOrgErrorCodes.USER_NOT_IN_ORG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 成员管理的组织范围校验：老板只能管理当前岗位所在组织；@post.has 权限码层由端到端覆盖。
 */
@ExtendWith(MockitoExtension.class)
class StaffOrgAdminControllerTest {

    private static final long BOSS_USER = 9L;
    private static final long STORE_ORG = 151L;

    @Mock
    private CurrentPost currentPost;
    @Mock
    private StaffPostService staffPostService;
    @Mock
    private OrgService orgService;
    @Mock
    private SystemDirectory systemDirectory;

    private StaffOrgAdminController controller;

    @BeforeEach
    void setUp() {
        controller = new StaffOrgAdminController();
        ReflectionTestUtils.setField(controller, "currentPost", currentPost);
        ReflectionTestUtils.setField(controller, "staffPostService", staffPostService);
        ReflectionTestUtils.setField(controller, "orgService", orgService);
        ReflectionTestUtils.setField(controller, "systemDirectory", systemDirectory);
    }

    private void loginAsBossAt(long orgId) {
        lenient().when(currentPost.find()).thenReturn(Optional.of(new cn.iocoder.yudao.module.platform.org.PostSnapshot(
                11L, BOSS_USER, orgId, "store", "boss", null, false, java.util.Set.of())));
    }

    private StaffPostDO post(long id, long orgId, long userId) {
        return new StaffPostDO().setId(id).setUserId(userId).setOrgId(orgId)
                .setRoleCode("sales").setTeamId(152L).setStatus(StaffPostDO.STATUS_ACTIVE)
                .setCreateTime(OffsetDateTime.parse("2026-10-31T10:15:00+08:00"));
    }

    @Test
    void rejectsWhenNoWorkingPost() {
        when(currentPost.find()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.staff(STORE_ORG))
                .isInstanceOfSatisfying(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(POST_REQUIRED.getCode()));
    }

    @Test
    void rejectsCrossOrgQuery() {
        loginAsBossAt(STORE_ORG);

        assertThatThrownBy(() -> controller.staff(999L))
                .isInstanceOfSatisfying(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ORG_SCOPE_FORBIDDEN.getCode()));
    }

    @Test
    void listsOwnOrgStaffWithUserBrief() {
        loginAsBossAt(STORE_ORG);
        when(staffPostService.listOrgPosts(STORE_ORG)).thenReturn(List.of(post(21L, STORE_ORG, 7L)));
        when(systemDirectory.usersByIds(any())).thenReturn(List.of(
                new SystemDirectory.UserBrief(7L, "陈新", "13900001234", true)));
        when(systemDirectory.deptName(152L)).thenReturn("业务部");

        List<StaffOrgAdminController.StaffRow> rows = controller.staff(STORE_ORG).getData();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).name()).isEqualTo("陈新");
        assertThat(rows.get(0).mobile()).isEqualTo("13900001234");
        assertThat(rows.get(0).roleCode()).isEqualTo("sales");
        assertThat(rows.get(0).teamName()).isEqualTo("业务部");
        assertThat(rows.get(0).active()).isTrue();
    }

    @Test
    void revokeChecksPostBelongsToOwnOrg() {
        loginAsBossAt(STORE_ORG);
        when(staffPostService.getPost(31L)).thenReturn(Optional.of(post(31L, 999L, 7L)));

        assertThatThrownBy(() -> controller.revokePost(31L))
                .isInstanceOfSatisfying(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ORG_SCOPE_FORBIDDEN.getCode()));
    }

    @Test
    void disableRejectsStaffOutsideOwnOrg() {
        loginAsBossAt(STORE_ORG);
        when(staffPostService.listActivePosts(8L)).thenReturn(List.of(post(41L, 999L, 8L)));

        assertThatThrownBy(() -> controller.setStatus(new StaffOrgAdminController.StaffStatusReqVO(8L, false)))
                .isInstanceOfSatisfying(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(USER_NOT_IN_ORG.getCode()));
    }

}
