package cn.iocoder.yudao.module.franchise.org;

import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 种子迁移在只有平台表的库上也能执行（yudao 系统表部分被守卫跳过），并与常量一致。
 */
class FranchiseOrgSeedIntegrationTest extends PlatformPgIntegrationTest {

    @Resource
    private JdbcTemplate jdbc;

    @Test
    void registersOrgTypesAndAllPostTemplates() {
        assertThat(jdbc.queryForList("SELECT code FROM pf_org_type WHERE domain = 'franchise' ORDER BY code", String.class))
                .containsExactly(FranchiseRoles.ORG_HEADQUARTERS, FranchiseRoles.ORG_STORE);
        assertThat(jdbc.queryForList("SELECT role_code FROM pf_post_template WHERE domain = 'franchise'", String.class))
                .containsExactlyInAnyOrderElementsOf(FranchiseRoles.ALL);
    }

    @Test
    void leadsInheritTheirSpecialistPostAndHeadquartersOnlyGetsSharedPosts() {
        assertThat(jdbc.queryForObject("SELECT inherits_role_code FROM pf_post_template WHERE role_code = ?", String.class,
                FranchiseRoles.SALES_LEAD)).isEqualTo(FranchiseRoles.SALES);
        assertThat(jdbc.queryForObject("SELECT inherits_role_code FROM pf_post_template WHERE role_code = ?", String.class,
                FranchiseRoles.PROJECT_MANAGER)).isEqualTo(FranchiseRoles.ENGINEER);
        assertThat(jdbc.queryForList("SELECT role_code FROM pf_post_template WHERE org_types LIKE '%headquarters%'", String.class))
                .containsExactlyInAnyOrder(FranchiseRoles.FINANCE, FranchiseRoles.FINANCE_LEAD, FranchiseRoles.BOSS,
                        FranchiseRoles.HQ_DESIGN_ADMIN);
    }

}
