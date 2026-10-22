package cn.iocoder.yudao.module.franchise.test;

import cn.iocoder.yudao.module.platform.org.OrgService;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.StaffPostService;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 快速搭建测试组织：唯一的总部、门店（含业务一组、业务二组、工程部）、任职。
 * 返回的岗位快照即授权时的「当前工作身份」。
 */
public class FranchiseFixture {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private final OrgService orgService;
    private final StaffPostService staffPostService;

    public FranchiseFixture(OrgService orgService, StaffPostService staffPostService) {
        this.orgService = orgService;
        this.staffPostService = staffPostService;
    }

    /** 门店及其团队 */
    public record Store(long orgId, long salesTeamA, long salesTeamB, long engineeringTeam) {
    }

    /** 唯一启用的总部组织；不存在时创建（全 JVM 共用一个数据库，只能有一个） */
    public synchronized long headquarters() {
        List<Long> existing = orgService.listActiveOrgIds("headquarters");
        return existing.isEmpty() ? orgService.createOrg("headquarters", "众墅总部", null) : existing.get(0);
    }

    public Store store() {
        long org = orgService.createOrg("store", "测试门店" + SEQ.incrementAndGet(), null);
        long sales = orgService.createTeam(org, null, "业务部", "sales");
        long teamA = orgService.createTeam(org, sales, "业务一组", "sales");
        long teamB = orgService.createTeam(org, sales, "业务二组", "sales");
        long engineering = orgService.createTeam(org, null, "工程部", "engineering");
        return new Store(org, teamA, teamB, engineering);
    }

    /** 新建一名员工并任职，返回岗位快照 */
    public PostSnapshot post(long orgId, String roleCode, Long teamId) {
        return postFor(FakeSystemDirectory.INSTANCE.newUser(), orgId, roleCode, teamId);
    }

    /** 给已有员工增加任职（兼岗） */
    public PostSnapshot postFor(long userId, long orgId, String roleCode, Long teamId) {
        long postId = staffPostService.assignPost(userId, orgId, roleCode, teamId, 1L);
        return staffPostService.findActivePost(userId, postId).orElseThrow();
    }

}
