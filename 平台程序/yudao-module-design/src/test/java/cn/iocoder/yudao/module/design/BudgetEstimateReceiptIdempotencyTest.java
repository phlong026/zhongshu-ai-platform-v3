package cn.iocoder.yudao.module.design;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.commerce.points.PointAccountService;
import cn.iocoder.yudao.module.commerce.points.PointLedgerPortAdapter;
import cn.iocoder.yudao.module.commerce.pricing.UsagePointPriceService;
import cn.iocoder.yudao.module.design.budget.BudgetService;
import cn.iocoder.yudao.module.design.project.DesignProjectService;
import cn.iocoder.yudao.module.infra.zhongshu.api.UsagePricingPort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.RESOURCE_FORBIDDEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class BudgetEstimateReceiptIdempotencyTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = cn.iocoder.yudao.module.platform.test.TestPostgres.database("postgres:17-alpine");

    private BudgetService budgets;
    private JdbcTemplate jdbc;
    private PointAccountService points;
    private UsagePointPriceService usage;
    private UsagePricingPort.Confirmation confirmation;
    private long project;
    private long otherProject;
    private long foreignProject;
    private long priceRule;

    @BeforeEach
    void setUp() {
        var dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.postgresql.Driver.class);
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        var flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(false)
                .locations("classpath:db/migration/platform", "classpath:db/migration/design",
                        "classpath:db/migration/commerce", "classpath:db/migration/ai-orchestration").load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        var transactions = new DataSourceTransactionManager(dataSource);
        var projects = new DesignProjectService(dataSource, transactions, null, null, null, null,
                org.mockito.Mockito.mock(cn.iocoder.yudao.module.infra.zhongshu.api.AccountStatePort.class));
        points = new PointAccountService(dataSource, transactions);
        usage = new UsagePointPriceService(dataSource, transactions, new PointLedgerPortAdapter(points));
        usage.createRule("BUDGET_ESTIMATE", 7, Instant.now().minusSeconds(1), null, "fixture");
        var quote = usage.quote("BUDGET_ESTIMATE");
        priceRule = quote.ruleId();
        confirmation = new UsagePricingPort.Confirmation(quote.product(),
                String.valueOf(quote.ruleId()), quote.ruleVersion());
        budgets = new BudgetService(dataSource, projects, transactions, usage);
        budgets.createRuleVersion("VAR1", "BRICK", "A", 100, 200, Instant.now().minusSeconds(1));
        project = projects.createProject(7, "SELF_UPLOAD", null, null, Map.of());
        otherProject = projects.createProject(7, "SELF_UPLOAD", null, null, Map.of());
        foreignProject = projects.createProject(8, "SELF_UPLOAD", null, null, Map.of());
        points.credit(7, "RECHARGE_BASE_CREDIT", 100, "fixture", "7", "fixture-credit7", null, "fixture");
    }

    @ParameterizedTest(name = "same key rejects changed {0}")
    @ValueSource(strings = {"buildingArea", "projectId", "region", "structure", "material", "resultVersionId"})
    void sameKeyRejectsChangedIntent(String field) {
        var first = create("same-key");
        assertThat(first.replayed()).isFalse();
        assertThatThrownBy(() -> budgets.createEstimateReceipt(7,
                field.equals("projectId") ? otherProject : project,
                field.equals("region") ? "VAR2" : "VAR1",
                field.equals("structure") ? "FRAME" : "BRICK",
                field.equals("material") ? "B" : "A",
                field.equals("buildingArea") ? 121 : 120,
                field.equals("resultVersionId") ? 999L : null,
                null, "same-key"))
                .isInstanceOfSatisfying(ServiceException.class,
                        error -> assertThat(error.getCode()).isEqualTo(409));
        assertChargedOnce();
    }

    @Test
    void replayStillChecksRequestedProjectOwner() {
        create("same-key");
        assertThatThrownBy(() -> budgets.createEstimateReceipt(7, foreignProject,
                "VAR1", "BRICK", "A", 120, null, null, "same-key"))
                .isInstanceOfSatisfying(ServiceException.class,
                        error -> assertThat(error.getCode()).isEqualTo(RESOURCE_FORBIDDEN.getCode()));
        assertChargedOnce();
    }

    @Test
    void unchangedReplaySurvivesPriceRetirementWithoutChargingAgain() {
        var first = create("same-key");
        usage.retireRule(priceRule, "fixture");
        var replay = budgets.createEstimateReceipt(7, project, "VAR1", "BRICK", "A", 120,
                null, null, "same-key");
        assertThat(replay).isEqualTo(new BudgetService.EstimateReceipt(first.estimateId(),
                first.totalLowCents(), first.totalHighCents(), first.ruleVersionId(), true));
        assertChargedOnce();
    }

    @Test
    void concurrentFirstRequestsReturnExactlyOneFreshReceipt() throws Exception {
        var pool = Executors.newFixedThreadPool(4);
        try {
            var calls = new ArrayList<Callable<BudgetService.EstimateReceipt>>();
            for (int i = 0; i < 8; i++) {
                calls.add(() -> create("concurrent-key"));
            }
            var results = new ArrayList<BudgetService.EstimateReceipt>();
            for (var future : pool.invokeAll(calls, 30, TimeUnit.SECONDS)) {
                results.add(future.get(1, TimeUnit.SECONDS));
            }
            assertThat(results.stream().map(BudgetService.EstimateReceipt::estimateId).distinct()).hasSize(1);
            assertThat(results.stream().filter(receipt -> !receipt.replayed())).hasSize(1);
            assertChargedOnce();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void serviceBudgetProviderPersistsCostSubjectAndReplaysWithoutConsumerCharge() {
        var source=jdbc.getDataSource();
        var transactions=new DataSourceTransactionManager(source);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        var costs=new cn.iocoder.yudao.module.aiorchestration.billing.PlatformCostService(source,transactions);
        costs.setServiceAuthorization(subject -> {
            if (!"platform-b".equals(subject.callerAppId()) || subject.serviceAccountId()!=7 || subject.orgId()!=21) {
                throw new IllegalStateException("Fixture service mapping mismatch");
            }
        });
        jdbc.update("INSERT INTO platform_cost_price(product,version,unit,prompt_micros,image_micros,usage_micros) VALUES('BUDGET_ESTIMATE',1,'MICRO_CNY',0,0,23)");
        var billing=new cn.iocoder.yudao.module.design.capability.billing.DurableServiceDesignBilling(costs,
                org.mockito.Mockito.mock(DesignProjectService.class),budgets,
                org.mockito.Mockito.mock(cn.iocoder.yudao.module.design.ai.NotePolishService.class));
        var scope=new cn.iocoder.yudao.module.design.capability.ServiceDesignBillingPort.ServiceScope("platform-b",21,31,7,
                "service-budget","request-budget",new cn.iocoder.yudao.module.franchise.api.DesignServicePort.Actor(51,41,"owner"));
        var payload=new cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.Budget(project,
                new cn.iocoder.yudao.module.franchise.api.DesignServicePort.BudgetEstimateCreate("VAR1","BRICK","A",120,null,"request-budget"));
        var operation=cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.Operation.CREATE_BUDGET_ESTIMATE;
        var prepared=billing.prepare(scope,operation,payload);
        var first=tx.execute(status -> billing.execute(scope,operation,payload,prepared));
        usage.retireRule(priceRule,"fixture");
        jdbc.update("UPDATE platform_cost_price SET active=FALSE");
        var replay=tx.execute(status -> billing.execute(scope,operation,payload,prepared));
        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM budget_estimate WHERE billing_mode='SERVICE_COST' AND cost_business_id='service-budget'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT charged_micros FROM platform_cost_business WHERE id='service-budget'",Long.class)).isEqualTo(23);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_cost_ledger WHERE event_type='USAGE_FINAL'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM service_usage_charge",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_point_ledger",Integer.class)).isEqualTo(1);
        assertThat(points.findAccount(7).orElseThrow().availablePoints()).isEqualTo(100);
    }

    @Test
    void missingBudgetRuleRejectsPreparedServiceCostWithoutChargingConsumer() {
        var source=jdbc.getDataSource();
        var transactions=new DataSourceTransactionManager(source);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        var costs=new cn.iocoder.yudao.module.aiorchestration.billing.PlatformCostService(source,transactions);
        costs.setServiceAuthorization(subject -> assertThat(subject.serviceAccountId()).isEqualTo(7));
        jdbc.update("INSERT INTO platform_cost_price(product,version,unit,prompt_micros,image_micros,usage_micros) VALUES('BUDGET_ESTIMATE',1,'MICRO_CNY',0,0,23)");
        var billing=new cn.iocoder.yudao.module.design.capability.billing.DurableServiceDesignBilling(costs,
                org.mockito.Mockito.mock(DesignProjectService.class),budgets,
                org.mockito.Mockito.mock(cn.iocoder.yudao.module.design.ai.NotePolishService.class));
        var scope=new cn.iocoder.yudao.module.design.capability.ServiceDesignBillingPort.ServiceScope("platform-b",21,31,7,
                "budget-no-rule","request-no-rule",new cn.iocoder.yudao.module.franchise.api.DesignServicePort.Actor(51,41,"owner"));
        var payload=new cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.Budget(project,
                new cn.iocoder.yudao.module.franchise.api.DesignServicePort.BudgetEstimateCreate("NO_RULE","BRICK","A",120,null,"request-no-rule"));
        var operation=cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.Operation.CREATE_BUDGET_ESTIMATE;
        var prepared=billing.prepare(scope,operation,payload);
        assertThat(jdbc.queryForObject("SELECT reserved_micros FROM platform_cost_business WHERE id='budget-no-rule'",Long.class)).isEqualTo(23);
        var failure=org.junit.jupiter.api.Assertions.assertThrows(ServiceException.class,
                () -> tx.execute(status -> billing.execute(scope,operation,payload,prepared)));
        assertThat(failure.getCode()).isEqualTo(1_071_000_002);
        var rejection=tx.execute(status -> billing.resolveFailure(scope,operation,payload,failure)).orElseThrow();
        assertThat(rejection.code()).isEqualTo(1_071_000_002);
        var replay=tx.execute(status -> billing.resolveFailure(scope,operation,payload,failure)).orElseThrow();
        assertThat(replay).isEqualTo(rejection);
        assertThat(jdbc.queryForObject("SELECT state FROM platform_cost_business WHERE id='budget-no-rule'",String.class)).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT charged_micros FROM platform_cost_business WHERE id='budget-no-rule'",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT refunded_micros FROM platform_cost_business WHERE id='budget-no-rule'",Long.class)).isEqualTo(23);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_cost_ledger WHERE event_type='PREPARATION_REJECTED'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM budget_estimate",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM service_usage_charge",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_point_ledger",Integer.class)).isEqualTo(1);
        assertThat(points.findAccount(7).orElseThrow().availablePoints()).isEqualTo(100);
        assertThat(points.findAccount(7).orElseThrow().reservedPoints()).isZero();
    }

    @Test
    void revokedServicePolishRecoveryNeverDispatchesButCanSettleAlreadyReceivedResult() {
        var source=jdbc.getDataSource();
        var transactions=new DataSourceTransactionManager(source);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        var costs=new cn.iocoder.yudao.module.aiorchestration.billing.PlatformCostService(source,transactions);
        var active=new java.util.concurrent.atomic.AtomicBoolean(true);
        costs.setServiceAuthorization(subject -> { if (!active.get()) throw new IllegalStateException("revoked"); });
        jdbc.update("INSERT INTO platform_cost_price(product,version,unit,prompt_micros,image_micros,usage_micros) VALUES('NOTE_POLISH',1,'MICRO_CNY',0,0,23)");
        var notes=org.mockito.Mockito.mock(cn.iocoder.yudao.module.design.ai.NotePolishService.class);
        var billing=new cn.iocoder.yudao.module.design.capability.billing.DurableServiceDesignBilling(costs,
                org.mockito.Mockito.mock(DesignProjectService.class),budgets,notes);
        var actor=new cn.iocoder.yudao.module.franchise.api.DesignServicePort.Actor(51,41,"owner");
        var operation=cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.Operation.NOTE_POLISH;
        var payload=new cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.Note("老人房在一楼","FLAT");
        var missing=new cn.iocoder.yudao.module.design.capability.ServiceDesignBillingPort.ServiceScope("platform-b",21,31,7,"missing","request-missing",actor);
        assertThat(billing.recoverPreparation(missing,operation,payload,false)).isEmpty();
        var preparedScope=new cn.iocoder.yudao.module.design.capability.ServiceDesignBillingPort.ServiceScope("platform-b",21,31,7,"prepared-only","request-prepared",actor);
        var invalid=new cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.Note("","FLAT");
        assertThatThrownBy(() -> billing.prepare(preparedScope,operation,invalid)).isInstanceOf(ServiceException.class);
        var scope=new cn.iocoder.yudao.module.design.capability.ServiceDesignBillingPort.ServiceScope("platform-b",21,31,7,"polish-recovery","request-polish",actor);
        org.mockito.Mockito.when(notes.polishForService(7,payload.note(),payload.line(),"svc:polish-recovery"))
                .thenThrow(new ServiceException(502,"uncertain"));
        assertThatThrownBy(() -> billing.prepare(scope,operation,payload)).isInstanceOf(ServiceException.class);
        active.set(false);
        assertThat(billing.recoverPreparation(preparedScope,operation,invalid,false)).isEmpty();
        org.mockito.Mockito.when(notes.serviceReceipt(7,"svc:polish-recovery"))
                .thenReturn(new cn.iocoder.yudao.module.design.ai.NotePolishService.ServiceReceipt("NOT_FOUND",null,null));
        assertThat(billing.recoverPreparation(scope,operation,payload,false)).isEmpty();
        org.mockito.Mockito.when(notes.serviceReceipt(7,"svc:polish-recovery"))
                .thenReturn(new cn.iocoder.yudao.module.design.ai.NotePolishService.ServiceReceipt("SUCCEEDED","一层设老人房",
                        cn.iocoder.yudao.module.design.ai.NotePolishService.serviceRequestHash(payload.note(),payload.line())));
        var prepared=billing.recoverPreparation(scope,operation,payload,false).orElseThrow();
        String first=tx.execute(status -> billing.completeRecoveredPolish(scope,payload,prepared));
        String replay=tx.execute(status -> billing.completeRecoveredPolish(scope,payload,prepared));
        assertThat(first).isEqualTo("一层设老人房");
        assertThat(replay).isEqualTo(first);
        org.mockito.Mockito.verify(notes,org.mockito.Mockito.times(1)).polishForService(7,payload.note(),payload.line(),"svc:polish-recovery");
        org.mockito.Mockito.verify(notes,org.mockito.Mockito.times(2)).serviceReceipt(7,"svc:polish-recovery");
        assertThat(jdbc.queryForObject("SELECT charged_micros FROM platform_cost_business WHERE id='polish-recovery'",Long.class)).isEqualTo(23);
        assertThat(jdbc.queryForObject("SELECT state FROM platform_cost_business WHERE id='prepared-only'",String.class)).isEqualTo("PREPARED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_cost_business WHERE id='missing'",Integer.class)).isZero();
        assertThat(points.findAccount(7).orElseThrow().availablePoints()).isEqualTo(100);
    }

    private BudgetService.EstimateReceipt create(String key) {
        return budgets.createEstimateReceipt(7, project, "VAR1", "BRICK", "A", 120,
                null, confirmation, key);
    }

    private void assertChargedOnce() {
        assertThat(points.findAccount(7).orElseThrow().availablePoints()).isEqualTo(93);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM budget_estimate", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM service_usage_charge", Integer.class)).isEqualTo(1);
    }
}
