package cn.iocoder.yudao.module.platform.org;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static cn.iocoder.yudao.module.platform.org.enums.PlatformOrgErrorCodes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M01-T03（同岗位改部门命令语义）+ M01-T05（建员与任职原子化）集成契约：
 * - 同 org+role 已有任职：teamId 一致幂等返回；不一致抛 POST_TEAM_CONFLICT（不静默忽略）
 * - 编排事务：新账号指派失败无任职残留；已有账号指派失败原任职保留
 */
@Import({OrgServiceImpl.class, StaffPostServiceImpl.class, StaffPostAssignContractIntegrationTest.Config.class})
class StaffPostAssignContractIntegrationTest extends PlatformPgIntegrationTest {

    private static final java.util.concurrent.atomic.AtomicLong USER_IDS =
            new java.util.concurrent.atomic.AtomicLong(System.nanoTime() % 1_000_000_000L);

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
                + "('t-finance','test','t-hq,t-store','finance',FALSE,NULL) ON CONFLICT DO NOTHING");
        directory.roleIdsByCode.put("t-sales", 501L);
        directory.roleIdsByCode.put("t-finance", 503L);
    }

    private long newUserId() {
        long id = USER_IDS.incrementAndGet();
        directory.users.add(id);
        return id;
    }

    @Test
    void sameTeamIdIsIdempotentAndDifferentTeamIdIsRejected() {
        long store = orgService.createOrg("t-store", "门店", null);
        long teamA = orgService.createTeam(store, null, "业务部", "sales");
        long teamB = orgService.createTeam(store, null, "二部", "sales");
        long user = newUserId();

        long first = staffPostService.assignPostInOrg(user, store, "t-sales", teamA, 99L);
        // 同参数重复：幂等返回同一任职
        assertThat(staffPostService.assignPostInOrg(user, store, "t-sales", teamA, 99L)).isEqualTo(first);
        // 同岗位不同 teamId：拒绝冲突输入（M01-I02 静默忽略修复）
        assertThatThrownBy(() -> staffPostService.assignPostInOrg(user, store, "t-sales", teamB, 99L))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode()).isEqualTo(POST_TEAM_CONFLICT.getCode());
        // 原任职不受拒绝影响
        assertThat(staffPostService.listActivePosts(user)).extracting(cn.iocoder.yudao.module.platform.org.dal.StaffPostDO::getId)
                .containsExactly(first);
        // teamId null↔值 也视为冲突
        assertThatThrownBy(() -> staffPostService.assignPostInOrg(user, store, "t-sales", null, 99L))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode()).isEqualTo(POST_TEAM_CONFLICT.getCode());
    }

    @Test
    void nullTeamIdSemanticsAreExplicit() {
        long store = orgService.createOrg("t-store", "门店", null);
        long team = orgService.createTeam(store, null, "业务部", "sales");
        long user = newUserId();
        long first = staffPostService.assignPostInOrg(user, store, "t-sales", null, 99L);
        // null↔null：幂等返回同一任职
        assertThat(staffPostService.assignPostInOrg(user, store, "t-sales", null, 99L)).isEqualTo(first);
        // null→值：冲突拒绝
        assertThatThrownBy(() -> staffPostService.assignPostInOrg(user, store, "t-sales", team, 99L))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode()).isEqualTo(POST_TEAM_CONFLICT.getCode());
    }

    @Test
    void concurrentFirstAssignmentResolvesIdempotentlyOrConflict() throws Exception {
        long store = orgService.createOrg("t-store", "门店", null);
        long teamA = orgService.createTeam(store, null, "业务部", "sales");
        long teamB = orgService.createTeam(store, null, "二部", "sales");
        long user = newUserId();
        // 并发首指派同 team：一胜一幂等返回同一 postId
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.CountDownLatch start1 = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Object> same = () -> {
                start1.await();
                return staffPostService.assignPostInOrg(user, store, "t-sales", teamA, 99L);
            };
            var f1 = pool.submit(same);
            var f2 = pool.submit(same);
            start1.countDown();
            Object r1 = f1.get(), r2 = f2.get();
            assertThat(r1).isEqualTo(r2);
            // 并发首指派异 team：一胜一 POST_TEAM_CONFLICT（而非 006）
            long user2 = newUserId();
            java.util.concurrent.CountDownLatch start2 = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Object> ca = () -> { start2.await(); return staffPostService.assignPostInOrg(user2, store, "t-sales", teamA, 99L); };
            java.util.concurrent.Callable<Object> cb = () -> { start2.await(); return staffPostService.assignPostInOrg(user2, store, "t-sales", teamB, 99L); };
            var f3 = pool.submit(ca);
            var f4 = pool.submit(cb);
            start2.countDown();
            long winner = (Long) f3.get();
            assertThatThrownBy(() -> f4.get())
                    .hasCauseInstanceOf(ServiceException.class)
                    .extracting(t -> ((ServiceException) t.getCause()).getCode())
                    .isEqualTo(POST_TEAM_CONFLICT.getCode());
            assertThat(winner).isPositive();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void createStaffInOrgReusesAccountAndAssigns() {
        long store = orgService.createOrg("t-store", "门店", null);
        long team = orgService.createTeam(store, null, "业务部", "sales");
        // 新手机号：建账号+指派
        long created = staffPostService.createStaffInOrg(store, "张新", "13800001111", "t-sales", team, 99L);
        assertThat(created).isPositive();
        // 已有手机号：复用账号再指派另一岗位
        long second = staffPostService.createStaffInOrg(store, "张新", "13800001111", "t-finance", null, 99L);
        assertThat(second).isPositive();
    }

    @Test
    void newAccountAssignmentFailureLeavesNoActivePost() {
        long store = orgService.createOrg("t-store", "门店", null);
        // 未注册模板——新账号指派必失败（编排事务内），不得留下有效任职
        assertThatThrownBy(() -> staffPostService.createStaffInOrg(store, "李孤立", "13800002222", "t-nope", null, 99L))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode()).isEqualTo(POST_TEMPLATE_NOT_FOUND.getCode());
        // 该手机号复用账号再指派合法岗位：不出现"提示成功但岗位不存在"
        long recovered = staffPostService.createStaffInOrg(store, "李孤立", "13800002222", "t-sales", null, 99L);
        assertThat(recovered).isPositive();
        assertThat(staffPostService.listActivePostsInOrg(store, "t-sales"))
                .extracting(cn.iocoder.yudao.module.platform.org.dal.StaffPostDO::getId).contains(recovered);
    }

    @Test
    void existingAccountAssignmentFailureKeepsOriginalPost() {
        long store = orgService.createOrg("t-store", "门店", null);
        long other = orgService.createOrg("t-store", "另一店", null);
        // 预置已有账号并绑定手机号（Codex R1 P2-2：编排须复用该账号而非新建）
        long user = newUserId();
        directory.userMobiles.put(user, "13800003333");
        long original = staffPostService.assignPost(user, store, "t-sales", null, 99L);
        // 已有手机号 + 指派失败（模板未注册）：原任职保留、不新建账号
        assertThatThrownBy(() -> staffPostService.createStaffInOrg(other, "王既有", "13800003333", "t-nope", null, 99L))
                .isInstanceOf(ServiceException.class);
        assertThat(staffPostService.findActivePost(user, original)).isPresent();
        assertThat(directory.userIdByMobile("13800003333")).isEqualTo(user);
        // 同手机号重试合法岗位：复用同一账号在另一组织指派成功，原任职不受影响
        long again = staffPostService.createStaffInOrg(other, "王既有", "13800003333", "t-finance", null, 99L);
        assertThat(again).isPositive();
        assertThat(directory.userIdByMobile("13800003333")).isEqualTo(user);
        assertThat(staffPostService.findActivePost(user, original)).isPresent();
    }
}
