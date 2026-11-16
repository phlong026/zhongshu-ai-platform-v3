package cn.iocoder.yudao.module.identity;

import cn.iocoder.yudao.module.identity.accesscode.AccessCodeService;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 授权码四态计数合同测试（真实 PostgreSQL）：
 * UNUSED/BOUND/EXPIRED/DISABLED 互斥、合计等于全量、EXPIRED 由 ACTIVE 且过期时间推导
 * （statusCounts 只做表上 SQL 统计，cipher/deliveryPort 不参与，传 null 即可）。
 * 断言用插入前后增量，避免对执行顺序与共享容器内既有数据的依赖。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccessCodeStatusCountsContractTest {

    @Container
    static final PostgreSQLContainer<?> PG = cn.iocoder.yudao.module.platform.test.TestPostgres.database(
            DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("zhongshu_design")
            .withUsername("zhongshu")
            .withPassword("zhongshu");

    private JdbcTemplate jdbc;
    private AccessCodeService accessCodeService;

    @BeforeAll
    void setUp() {
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriverClass(org.postgresql.Driver.class);
        ds.setUrl(PG.getJdbcUrl());
        ds.setUsername(PG.getUsername());
        ds.setPassword(PG.getPassword());
        DataSource dataSource = ds;
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/identity").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        accessCodeService = new AccessCodeService(dataSource,
                new DataSourceTransactionManager(dataSource), null, null);
    }

    private void insertCode(String hash, String status, String expiresAtSql) {
        jdbc.update("INSERT INTO design_access_code (id, batch_id, code_hash, pepper_version, code_mask, "
                        + "status, expires_at) VALUES (?, ?, ?, 'v1', ?, ?, "
                        + (expiresAtSql == null ? "NULL" : expiresAtSql) + ")",
                IdWorker.getId(), 1L, hash, "ZSZJ-****-" + hash.substring(0, 4), status);
    }

    @Test
    void 四态互斥且合计等于全量_过期由ACTIVE推导() {
        Map<String, Long> before = accessCodeService.statusCounts();
        insertCode("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "ACTIVE", null);
        insertCode("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "ACTIVE", "now() - interval '1 hour'");
        insertCode("cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc", "CONSUMED", null);
        insertCode("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd", "DISABLED", null);

        Map<String, Long> after = accessCodeService.statusCounts();

        assertThat(after.getOrDefault("UNUSED", 0L) - before.getOrDefault("UNUSED", 0L)).isEqualTo(1L);
        assertThat(after.getOrDefault("EXPIRED", 0L) - before.getOrDefault("EXPIRED", 0L)).isEqualTo(1L);
        assertThat(after.getOrDefault("BOUND", 0L) - before.getOrDefault("BOUND", 0L)).isEqualTo(1L);
        assertThat(after.getOrDefault("DISABLED", 0L) - before.getOrDefault("DISABLED", 0L)).isEqualTo(1L);
        // 四类互斥：合计恒等于全量
        long total = after.values().stream().mapToLong(Long::longValue).sum();
        Long all = jdbc.queryForObject("SELECT count(*) FROM design_access_code WHERE deleted = FALSE", Long.class);
        assertThat(total).isEqualTo(all);
    }

    @Test
    void 未过期但设置了有效期的码仍计为未使用() {
        Map<String, Long> before = accessCodeService.statusCounts();
        insertCode("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", "ACTIVE", "now() + interval '1 hour'");
        insertCode("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", "ACTIVE", "now() - interval '2 hours'");

        Map<String, Long> after = accessCodeService.statusCounts();

        assertThat(after.getOrDefault("UNUSED", 0L) - before.getOrDefault("UNUSED", 0L)).isEqualTo(1L);
        assertThat(after.getOrDefault("EXPIRED", 0L) - before.getOrDefault("EXPIRED", 0L)).isEqualTo(1L);
    }
    @Test
    void companyConsumptionAddsAccountsOnceAndExcludesNonUsageAndAmbiguousHistory() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS design_point_ledger (id bigint, user_id bigint, type varchar(40), delta bigint, biz_type varchar(64), biz_id varchar(64), create_time timestamptz DEFAULT now(), deleted boolean DEFAULT false, tenant_id bigint DEFAULT 0)");
        jdbc.update("DELETE FROM access_code_redemption");
        jdbc.update("DELETE FROM access_code_binding_audit");
        jdbc.update("DELETE FROM design_point_ledger");
        jdbc.update("INSERT INTO access_code_redemption(id,code_id,account_id,appid,openid,company_name,create_time) VALUES (1,1,901,'stub','a','甲','2026-01-01'),(2,2,901,'stub','a','甲','2026-01-02'),(3,3,902,'stub','b','甲','2026-01-01')");
        jdbc.update("INSERT INTO design_point_ledger(id,user_id,type,delta,biz_type,biz_id,create_time) VALUES (1,901,'FLAT_GENERATION_DEBIT',-100,'ai_job','1','2026-02-01'),(2,902,'ELEVATION_GENERATION_DEBIT',-200,'ai_job','2','2026-02-01')");
        var controller = new cn.iocoder.yudao.module.identity.controller.admin.AccessCodeBatchAdminController();
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "dataSource", jdbc.getDataSource());
        jdbc.update("UPDATE access_code_redemption SET update_time=create_time");
        var first = controller.companySummary().getData();
        assertThat(first).hasSize(1);
        assertThat(first.get(0)).containsEntry("netPointsConsumed", 300L).containsEntry("codeCount", 3L).containsEntry("accountCount", 2L);
        jdbc.update("INSERT INTO design_point_ledger(id,user_id,type,delta,biz_type,biz_id,create_time) VALUES (3,901,'TASK_SETTLEMENT_REFUND',40,'ai_job','1','2026-04-01'),(4,901,'MANUAL_DEBIT',-500,'MANUAL_ADJUSTMENT','4','2026-02-01'),(5,902,'RECHARGE_BASE_REVERSAL',-800,'refund_order','5','2026-02-01')");
        jdbc.update("UPDATE access_code_redemption SET company_name='乙',update_time='2026-03-01' WHERE account_id=901");
        jdbc.update("INSERT INTO access_code_binding_audit(id,code_id,actor_id,old_company_name,new_company_name,create_time) VALUES (1,1,'stub','甲','乙','2026-03-01'),(2,2,'stub','甲','乙','2026-03-01')");
        var result = controller.companySummary().getData();
        assertThat(result.stream().filter(r -> "甲".equals(r.get("company"))).findFirst().orElseThrow()).containsEntry("netPointsConsumed",260L);
        assertThat(result.stream().filter(r -> "乙".equals(r.get("company"))).findFirst().orElseThrow()).containsEntry("netPointsConsumed",0L);
        jdbc.update("INSERT INTO access_code_redemption(id,code_id,account_id,appid,openid,company_name,create_time) VALUES (4,4,902,'stub','b','丙','2026-01-01')");
        jdbc.update("UPDATE access_code_redemption SET update_time=create_time WHERE id=4");
        var ambiguous = controller.companySummary().getData();
        assertThat(ambiguous.stream().filter(r -> "归属待核实".equals(r.get("company"))).findFirst().orElseThrow()).containsEntry("netPointsConsumed",200L);
        // Pre-audit or missing audit history is not reconstructed from today's binding.
        jdbc.update("UPDATE access_code_redemption SET company_name='丁',update_time='2026-04-01' WHERE account_id=901");
        jdbc.update("INSERT INTO design_point_ledger(id,user_id,type,delta,biz_type,biz_id,create_time) VALUES (6,901,'AI_PROMPT_DEBIT',-10,'ai_job','6','2026-03-15'),(7,901,'BUDGET_ESTIMATE_DEBIT',-30,'budget','7','2025-12-01')");
        var unknown = controller.companySummary().getData();
        assertThat(unknown.stream().filter(r -> "归属待核实".equals(r.get("company"))).findFirst().orElseThrow()).containsEntry("netPointsConsumed",240L);

    }

}
