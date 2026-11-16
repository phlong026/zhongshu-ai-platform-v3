package cn.iocoder.yudao.module.platform.wallet.orgpoint;

import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@TestPropertySource(properties = "platform.test.extra-flyway-locations=,classpath:db/migration/platform-wallet")
class OrgPointPricingPgIntegrationTest extends PlatformPgIntegrationTest {
    @Autowired DataSource source;
    @Autowired PlatformTransactionManager manager;
    private JdbcTemplate jdbc;
    private OrgPointPriceService prices;
    private OrgPointPricingController controller;
    private OrgPointHeadquartersService headquarters;

    @BeforeEach void prepare() {
        jdbc = new JdbcTemplate(source);
        prices = new OrgPointPriceService(source, manager);
        headquarters = mock(OrgPointHeadquartersService.class);
        controller = new OrgPointPricingController(headquarters, source);
        jdbc.execute("CREATE TABLE IF NOT EXISTS system_users(id bigint PRIMARY KEY,nickname varchar(64))");
        jdbc.execute("TRUNCATE org_point_hq_price_request,org_point_price_audit,org_point_subject_price");
        jdbc.update("INSERT INTO system_users(id,nickname) VALUES(88,'定价测试员') "
                + "ON CONFLICT(id) DO UPDATE SET nickname=excluded.nickname");
        when(headquarters.catalog(88L)).thenAnswer(invocation -> prices.catalog());
    }

    @Test void historyReadsVersionsOperatorAndAuditWithoutInventingMissingReasons() {
        prices.publish("copy", 2_000_000, 1, "88");
        long second = prices.publish("copy", 3_000_000, 2, "88");
        prices.publish("ai_flat", 4_000_000, 1, "88");
        jdbc.update("INSERT INTO org_point_price_audit(id,subject,price_id,price_micros,min_charge,actor_id,reason) "
                + "VALUES(1,'copy',?,3000000,2,88,'运营调价')", second);
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(88L);
            var rows = controller.history("copy").getData();
            assertThat(rows).hasSize(2);
            assertThat(rows).allMatch(row -> row.subject().equals("copy") && row.unit().equals("CALL"));
            assertThat(rows.get(0).version()).isEqualTo(2);
            assertThat(rows.get(0).status()).isEqualTo("ACTIVE");
            assertThat(rows.get(0).operatorName()).isEqualTo("定价测试员");
            assertThat(rows.get(0).reason()).isEqualTo("运营调价");
            assertThat(rows.get(1).status()).isEqualTo("RETIRED");
            assertThat(rows.get(1).reason()).isNull();
            assertThat(controller.history(null).getData()).hasSize(3);
        }
    }

    @Test void boundsHistoryAndCanConfigureBothCloneSubjects() {
        prices.publish("avatar_clone", 8_000_000, 1, "88");
        prices.publish("voice_clone", 5_000_000, 1, "88");
        assertThat(prices.resolve("avatar_clone").priceMicros()).isEqualTo(8_000_000);
        assertThat(prices.resolve("voice_clone").priceMicros()).isEqualTo(5_000_000);
        jdbc.execute("INSERT INTO org_point_subject_price(id,subject,unit,price_micros,min_charge,version,status,operator,effective_at) "
                + "SELECT 4000000+n,'copy','CALL',1000000,1,n,'RETIRED','88',now()+n*interval '1 second' "
                + "FROM generate_series(1,220) AS n");
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(88L);
            assertThat(controller.history("copy").getData()).hasSize(200);
            assertThat(controller.history("avatar_clone").getData()).singleElement()
                    .satisfies(row -> assertThat(row.priceMicros()).isEqualTo(8_000_000));
        }
    }
}
