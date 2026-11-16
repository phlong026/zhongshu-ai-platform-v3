package cn.iocoder.yudao.module.platform.authz.service;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.platform.authz.FakeSystemDirectoryForOnboard;
import cn.iocoder.yudao.module.platform.org.FranchiseAccountNaming;
import cn.iocoder.yudao.module.platform.org.OrgServiceImpl;
import cn.iocoder.yudao.module.platform.org.StaffPostServiceImpl;
import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({OrgServiceImpl.class, StaffPostServiceImpl.class, FranchiseAccountNaming.class,
        FranchiseOnboardService.class, StaffAccountOnboardService.class, FranchiseOnboardIntegrationTest.Config.class})
class FranchiseOnboardIntegrationTest extends PlatformPgIntegrationTest {
    @TestConfiguration
    static class Config {
        @Bean Directory directory() { return new Directory(); }
    }

    static class Directory extends FakeSystemDirectoryForOnboard {
        final Map<Long, String> passwords = new ConcurrentHashMap<>();
        @Override public long createDept(Long parent, String name) {
            return super.createDept(parent, TenantContextHolder.getRequiredTenantId() + ":" + name);
        }
        @Override public java.util.List<Long> findRootDeptIdsByName(String name) {
            return super.findRootDeptIdsByName(TenantContextHolder.getRequiredTenantId() + ":" + name);
        }
        @Override public String deptName(Long id) {
            return super.deptName(id).replaceFirst("^[0-9]+:", "");
        }
        @Override public long createUserWithPassword(String name, String mobile, Long deptId, String password) {
            long id = createUser(name, mobile, deptId);
            passwords.put(id, password);
            return id;
        }
    }

    @Resource private FranchiseOnboardService service;
    @Resource private StaffAccountOnboardService staffAccounts;
    @Resource private Directory directory;
    @Resource private JdbcTemplate jdbc;
    private static final java.util.concurrent.atomic.AtomicLong IDS = new java.util.concurrent.atomic.AtomicLong(100);

    @BeforeEach
    void setup() {
        TenantContextHolder.setTenantId(IDS.incrementAndGet());
        jdbc.update("INSERT INTO pf_org_type(code, domain, name) VALUES ('store','test','加盟门店') ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO pf_post_template(role_code, domain, org_types, line, lead) VALUES ('boss','test','store,t-store,t-hq,headquarters','management',FALSE) ON CONFLICT (role_code) DO UPDATE SET org_types='store,t-store,t-hq,headquarters'");
        directory.roleIdsByCode.put("boss", 501L);
        jdbc.update("INSERT INTO pf_post_template(role_code, domain, org_types, line, lead) VALUES ('sales','test','store','sales',FALSE),('finance','test','store','finance',FALSE) ON CONFLICT DO NOTHING");
        directory.roleIdsByCode.put("sales", 502L);
        directory.roleIdsByCode.put("finance", 503L);
    }
    @AfterEach void clearTenant() { TenantContextHolder.clear(); }

    private FranchiseOnboardService.Result open(String owner, String mobile) {
        return service.onboard("合家美宅", "江西省", "南昌市", "高新区", owner, mobile, null, null, "测试开通", 9L);
    }

    @Test
    void opensSingleUserAndOnlyReturnsInitialPasswordOnce() {
        var first = open("李忠", "13800100001");
        assertThat(first.loginName()).isEqualTo("hjmz-jxnc-gaoxin");
        assertThat(first.orgName()).isEqualTo("合家美宅-江西南昌-高新-李忠");
        assertThat(first.initialPassword()).hasSize(16);
        assertThat(directory.passwords.get(first.adminUserId())).isEqualTo(first.initialPassword());
        assertThat(first.requiresPasswordChange()).isTrue();
        var repeated = open("李忠", "13800100001");
        assertThat(repeated.orgId()).isEqualTo(first.orgId());
        assertThat(repeated.adminUserId()).isEqualTo(first.adminUserId());
        assertThat(repeated.accountId()).isEqualTo(first.accountId());
        assertThat(repeated.initialPassword()).isNull();
        assertThat(repeated.reusedUser()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pf_franchise_account WHERE account_id=?", Integer.class, first.accountId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pf_franchise_account_alias WHERE account_id=? AND alias='lz'", Integer.class, first.accountId())).isEqualTo(1);
        assertThat(first.toString()).doesNotContain(first.initialPassword());
    }

    @Test
    void regionalCollisionsKeepOriginalLoginAndAppendOwnerThenSequence() {
        var first = open("张三", "13800100002");
        var second = open("李忠", "13800100003");
        assertThat(second.loginName()).isEqualTo("hjmz-jxnc-gaoxin-lizhong");
        var third = open("李中", "13800100004");
        assertThat(third.loginName()).isEqualTo("hjmz-jxnc-gaoxin-lizhong-2");
        assertThat(open("张三", "13800100002").loginName()).isEqualTo(first.loginName());
    }

    @Test
    void anotherStoreForTheSamePersonReusesUserAndNeverResetsPassword() {
        var first = open("王五", "13800100005");
        String password = directory.passwords.get(first.adminUserId());
        var other = service.onboard("合家美宅", "江西", "南昌", "红谷滩", "王五", "13800100005", null, null, "", 9L);
        assertThat(other.adminUserId()).isEqualTo(first.adminUserId());
        assertThat(other.orgId()).isNotEqualTo(first.orgId());
        assertThat(other.initialPassword()).isNull();
        assertThat(directory.passwords.get(first.adminUserId())).isEqualTo(password);
    }

    @Test
    void identicalNameWithDifferentOwnerAccountCannotGrantAnotherBossSilently() {
        var first = open("赵六", "13800100006");
        assertThatThrownBy(() -> open("赵六", "13800100007")).hasMessageContaining("已开通");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pf_staff_post WHERE org_id=? AND status='ACTIVE'", Integer.class, first.orgId())).isEqualTo(1);
    }

    @Test
    void invalidOrDisabledAccountCannotLeaveHalfOpenedData() {
        var first = open("孙七", "13800100008");
        directory.userActive.put(first.adminUserId(), false);
        assertThatThrownBy(() -> service.onboard("合家美宅", "江西", "南昌", "经开", "孙七", "13800100008", null, null, "", 9L))
                .hasMessageContaining("停用");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pf_franchise_account WHERE user_id=?", Integer.class, first.adminUserId())).isEqualTo(1);
    }

    @Test
    void concurrentRegionalOpeningProducesUniqueNamesAndSingleUserPerPhone() throws Exception {
        long tenant = TenantContextHolder.getRequiredTenantId();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<FranchiseOnboardService.Result> task = () -> {
                TenantContextHolder.setTenantId(tenant);
                try { start.await(); return open("周八", "13800100009"); }
                finally { TenantContextHolder.clear(); }
            };
            var a = pool.submit(task); var b = pool.submit(task); start.countDown();
            var x = a.get(); var y = b.get();
            assertThat(x.orgId()).isEqualTo(y.orgId());
            assertThat(x.adminUserId()).isEqualTo(y.adminUserId());
            assertThat((x.initialPassword() == null) != (y.initialPassword() == null)).isTrue();
        } finally { pool.shutdownNow(); }
    }

    @Test
    void bossCreatesIndependentEmployeeAccountInSameStoreAndCanCopyPasswordOnlyOnce() {
        var boss = open("吴九", "13800100010");
        var employee = staffAccounts.open(boss.orgId(), null, "张三", "13900100001", "sales", null,
                boss.adminUserId(), boss.postId());
        assertThat(employee.userId()).isNotEqualTo(boss.adminUserId());
        assertThat(employee.loginName()).isEqualTo("zhangsan");
        assertThat(employee.initialPassword()).isEqualTo("zhangsan123");
        assertThat(employee.orgName()).isEqualTo(boss.orgName());
        assertThat(employee.aliases()).contains("zhangsan", "zs");
        assertThat(employee.toString()).doesNotContain(employee.initialPassword());
        var repeat = staffAccounts.open(boss.orgId(), null, "张三", "13900100001", "finance", null,
                boss.adminUserId(), boss.postId());
        assertThat(repeat.userId()).isEqualTo(employee.userId());
        assertThat(repeat.accountId()).isEqualTo(employee.accountId());
        assertThat(repeat.initialPassword()).isNull();
        assertThat(repeat.reusedUser()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pf_staff_post WHERE user_id=? AND org_id=? AND status='ACTIVE'", Integer.class, employee.userId(), boss.orgId())).isEqualTo(2);
    }

    @Test
    void employeeCannotOpenAccountsOrManageAnotherStore() {
        var boss = open("郑十", "13800100011");
        var employee = staffAccounts.open(boss.orgId(), null, "王五", "13900100002", "sales", null,
                boss.adminUserId(), boss.postId());
        assertThatThrownBy(() -> staffAccounts.open(boss.orgId(), null, "李四", "13900100003", "sales", null,
                employee.userId(), employee.postId())).hasMessageContaining("负责人");
        assertThatThrownBy(() -> staffAccounts.open(boss.orgId()+1000000, null, "李四", "13900100003", "sales", null,
                boss.adminUserId(), boss.postId())).hasMessageContaining("本门店");
        assertThat(directory.userIdByMobile("13900100003")).isNull();
    }

    @Test
    void bossCannotCreateAnotherManagementAccountThroughEmployeeOpening() {
        var boss = open("钱一", "13800100012");
        assertThatThrownBy(() -> staffAccounts.open(boss.orgId(), null, "新老板", "13900100004", "boss", null,
                boss.adminUserId(), boss.postId())).hasMessageContaining("总部");
        assertThat(directory.userIdByMobile("13900100004")).isNull();
    }

    @Test
    void undoRejectsNonemptyAccountDataAndKeepsUserMappings() throws Exception {
        var boss = open("陈二", "13800100013");
        String undo = new org.springframework.core.io.ClassPathResource("db/migration-undo/pf/V20261115.710__undo_pf_franchise_account.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(() -> jdbc.execute(undo)).hasMessageContaining("加盟商账号表非空");
        assertThat(jdbc.queryForObject("SELECT user_id FROM pf_franchise_account WHERE account_id=?", Long.class, boss.accountId()))
                .isEqualTo(boss.adminUserId());
    }
}
