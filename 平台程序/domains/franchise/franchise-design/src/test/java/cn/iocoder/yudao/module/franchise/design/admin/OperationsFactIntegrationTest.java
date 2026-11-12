package cn.iocoder.yudao.module.franchise.design.admin;

import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import cn.iocoder.yudao.module.platform.wallet.admin.*;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@TestPropertySource(properties = "platform.test.extra-flyway-locations=,classpath:db/migration/platform-wallet,classpath:db/migration/franchise-design-remote")
class OperationsFactIntegrationTest extends PlatformPgIntegrationTest {
    @Autowired DataSource source;
    @Autowired PlatformTransactionManager manager;
    private JdbcTemplate jdbc;
    private OperationsAuditService audit;
    private RechargePlanAdminService plans;
    private DesignPriceAdminService prices;
    private long tenant;
    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(source);
        tenant = IdWorker.getId();
        login(tenant);
        audit = new OperationsAuditService(source);
        plans = new RechargePlanAdminService(source, manager, audit);
        prices = new DesignPriceAdminService(source, manager, audit);
    }
    @AfterEach void clear() { TenantContextHolder.clear(); SecurityContextHolder.clearContext(); }
    private void login(long id) {
        TenantContextHolder.setTenantId(id);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser().setId(99L).setTenantId(id).setUserType(2), null, List.of()));
    }
    private RechargePlanAdminService.Change plan(long version, long amount) {
        return new RechargePlanAdminService.Change(version, "真实测试套餐", amount, 10, 2, "ACTIVE", 0, "test-product", "配置验证");
    }
    private DesignPriceAdminService.Change price(long version, long points) {
        return new DesignPriceAdminService.Change(version, "NOTE_POLISH", "", 0, 0, points, 0, true, "调整报价");
    }
    @Test void configurationAndReviewsNeverRewriteOrderFacts() {
        var first = plans.create(plan(0, 199));
        long org = IdWorker.getId(), wallet = IdWorker.getId(), order = IdWorker.getId(), anomaly = IdWorker.getId();
        jdbc.update("INSERT INTO pf_org_type(code,domain,name) VALUES('store','franchise','门店') ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO pf_org_unit(dept_id,kind,org_id,org_type) VALUES(?,'ORG',?,'store')", org, org);
        jdbc.update("INSERT INTO pf_wallet(id,tenant_id,org_id) VALUES(?,?,?)", wallet, tenant, org);
        jdbc.update("""
            INSERT INTO pf_recharge_order(id,tenant_id,wallet_id,org_id,actor_id,post_id,binding_id,order_no,plan_id,
              plan_snapshot,price_fen,base_points,bonus_points,payer_appid,payer_openid,channel,offer_id,environment,idempotency_key,request_hash)
            VALUES(?,?,?,?,99,1,1,?,?,?::jsonb,199,10,2,'test-app','test-payer','TEST','test-offer',1,?,?)
            """, order, tenant, wallet, org, "PFW" + order, Long.valueOf(first.id()),
                "{\"version\":1,\"priceFen\":199,\"basePoints\":10,\"bonusPoints\":2}", "intent-" + order, "0".repeat(64));
        jdbc.update("INSERT INTO pf_payment_anomaly_audit(id,tenant_id,wallet_id,order_id,anomaly_type,detail) VALUES(?,?,?,?,'MISMATCH','待核对')",
                anomaly, tenant, wallet, order);
        String orderBefore = jdbc.queryForObject("SELECT to_jsonb(o)::text FROM pf_recharge_order o WHERE id=?", String.class, order);
        String anomalyBefore = jdbc.queryForObject("SELECT to_jsonb(a)::text FROM pf_payment_anomaly_audit a WHERE id=?", String.class, anomaly);
        plans.update(first.id(), plan(1, 399));
        String review = audit.review(Long.toString(anomaly), "已核对，不修改支付与积分事实");
        assertThat(audit.reviews(Long.toString(anomaly), 1, 20).getList()).singleElement()
                .satisfies(row -> assertThat(row.id()).isEqualTo(review));
        assertThat(jdbc.queryForObject("SELECT to_jsonb(o)::text FROM pf_recharge_order o WHERE id=?", String.class, order)).isEqualTo(orderBefore);
        assertThat(jdbc.queryForObject("SELECT to_jsonb(a)::text FROM pf_payment_anomaly_audit a WHERE id=?", String.class, anomaly)).isEqualTo(anomalyBefore);
        assertThatThrownBy(() -> jdbc.update("UPDATE pf_ops_anomaly_review SET note='overwrite' WHERE id=?", Long.valueOf(review)))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM pf_ops_anomaly_review WHERE id=?", Long.valueOf(review)))
                .hasMessageContaining("append-only");
        login(tenant + 1);
        assertThatThrownBy(() -> audit.review(Long.toString(anomaly), "跨租户核对")).hasMessageContaining("不存在");
        assertThatThrownBy(() -> audit.reviews(Long.toString(anomaly), 1, 20)).hasMessageContaining("不存在");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_ops_anomaly_review WHERE anomaly_id=?", Long.class, anomaly)).isEqualTo(1);
    }
    @Test void designPublishingKeepsExistingCommandAndBillingSnapshots() {
        var first = prices.create(price(0, 5));
        long command = jdbc.queryForObject("""
            INSERT INTO fr_design_remote_command(caller_app_id,tenant_id,org_id,operation,request_key,remote_request_key,
                fingerprint,actor_json,payload_json,state,price_snapshot)
            VALUES('B',?,88,'NOTE_POLISH',?,?,'0000000000000000000000000000000000000000000000000000000000000000',
                '{}','{}','UNKNOWN',?::jsonb) RETURNING command_id
            """, Long.class, tenant, "intent-" + tenant, java.util.UUID.randomUUID().toString(),
                "{\"priceId\":\"" + first.id() + "\",\"version\":1,\"usagePoints\":5}");
        jdbc.update("""
            INSERT INTO fr_design_billing(command_id,tenant_id,org_id,actor_id,actor_post_id,operation,price_id,price_version,
                prompt_points,image_points,usage_points,max_prompt_units,max_image_units,max_usage_units,reserved_points)
            VALUES(?,?,88,99,1,'NOTE_POLISH',?,1,0,0,5,0,0,1,5)
            """, command, tenant, first.id());
        String commandBefore = jdbc.queryForObject("SELECT to_jsonb(c)::text FROM fr_design_remote_command c WHERE command_id=?", String.class, command);
        String billingBefore = jdbc.queryForObject("SELECT to_jsonb(b)::text FROM fr_design_billing b WHERE command_id=?", String.class, command);
        prices.update(first.id(), price(1, 12));
        assertThat(jdbc.queryForObject("SELECT to_jsonb(c)::text FROM fr_design_remote_command c WHERE command_id=?", String.class, command)).isEqualTo(commandBefore);
        assertThat(jdbc.queryForObject("SELECT to_jsonb(b)::text FROM fr_design_billing b WHERE command_id=?", String.class, command)).isEqualTo(billingBefore);
        assertThat(jdbc.queryForObject("SELECT usage_points FROM fr_design_price WHERE tenant_id=? AND active", Long.class, tenant)).isEqualTo(12);
    }
    @Test void auditFailureRestoresActiveDesignPriceAndPreventsNewVersion() {
        var first = prices.create(price(0, 5));
        jdbc.update("""
            INSERT INTO pf_ops_config_audit(id,tenant_id,resource_type,resource_id,resource_version,action,actor_id,reason,before_json,after_json)
            VALUES(?,?,'DESIGN_PRICE',?,2,'UPDATE',99,'冲突测试','{}','{}')
            """, IdWorker.getId(), tenant, first.id());
        assertThatThrownBy(() -> prices.update(first.id(), price(1, 12))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_design_price WHERE tenant_id=?", Long.class, tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT usage_points FROM fr_design_price WHERE tenant_id=? AND active", Long.class, tenant)).isEqualTo(5);
    }
    @Test void undoSucceedsOnlyWithoutAuditOrReviewFacts() throws Exception {
        for (String fact : List.of("empty", "audit", "review")) {
            String schema = "ops_undo_" + IdWorker.getId();
            try (Connection connection = source.getConnection(); var statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA " + schema);
                try {
                    statement.execute("SET search_path TO " + schema);
                    for (String table : List.of("system_menu", "system_role_menu", "pf_wallet", "pf_recharge_order", "pf_point_ledger",
                            "pf_payment_anomaly_audit", "fr_design_price", "fr_design_remote_command")) {
                        statement.execute("CREATE TABLE " + table + " (LIKE public." + table + " INCLUDING ALL)");
                    }
                    statement.execute(sql("db/migration/platform-wallet/V20261111.960__platform_operations_admin.sql"));
                    statement.execute(sql("db/migration/franchise-design-remote/V20261111.961__design_operations_admin.sql"));
                    if (fact.equals("audit")) statement.execute("""
                        INSERT INTO pf_ops_config_audit(id,tenant_id,resource_type,resource_id,resource_version,action,actor_id,reason,before_json,after_json)
                        VALUES(1,1,'DESIGN_PRICE','1',1,'CREATE',99,'保留事实','{}','{}')
                        """);
                    if (fact.equals("review")) {
                        statement.execute("INSERT INTO pf_payment_anomaly_audit(id,tenant_id,wallet_id,order_id,anomaly_type,detail) VALUES(1,1,1,1,'TEST','核对测试')");
                        statement.execute("INSERT INTO pf_ops_anomaly_review(id,tenant_id,anomaly_id,actor_id,note) VALUES(1,1,1,99,'保留核对记录')");
                    }
                    statement.execute(sql("db/migration-undo/franchise-design-remote/V20261111.961__undo_design_operations_admin.sql"));
                    String undo = sql("db/migration-undo/platform-wallet/V20261111.960__undo_platform_operations_admin.sql");
                    if (fact.equals("empty")) {
                        statement.execute(undo);
                        try (var result = statement.executeQuery("SELECT to_regclass('" + schema + ".pf_ops_config_audit')")) {
                            assertThat(result.next()).isTrue(); assertThat(result.getString(1)).isNull();
                        }
                    } else {
                        assertThatThrownBy(() -> statement.execute(undo)).hasMessageContaining("preserve data");
                        statement.execute("ROLLBACK");
                        String table = fact.equals("audit") ? "pf_ops_config_audit" : "pf_ops_anomaly_review";
                        try (var result = statement.executeQuery("SELECT count(*) FROM " + table)) {
                            assertThat(result.next()).isTrue(); assertThat(result.getLong(1)).isEqualTo(1);
                        }
                    }
                } finally {
                    statement.execute("ROLLBACK");
                    statement.execute("SET search_path TO public");
                    statement.execute("DROP SCHEMA " + schema + " CASCADE");
                }
            }
        }
    }
    private String sql(String path) throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing migration: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
