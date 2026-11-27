package cn.iocoder.yudao.module.franchise.org;

import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FranchiseRolesTest extends FranchisePgIntegrationTest {

    @Resource
    private JdbcTemplate jdbc;

    @Test
    void seededTemplatesMatchTheMiniProgramRoles() {
        Map<String, String> inherits = new HashMap<>();
        jdbc.query("SELECT role_code, inherits_role_code FROM pf_post_template WHERE domain = 'franchise'",
                rs -> {
                    if (rs.getString(2) != null) {
                        inherits.put(rs.getString(1), rs.getString(2));
                    }
                });
        Integer templates = jdbc.queryForObject("SELECT count(*) FROM pf_post_template WHERE domain = 'franchise'", Integer.class);

        assertThat(templates).isEqualTo(11);
        assertThat(inherits).isEqualTo(FranchiseRoles.INHERITS);
        assertThat(jdbc.queryForObject("SELECT org_types FROM pf_post_template WHERE role_code='hq-admin'", String.class))
                .isEqualTo("headquarters");
    }

    @Test
    void storeAndHeadquartersPostsFollowTheTemplates() {
        var store = fixture.store();
        PostSnapshot lead = fixture.post(store.orgId(), FranchiseRoles.SALES_LEAD, store.salesTeamA());
        PostSnapshot operations = fixture.post(fixture.headquarters(), FranchiseRoles.OPERATIONS, null);

        assertThat(FranchiseRoles.has(lead, FranchiseRoles.SALES)).isTrue();
        assertThat(FranchiseRoles.has(lead, FranchiseRoles.ENGINEER)).isFalse();
        assertThat(FranchiseRoles.atStore(lead)).isTrue();
        assertThat(lead.managedTeamIds()).containsExactly(store.salesTeamA());
        assertThat(FranchiseRoles.atHeadquarters(operations)).isTrue();
        assertThat(FranchiseRoles.isBoss(operations)).isFalse();
    }

}
