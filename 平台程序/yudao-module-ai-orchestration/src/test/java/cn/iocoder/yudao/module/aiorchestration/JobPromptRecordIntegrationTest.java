package cn.iocoder.yudao.module.aiorchestration;

import cn.iocoder.yudao.module.aiorchestration.controller.admin.AiJobAdminController;
import cn.iocoder.yudao.module.aiorchestration.controller.app.AppAiJobController;
import cn.iocoder.yudao.module.aiorchestration.job.AiJobOrchestrationService;
import cn.iocoder.yudao.module.aiorchestration.job.AiJobQueryService;
import cn.iocoder.yudao.module.aiorchestration.job.AiJobSettlementService;
import cn.iocoder.yudao.module.commerce.points.PointAccountService;
import cn.iocoder.yudao.module.commerce.points.PointLedgerPortAdapter;
import cn.iocoder.yudao.module.commerce.pricing.PriceRuleService;
import cn.iocoder.yudao.module.commerce.pricing.PricingPortAdapter;
import cn.iocoder.yudao.module.commerce.pricing.UsagePointPriceService;
import cn.iocoder.yudao.module.infra.zhongshu.api.PricingPort;
import cn.iocoder.yudao.module.infra.zhongshu.api.UsagePricingPort;
import cn.iocoder.yudao.module.infra.zhongshu.event.JdbcReliableEventPort;
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

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 需求四二期：把 plan 阶段产出的最终提示词与所用模型按「尝试」落库，供后台详情复盘。
 * 提示词是用户补充需求的衍生文本，只给运营详情看：不进列表、不进小程序视图。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JobPromptRecordIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> PG = cn.iocoder.yudao.module.platform.test.TestPostgres.database(DockerImageName.parse("postgres:17-alpine"));

    private JdbcTemplate jdbc;
    private AiJobOrchestrationService orch;
    private AiJobSettlementService service;
    private AiJobQueryService query;
    private PricingPort.PriceConfirmation price;
    private PointAccountService points;
    private UsagePointPriceService usage;

    @BeforeAll
    void setUp() {
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriverClass(org.postgresql.Driver.class);
        ds.setUrl(PG.getJdbcUrl());
        ds.setUsername(PG.getUsername());
        ds.setPassword(PG.getPassword());
        Flyway.configure().dataSource(ds)
                .locations("classpath:db/migration/platform", "classpath:db/migration/commerce",
                        "classpath:db/migration/ai-orchestration")
                .load().migrate();
        jdbc = new JdbcTemplate(ds);
        var tx = new DataSourceTransactionManager(ds);
        points = new PointAccountService(ds, tx);
        var ledger = new PointLedgerPortAdapter(points);
        var events = new JdbcReliableEventPort(ds);
        orch = new AiJobOrchestrationService(ds, tx, events, null, null);
        usage = new UsagePointPriceService(ds, tx, ledger);
        service = new AiJobSettlementService(ds, tx, new PricingPortAdapter(new PriceRuleService(ds)), ledger, orch, events, usage);
        query = new AiJobQueryService(ds);
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE ai_job,ai_job_attempt,ai_job_result,ai_job_settlement,ai_task_charge,service_usage_charge,"
                + "service_usage_price_rule,generation_price_rule,design_point_account,design_point_ledger,outbox_event");
        long ruleId = new PriceRuleService(jdbc.getDataSource()).createRule("FLAT", 10, 1, 4, Instant.now().minusSeconds(2), null);
        usage.createRule("AI_PROMPT", 7, Instant.now().minusSeconds(1), null, "fixture");
        var quote = usage.quote("AI_PROMPT");
        price = new PricingPort.PriceConfirmation(String.valueOf(ruleId), 1,
                new UsagePricingPort.Confirmation("AI_PROMPT", String.valueOf(quote.ruleId()), quote.ruleVersion()));
        points.credit(901, "RECHARGE_BASE_CREDIT", 1000, "fixture", "fixture", "fixture-credit", null, "fixture");
    }

    private long pricedJob() {
        return service.createJobWithCharge(901, "FLAT", 1, "fixture-job", "91", price);
    }

    @Test
    void recordsPromptAndModelOfTheCurrentAttemptEvenForBillingExemptLegacyTask() {
        // 历史任务没有提示词计价（pricing_version=0），记录提示词不能依赖计费分支
        var legacy = new AiJobSettlementService(jdbc.getDataSource(), new DataSourceTransactionManager(jdbc.getDataSource()),
                new PricingPortAdapter(new PriceRuleService(jdbc.getDataSource())), new PointLedgerPortAdapter(points), orch,
                new JdbcReliableEventPort(jdbc.getDataSource()));
        long id = legacy.createJobWithCharge(901, "FLAT", 1, "legacy", "91",
                new PricingPort.PriceConfirmation(price.ruleId(), price.ruleVersion()));
        var claim = orch.claim("w1", 1, 60, "fake").get(0);

        assertThat(legacy.recordPromptText(id, claim.attemptNo(), claim.fencingToken(), "两层别墅，坡屋顶，白墙灰瓦", "gpt-fixture")).isTrue();

        var record = query.promptRecord(id).orElseThrow();
        assertThat(record.promptText()).isEqualTo("两层别墅，坡屋顶，白墙灰瓦");
        assertThat(record.providerModel()).isEqualTo("gpt-fixture");
    }

    @Test
    void latestRecordedAttemptWinsAndStaleHolderCannotOverwrite() {
        long id = pricedJob();
        var first = orch.claim("w-first", 1, 60, "fake").get(0);
        assertThat(service.recordPromptText(id, first.attemptNo(), first.fencingToken(), "第一次提示词", "m-1")).isTrue();

        jdbc.update("UPDATE ai_job SET claim_expires_at=now()-interval '1 second' WHERE id=?", id);
        var next = orch.claim("w-next", 1, 60, "fake").get(0);

        // 租约已被新持有者接管：旧持有者的迟到回写必须被拒，且不得改动已有记录
        assertThatThrownBy(() -> service.recordPromptText(id, first.attemptNo(), first.fencingToken(), "迟到的提示词", "m-late"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(query.promptRecord(id).orElseThrow().promptText()).isEqualTo("第一次提示词");

        assertThat(service.recordPromptText(id, next.attemptNo(), next.fencingToken(), "第二次提示词", "m-2")).isTrue();
        var record = query.promptRecord(id).orElseThrow();
        assertThat(record.promptText()).as("多次尝试时展示最近一次").isEqualTo("第二次提示词");
        assertThat(record.providerModel()).isEqualTo("m-2");
        assertThat(service.recordPromptText(id, next.attemptNo(), next.fencingToken(), "第二次提示词", "m-2"))
                .as("重放幂等").isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job_attempt WHERE job_id=? AND prompt_text IS NOT NULL",
                Integer.class, id)).isEqualTo(2);
    }

    @Test
    void ignoresBlankOversizedPromptAndDropsOverlongModelInsteadOfFailingTheJob() {
        long id = pricedJob();
        var claim = orch.claim("w1", 1, 60, "fake").get(0);

        assertThat(service.recordPromptText(id, claim.attemptNo(), claim.fencingToken(), "   ", "m")).isFalse();
        assertThat(service.recordPromptText(id, claim.attemptNo(), claim.fencingToken(), null, "m")).isFalse();
        assertThat(service.recordPromptText(id, claim.attemptNo(), claim.fencingToken(), "x".repeat(8001), "m")).isFalse();
        assertThat(query.promptRecord(id)).as("无效输入不落库").isEmpty();

        assertThat(service.recordPromptText(id, claim.attemptNo(), claim.fencingToken(), "x".repeat(8000), "m".repeat(65))).isTrue();
        var record = query.promptRecord(id).orElseThrow();
        assertThat(record.promptText()).hasSize(8000);
        assertThat(record.providerModel()).as("过长的模型名不落库，但不影响提示词").isNull();
    }

    @Test
    void promptTextOnlyReachesTheAdminDetailNeverTheCustomerOrListViews() {
        long id = pricedJob();
        var claim = orch.claim("w1", 1, 60, "fake").get(0);
        service.recordPromptText(id, claim.attemptNo(), claim.fencingToken(), "含用户补充需求的衍生文本", "m");

        var snapshot = query.getJob(id).orElseThrow();
        var customer = AppAiJobController.toVo(snapshot);
        var adminRow = AiJobAdminController.toAdminVo(snapshot);
        assertThat(customer.getPromptText()).isNull();
        assertThat(customer.getProviderModel()).isNull();
        assertThat(adminRow.getPromptText()).as("列表行不带提示词").isNull();
        assertThat(adminRow.getProviderModel()).isNull();
    }
}
