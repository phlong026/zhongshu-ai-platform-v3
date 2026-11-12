package cn.iocoder.yudao.module.platform.wallet.admin;

import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import javax.sql.DataSource;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestPropertySource(properties = "platform.test.extra-flyway-locations=,classpath:db/migration/platform-wallet")
class WalletOperationsIntegrationTest extends PlatformPgIntegrationTest {
    @Autowired DataSource source;
    private JdbcTemplate jdbc;
    private WalletOperationsService service;
    private long tenant;

    @BeforeEach void setUp() {
        jdbc = new JdbcTemplate(source);
        service = new WalletOperationsService(source);
        jdbc.update("INSERT INTO pf_org_type(code,domain,name) VALUES('store','franchise','门店') ON CONFLICT DO NOTHING");
        tenant = IdWorker.getId();
        login(tenant);
    }
    @AfterEach void clear() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }
    private void login(long id) {
        TenantContextHolder.setTenantId(id);
        var user = new LoginUser().setId(99L).setTenantId(id).setUserType(2);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
    @Test void sameTenantCrossOrgReadsArePagedAndNeverCreateWallets() {
        long first = wallet(tenant, 11);
        wallet(tenant, 22);
        wallet(tenant + 1, 33);
        var page = service.wallets(1, 1, null);
        assertThat(page.getTotal()).isEqualTo(2);
        assertThat(page.getList()).hasSize(1);
        assertThat(service.wallets(1, 20, Long.toString(first)).getList()).singleElement()
                .satisfies(row -> assertThat(row.availablePoints()).isEqualTo(11));
        assertThat(service.wallets(1, 20, "9007199254740993").getList()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_wallet WHERE tenant_id=?", Long.class, tenant)).isEqualTo(2);
    }
    @Test void pagingRejectsUnboundedOrInvalidQueriesAndTenantImpersonation() {
        assertThatThrownBy(() -> service.wallets(0, 20, null)).hasMessageContaining("分页");
        assertThatThrownBy(() -> service.wallets(1, 101, null)).hasMessageContaining("分页");
        assertThatThrownBy(() -> service.wallets(1, 20, "1 OR 1=1")).hasMessageContaining("组织");
        TenantContextHolder.setTenantId(tenant + 1);
        assertThatThrownBy(() -> service.wallets(1, 20, null)).hasMessageContaining("租户");
        TenantContextHolder.setTenantId(tenant);
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> service.wallets(1, 20, null)).hasMessageContaining("管理员");
    }
    @Test void planAndMoneyFactsRemainLocalAndRetainExactIds() {
        long id = 9007199254740993L;
        jdbc.update("INSERT INTO pf_recharge_plan(id,tenant_id,name,price_fen,base_points,bonus_points) VALUES(?,?,?,199,20,3)", id, tenant, "测试套餐");
        jdbc.update("INSERT INTO pf_recharge_plan(id,tenant_id,name,price_fen,base_points) VALUES(?,?,?,999,99)", IdWorker.getId(), tenant + 1, "他租户");
        var plans = service.plans(1, 20);
        assertThat(plans.getList()).singleElement().satisfies(plan -> {
            assertThat(plan.id()).isEqualTo("9007199254740993");
            assertThat(plan.priceFen()).isEqualTo(199);
            assertThat(plan.bonusPoints()).isEqualTo(3);
        });
        assertThat(service.orders(1, 20, null).getList()).isEmpty();
        assertThat(service.ledger(1, 20, null).getList()).isEmpty();
        assertThat(service.anomalies(1, 20, null).getList()).isEmpty();
    }
    @Test void headquartersMenusRegisterPermissionsWithoutGrantingAnyRole() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM system_menu WHERE id BETWEEN 960000 AND 960099", Long.class)).isGreaterThanOrEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM system_role_menu WHERE menu_id BETWEEN 960000 AND 960099", Long.class)).isZero();
    }
    private long wallet(long owner, long points) {
        long org = IdWorker.getId();
        long id = IdWorker.getId();
        jdbc.update("INSERT INTO pf_org_unit(dept_id,kind,org_id,org_type) VALUES(?,'ORG',?,'store')", org, org);
        jdbc.update("INSERT INTO pf_wallet(id,org_id,tenant_id) VALUES(?,?,?)", id, org, owner);
        jdbc.update("INSERT INTO pf_point_account(wallet_id,tenant_id,available_points) VALUES(?,?,?)", id, owner, points);
        return org;
    }
}
