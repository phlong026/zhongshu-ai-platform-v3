package cn.iocoder.yudao.module.platform.authz.controller.admin;

import cn.iocoder.yudao.framework.test.core.util.AssertUtils;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.OrgService;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.platform.org.dal.StaffPostDO;
import cn.iocoder.yudao.module.platform.org.enums.PlatformOrgErrorCodes;
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
    @Mock
    private cn.iocoder.yudao.module.platform.org.DepartureHandoverService handoverService;
    @Mock
    private cn.iocoder.yudao.module.platform.org.StaffHandoverHistoryQuery handoverHistory;
    @Mock
    private TeamCommandService teamCommandService;

    private StaffOrgAdminController controller;

    @Test
    void memberEndpointsCannotCreateAdditionalBossOrOwner() {
        loginAsBossAt(STORE_ORG);
        assertThatThrownBy(() -> controller.createStaff(new StaffOrgAdminController.CreateStaffReqVO(
                STORE_ORG, null, "另一老板", "13800000000", "boss", null)))
                .hasMessageContaining("总部");
        assertThatThrownBy(() -> controller.assignPost(new StaffOrgAdminController.AssignPostReqVO(
                STORE_ORG, 100L, "owner", null)))
                .hasMessageContaining("总部");
        org.mockito.Mockito.verifyNoInteractions(staffPostService);
    }

    @BeforeEach
    void setUp() {
        lenient().when(currentPost.require()).thenCallRealMethod();
        controller = new StaffOrgAdminController();
        ReflectionTestUtils.setField(controller, "currentPost", currentPost);
        ReflectionTestUtils.setField(controller, "staffPostService", staffPostService);
        ReflectionTestUtils.setField(controller, "orgService", orgService);
        ReflectionTestUtils.setField(controller, "systemDirectory", systemDirectory);
        ReflectionTestUtils.setField(controller, "handoverService", handoverService);
        ReflectionTestUtils.setField(controller, "handoverHistory", handoverHistory);
        ReflectionTestUtils.setField(controller, "teamCommandService", teamCommandService);
    }

    @Test
    void createTeamDelegatesStableKeyAndActor() {
        loginAsBossAt(STORE_ORG);
        var post = currentPost.require();
        when(teamCommandService.create(post, " team-1 ", "业务部", "sales"))
                .thenReturn(152L);

        var result = controller.createTeam(" team-1 ",
                new StaffOrgAdminController.CreateTeamReqVO(STORE_ORG, "业务部", "sales"));

        assertThat(result.getData().deptId()).isEqualTo(152L);
        org.mockito.Mockito.verify(teamCommandService)
                .create(post, " team-1 ", "业务部", "sales");
    }

    @Test
    void suspendRevokesOnlyCurrentOrgPosts() {
        loginAsBossAt(STORE_ORG);
        when(staffPostService.listActivePosts(5L)).thenReturn(List.of(
                post(21L, 5L, STORE_ORG, "sales"), post(22L, 5L, 999L, "finance")));

        controller.setStatus(new StaffOrgAdminController.StaffStatusReqVO(5L, false));

        org.mockito.Mockito.verify(handoverService).emergencyRevoke(STORE_ORG, 5L, BOSS_USER);
        org.mockito.Mockito.verify(staffPostService, org.mockito.Mockito.never())
                .revokeAllInOrg(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
        org.mockito.Mockito.verify(systemDirectory, org.mockito.Mockito.never()).setUserActive(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void resumeRejectsGloballyDisabledAccount() {
        loginAsBossAt(STORE_ORG);
        when(systemDirectory.userExists(5L)).thenReturn(true);
        when(systemDirectory.userUsable(5L)).thenReturn(false);

        assertThatThrownBy(() -> controller.setStatus(new StaffOrgAdminController.StaffStatusReqVO(5L, true)))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode())
                .isEqualTo(PlatformOrgErrorCodes.STAFF_GLOBAL_RESUME_FORBIDDEN.getCode());
    }

    @Test
    void resumeRejectsOrgSuspendedAccountWithAssignHint() {
        loginAsBossAt(STORE_ORG);
        when(staffPostService.listActivePosts(5L)).thenReturn(List.of()); // 本组织已无任职（曾停职）
        when(staffPostService.listOrgIdsEverHeld(5L)).thenReturn(List.of(STORE_ORG, 999L));
        when(systemDirectory.userExists(5L)).thenReturn(true);
        when(systemDirectory.userUsable(5L)).thenReturn(true);

        assertThatThrownBy(() -> controller.setStatus(new StaffOrgAdminController.StaffStatusReqVO(5L, true)))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode())
                .isEqualTo(PlatformOrgErrorCodes.STAFF_RESUME_NEED_ASSIGN.getCode());
    }

    @Test
    void resumeActiveUserIsIdempotentSuccess() {
        loginAsBossAt(STORE_ORG);
        when(staffPostService.listActivePosts(5L)).thenReturn(List.of(
                post(21L, 5L, STORE_ORG, "sales"), post(22L, 5L, 999L, "finance")));
        when(systemDirectory.userExists(5L)).thenReturn(true);
        when(systemDirectory.userUsable(5L)).thenReturn(true);

        assertThat(controller.setStatus(new StaffOrgAdminController.StaffStatusReqVO(5L, true)).getData()).isTrue();
        org.mockito.Mockito.verify(systemDirectory, org.mockito.Mockito.never()).setUserActive(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void resumeRejectsUserNeverInOrg() {
        loginAsBossAt(STORE_ORG);
        when(staffPostService.listActivePosts(5L)).thenReturn(List.of());
        when(staffPostService.listOrgIdsEverHeld(5L)).thenReturn(List.of(999L)); // 从未属于本组织
        when(systemDirectory.userExists(5L)).thenReturn(true);
        when(systemDirectory.userUsable(5L)).thenReturn(true);

        assertThatThrownBy(() -> controller.setStatus(new StaffOrgAdminController.StaffStatusReqVO(5L, true)))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode())
                .isEqualTo(PlatformOrgErrorCodes.USER_NOT_IN_ORG.getCode());
    }

    private static StaffPostDO post(long id, long userId, long orgId, String role) {
        return new StaffPostDO().setId(id).setUserId(userId).setOrgId(orgId).setRoleCode(role)
                .setStatus(StaffPostDO.STATUS_ACTIVE);
    }

    // ===== M01-T04 离职交接端点（Codex R1 P2-5）=====

    @Test
    void handoverDelegatesToServiceWithOperatorContext() {
        loginAsBossAt(STORE_ORG);
        when(handoverService.handover(STORE_ORG, 5L, 6L, BOSS_USER)).thenReturn(
                new cn.iocoder.yudao.module.platform.org.DepartureHandoverService.HandoverResult(
                        "NORMAL", STORE_ORG, 5L, 6L, List.of(), List.of()));

        var result = controller.handover(new StaffOrgAdminController.StaffHandoverReqVO(STORE_ORG, 5L, 6L));

        assertThat(result.getData().toUserId()).isEqualTo(6L);
        org.mockito.Mockito.verify(handoverService).handover(STORE_ORG, 5L, 6L, BOSS_USER);
    }

    @Test
    void handoverRejectsOutOfScopeOrg() {
        loginAsBossAt(STORE_ORG); // 操作者在本店，请求交接别家组织（越权在 service 调用前即拒）

        assertThatThrownBy(() -> controller.handover(new StaffOrgAdminController.StaffHandoverReqVO(999L, 5L, 6L)))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode())
                .isEqualTo(ORG_SCOPE_FORBIDDEN.getCode());
        org.mockito.Mockito.verify(handoverService, org.mockito.Mockito.never())
                .handover(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void emergencyRevokeRejectsOperatorWithoutPost() {
        when(currentPost.find()).thenReturn(Optional.empty()); // 无岗位上下文：POST_REQUIRED

        assertThatThrownBy(() -> controller.emergencyRevoke(new StaffOrgAdminController.StaffEmergencyRevokeReqVO(STORE_ORG, 5L)))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode())
                .isEqualTo(POST_REQUIRED.getCode());
    }

    @Test
    void handoverPendingDelegatesWithScopeCheck() {
        loginAsBossAt(STORE_ORG);
        when(handoverService.listPending(STORE_ORG)).thenReturn(List.of());

        assertThat(controller.handoverPending(STORE_ORG).getData()).isEmpty();
        org.mockito.Mockito.verify(handoverService).listPending(STORE_ORG);
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
    void singleRevocationUsesTheHandoverResponsibilityCheck() {
        loginAsBossAt(STORE_ORG);
        when(staffPostService.getPost(31L)).thenReturn(Optional.of(post(31L, 5L, STORE_ORG, "sales")));
        assertThat(controller.revokePost(31L).getData()).isTrue();
        org.mockito.Mockito.verify(handoverService).revokePost(31L, BOSS_USER);
        org.mockito.Mockito.verify(staffPostService, org.mockito.Mockito.never()).revokePost(31L, BOSS_USER);
    }

    @Test
    void historicalSourceSearchKeepsCurrentOrganizationScopeAndPagination() {
        loginAsBossAt(STORE_ORG);
        var page = new cn.iocoder.yudao.module.platform.org.StaffHandoverHistoryQuery.Page(List.of(), false);
        when(handoverHistory.search(STORE_ORG, "陈新", 2)).thenReturn(page);
        assertThat(controller.handoverSources(STORE_ORG, "陈新", 2).getData()).isEqualTo(page);
        assertThatThrownBy(() -> controller.handoverSources(999L, "陈新", 2))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(ORG_SCOPE_FORBIDDEN.getCode()));
        org.mockito.Mockito.verify(handoverHistory, org.mockito.Mockito.never()).search(999L, "陈新", 2);
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
