package cn.iocoder.yudao.module.platform.authz.service;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.platform.authz.FakeSystemDirectoryForOnboard;
import cn.iocoder.yudao.module.platform.org.OrgService;
import cn.iocoder.yudao.module.platform.org.dal.OrgUnitDO;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static cn.iocoder.yudao.module.platform.org.enums.PlatformOrgErrorCodes.ORG_TYPE_NOT_REGISTERED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M01-T06 / R02 组织开通编排集成契约：
 * 首次开通建组织+首管理员 boss 任职；重复开通幂等复用不重复建组织；同名校验区分类型；失败回滚不留半开通。
 */
@Import({cn.iocoder.yudao.module.platform.org.OrgServiceImpl.class, cn.iocoder.yudao.module.platform.org.StaffPostServiceImpl.class, OrgOnboardService.class, OrgOnboardIntegrationTest.Config.class})
class OrgOnboardIntegrationTest extends PlatformPgIntegrationTest {

    private static final java.util.concurrent.atomic.AtomicLong USER_IDS =
            new java.util.concurrent.atomic.AtomicLong(System.nanoTime() % 1_000_000_000L);

    @TestConfiguration
    static class Config {
        @Bean
        FakeSystemDirectoryForOnboard fakeSystemDirectory() {
            return new FakeSystemDirectoryForOnboard();
        }
    }

    @Resource
    private OrgOnboardService onboardService;
    @Resource
    private OrgService orgService;
    @Resource
    private FakeSystemDirectoryForOnboard directory;
    @Resource
    private JdbcTemplate jdbc;

    @BeforeEach
    void registerOrgTypesAndTemplates() {
        jdbc.update("INSERT INTO pf_org_type(code, domain, name) VALUES ('t-hq','test','总部'),('t-store','test','门店') ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO pf_post_template(role_code, domain, org_types, line, lead, inherits_role_code) VALUES "
                + "('boss','test','t-hq,t-store','management',FALSE,NULL) ON CONFLICT DO NOTHING");
        directory.roleIdsByCode.put("boss", 501L);
    }

    @Test
    void firstOnboardCreatesOrgAdminWithBossPost() {
        OrgOnboardService.OnboardResult r = onboardService.onboard("t-store", "湖北门店", "赵管理员", "13800001001", 9L);
        assertThat(r.orgId()).isPositive();
        assertThat(r.reusedOrg()).isFalse();
        OrgUnitDO org = orgService.getUnit(r.orgId());
        assertThat(org.isOrg()).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pf_staff_post WHERE user_id = " + r.adminUserId()
                        + " AND org_id = " + r.orgId() + " AND role_code = 'boss' AND status = 'ACTIVE'", Integer.class))
                .isEqualTo(1);
        assertThat(directory.userIdByMobile("13800001001")).isEqualTo(r.adminUserId());
    }

    @Test
    void repeatOnboardIsIdempotentWithoutDuplicatingOrg() {
        OrgOnboardService.OnboardResult first = onboardService.onboard("t-store", "湖北门店", "赵管理员", "13800001002", 9L);
        OrgOnboardService.OnboardResult second = onboardService.onboard("t-store", "湖北门店", "钱复用", "13800001003", 9L);
        assertThat(second.orgId()).isEqualTo(first.orgId());
        assertThat(second.reusedOrg()).isTrue();
        // Fake 环境 dept 在内存（shim 的 system_dept 未被写入）——内存反查 deptId 统计 ACTIVE ORG 行唯一
        long orgRows = directory.findRootDeptIdsByName("湖北门店").stream()
                .mapToLong(id -> jdbc.queryForObject(
                        "SELECT count(*) FROM pf_org_unit WHERE dept_id = " + id + " AND kind = 'ORG' AND status = 'ACTIVE'", Integer.class))
                .sum();
        assertThat(orgRows).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pf_staff_post WHERE user_id = " + second.adminUserId()
                        + " AND org_id = " + first.orgId() + " AND role_code = 'boss' AND status = 'ACTIVE'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void sameNameDifferentTypeIsRejected() {
        // Codex R1 P2-1：底座根部门按名唯一（跨类型）——同名已开通为其他类型 → 016 显式拒绝
        onboardService.onboard("t-store", "双类型组织", "孙管理", "13800001004", 9L);
        assertThatThrownBy(() -> onboardService.onboard("t-hq", "双类型组织", "李管理", "13800001005", 9L))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode())
                .isEqualTo(cn.iocoder.yudao.module.platform.org.enums.PlatformOrgErrorCodes.ORG_NAME_TAKEN.getCode());
    }

    @Test
    void concurrentOnboardResolvesSingleOrg() throws Exception {
        // Codex R1 P1-1：并发同名开通——业务键锁串行化，两请求同 orgId（一建一复用），不重复建组织
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<OrgOnboardService.OnboardResult> task = () -> {
                start.await();
                return onboardService.onboard("t-store", "并发开通门店", "并发管理", "13800001008", 9L);
            };
            var f1 = pool.submit(task);
            var f2 = pool.submit(task);
            start.countDown();
            OrgOnboardService.OnboardResult r1 = f1.get(), r2 = f2.get();
            assertThat(r1.orgId()).isEqualTo(r2.orgId());
            assertThat(r1.reusedOrg() ^ r2.reusedOrg()).isTrue(); // 恰好一建一复用
            long orgRows = directory.findRootDeptIdsByName("并发开通门店").stream()
                    .mapToLong(id -> jdbc.queryForObject(
                            "SELECT count(*) FROM pf_org_unit WHERE dept_id = " + id + " AND kind = 'ORG' AND status = 'ACTIVE'", Integer.class))
                    .sum();
            assertThat(orgRows).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void disabledAccountIsRejectedAsAdmin() {
        long disabled = USER_IDS.incrementAndGet();
        directory.users.add(disabled);
        directory.userMobiles.put(disabled, "13800001009");
        directory.userActive.put(disabled, false);
        assertThatThrownBy(() -> onboardService.onboard("t-store", "停用账号门店", "停用员工", "13800001009", 9L))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode())
                .isEqualTo(cn.iocoder.yudao.module.platform.org.enums.PlatformOrgErrorCodes.STAFF_GLOBAL_RESUME_FORBIDDEN.getCode());
    }

    @Test
    void unregisteredOrgTypeFailsWholeOnboard() {
        assertThatThrownBy(() -> onboardService.onboard("t-unknown", "未知类型", "周失败", "13800001006", 9L))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode()).isEqualTo(ORG_TYPE_NOT_REGISTERED.getCode());
        Long uid = directory.userIdByMobile("13800001006");
        assertThat(uid == null || staffPostServiceListActivePostsEmpty(uid)).isTrue();
    }

    private boolean staffPostServiceListActivePostsEmpty(long uid) {
        return staffPostService.listActivePosts(uid).isEmpty();
    }

    @Test
    void existingMobileIsReusedAsAdmin() {
        long preUser = USER_IDS.incrementAndGet();
        directory.users.add(preUser);
        directory.userMobiles.put(preUser, "13800001007");
        OrgOnboardService.OnboardResult r = onboardService.onboard("t-store", "复用账号门店", "已有员工", "13800001007", 9L);
        assertThat(r.adminUserId()).isEqualTo(preUser);
    }

    @Resource
    private StaffPostService staffPostService;
}
