package cn.iocoder.yudao.module.commerce.orgpoint;

import cn.iocoder.yudao.module.franchise.api.OrgPointPort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 C 设计域接入验收（24 号 D6 v2 §2.1/§2.3-A）：计费归属绑定 + 按业务引用反查 + 图片终态结算。
 * 覆盖：quoteCredits 与 reserve 同源、binding 幂等、findOperationByRef、ACCEPTED=0 全退 / 部分退差 / 全交付不退。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DesignBillingBindingIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("zhongshu_design")
            .withUsername("zhongshu")
            .withPassword("zhongshu");

    private static final long ORG = 5001L;
    private static final long USER = 7L;

    private JdbcTemplate jdbc;
    private OrgPointService service;

    @BeforeAll
    void setUp() {
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriverClass(org.postgresql.Driver.class);
        ds.setUrl(PG.getJdbcUrl());
        ds.setUsername(PG.getUsername());
        ds.setPassword(PG.getPassword());
        DataSource dataSource = ds;
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration/platform", "classpath:db/migration/commerce")
                .load().migrate();
        service = new OrgPointService(dataSource, new DataSourceTransactionManager(dataSource), m -> 0L);
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE org_point_ledger, org_point_account, org_member_quota, "
                + "org_member_permission, design_billing_binding");
        service.ensurePool(ORG);
        service.adjust(ORG, 10_000, "adjust:design-base", "测试充值", 1L);
    }

    private long reserveJob(long jobId, String subject, int count) {
        String bizKey = "design:job:" + jobId + ":" + subject;
        long operationId = service.reserve(new OrgPointPort.ReserveCommand(
                bizKey, ORG, USER, null, subject, count, "ai_job", jobId));
        service.bindOperation("BRIDGE", ORG, USER, null, "ai_job", jobId, subject, operationId, bizKey);
        return operationId;
    }

    @Test
    void quoteMatchesReserveAmount() {
        assertThat(service.quoteCredits("ai_flat", 2)).isEqualTo(4);      // 2 积分/张
        assertThat(service.quoteCredits("ai_elevation", 2)).isEqualTo(6); // 3 积分/张
        assertThat(service.quoteCredits("budget_estimate", 1)).isEqualTo(1);
    }

    @Test
    void bindingIsIdempotentAndFindable() {
        long operationId = reserveJob(9001L, "ai_flat", 2);
        assertThat(service.findOperationByRef("ai_job", 9001L, "ai_flat")).isEqualTo(operationId);
        // 重复绑定不报错、不产生第二行
        service.bindOperation("BRIDGE", ORG, USER, null, "ai_job", 9001L, "ai_flat", operationId,
                "design:job:9001:ai_flat");
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM design_billing_binding WHERE ref_type='ai_job' AND ref_id=9001",
                Integer.class);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void promptReceiptAllowsNullCallId() {
        // 无收据重载（NOT_SENT/UNKNOWN）写 callId=null：表结构必须允许
        service.savePromptReceipt(9001L, 1, null, null, "NOT_SENT", null);
        var receipt = service.findPromptReceipt(9001L, 1);
        assertThat(receipt.outcome()).isEqualTo("NOT_SENT");
        assertThat(receipt.callId()).isNull();
        // 后续带收据升级为 SUCCEEDED
        service.savePromptReceipt(9001L, 1, "plan-9001", "a".repeat(64), "SUCCEEDED", 1001L);
        assertThat(service.findPromptReceipt(9001L, 1).operationId()).isEqualTo(1001L);
    }

    @Test
    void fullDeliveryChargesAll() {
        long op = reserveJob(9002L, "ai_flat", 2); // 预扣 4
        var settlement = service.settle(op, 2);
        assertThat(settlement.refunded()).isZero();
        assertThat(service.findAccount(ORG).balance()).isEqualTo(9996);
    }

    @Test
    void partialDeliveryRefundsDifference() {
        long op = reserveJob(9003L, "ai_elevation", 3); // 预扣 9
        var settlement = service.settle(op, 1);         // 实收 3，退 6
        assertThat(settlement.charged()).isEqualTo(9);
        assertThat(settlement.refunded()).isEqualTo(6);
        assertThat(service.findAccount(ORG).balance()).isEqualTo(9997);
    }

    @Test
    void zeroDeliveryReleasesAll() {
        long op = reserveJob(9004L, "ai_flat", 2); // 预扣 4
        var settlement = service.release(op);
        assertThat(settlement.refunded()).isEqualTo(4);
        assertThat(service.findAccount(ORG).balance()).isEqualTo(10_000);
    }
}
