package cn.iocoder.yudao.module.franchise.design.admin;

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

@TestPropertySource(properties = "platform.test.extra-flyway-locations=,classpath:db/migration/platform-wallet,classpath:db/migration/franchise-design-remote")
class DesignOperationsIntegrationTest extends PlatformPgIntegrationTest {
    @Autowired DataSource source;
    private JdbcTemplate jdbc;
    private DesignOperationsService service;
    private long tenant;
    @BeforeEach void setUp() {
        jdbc = new JdbcTemplate(source);
        service = new DesignOperationsService(source);
        tenant = IdWorker.getId();
        TenantContextHolder.setTenantId(tenant);
        var user = new LoginUser().setId(99L).setTenantId(tenant).setUserType(2);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
    @AfterEach void clear() { TenantContextHolder.clear(); SecurityContextHolder.clearContext(); }
    @Test void costsUsePersistedCommandTenantAndNeverInventMissingFacts() {
        command(tenant, "one", null);
        command(tenant + 1, "two", null);
        var page = service.costs(1, 20, null);
        assertThat(page.getTotal()).isEqualTo(1);
        assertThat(page.getList()).singleElement().satisfies(row -> {
            assertThat(row.costState()).isEqualTo("UNAVAILABLE");
            assertThat(row.chargedCostMicros()).isNull();
            assertThat(row.chargedPoints()).isNull();
        });
        assertThat(service.costs(1, 20, "999").getList()).isEmpty();
    }
    @Test void priceListsAreTenantScopedAndPreserveVersions() {
        jdbc.update("""
            INSERT INTO fr_design_price(tenant_id,id,version,operation,resolution,unit,prompt_points,image_points,usage_points,max_prompt_units,max_image_units,max_usage_units)
            VALUES(?,?,1,'CREATE_FLAT_JOB','2K','B_WALLET_POINTS',2,3,0,1,8,0)
            """, tenant, IdWorker.getId());
        assertThat(service.prices(1, 20).getList()).singleElement().satisfies(price -> {
            assertThat(price.promptPoints()).isEqualTo(2);
            assertThat(price.imagePoints()).isEqualTo(3);
            assertThat(price.version()).isEqualTo(1);
        });
    }
    @Test void activePricePageExcludesInactiveHistoryAndOtherTenants() {
        price(tenant, 1, "CREATE_FLAT_JOB", false);
        price(tenant, 2, "CREATE_FLAT_JOB", true);
        price(tenant, 1, "CREATE_FACADE_JOB", true);
        price(tenant + 1, 1, "CREATE_OTHER_JOB", true);

        var active = service.activePrices(1, 20);

        assertThat(active.getTotal()).isEqualTo(2);
        assertThat(active.getList()).hasSize(2).allMatch(DesignOperationsService.Price::active)
                .extracting(DesignOperationsService.Price::operation)
                .containsExactly("CREATE_FACADE_JOB", "CREATE_FLAT_JOB");
        assertThat(service.prices(1, 20).getTotal()).isEqualTo(3);
    }
    @Test void persistedMicrosAreReportedWithoutApplyingRetailPrice() {
        command(tenant, "settled", """
            {"jobId":1,"version":2,"status":"SUCCEEDED","terminal":true,"requestedCount":1,"validResultCount":1,
            "cumulativeReservedCostMicros":10000,"cumulativeChargedCostMicros":4321,"cumulativeRefundedCostMicros":5679,
            "cumulativePromptUnits":1,"cumulativeImageUnits":1,"cumulativeUsageUnits":0}
            """);
        assertThat(service.costs(1, 20, null).getList()).singleElement().satisfies(row -> {
            assertThat(row.chargedCostMicros()).isEqualTo(4321);
            assertThat(row.costState()).isEqualTo("RECORDED");
        });
    }
    private void command(long owner, String key, String settlement) {
        jdbc.update("""
            INSERT INTO fr_design_remote_command(caller_app_id,tenant_id,org_id,operation,request_key,remote_request_key,fingerprint,actor_json,payload_json,state,settlement_json)
            VALUES('B',?,88,'CREATE_FLAT_JOB',?,?,'0000000000000000000000000000000000000000000000000000000000000000','{}','{}','UNKNOWN',?::jsonb)
            """, owner, key, java.util.UUID.randomUUID().toString(), settlement);
    }
    private void price(long owner, long version, String operation, boolean active) {
        jdbc.update("""
            INSERT INTO fr_design_price(tenant_id,id,version,operation,resolution,unit,prompt_points,image_points,
                                        usage_points,max_prompt_units,max_image_units,max_usage_units,active)
            VALUES(?,?,?,?,'2K','B_WALLET_POINTS',2,3,0,1,8,0,?)
            """, owner, IdWorker.getId(), version, operation, active);
    }
}
