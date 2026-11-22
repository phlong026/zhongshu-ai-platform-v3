package cn.iocoder.yudao.module.platform.org;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(StaffHandoverHistoryQuery.class)
class StaffHandoverHistoryQueryIntegrationTest extends PlatformPgIntegrationTest {
    private static final AtomicLong IDS = new AtomicLong(4_000_000_000L);
    @Resource private JdbcTemplate jdbc;
    @Resource private StaffHandoverHistoryQuery query;
    private long org;

    @BeforeEach
    void setup() {
        TenantContextHolder.setTenantId(1L);
        jdbc.execute("CREATE TABLE IF NOT EXISTS system_users(id bigint PRIMARY KEY,tenant_id bigint,nickname text,mobile text,status int DEFAULT 0,deleted int2 DEFAULT 0)");
        // 平台测试共库；导入等旧用例可能先创建只有 id/nickname/mobile 的 shim。
        jdbc.execute("ALTER TABLE system_users ADD COLUMN IF NOT EXISTS tenant_id bigint, ADD COLUMN IF NOT EXISTS status int DEFAULT 0, ADD COLUMN IF NOT EXISTS deleted int2 DEFAULT 0");
        jdbc.update("INSERT INTO pf_org_type(code,domain,name) VALUES('audit-history','test','历史员工') ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO pf_post_template(role_code,domain,org_types,lead) VALUES('audit-history-staff','test','audit-history',false) ON CONFLICT DO NOTHING");
        org = organization(1L);
    }

    @AfterEach
    void cleanup() { TenantContextHolder.clear(); }

    @Test
    void historyIsPaginatedDeduplicatedAndAvailableWithoutActivePostsOrPendingRows() {
        long first = employee(1, org, "历史员工", "13800000001");
        historicalPost(first, org);
        for (int i = 0; i < 20; i++) employee(1, org, "历史员工" + i, "13800000002");

        var page1 = query.search(org, "历史员工", 1);
        var page2 = query.search(org, "历史员工", 2);
        assertThat(page1.items()).hasSize(20);
        assertThat(page1.hasMore()).isTrue();
        assertThat(page2.items()).hasSize(1);
        assertThat(page2.hasMore()).isFalse();
        assertThat(page1.items()).doesNotContainAnyElementsOf(page2.items());
        assertThat(query.search(org, "13800000001", 1).items()).singleElement()
                .satisfies(row -> assertThat(row.userId()).isEqualTo(String.valueOf(first)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pf_handover_pending WHERE org_id=?", Long.class, org)).isZero();
    }

    @Test
    void searchCannotReturnGlobalUsersOtherOrganizationsOrOtherTenants() {
        long own = employee(1, org, "同名员工", "13800000000");
        employee(1, organization(1), "同名员工", "13800000000");
        employee(1, 0, "同名员工", "13800000000");
        long otherTenantOrg = organization(2);
        employee(2, otherTenantOrg, "同名员工", "13800000000");
        employee(2, org, "同名员工", "13800000000"); // 损坏的跨租户关系也不能泄露资料

        assertThat(query.search(org, "同名员工", 1).items()).singleElement()
                .satisfies(row -> assertThat(row.userId()).isEqualTo(String.valueOf(own)));
        assertThat(query.search(otherTenantOrg, "同名员工", 1).items()).isEmpty();
        TenantContextHolder.setTenantId(2L);
        assertThat(query.search(org, "同名员工", 1).items()).isEmpty();
        assertThat(query.search(otherTenantOrg, "同名员工", 1).items()).hasSize(1);
    }

    @Test
    void deletedStaffRemainSelectableByHistoryIdWithoutExposingDeletedProfileFields() {
        long former = employee(1, org, "已删除员工", "13800000000");
        jdbc.update("UPDATE system_users SET deleted=1 WHERE id=?", former);
        assertThat(query.search(org, String.valueOf(former), 1).items()).singleElement().satisfies(row -> {
            assertThat(row.name()).isEqualTo("用户" + former);
            assertThat(row.mobile()).isEmpty();
        });
        assertThat(query.search(org, "13800000000", 1).items()).isEmpty();
    }

    @Test
    void searchTreatsWildcardsLiterallyAndRejectsInvalidPaging() {
        employee(1, org, "百分%员工", "13800000000");
        employee(1, org, "普通员工", "13800000001");
        assertThat(query.search(org, "%", 1).items()).hasSize(1);
        assertThat(query.search(org, "' OR 1=1 --", 1).items()).isEmpty();
        assertThatThrownBy(() -> query.search(org, " ", 1)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> query.search(org, "员工", 0)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> query.search(org, "员工", 10001)).isInstanceOf(ServiceException.class);
    }

    private long organization(long tenant) {
        long id = IDS.incrementAndGet();
        jdbc.update("INSERT INTO system_dept(id,name,tenant_id,status,deleted) VALUES(?,'历史查询门店',?,0,0)", id, tenant);
        jdbc.update("INSERT INTO pf_org_unit(dept_id,kind,org_id,org_type) VALUES(?,'ORG',?,'audit-history')", id, id);
        return id;
    }

    private long employee(long tenant, long orgId, String name, String mobile) {
        long id = IDS.incrementAndGet();
        jdbc.update("INSERT INTO system_users(id,tenant_id,nickname,mobile,deleted) VALUES(?,?,?,?,0)", id, tenant, name, mobile);
        if (orgId != 0) historicalPost(id, orgId);
        return id;
    }

    private void historicalPost(long user, long orgId) {
        jdbc.update("INSERT INTO pf_staff_post(id,user_id,org_id,role_code,status,revoked_at) VALUES(?,?,?,'audit-history-staff','REVOKED',CURRENT_TIMESTAMP)", IDS.incrementAndGet(), user, orgId);
    }
}
