package cn.iocoder.yudao.module.platform.org;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.platform.org.dal.OrgUnitDO;
import cn.iocoder.yudao.module.platform.org.dal.StaffPostDO;
import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import jakarta.annotation.Resource;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static cn.iocoder.yudao.module.platform.org.enums.PlatformOrgErrorCodes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({OrgServiceImpl.class, StaffPostServiceImpl.class, OrgAndPostIntegrationTest.Config.class})
class OrgAndPostIntegrationTest extends PlatformPgIntegrationTest {

    private static final AtomicLong USER_IDS = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @TestConfiguration
    static class Config {
        @Bean
        FakeSystemDirectory fakeSystemDirectory() {
            return new FakeSystemDirectory();
        }
    }

    @Resource
    private OrgService orgService;
    @Resource
    private StaffPostService staffPostService;
    @Resource
    private FakeSystemDirectory directory;
    @Resource
    private JdbcTemplate jdbc;

    @BeforeEach
    void registerOrgTypesAndTemplates() {
        jdbc.update("INSERT INTO pf_org_type(code, domain, name) VALUES ('t-hq','test','总部'),('t-store','test','门店') ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO pf_post_template(role_code, domain, org_types, line, lead, inherits_role_code) VALUES "
                + "('t-sales','test','t-store','sales',FALSE,NULL),"
                + "('t-sales-lead','test','t-store','sales',TRUE,'t-sales'),"
                + "('t-finance','test','t-hq,t-store','finance',FALSE,NULL),"
                + "('t-unmapped','test','t-store','sales',FALSE,NULL) ON CONFLICT DO NOTHING");
        directory.roleIdsByCode.put("t-sales", 501L);
        directory.roleIdsByCode.put("t-sales-lead", 502L);
        directory.roleIdsByCode.put("t-finance", 503L);
    }

    // ===================== 组织与团队 =====================

    @Test
    void createsOrgAndTeamsOnTopOfDepartments() {
        long orgId = orgService.createOrg("t-store", "湖北加盟商", null);
        long team = orgService.createTeam(orgId, null, "业务部", "sales");
        long subTeam = orgService.createTeam(orgId, team, "业务一组", "sales");

        OrgUnitDO org = orgService.getUnit(orgId);
        assertThat(org.getKind()).isEqualTo("ORG");
        assertThat(org.getOrgId()).isEqualTo(orgId);
        assertThat(org.getOrgType()).isEqualTo("t-store");
        assertThat(orgService.getUnit(subTeam).getOrgId()).isEqualTo(orgId);
        assertThat(directory.deptParent.get(subTeam)).isEqualTo(team);
        assertThat(directory.deptParent.get(team)).isEqualTo(orgId);
    }

    @Test
    void listsActiveOrgsOfAType() {
        jdbc.update("INSERT INTO pf_org_type(code, domain, name) VALUES ('t-only','test','单测类型') ON CONFLICT DO NOTHING");
        long first = orgService.createOrg("t-only", "甲", null);
        long second = orgService.createOrg("t-only", "乙", null);
        long disabled = orgService.createOrg("t-only", "丙", null);
        orgService.setOrgActive(disabled, false);

        assertThat(orgService.listActiveOrgsByType("t-only")).extracting(OrgUnitDO::getDeptId).containsExactly(first, second);
    }

    @Test
    void rejectsUnregisteredOrgType() {
        assertError(() -> orgService.createOrg("t-unknown", "某组织", null), ORG_TYPE_NOT_REGISTERED);
    }

    @Test
    void teamParentMustBelongToTheSameOrg() {
        long orgA = orgService.createOrg("t-store", "A店", null);
        long orgB = orgService.createOrg("t-store", "B店", null);
        long teamOfB = orgService.createTeam(orgB, null, "业务部", "sales");

        assertError(() -> orgService.createTeam(orgA, teamOfB, "错挂团队", "sales"), TEAM_NOT_IN_ORG);
        assertError(() -> orgService.createTeam(teamOfB, null, "团队下当组织", "sales"), ORG_NOT_FOUND);
    }

    // ===================== 任职 =====================

    @Test
    void assignsPostAndSyncsUserRolesWhileKeepingNonPlatformRoles() {
        long orgId = orgService.createOrg("t-store", "门店", null);
        long team = orgService.createTeam(orgId, null, "业务部", "sales");
        long user = newUser();
        directory.userRoles.put(user, Set.of(1L)); // 平台之外的角色（如超级管理员）不应被同步覆盖

        long postId = staffPostService.assignPost(user, orgId, "t-sales", team, 99L);

        assertThat(directory.userRoles.get(user)).containsExactlyInAnyOrder(1L, 501L);
        StaffPostDO post = staffPostService.listActivePosts(user).get(0);
        assertThat(post.getId()).isEqualTo(postId);
        assertThat(post.getCreatedBy()).isEqualTo(99L);
    }

    @Test
    void dualPostsAreTwoRecordsAndRevokingOneKeepsTheOther() {
        long orgId = orgService.createOrg("t-store", "门店", null);
        long user = newUser();
        long sales = staffPostService.assignPost(user, orgId, "t-sales", null, 99L);
        long finance = staffPostService.assignPost(user, orgId, "t-finance", null, 99L);
        assertThat(directory.userRoles.get(user)).containsExactlyInAnyOrder(501L, 503L);

        staffPostService.revokePost(sales, 98L);

        assertThat(staffPostService.listActivePosts(user)).extracting(StaffPostDO::getId).containsExactly(finance);
        assertThat(directory.userRoles.get(user)).containsExactly(503L);
        assertThat(staffPostService.findActivePost(user, sales)).isEmpty();
        assertError(() -> staffPostService.revokePost(sales, 98L), POST_NOT_FOUND);
    }

    @Test
    void sameRoleInTwoOrgsKeepsTheRoleUntilBothAreRevoked() {
        long hq = orgService.createOrg("t-hq", "总部", null);
        long store = orgService.createOrg("t-store", "门店", null);
        long user = newUser();
        long inHq = staffPostService.assignPost(user, hq, "t-finance", null, 99L);
        staffPostService.assignPost(user, store, "t-finance", null, 99L);

        staffPostService.revokePost(inHq, 98L);

        assertThat(directory.userRoles.get(user)).containsExactly(503L);
    }

    @Test
    void rejectsInvalidAssignments() {
        long hq = orgService.createOrg("t-hq", "总部", null);
        long store = orgService.createOrg("t-store", "门店", null);
        long otherStore = orgService.createOrg("t-store", "另一门店", null);
        long teamOfOther = orgService.createTeam(otherStore, null, "业务部", "sales");
        long user = newUser();

        assertError(() -> staffPostService.assignPost(user, hq, "t-sales", null, 99L), POST_NOT_ALLOWED_FOR_ORG);
        assertError(() -> staffPostService.assignPost(user, store, "t-sales", teamOfOther, 99L), TEAM_NOT_IN_ORG);
        assertError(() -> staffPostService.assignPost(user, store, "t-nope", null, 99L), POST_TEMPLATE_NOT_FOUND);
        assertError(() -> staffPostService.assignPost(user, store, "t-unmapped", null, 99L), ROLE_NOT_CONFIGURED);
        assertError(() -> staffPostService.assignPost(-1L, store, "t-sales", null, 99L), USER_NOT_FOUND);

        staffPostService.assignPost(user, store, "t-sales", null, 99L);
        assertError(() -> staffPostService.assignPost(user, store, "t-sales", null, 99L), POST_ALREADY_ACTIVE);
    }

    @Test
    void cannotAssignPostsInADisabledOrg() {
        long store = orgService.createOrg("t-store", "门店", null);
        orgService.setOrgActive(store, false);

        assertError(() -> staffPostService.assignPost(newUser(), store, "t-sales", null, 99L), ORG_DISABLED);
    }

    // ===================== 岗位快照（供授权使用） =====================

    @Test
    void activePostSnapshotBelongsToItsOwnerOnly() {
        long store = orgService.createOrg("t-store", "门店", null);
        long owner = newUser();
        long other = newUser();
        long postId = staffPostService.assignPost(owner, store, "t-sales", null, 99L);

        assertThat(staffPostService.findActivePost(owner, postId)).get()
                .satisfies(p -> {
                    assertThat(p.orgId()).isEqualTo(store);
                    assertThat(p.orgType()).isEqualTo("t-store");
                    assertThat(p.roleCode()).isEqualTo("t-sales");
                    assertThat(p.lead()).isFalse();
                    assertThat(p.managedTeamIds()).isEmpty();
                });
        assertThat(staffPostService.findActivePost(other, postId)).isEmpty();
    }

    @Test
    void postInADisabledOrgIsNoLongerUsable() {
        long store = orgService.createOrg("t-store", "门店", null);
        long user = newUser();
        long postId = staffPostService.assignPost(user, store, "t-sales", null, 99L);

        orgService.setOrgActive(store, false);

        assertThat(staffPostService.findActivePost(user, postId)).isEmpty();
    }

    @Test
    void leadManagesOwnTeamAndItsSubTeams() {
        long store = orgService.createOrg("t-store", "门店", null);
        long team = orgService.createTeam(store, null, "业务部", "sales");
        long sub = orgService.createTeam(store, team, "业务一组", "sales");
        long sibling = orgService.createTeam(store, null, "工程部", "engineering");
        long lead = newUser();
        long postId = staffPostService.assignPost(lead, store, "t-sales-lead", team, 99L);

        assertThat(staffPostService.findActivePost(lead, postId)).get()
                .satisfies(p -> {
                    assertThat(p.lead()).isTrue();
                    assertThat(p.managedTeamIds()).containsExactlyInAnyOrder(team, sub).doesNotContain(sibling);
                });
    }

    @Test
    void listsActivePostsOfARoleInAnOrg() {
        long store = orgService.createOrg("t-store", "门店", null);
        long a = newUser();
        long b = newUser();
        staffPostService.assignPost(a, store, "t-finance", null, 99L);
        long revoked = staffPostService.assignPost(b, store, "t-finance", null, 99L);
        staffPostService.revokePost(revoked, 98L);

        assertThat(staffPostService.listActivePostsInOrg(store, "t-finance"))
                .extracting(StaffPostDO::getUserId).containsExactly(a);
    }

    @Test
    void orgsEverHeldIncludeRevokedPosts() {
        long a = orgService.createOrg("t-store", "A店", null);
        long b = orgService.createOrg("t-hq", "总部", null);
        long user = newUser();
        long inA = staffPostService.assignPost(user, a, "t-sales", null, 99L);
        staffPostService.assignPost(user, b, "t-finance", null, 99L);
        staffPostService.revokePost(inA, 98L);

        assertThat(staffPostService.listOrgIdsEverHeld(user)).containsExactly(Math.min(a, b), Math.max(a, b));
        assertThat(staffPostService.listOrgIdsEverHeld(newUser())).isEmpty();
    }

    @Test
    void leadInheritsTheSpecialistRole() {
        assertThat(staffPostService.roleHas("t-sales-lead", "t-sales")).isTrue();
        assertThat(staffPostService.roleHas("t-sales", "t-sales")).isTrue();
        assertThat(staffPostService.roleHas("t-sales", "t-sales-lead")).isFalse();
        assertThat(staffPostService.roleHas("t-finance", "t-sales")).isFalse();
    }

    private long newUser() {
        long id = USER_IDS.incrementAndGet();
        directory.users.add(id);
        return id;
    }

    private static void assertError(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call).isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode()).isEqualTo(expected.getCode());
    }

}
