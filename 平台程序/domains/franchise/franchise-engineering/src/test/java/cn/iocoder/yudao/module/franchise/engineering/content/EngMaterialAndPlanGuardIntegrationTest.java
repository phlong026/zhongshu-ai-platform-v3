package cn.iocoder.yudao.module.franchise.engineering.content;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.test.FranchiseFixture.Store;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.command.CommandLogImpl;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED;
import static cn.iocoder.yudao.module.franchise.test.FranchiseAssertions.assertError;

/**
 * M07-T01 材料幂等（I02）+ M07-T03 资产端口引用校验（I04）：
 * 1. 同键同输入两次返回同结果（重试命中合法重放，不再误判键复用——replay/record canonical 统一）；
 * 2. 同键改输入拒绝；仅一条变更记录；
 * 3. 工程凭据资产域侧校验：假 ID/跨组织/未 ACCEPTED 拒绝（validateReference），类型不符拒绝（assetTypeOf）。
 */
@Import({EngProjectService.class, EngMaterialService.class, EngPlanService.class, EngCollectionService.class,
        EngClosureService.class,
        EngExpenseService.class,
        cn.iocoder.yudao.module.franchise.finance.FinPaymentService.class,
        cn.iocoder.yudao.module.franchise.finance.FinWorkbenchService.class,
        cn.iocoder.yudao.module.franchise.finance.FinRefundService.class,
        cn.iocoder.yudao.module.franchise.finance.FinWorkbenchService.class,
        cn.iocoder.yudao.module.franchise.finance.FinProjectAccountingService.class,
        cn.iocoder.yudao.module.franchise.finance.FinProjectAccountingAccess.class,
        FranchiseCommands.class, CommandLogImpl.class,
        EngMaterialAndPlanGuardIntegrationTest.Config.class})
class EngMaterialAndPlanGuardIntegrationTest extends FranchisePgIntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean cn.iocoder.yudao.module.franchise.finance.FinReceiptService receiptService() {
            return org.mockito.Mockito.mock(cn.iocoder.yudao.module.franchise.finance.FinReceiptService.class);
        }
        @Bean cn.iocoder.yudao.module.franchise.api.CommissionQueryPort commissionQueryPort() {
            return org.mockito.Mockito.mock(cn.iocoder.yudao.module.franchise.api.CommissionQueryPort.class);
        }

        /** 资产端口桩：白名单资产=ACCEPTED 本组织；类型可控 */
        @Bean
        cn.iocoder.yudao.module.franchise.api.AssetAccessPort assetAccessPort(JdbcTemplate jdbc) {
            return new cn.iocoder.yudao.module.franchise.api.AssetAccessPort() {
                // R1 P2：属主维度=服务账号（测试约定 serviceAccountId==orgId）——跨组织资产拒之门外
                private boolean existsAccepted(long serviceAccountId, long assetId) {
                    Integer n = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM eng_test_asset WHERE id = ? AND accepted = TRUE AND org_id = ?",
                            Integer.class, assetId, serviceAccountId);
                    return n != null && n > 0;
                }

                @Override
                public cn.iocoder.yudao.module.franchise.api.AssetAccessPort.UploadTicketView createUploadTicket(long serviceAccountId, String assetType,
                                                                             String mimeType, long sizeBytes, String sha256) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public cn.iocoder.yudao.module.franchise.api.AssetAccessPort.AssetCompleted completeUpload(long serviceAccountId, long assetId) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public cn.iocoder.yudao.module.franchise.api.AssetAccessPort.DownloadUrlView createDownloadUrl(long serviceAccountId, long assetId) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public String assetTypeOf(long serviceAccountId, long assetId) {
                    return jdbc.queryForObject(
                            "SELECT asset_type FROM eng_test_asset WHERE id = ? AND org_id = ?",
                            String.class, assetId, serviceAccountId);
                }

                @Override
                public boolean validateReference(long serviceAccountId, long assetId) {
                    return existsAccepted(serviceAccountId, assetId);
                }
            };
        }

        @Bean
        cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort storeServiceAccountPort() {
            return orgId -> orgId; // R1 P2 约定：服务账号 id==组织 id，属主维度=组织
        }

        /** M07-T06：统一合同三期桩——CONTRACT_STAGES 非空返回约定（测试静态直控），null=无活跃合同 */
        static volatile cn.iocoder.yudao.module.franchise.api.SaleContractQueryPort.StageAmounts CONTRACT_STAGES;

        static volatile cn.iocoder.yudao.module.franchise.api.SaleContractQueryPort.PaymentPlan FORMAL_PLAN;
        static final AtomicLong ACTIVE_CONTRACT_ID = new AtomicLong(900L);

        @Bean
        cn.iocoder.yudao.module.franchise.api.SaleContractQueryPort saleContractQueryPort() {
            return new cn.iocoder.yudao.module.franchise.api.SaleContractQueryPort() {
                @Override
                public Long findActiveSignedId(PostSnapshot post, long customerId) {
                    // R3：合同存在性与三期金额是两个独立维度——默认 900（fixture 固定合同），
                    // ACTIVE_CONTRACT_ID 可变（重签拒绝用例）；不随 CONTRACT_STAGES 重置而消失
                    return ACTIVE_CONTRACT_ID.get();
                }

                @Override
                public PaymentPlan findPaymentPlanById(PostSnapshot post,long contractId) {
                    return FORMAL_PLAN!=null && FORMAL_PLAN.contractId()==contractId?FORMAL_PLAN:null;
                }
                @Override public PaymentPlan findPaymentPlan(PostSnapshot post,long customerId) { return FORMAL_PLAN; }
                @Override
                public cn.iocoder.yudao.module.franchise.api.SaleContractQueryPort.StageAmounts findStageAmounts(
                        PostSnapshot post, long customerId) {
                    return CONTRACT_STAGES;
                }
            };
        }

        @Bean
        cn.iocoder.yudao.module.franchise.api.BusinessReceiptPort businessReceiptPort(JdbcTemplate jdbc) {
            return new cn.iocoder.yudao.module.franchise.test.BusinessReceiptPortTestStub(jdbc);
        }

        /** M07-T06：统一实收账桩——STATIC_COLLECTED 控制门禁读数；collectStage 记录并返回成功 */
        static final String SIGN_AFTER = "SIGN_AFTER";
        static final String BASE_DONE = "BASE_DONE";
        static final String CAP_DONE = "CAP_DONE";
        static final java.util.List<String> COLLECT_CALLS = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        /** R2 P2-5：按期次返回（SIGN_AFTER 全额/BASE_DONE+CAP_DONE=0）——三期累计=声明总额而非三倍 */
        static volatile java.util.Map<String, Long> STAGE_COLLECTED_MAP = new java.util.concurrent.ConcurrentHashMap<>();

        @Bean
        cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort unifiedStageAccountPort() {
            return new cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort() {
                @Override
                public Long activeContractId(PostSnapshot post, long customerId) {
                    return ACTIVE_CONTRACT_ID.get(); // 与 SaleContractQueryPort 桩同源（重签用例可变）
                }

                @Override
                public java.util.List<StageBalance> stageBalances(PostSnapshot post,long contractId) {
                    return FORMAL_PLAN==null?java.util.List.of():FORMAL_PLAN.stages().stream().map(s->new StageBalance(s.key(),s.amountCents(),STAGE_COLLECTED_MAP.getOrDefault(s.key(),0L),s.dueDate(),s.id(),0,s.label(),s.trigger(),s.nodeIndex(),FORMAL_PLAN.version())).toList();
                }
                @Override
                public long stageCollectedCents(PostSnapshot post, long contractId, String stage) {
                    Long v = STAGE_COLLECTED_MAP.get(stage);
                    return v == null ? 0 : v;
                }

                @Override
                public cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort.StageCollectResult collectStage(
                        PostSnapshot post, long contractId, String stage,
                        cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort.CollectInput input) {
                    COLLECT_CALLS.add(stage + ":" + input.amountCents() + ":" + input.transactionNo());
                    return new cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort.StageCollectResult(
                            8001L, input.amountCents(), "OPEN", false);
                }
            };
        }

        @Bean
        cn.iocoder.yudao.module.franchise.api.EngineeringPayablePort engineeringPayablePort(
                cn.iocoder.yudao.module.franchise.finance.FinPaymentService payments) {
            return new cn.iocoder.yudao.module.franchise.finance.EngineeringPayableAdapter(payments);
        }

        @Bean
        cn.iocoder.yudao.module.franchise.api.EngineeringLedgerQueryPort engineeringLedgerQueryPort(
                cn.iocoder.yudao.module.franchise.finance.dal.FinPaymentMapper payments,
                cn.iocoder.yudao.module.franchise.finance.dal.FinRefundMapper refunds, JdbcTemplate jdbc) {
            return new cn.iocoder.yudao.module.franchise.finance.EngineeringLedgerQueryAdapter(payments, refunds,
                    new cn.iocoder.yudao.module.franchise.finance.FinProjectLedgerService(jdbc));
        }
    }

    private static final AtomicLong IDS = new AtomicLong(950_000_000_000L + System.nanoTime() % 100_000_000L);

    @Resource
    private cn.iocoder.yudao.module.platform.org.SystemDirectory managerDirectory;
    @Resource
    private EngMaterialService materialsService;
    @Resource
    private EngPlanService planService;
    @Resource
    private FranchiseCommands commands;
    @Resource
    private EngCollectionService collectionsService;
    @Resource
    private EngClosureService closuresService;
    @Resource
    private cn.iocoder.yudao.module.franchise.api.SaleContractQueryPort saleContractsPort;
    @Resource
    private JdbcTemplate jdbc;
    @Resource private cn.iocoder.yudao.module.franchise.finance.FinWorkbenchService workbenchCosts;
    @Resource private cn.iocoder.yudao.module.franchise.finance.FinProjectAccountingService accountingCosts;

    private Store store;
    private PostSnapshot engineer;
    private long projectId;
    private long materialId;
    private long supplyId;
    private long nodeId;

    @BeforeEach
    void setUpProjectAndMaterial() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS eng_test_asset (id BIGINT PRIMARY KEY, "
                + "asset_type VARCHAR(64) NOT NULL, accepted BOOLEAN NOT NULL, org_id BIGINT NOT NULL)");
        jdbc.update("DELETE FROM eng_test_asset WHERE org_id <> -1"); // 跨用例清理（R1 P3）
        Config.CONTRACT_STAGES = null; // 静态桩重置
        Config.FORMAL_PLAN=null;
        Config.ACTIVE_CONTRACT_ID.set(900L);
        Config.STAGE_COLLECTED_MAP.clear();
        Config.COLLECT_CALLS.clear();
        store = fixture.store();
        engineer = fixture.post(store.orgId(), "engineer", store.engineeringTeam());
        projectId = insertProject();
        // 统一账关联前提：工程档案挂客户（customer_id NULL 时统一门禁读数恒 0）
        jdbc.update("UPDATE fr_eng_project SET customer_id = ? WHERE id = ?",
                IDS.incrementAndGet(), projectId);
        nodeId = insertNode();
        materialId = insertMaterial();
        supplyId = jdbc.queryForObject(
                "SELECT id FROM fr_eng_supply WHERE material_id = ?", Long.class, materialId);
    }

    @Test
    void originalExpenseHasOneCostAcrossEngineeringFinanceAndAccountingThroughoutPayment() {
        var finance=fixture.post(store.orgId(),"finance",null);
        var boss=fixture.post(store.orgId(),"boss",null);
        var expense=as(engineer,()->expensesService.register(engineer,projectId,
                new EngExpenseService.RegisterCommand("现场劳务确认","LABOR",new BigDecimal("1000"),"原费用"),idem()));
        long expenseId=Long.parseLong((String)expense.get("expenseId"));
        assertExpenseCostViews(finance,boss,100000,0);
        assertThat(ledgerPort.projectCostAmounts(engineer,projectId)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_payable WHERE project_id=?",Long.class,projectId)).isZero();

        as(engineer,()->expensesService.revise(engineer,expenseId,new BigDecimal("800"),"未付款前核减",idem()));
        assertExpenseCostViews(finance,boss,80000,0);
        var cancelled=as(engineer,()->expensesService.register(engineer,projectId,
                new EngExpenseService.RegisterCommand("误录差旅","TRAVEL",new BigDecimal("200"),""),idem()));
        assertExpenseCostViews(finance,boss,100000,0);
        as(engineer,()->{expensesService.cancel(engineer,Long.parseLong((String)cancelled.get("expenseId")),"原单重复",idem());return true;});
        assertExpenseCostViews(finance,boss,80000,0);

        var terms=new EngExpenseService.TermsCommand("劳务队","原费用付款凭据");String termsKey=idem();
        long payable=Long.parseLong((String)as(engineer,()->expensesService.terms(engineer,expenseId,terms,termsKey)).get("payableId"));
        assertThat(as(engineer,()->expensesService.terms(engineer,expenseId,terms,termsKey)).get("payableId")).isEqualTo(Long.toString(payable));
        assertExpenseCostViews(finance,boss,80000,0);
        assertThat(ledgerPort.projectCostAmounts(engineer,projectId)).containsOnly(java.util.Map.entry(payable,80000L));
        long payment=paymentService.register(finance,idem(),new cn.iocoder.yudao.module.franchise.finance.FinPaymentService.PaymentCommand(payable,30000,java.time.LocalDate.now(),null,null));
        assertExpenseCostViews(finance,boss,80000,30000);
        refundService.registerExpense(finance,idem(),new cn.iocoder.yudao.module.franchise.finance.FinRefundService.ExpenseRefundCommand(payment,5000,"原付款部分退回",java.time.LocalDate.now(),null,null,null));
        assertExpenseCostViews(finance,boss,80000,25000);
        long duplicate=paymentService.register(finance,idem(),new cn.iocoder.yudao.module.franchise.finance.FinPaymentService.PaymentCommand(payable,20000,java.time.LocalDate.now(),null,null));
        paymentService.void_(finance,duplicate,"实际未支出，作废原付款");
        assertExpenseCostViews(finance,boss,80000,25000);

        String sourceHash=(String)accountingCosts.readAuthorized(store.orgId(),projectId).get("sourceHash");
        as(engineer,()->expensesService.revise(engineer,expenseId,new BigDecimal("700"),"已付款后待原应付核对",idem()));
        assertExpenseCostViews(finance,boss,80000,25000);
        assertThat(accountingCosts.readAuthorized(store.orgId(),projectId).get("sourceHash")).isNotEqualTo(sourceHash);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_payable WHERE project_id=?",Long.class,projectId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT payable_id FROM fr_eng_expense WHERE id=?",Long.class,expenseId)).isEqualTo(payable);
    }

    private void assertExpenseCostViews(PostSnapshot finance,PostSnapshot boss,long cost,long netPaid) {
        for(var post:java.util.List.of(engineer,finance,boss)) {
            assertThat(as(post,()->closuresService.settleSummary(post,projectId)))
                    .containsEntry("expensesTotalCents",cost).containsEntry("expensesPaidCents",netPaid);
        }
        var summary=(java.util.Map<?,?>)workbenchCosts.ledger(finance.orgId(),"project:"+projectId).get("summary");
        assertThat(((Number)summary.get("committed_cents")).longValue()).isEqualTo(cost);
        assertThat(((Number)summary.get("paid_cents")).longValue()-((Number)summary.get("expense_refunded_cents")).longValue()).isEqualTo(netPaid);
        assertThat(accountingCosts.readAuthorized(boss.orgId(),projectId))
                .containsEntry("confirmedCostCents",Long.toString(cost)).containsEntry("companyNetPaidCents",Long.toString(netPaid));
        var old=new cn.iocoder.yudao.module.franchise.finance.FinProjectLedgerService(jdbc).page(finance.orgId(),projectId,1,20).summary();
        assertThat(old.payableCents()).isEqualTo(cost);
        assertThat(old.paidCents()-old.expenseRefundedCents()).isEqualTo(netPaid);
    }

    @Test
    void planRetryUsesOriginalLineDetailsAndReturnsOnlyItsOwnPlan() {
        var due = java.time.LocalDate.now().plusDays(3);
        var line = new EngMaterialService.PlanLine("钢筋", "HRB400", "吨", new BigDecimal("2.000"));
        var command = new EngMaterialService.PlanCommand(nodeId, due, java.util.List.of(line), "周五到场");
        String key = idem();
        var saved = as(engineer, () -> materialsService.submitPlan(engineer, projectId, command, key));
        long planId = ((Number) saved.get("materialPlanId")).longValue();
        as(engineer, () -> materialsService.submitPlan(engineer, projectId, command, idem()));
        var changes = java.util.List.of(
                new EngMaterialService.PlanLine("钢管", line.spec(), line.unit(), line.quantity()),
                new EngMaterialService.PlanLine(line.name(), "HRB500", line.unit(), line.quantity()),
                new EngMaterialService.PlanLine(line.name(), line.spec(), "根", line.quantity()),
                new EngMaterialService.PlanLine(line.name(), line.spec(), line.unit(), BigDecimal.TEN));
        for (var changed : changes) {
            var retry = new EngMaterialService.PlanCommand(nodeId, due, java.util.List.of(changed), command.note());
            assertPlanKeyConflict(retry, key);
        }
        assertPlanKeyConflict(new EngMaterialService.PlanCommand(nodeId, due, command.lines(), "提前到场"), key);
        var normalized = new EngMaterialService.PlanLine("  钢筋 ", "HRB400", " 吨 ", new BigDecimal("2"));
        var retry = new EngMaterialService.PlanCommand(nodeId, due, java.util.List.of(normalized), command.note());
        assertThat(as(engineer, () -> materialsService.submitPlan(engineer, projectId, retry, key)))
                .isEqualTo(saved);
        Long id = jdbc.queryForObject("SELECT id FROM fr_eng_material WHERE material_plan_id=?", Long.class, planId);
        as(engineer, () -> materialsService.adjust(engineer, id,
                new EngMaterialService.AdjustCommand(BigDecimal.ONE, "用量调整"), idem()));
        assertThat(as(engineer, () -> materialsService.submitPlan(engineer, projectId, command, key)))
                .containsEntry("materialPlanId", planId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fr_eng_material WHERE project_id=? AND required_date=?",
                Integer.class, projectId, due)).isEqualTo(2);
    }

    @Test
    void legacyPlanRetryVerifiesTheOriginalBatchBeforeReturningIt() {
        var due = java.time.LocalDate.now().plusDays(4);
        var line = new EngMaterialService.PlanLine("外墙砖", "灰色", "箱", BigDecimal.TEN);
        var command = new EngMaterialService.PlanCommand(nodeId, due, java.util.List.of(line), null);
        String key = idem();
        var saved = as(engineer, () -> materialsService.submitPlan(engineer, projectId, command, key));
        var old = new java.util.LinkedHashMap<String, Object>();
        old.put("nodeId", nodeId);
        old.put("due", due.toString());
        old.put("lineCount", 1);
        String payload = cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(old);
        assertThat(jdbc.update("UPDATE pf_command_event SET payload=?,payload_hash=? "
                        + "WHERE aggregate_id=? AND command='eng-material-plan'",
                payload, cn.hutool.crypto.digest.DigestUtil.sha256Hex(payload), projectId)).isOne();
        assertThat(as(engineer, () -> materialsService.submitPlan(engineer, projectId, command, key)))
                .isEqualTo(saved);
        var changed = new EngMaterialService.PlanLine(line.name(), "白色", line.unit(), line.quantity());
        assertPlanKeyConflict(new EngMaterialService.PlanCommand(nodeId, due, java.util.List.of(changed), null), key);
    }

    private void assertPlanKeyConflict(EngMaterialService.PlanCommand command, String key) {
        cn.iocoder.yudao.module.franchise.test.FranchiseAssertions.assertError(
                () -> as(engineer, () -> materialsService.submitPlan(engineer, projectId, command, key)),
                cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void constructionPlanKeyRejectsAnotherProjectOperationOrInputWithoutWriting() {
        var inputs = constructionPlanInputs();
        String key = idem();
        var saved = as(engineer, () -> planService.savePlan(engineer, projectId, inputs, key));
        var before = constructionPlanState(projectId);
        long other = insertProject("计划键不能串到此工程");
        var otherBefore = constructionPlanState(other);
        assertThat(as(engineer, () -> planService.savePlan(engineer, projectId, inputs, " " + key + " "))).isEqualTo(saved);
        assertError(() -> as(engineer, () -> planService.savePlan(engineer, other, inputs, key)), IDEMPOTENCY_KEY_REUSED);
        var changed = List.of(inputs.get(0), new EngPlanService.NodeInput(1, "另一施工说明",
                inputs.get(1).startDate(), inputs.get(1).endDate()));
        assertError(() -> as(engineer, () -> planService.savePlan(engineer, projectId, changed, key)), IDEMPOTENCY_KEY_REUSED);
        var reschedule = new EngPlanService.RescheduleCommand(1, inputs.get(1).startDate().plusDays(2), "顺延");
        assertError(() -> as(engineer, () -> planService.reschedule(engineer, projectId, reschedule, key)), IDEMPOTENCY_KEY_REUSED);
        assertThat(constructionPlanState(projectId)).isEqualTo(before);
        assertThat(constructionPlanState(other)).isEqualTo(otherBefore);
        assertConstructionCommandCount("eng-plan-save", 1, 2);
    }

    @Test
    void constructionRescheduleKeyRejectsAnotherProjectOperationOrInputWithoutShiftingAgain() {
        var inputs = constructionPlanInputs();
        as(engineer, () -> planService.savePlan(engineer, projectId, inputs, idem()));
        LocalDate originalDue = inputs.get(1).endDate();
        jdbc.update("UPDATE fr_eng_material SET required_date=? WHERE id=?", originalDue, materialId);
        var command = new EngPlanService.RescheduleCommand(1, inputs.get(1).startDate().plusDays(2), "顺延两天");
        String key = idem();
        var saved = as(engineer, () -> planService.reschedule(engineer, projectId, command, key));
        var before = constructionPlanState(projectId);
        long other = insertProject("改期键不能串到此工程");
        var otherBefore = constructionPlanState(other);
        assertThat(as(engineer, () -> planService.reschedule(engineer, projectId, command, key))).isEqualTo(saved);
        assertError(() -> as(engineer, () -> planService.reschedule(engineer, other, command, key)), IDEMPOTENCY_KEY_REUSED);
        for (var changed : List.of(new EngPlanService.RescheduleCommand(0, command.newStart(), command.note()),
                new EngPlanService.RescheduleCommand(1, command.newStart().plusDays(1), command.note()),
                new EngPlanService.RescheduleCommand(1, command.newStart(), "另一原因"))) {
            assertError(() -> as(engineer, () -> planService.reschedule(engineer, projectId, changed, key)), IDEMPOTENCY_KEY_REUSED);
        }
        assertError(() -> as(engineer, () -> planService.savePlan(engineer, projectId, inputs, key)), IDEMPOTENCY_KEY_REUSED);
        assertThat(constructionPlanState(projectId)).isEqualTo(before);
        assertThat(constructionPlanState(other)).isEqualTo(otherBefore);
        assertThat(jdbc.queryForObject("SELECT required_date FROM fr_eng_material WHERE id=?", LocalDate.class, materialId))
                .isEqualTo(originalDue.plusDays(2));
        assertConstructionCommandCount("eng-plan-reschedule", 1, 3);
    }

    @Test
    void legacyConstructionPlanRetryChecksOriginalProjectAggregateCommandAndFullInput() {
        var inputs = constructionPlanInputs();
        String key = idem();
        as(engineer, () -> planService.savePlan(engineer, projectId, inputs, key));
        var legacy = inputs.stream().map(input -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("nodeIndex", input.nodeIndex()); row.put("name", input.name());
            row.put("startDate", input.startDate().toString()); row.put("endDate", input.endDate().toString());
            return row;
        }).toList();
        useLegacyConstructionEvent("eng-plan-save", key, legacy);
        var changed = List.of(inputs.get(0), new EngPlanService.NodeInput(1, "后来保存的施工说明",
                inputs.get(1).startDate(), inputs.get(1).endDate().plusDays(1)));
        var current = as(engineer, () -> planService.savePlan(engineer, projectId, changed, idem()));
        var before = constructionPlanState(projectId);
        assertThat(as(engineer, () -> planService.savePlan(engineer, projectId, inputs, key))).isEqualTo(current);
        assertError(() -> as(engineer, () -> planService.savePlan(engineer, projectId, changed, key)), IDEMPOTENCY_KEY_REUSED);
        long other = insertProject("历史计划键不能串到此工程");
        var otherBefore = constructionPlanState(other);
        assertError(() -> as(engineer, () -> planService.savePlan(engineer, other, inputs, key)), IDEMPOTENCY_KEY_REUSED);
        assertLegacyConstructionMetadata(key, "eng-plan-save", () -> planService.savePlan(engineer, projectId, inputs, key));
        assertThat(constructionPlanState(projectId)).isEqualTo(before);
        assertThat(constructionPlanState(other)).isEqualTo(otherBefore);
        assertConstructionCommandCount("eng-plan-save", 2, 3);
    }

    @Test
    void legacyConstructionRescheduleRetryChecksOriginalProjectAggregateCommandAndFullInput() {
        var inputs = constructionPlanInputs();
        as(engineer, () -> planService.savePlan(engineer, projectId, inputs, idem()));
        var command = new EngPlanService.RescheduleCommand(1, inputs.get(1).startDate().plusDays(2), "原改期说明");
        String key = idem();
        as(engineer, () -> planService.reschedule(engineer, projectId, command, key));
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("nodeIndex", command.nodeIndex()); legacy.put("newStart", command.newStart().toString());
        legacy.put("note", command.note());
        useLegacyConstructionEvent("eng-plan-reschedule", key, legacy);
        var changed = new EngPlanService.RescheduleCommand(1, command.newStart().plusDays(3), "后一次改期");
        var current = as(engineer, () -> planService.reschedule(engineer, projectId, changed, idem()));
        var before = constructionPlanState(projectId);
        assertThat(as(engineer, () -> planService.reschedule(engineer, projectId, command, key))).isEqualTo(current);
        assertError(() -> as(engineer, () -> planService.reschedule(engineer, projectId, changed, key)), IDEMPOTENCY_KEY_REUSED);
        long other = insertProject("历史改期键不能串到此工程");
        var otherBefore = constructionPlanState(other);
        assertError(() -> as(engineer, () -> planService.reschedule(engineer, other, command, key)), IDEMPOTENCY_KEY_REUSED);
        assertLegacyConstructionMetadata(key, "eng-plan-reschedule", () -> planService.reschedule(engineer, projectId, command, key));
        assertThat(constructionPlanState(projectId)).isEqualTo(before);
        assertThat(constructionPlanState(other)).isEqualTo(otherBefore);
        assertConstructionCommandCount("eng-plan-reschedule", 2, 4);
    }

    @Test
    void constructionPlanRetriesStillRequireCurrentProjectOwnership() {
        var inputs = constructionPlanInputs();
        String saveKey = idem(), rescheduleKey = idem();
        as(engineer, () -> planService.savePlan(engineer, projectId, inputs, saveKey));
        var command = new EngPlanService.RescheduleCommand(1, inputs.get(1).startDate().plusDays(2), "顺延");
        as(engineer, () -> planService.reschedule(engineer, projectId, command, rescheduleKey));
        var successor = fixture.post(store.orgId(), "engineer", store.engineeringTeam());
        jdbc.update("UPDATE fr_eng_project SET manager_user_id=?,assigned_engineer_user_id=? WHERE id=?",
                successor.userId(), successor.userId(), projectId);
        var before = constructionPlanState(projectId);
        assertThatThrownBy(() -> as(engineer, () -> planService.savePlan(engineer, projectId, inputs, saveKey))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> as(engineer, () -> planService.reschedule(engineer, projectId, command, rescheduleKey))).isInstanceOf(ServiceException.class);
        var otherStore = fixture.store();
        var foreign = fixture.post(otherStore.orgId(), "engineer", otherStore.engineeringTeam());
        assertThatThrownBy(() -> as(foreign, () -> planService.savePlan(foreign, projectId, inputs, saveKey))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> as(foreign, () -> planService.reschedule(foreign, projectId, command, rescheduleKey))).isInstanceOf(ServiceException.class);
        assertThat(constructionPlanState(projectId)).isEqualTo(before);
    }

    @Test
    void simultaneousConstructionPlanAndRescheduleRetriesWriteOneVersionEach() throws Exception {
        var inputs = constructionPlanInputs();
        String saveKey = idem(), rescheduleKey = idem();
        assertConcurrentConstructionRetry(() -> planService.savePlan(engineer, projectId, inputs, saveKey));
        assertConstructionCommandCount("eng-plan-save", 1, 2);
        var command = new EngPlanService.RescheduleCommand(1, inputs.get(1).startDate().plusDays(2), "只顺延一次");
        assertConcurrentConstructionRetry(() -> planService.reschedule(engineer, projectId, command, rescheduleKey));
        assertConstructionCommandCount("eng-plan-reschedule", 1, 3);
    }

    private List<EngPlanService.NodeInput> constructionPlanInputs() {
        jdbc.update("UPDATE fr_eng_plan_node SET milestone='TOPPING' WHERE id=?", nodeId);
        LocalDate date = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        return List.of(new EngPlanService.NodeInput(0, "正式开工", date, date),
                new EngPlanService.NodeInput(1, "主体施工", date.plusDays(1), date.plusDays(10)));
    }

    private Map<String, Object> constructionPlanState(long id) {
        return Map.of("revision", jdbc.queryForObject("SELECT revision FROM fr_eng_project WHERE id=?", Long.class, id),
                "nodes", jdbc.queryForList("SELECT id,node_index,name,start_date,end_date,baseline_end_date,status FROM fr_eng_plan_node WHERE project_id=? ORDER BY node_index", id),
                "versions", jdbc.queryForList("SELECT version,nodes::text,source FROM fr_eng_plan_version WHERE project_id=? ORDER BY version", id),
                "materials", jdbc.queryForList("SELECT id,node_id,required_date FROM fr_eng_material WHERE project_id=? ORDER BY id", id),
                "events", jdbc.queryForList("SELECT aggregate_type,command,revision,payload,payload_hash FROM pf_command_event WHERE aggregate_id=? ORDER BY revision", id));
    }

    private void assertConstructionCommandCount(String command, int events, int versions) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_command_event WHERE aggregate_id=? AND command=?", Integer.class, projectId, command)).isEqualTo(events);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_eng_plan_version WHERE project_id=?", Integer.class, projectId)).isEqualTo(versions);
    }

    private void useLegacyConstructionEvent(String command, String key, Object legacy) {
        String json = cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(legacy);
        assertThat(jdbc.update("UPDATE pf_command_event SET payload=?,payload_hash=? WHERE aggregate_id=? AND command=? AND idempotency_key=?",
                json, cn.hutool.crypto.digest.DigestUtil.sha256Hex(json), projectId, command, "franchise:" + engineer.userId() + ":" + key)).isOne();
    }

    private void assertLegacyConstructionMetadata(String key, String command, java.util.function.Supplier<?> retry) {
        String scoped = "franchise:" + engineer.userId() + ":" + key;
        jdbc.update("UPDATE pf_command_event SET aggregate_type='fr-eng-material' WHERE idempotency_key=?", scoped);
        assertError(() -> as(engineer, retry), IDEMPOTENCY_KEY_REUSED);
        jdbc.update("UPDATE pf_command_event SET aggregate_type='fr-eng-project',command='eng-prepare' WHERE idempotency_key=?", scoped);
        assertError(() -> as(engineer, retry), IDEMPOTENCY_KEY_REUSED);
        jdbc.update("UPDATE pf_command_event SET command=? WHERE idempotency_key=?", command, scoped);
    }

    private void assertConcurrentConstructionRetry(java.util.function.Supplier<Map<String, Object>> command) throws Exception {
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        var waiting = new java.util.concurrent.atomic.AtomicInteger();
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
        try {
            var second = tx.execute(status -> {
                int firstPid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                var first = as(engineer, command);
                var replay = pool.submit(() -> tx.execute(other -> {
                    waiting.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    return as(engineer, command);
                }));
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(8)).until(() ->
                        waiting.get() != 0 && Boolean.TRUE.equals(jdbc.queryForObject(
                                "SELECT ? = ANY(pg_blocking_pids(?))", Boolean.class, firstPid, waiting.get())));
                return Map.entry(first, replay);
            });
            assertThat(second.getValue().get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(second.getKey());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void essentialStartPlanAllowsUnscheduledFutureNodesWithoutWeakeningGates() {
        var dated = constructionPlanInputs();
        var inputs = List.of(dated.get(0), new EngPlanService.NodeInput(1, "主体施工", null, null));
        var unscheduled = List.of(new EngPlanService.NodeInput(0, "正式开工", null, null), inputs.get(1));
        long asset = insertAsset("ENG_DRAWING", true, store.orgId());
        var base = startCmd(asset);
        var prepare = new EngPlanService.PrepareCommand(base.total(), base.signAfter(), base.baseDone(), base.capDone(),
                asset, base.note(), null, unscheduled);
        var ids = jdbc.queryForList("SELECT id FROM fr_eng_plan_node WHERE project_id=? ORDER BY node_index", Long.class, projectId);
        as(engineer, () -> planService.prepare(engineer, projectId, prepare, idem()));
        var start = new EngPlanService.NodeRecordCommand(dated.get(0).startDate(), "实际开工", asset, false);
        assertThatThrownBy(() -> as(engineer, () -> planService.start(engineer, projectId, start, idem())))
                .isInstanceOf(ServiceException.class).hasMessageContaining("计划开工日期");
        as(engineer, () -> planService.savePlan(engineer, projectId, inputs, idem()));
        assertError(() -> as(engineer, () -> planService.start(engineer, projectId, start, idem())),
                cn.iocoder.yudao.module.franchise.engineering.enums.FranchiseEngineeringErrorCodes.GATE_NOT_MET);
        Config.STAGE_COLLECTED_MAP.put("SIGN_AFTER", 40_800_000L);
        assertThatThrownBy(() -> as(engineer, () -> planService.start(engineer, projectId,
                new EngPlanService.NodeRecordCommand(start.date(), start.note(), IDS.incrementAndGet(), false), idem())))
                .isInstanceOf(ServiceException.class);
        String key = idem();
        as(engineer, () -> planService.start(engineer, projectId, start, key));
        as(engineer, () -> planService.start(engineer, projectId, start, key));
        assertThat(jdbc.queryForList("SELECT id FROM fr_eng_plan_node WHERE project_id=? ORDER BY node_index", Long.class, projectId)).isEqualTo(ids);
        assertThat(jdbc.queryForMap("SELECT start_date,end_date,status FROM fr_eng_plan_node WHERE id=?", nodeId))
                .containsEntry("start_date", null).containsEntry("end_date", null).containsEntry("status", "IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_eng_site_record WHERE project_id=? AND completion", Integer.class, projectId)).isOne();
        assertConstructionCommandCount("eng-start", 1, 3);
    }

    @Test
    void parallelAndUnscheduledDatesKeepOriginalNodeIdsAndSnapshotValues() {
        var dated = constructionPlanInputs();
        long roof = IDS.incrementAndGet();
        jdbc.update("INSERT INTO fr_eng_plan_node(id,company_id,project_id,node_index,name,status) VALUES(?,?,?,2,'屋面施工','PENDING')",
                roof, store.orgId(), projectId);
        var inputs = List.of(dated.get(0), dated.get(1),
                new EngPlanService.NodeInput(2, "屋面施工", dated.get(1).startDate().plusDays(2), dated.get(1).endDate().plusDays(2)));
        as(engineer, () -> planService.savePlan(engineer, projectId, inputs, idem()));
        var ids = jdbc.queryForList("SELECT id FROM fr_eng_plan_node WHERE project_id=? ORDER BY node_index", Long.class, projectId);
        var cleared = List.of(inputs.get(0), inputs.get(1), new EngPlanService.NodeInput(2, "屋面施工", null, null));
        as(engineer, () -> planService.savePlan(engineer, projectId, cleared, idem()));
        assertThat(jdbc.queryForMap("SELECT start_date,end_date FROM fr_eng_plan_node WHERE id=?", roof))
                .containsEntry("start_date", null).containsEntry("end_date", null);
        assertThat(jdbc.queryForObject("SELECT nodes->2->>'startDate' FROM fr_eng_plan_version WHERE project_id=? ORDER BY version DESC LIMIT 1",
                String.class, projectId)).isNull();
        // A partially entered or inverted range must not create a version or change any existing date.
        var before = constructionPlanState(projectId);
        for (var invalid : List.of(new EngPlanService.NodeInput(2, "屋面施工", dated.get(1).startDate(), null),
                new EngPlanService.NodeInput(2, "屋面施工", dated.get(1).endDate(), dated.get(1).startDate()))) {
            assertThatThrownBy(() -> as(engineer, () -> planService.savePlan(engineer, projectId,
                    List.of(inputs.get(0), inputs.get(1), invalid), idem()))).isInstanceOf(ServiceException.class);
        }
        assertThat(constructionPlanState(projectId)).isEqualTo(before);
        // Parallel plans may start on the same day as a preceding planned node; empty future ranges stay empty.
        as(engineer, () -> planService.reschedule(engineer, projectId,
                new EngPlanService.RescheduleCommand(1, dated.get(0).startDate(), "并行准备后施工"), idem()));
        assertThat(jdbc.queryForList("SELECT id FROM fr_eng_plan_node WHERE project_id=? ORDER BY node_index", Long.class, projectId)).isEqualTo(ids);
        assertThat(jdbc.queryForMap("SELECT start_date,end_date FROM fr_eng_plan_node WHERE id=?", roof))
                .containsEntry("start_date", null).containsEntry("end_date", null);
    }

    @Test
    void formalPaymentPlanIsInheritedWithoutEngineeringReentryAndKeepsOneSnapshotOnRetry() {
        long asset=insertAsset("CONTRACT_ATTACHMENT",true,store.orgId());
        Config.FORMAL_PLAN=new cn.iocoder.yudao.module.franchise.api.SaleContractQueryPort.PaymentPlan(900,1,1,100000,asset,java.util.List.of(
                paymentStage(101,"签约款",20000,"ON_SIGN",null),paymentStage(102,"开工款",30000,"BEFORE_START",null),
                paymentStage(103,"节点款",50000,"NODE_COMPLETED",1)));
        var cmd=new EngPlanService.PrepareCommand(null,null,null,null,null,"确认继承合同约定",null,null,1);
        String key=idem();
        var once=as(engineer,()->planService.prepare(engineer,projectId,cmd,key));
        var twice=as(engineer,()->planService.prepare(engineer,projectId,cmd,key));
        assertThat(twice.get("revision")).isEqualTo(once.get("revision"));
        assertThat(jdbc.queryForList("SELECT stage FROM fr_eng_collection_term WHERE project_id=? ORDER BY stage",String.class,projectId))
                .containsExactly("STAGE_101","STAGE_102","STAGE_103");
        assertThat(jdbc.queryForMap("SELECT contract_total_cents,first_cents,payment_plan_version FROM fr_eng_project WHERE id=?",projectId))
                .containsEntry("contract_total_cents",100000L).containsEntry("first_cents",50000L).containsEntry("payment_plan_version",1);
        assertThat(jdbc.queryForObject("SELECT payment_plan_snapshot->>'contractId' FROM fr_eng_project WHERE id=?",String.class,projectId)).isEqualTo("900");
        Config.STAGE_COLLECTED_MAP.put("STAGE_101",20000L);
        Config.STAGE_COLLECTED_MAP.put("STAGE_102",29999L);
        assertThatThrownBy(()->as(engineer,()->{planService.requireStartGate(engineer,projectId);return true;})).isInstanceOf(ServiceException.class);
        Config.STAGE_COLLECTED_MAP.put("STAGE_102",30000L);
        as(engineer,()->{planService.requireStartGate(engineer,projectId);return true;});
        // Migration-era prepared projects have no frozen version: a current formal plan requires reconfirmation.
        jdbc.update("UPDATE fr_eng_project SET payment_plan_version=NULL WHERE id=?",projectId);
        assertThat(planService.paymentPlanView(engineer,projectId)).containsEntry("needsRefresh",true);
        assertThatThrownBy(()->as(engineer,()->{planService.requireStartGate(engineer,projectId);return true;})).hasMessageContaining("重新确认开工准备");
        as(engineer,()->planService.prepare(engineer,projectId,cmd,idem()));
        as(engineer,()->{planService.requireStartGate(engineer,projectId);return true;});
        Config.FORMAL_PLAN=new cn.iocoder.yudao.module.franchise.api.SaleContractQueryPort.PaymentPlan(900,2,2,100000,asset,Config.FORMAL_PLAN.stages());
        assertThatThrownBy(()->as(engineer,()->{planService.requireStartGate(engineer,projectId);return true;})).hasMessageContaining("重新确认开工准备");
        assertThatThrownBy(()->as(engineer,()->planService.prepare(engineer,projectId,cmd,idem()))).hasMessageContaining("付款计划已更新");
    }

    @Test
    void aPlanReferencingAnotherConstructionNodeIsRejectedWithoutChangingPreparedData() {
        long asset=insertAsset("CONTRACT_ATTACHMENT",true,store.orgId());
        Config.FORMAL_PLAN=new cn.iocoder.yudao.module.franchise.api.SaleContractQueryPort.PaymentPlan(900,1,1,100000,asset,
                java.util.List.of(paymentStage(101,"节点款",100000,"NODE_COMPLETED",99)));
        assertThatThrownBy(()->as(engineer,()->planService.prepare(engineer,projectId,
                new EngPlanService.PrepareCommand(null,null,null,null,null,"检查施工约定",null,null,1),idem()))).hasMessageContaining("不存在的施工节点");
        assertThat(jdbc.queryForObject("SELECT payment_plan_version FROM fr_eng_project WHERE id=?",Integer.class,projectId)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_eng_collection_term WHERE project_id=?",Long.class,projectId)).isZero();
    }

    private cn.iocoder.yudao.module.franchise.api.SaleContractQueryPort.PaymentStage paymentStage(long id,String label,long cents,String trigger,Integer index) {
        return new cn.iocoder.yudao.module.franchise.api.SaleContractQueryPort.PaymentStage(id,"STAGE_"+id,label,(int)id,cents,null,null,trigger,index);
    }

    @Test
    void originalManagerCanSelectAnActiveColleagueAndRecoverTheExactTransfer() {
        PostSnapshot next = fixture.post(store.orgId(), "engineer", store.engineeringTeam());
        PostSnapshot inactive = fixture.post(store.orgId(), "engineer", store.engineeringTeam());
        Store otherStore = fixture.store();
        PostSnapshot outsider = fixture.post(otherStore.orgId(), "engineer", otherStore.engineeringTeam());
        managerDirectory.setUserActive(inactive.userId(), false);
        long oldRevision = jdbc.queryForObject("SELECT revision FROM fr_eng_project WHERE id=?", Long.class, projectId);
        var candidates = as(engineer, () -> projectService.managerCandidates(engineer, projectId));
        assertThat(candidates).extracting(row -> row.get("id")).containsExactly(Long.toString(next.userId()));
        assertThatThrownBy(() -> as(next, () -> projectService.managerCandidates(next, projectId)))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> as(outsider, () -> projectService.managerCandidates(outsider, projectId)))
                .isInstanceOf(ServiceException.class);
        var unavailable = new EngProjectService.ManagerCommand(inactive.userId(), "");
        assertThatThrownBy(() -> as(engineer, () -> projectService.changeManager(engineer, projectId, unavailable, idem())))
                .isInstanceOf(ServiceException.class);

        String key = idem();
        var command = new EngProjectService.ManagerCommand(next.userId(), "施工交接");
        as(engineer, () -> projectService.changeManager(engineer, projectId, command, key));
        as(engineer, () -> projectService.changeManager(engineer, projectId, command, key));
        assertThat(jdbc.queryForObject("SELECT manager_user_id FROM fr_eng_project WHERE id=?", Long.class, projectId))
                .isEqualTo(next.userId());
        assertThat(jdbc.queryForObject("SELECT revision FROM fr_eng_project WHERE id=?", Long.class, projectId))
                .isEqualTo(oldRevision + 1);
        assertThatThrownBy(() -> as(engineer, () -> projectService.changeManager(engineer, projectId,
                new EngProjectService.ManagerCommand(next.userId(), "别的转交说明"), key))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> as(engineer, () -> projectService.changeManager(engineer, projectId, command, idem())))
                .isInstanceOf(ServiceException.class);
    }

    // ========== M07-T01：材料幂等 ==========

    @Test
    void financeOnlyProjectCostIsIncludedAndItsEngineeringReferenceDoesNotDoubleCount() {
        long payable = IDS.incrementAndGet();
        jdbc.update("""
                INSERT INTO fr_fin_payable(id,company_id,project_id,stage,total_cents,recipient)
                VALUES(?,?,?,'施工图深化',600000,'设计工作室')
                """, payable, engineer.orgId(), projectId);
        jdbc.update("""
                INSERT INTO fr_fin_payment(id,payable_id,company_id,project_id,amount_cents,operator_user_id)
                VALUES(?,?,?,?,460000,?)
                """, IDS.incrementAndGet(),payable,engineer.orgId(),projectId,engineer.userId());
        var financeOnly = as(engineer, () -> closuresService.settleSummary(engineer,projectId));
        assertThat(financeOnly).containsEntry("expensesTotalCents",600000L)
                .containsEntry("expensesPaidCents",460000L).containsEntry("expensesRemainingCents",140000L);
        jdbc.update("""
                INSERT INTO fr_eng_expense(id,company_id,project_id,name,purpose,amount_cents,payable_id,author_user_id)
                VALUES(?,?,?,'施工图深化','OTHER',600000,?,?)
                """,IDS.incrementAndGet(),engineer.orgId(),projectId,payable,engineer.userId());
        assertThat(as(engineer, () -> closuresService.settleSummary(engineer,projectId)))
                .containsAllEntriesOf(financeOnly);
    }

    @Test
    void threeStagePrepareAlignsWithUnifiedContract() {
        // 有活跃统一合同：三期金额与合同一致 → 通过并固化三期 term 行（SIGN_AFTER/BASE_DONE/CAP_DONE）
        Config.CONTRACT_STAGES = new cn.iocoder.yudao.module.franchise.api.SaleContractQueryPort.StageAmounts(
                40800000L, 18000000L, 9200000L);
        // 对齐校验前提：工程档案挂客户（customerId NULL 时跳过合同对齐）
        long customerId = IDS.incrementAndGet();
        jdbc.update("UPDATE fr_eng_project SET customer_id = ? WHERE id = ?", customerId, projectId);
        long validAsset = insertAsset("ENG_SITE_PHOTO", true, store.orgId());
        as(engineer, () -> planService.prepare(engineer, projectId, startCmd(validAsset), idem()));
        assertThat(jdbc.queryForObject(
                "SELECT amount_cents FROM fr_eng_collection_term WHERE project_id = ? AND stage = 'SIGN_AFTER'",
                Long.class, projectId)).isEqualTo(40800000L);
        assertThat(jdbc.queryForObject(
                "SELECT amount_cents FROM fr_eng_collection_term WHERE project_id = ? AND stage = 'BASE_DONE'",
                Long.class, projectId)).isEqualTo(18000000L);
        assertThat(jdbc.queryForObject(
                "SELECT amount_cents FROM fr_eng_collection_term WHERE project_id = ? AND stage = 'CAP_DONE'",
                Long.class, projectId)).isEqualTo(9200000L);

        // 与合同不一致 → 拒绝（不发明固定比例/不得自行拆分）
        assertThatThrownBy(() -> as(engineer, () -> planService.prepare(engineer, projectId,
                startCmd(validAsset, 40000000L, 20000000L, 8000000L), idem())))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("三期金额须与统一建房合同约定一致");
    }

    @Test
    void baseDoneStageRequiresFoundationNodeDoneBeforeCollect() {
        // M07-T06：「基础/封顶完成先留痕再触发应收」——BASE_DONE 期收款须有非封顶节点已完成留痕
        Config.CONTRACT_STAGES = null; // 无活跃合同：表单三期即约定（存量兼容）
        long customerId = IDS.incrementAndGet();
        jdbc.update("UPDATE fr_eng_project SET customer_id = ? WHERE id = ?", customerId, projectId);
        long validAsset = insertAsset("ENG_SITE_PHOTO", true, store.orgId());
        as(engineer, () -> planService.prepare(engineer, projectId, startCmd(validAsset), idem()));
        // start 前置：节点日期齐备 + SIGN_AFTER 门禁（统一账读侧桩置累计 40800000=签约后期已收齐）
        jdbc.update("UPDATE fr_eng_plan_node SET start_date = CURRENT_DATE, end_date = CASE WHEN milestone='START' THEN CURRENT_DATE ELSE CURRENT_DATE + 30 END "
                + "WHERE project_id = ?", projectId);
        Config.STAGE_COLLECTED_MAP.put(Config.SIGN_AFTER, 40800000L);
        // R1 P1-1：SIGN_AFTER 门禁读统一账——桩置累计 40800000（签约后期已收齐）
        Config.STAGE_COLLECTED_MAP.put(Config.SIGN_AFTER, 40800000L);
        as(engineer, () -> planService.start(engineer, projectId,
                new EngPlanService.NodeRecordCommand(java.time.LocalDate.now(), "实际开工", validAsset, true), idem()));
        // 基础完成节点留痕（非封顶节点 DONE）——completeNode 前置：材料合格到齐
        jdbc.update("UPDATE fr_eng_material SET quantity = 10.000 WHERE id = ?", materialId);
        jdbc.update("UPDATE fr_eng_supply SET quantity = 10.000 WHERE id = ?", supplyId);
        long baseArrivalAsset = insertAsset("ENG_SITE_PHOTO", true, store.orgId());
        jdbc.update("INSERT INTO fr_eng_supply_receipt (id, company_id, project_id, supply_id, kind, "
                        + "transaction_no, occurred_at, file_asset_id, quality, actual_quantity, "
                        + "author_user_id, create_time) "
                        + "VALUES (?, ?, ?, ?, 'RECEIPT', 'TXN-BASE-ARRIVAL', CURRENT_DATE, ?, 'OK', 10.000, ?, now())",
                IDS.incrementAndGet(), store.orgId(), projectId, supplyId, baseArrivalAsset, engineer.userId());
        as(engineer, () -> planService.completeNode(engineer, projectId, nodeId,
                new EngPlanService.NodeRecordCommand(java.time.LocalDate.now(), "基础施工完成", validAsset, true), idem()));
        // 工程已留痕，再由财务持收款凭据确认入账。
        var finance = fixture.post(store.orgId(), "finance", null);
        long voucher = insertAsset("FIN_VOUCHER", true, store.orgId());
        var view = as(finance, () -> collectionsService.register(finance, projectId,
                new EngCollectionService.CollectCommand("BASE_DONE", new BigDecimal("150000"), "TXN-BASE",
                        java.time.LocalDate.now(), voucher), idem()));
        assertThat(view.get("collectionId")).isNotNull();
        // R2 P2-8：统一链调用断言——Port.collectStage 收到 BASE_DONE 期分笔；工程留痕行引用统一 receiptId
        assertThat(Config.COLLECT_CALLS).anyMatch(c -> c.startsWith("BASE_DONE:15000000:TXN-BASE"));
        assertThat(jdbc.queryForObject(
                "SELECT receipt_id FROM fr_eng_collection WHERE project_id = ? AND stage = 'BASE_DONE'",
                Long.class, projectId)).isEqualTo(8001L);
        // 财务原单仍支持旧版用现场图片留存收款证据的档案。
        long historicPhoto = insertAsset("ENG_SITE_PHOTO", true, store.orgId());
        as(finance, () -> collectionsService.register(finance, projectId,
                new EngCollectionService.CollectCommand("BASE_DONE", new BigDecimal("50"), "TXN-OLD-PHOTO",
                        java.time.LocalDate.now(), historicPhoto), idem()));
        assertThat(as(engineer, () -> collectionsService.list(engineer, projectId))).anySatisfy(row ->
                assertThat(row).containsEntry("fileAssetId", historicPhoto).containsEntry("transactionNo", "TXN-OLD-PHOTO"));

        // 作废重签拒绝（R2 P1-2）：冻结合同 900 ≠ 当前活跃 901 → 收款拒绝
        Config.ACTIVE_CONTRACT_ID.set(901L);
        assertThatThrownBy(() -> as(finance, () -> collectionsService.register(finance, projectId,
                new EngCollectionService.CollectCommand("BASE_DONE", new BigDecimal("100000"), "TXN-BASE2",
                        java.time.LocalDate.now(), voucher), idem())))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("统一建房合同已变更");
        Config.ACTIVE_CONTRACT_ID.set(900L);
        // 封顶期收款在封顶节点完成前拒绝（既有 toppingDone 门禁）
        assertThatThrownBy(() -> as(finance, () -> collectionsService.register(finance, projectId,
                new EngCollectionService.CollectCommand("CAP_DONE", new BigDecimal("100000"), "TXN-CAP",
                        java.time.LocalDate.now(), voucher), idem())))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("请先完成主体封顶");
    }

    @Test
    void collectVoucherAssetVerifiedWhenPresent() {
        var finance = fixture.post(store.orgId(), "finance", null);
        // 财务实收凭据须归属本组织、已验收、类型正确。
        assertThatThrownBy(() -> as(finance, () -> collectionsService.register(finance, projectId,
                new EngCollectionService.CollectCommand("BASE_DONE", new BigDecimal("1000"), "TXN-VC1",
                        java.time.LocalDate.now(), 9_999_999L), idem())))
                .isInstanceOf(ServiceException.class); // 假 ID
        long unaccepted = insertAsset("FIN_VOUCHER", false, store.orgId());
        assertThatThrownBy(() -> as(finance, () -> collectionsService.register(finance, projectId,
                new EngCollectionService.CollectCommand("BASE_DONE", new BigDecimal("1000"), "TXN-VC2",
                        java.time.LocalDate.now(), unaccepted), idem())))
                .isInstanceOf(ServiceException.class); // 未验收
        long wrongType = insertAsset("DOC_OTHER", true, store.orgId());
        assertThatThrownBy(() -> as(finance, () -> collectionsService.register(finance, projectId,
                new EngCollectionService.CollectCommand("BASE_DONE", new BigDecimal("1000"), "TXN-VC3",
                        java.time.LocalDate.now(), wrongType), idem())))
                .isInstanceOf(ServiceException.class); // 类型不符（非 ENG 类）
        // 凭据缺失：既有必填校验（收款流水并留存凭据）先于域侧校验——语义锁定
        assertThatThrownBy(() -> as(finance, () -> collectionsService.register(finance, projectId,
                new EngCollectionService.CollectCommand("BASE_DONE", new BigDecimal("500"), "TXN-VC4",
                        java.time.LocalDate.now(), null), idem())))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("留存凭据");
    }

    @Test
    void sameKeySameInputReplayReturnsSameResultWithoutDuplication() {
        var cmd = new EngMaterialService.AdjustCommand(new BigDecimal("15.000"), "采购追加用量");
        String key = "idem-adj-1";

        var first = as(engineer, () -> materialsService.adjust(engineer, materialId, cmd, key));
        // 响应丢失后同键同表单重试：命中合法重放（此前 replay/record 字段集不同被误判键复用）
        var retry = as(engineer, () -> materialsService.adjust(engineer, materialId, cmd, key));

        assertThat(retry).isEqualTo(first);
        // 仅一条变更记录（不重复创建业务）
        Integer events = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pf_command_event WHERE command = 'eng-material-adjust' "
                        + "AND payload LIKE ?", Integer.class, "%\"materialId\":" + materialId + "%");
        assertThat(events).isEqualTo(1);
        assertThat(quantityOf(materialId)).isEqualByComparingTo(new BigDecimal("15.000"));
    }

    @Test
    void sameKeySameQuantityDifferentReasonIsRejected() {
        // R1 建议：reason 属调整意图，实质不同须拒绝复用键
        as(engineer, () -> materialsService.adjust(engineer, materialId,
                new EngMaterialService.AdjustCommand(new BigDecimal("15.000"), "采购追加用量"), "idem-adj-r"));
        assertThatThrownBy(() -> as(engineer, () -> materialsService.adjust(engineer, materialId,
                new EngMaterialService.AdjustCommand(new BigDecimal("15.000"), "换了个说法"), "idem-adj-r")))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void startAndNodeCompleteAssetGuardsRejectInvalidAssets() {
        // start 凭据校验：假 ID 拒绝（开工须先 prepare——先合法 prepare 再 start）
        long validAsset = insertAsset("ENG_SITE_PHOTO", true, store.orgId());
        as(engineer, () -> planService.prepare(engineer, projectId, startCmd(validAsset), idem()));
        // start 前置：节点计划日期齐备；SIGN_AFTER 门禁读统一账——桩置累计 40800000（R1 P1-1 读侧）
        jdbc.update("UPDATE fr_eng_plan_node SET start_date = CURRENT_DATE, end_date = CASE WHEN milestone='START' THEN CURRENT_DATE ELSE CURRENT_DATE + 30 END "
                + "WHERE project_id = ?", projectId);
        Config.STAGE_COLLECTED_MAP.put(Config.SIGN_AFTER, 40800000L);
        long fakeId = IDS.incrementAndGet();
        org.assertj.core.api.ThrowableAssert.ThrowingCallable startWithFake = () ->
                as(engineer, () -> planService.start(engineer, projectId,
                        new EngPlanService.NodeRecordCommand(java.time.LocalDate.now(), "实际开工", fakeId, true), idem()));
        org.assertj.core.api.Assertions.assertThatThrownBy(startWithFake).isInstanceOf(ServiceException.class);
        // completeNode 凭据校验：假 ID 拒绝（M07-T03 旁路封堵回归）
        // 合法凭据通过 start（承接 prepare 状态）
        long validStartAsset = insertAsset("ENG_DRAWING", true, store.orgId());
        as(engineer, () -> planService.start(engineer, projectId,
                new EngPlanService.NodeRecordCommand(java.time.LocalDate.now(), "实际开工", validStartAsset, true), idem()));
        assertThat(jdbc.queryForObject(
                "SELECT actual_start_at FROM fr_eng_project WHERE id = ?", java.sql.Date.class, projectId))
                .isNotNull();

        // R2：completeNode 负测在合法 start 之后（actualStartAt 已置，前置门通过）——
        // 假 ID 到达资产校验层，断言资产校验的具体文案（区别于前置门错误，防假阳性）
        long fakeForNode = IDS.incrementAndGet();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                as(engineer, () -> planService.completeNode(engineer, projectId, nodeId,
                        new EngPlanService.NodeRecordCommand(java.time.LocalDate.now(), "主体完成", fakeForNode, true), idem())))
                .isInstanceOfSatisfying(ServiceException.class,
                        error -> assertThat(error.getCode()).isEqualTo(1_115_000_001))
                .hasMessage("提交内容不合法：现场验收凭据资产不存在、未验收、不属于本组织或类型不符");
        // 合法 completeNode：节点 DONE + 凭据文档落库（前置：材料合格到齐——到货行 quantity>=需用）
        long gateReceiptAsset = insertAsset("ENG_SITE_PHOTO", true, store.orgId());
        // 合格到货行（quality=OK + actual_quantity=15 ≥ 需用——共享库中幂等用例已把需用改到 15，
        // 到货须 ≥ 现需用才能过 materialReadyForNode）
        jdbc.update("UPDATE fr_eng_material SET quantity = 15.000 WHERE id = ?", materialId);
        jdbc.update("UPDATE fr_eng_supply SET quantity = 15.000 WHERE id = ?", supplyId);
        jdbc.update("INSERT INTO fr_eng_supply_receipt (id, company_id, project_id, supply_id, kind, "
                        + "transaction_no, occurred_at, file_asset_id, quality, actual_quantity, "
                        + "author_user_id, create_time) "
                        + "VALUES (?, ?, ?, ?, 'RECEIPT', 'TXN-ARRIVAL', CURRENT_DATE, ?, 'OK', 15.000, ?, now())",
                IDS.incrementAndGet(), store.orgId(), projectId, supplyId, gateReceiptAsset, engineer.userId());
        long validNodeAsset = insertAsset("ENG_SITE_PHOTO", true, store.orgId());
        as(engineer, () -> planService.completeNode(engineer, projectId, nodeId,
                new EngPlanService.NodeRecordCommand(java.time.LocalDate.now(), "主体完成", validNodeAsset, true), idem()));
        assertThat(jdbc.queryForObject("SELECT status FROM fr_eng_plan_node WHERE id = ?",
                String.class, nodeId)).isEqualTo("DONE");
        // 凭据落 fr_eng_site_record（file_asset_ids JSON 含凭据资产）
        Integer siteRecords = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fr_eng_site_record WHERE project_id = ? AND node_id = ? "
                        + "AND file_asset_ids::text LIKE ?", Integer.class, projectId, nodeId, "%" + validNodeAsset + "%");
        assertThat(siteRecords).isGreaterThanOrEqualTo(1);
    }

    @Test
    void sameKeyDifferentInputIsRejected() {
        as(engineer, () -> materialsService.adjust(engineer, materialId,
                new EngMaterialService.AdjustCommand(new BigDecimal("15.000"), "采购追加用量"), "idem-adj-2"));

        // 同键改输入（数量与原因都不同）：键复用拒绝
        assertThatThrownBy(() -> as(engineer, () -> materialsService.adjust(engineer, materialId,
                new EngMaterialService.AdjustCommand(new BigDecimal("18.000"), "改口径"), "idem-adj-2")))
                .isInstanceOf(ServiceException.class);
        // 数量保持首次值
        assertThat(quantityOf(materialId)).isEqualByComparingTo(new BigDecimal("15.000"));
    }

    @Test
    void nodeCompletionKeepsEveryAuthorizedPhotoAndReplaysTheExactEvidence() {
        long startAsset=insertAsset("ENG_DRAWING",true,store.orgId());
        as(engineer,()->planService.prepare(engineer,projectId,startCmd(startAsset),idem()));
        jdbc.update("UPDATE fr_eng_plan_node SET start_date=CURRENT_DATE,end_date=CASE WHEN milestone='START' THEN CURRENT_DATE ELSE CURRENT_DATE+30 END WHERE project_id=?",projectId);
        Config.STAGE_COLLECTED_MAP.put(Config.SIGN_AFTER,40800000L);
        as(engineer,()->planService.start(engineer,projectId,
                new EngPlanService.NodeRecordCommand(java.time.LocalDate.now(),"实际开工",startAsset,true),idem()));
        jdbc.update("UPDATE fr_eng_material SET status='CANCELLED' WHERE project_id=?",projectId);
        var photos=java.util.List.of(insertAsset("ENG_SITE_PHOTO",true,store.orgId()),
                insertAsset("ENG_SITE_PHOTO",true,store.orgId()),insertAsset("ENG_SITE_PHOTO",true,store.orgId()));
        var bad=java.util.List.of(photos.get(0),insertAsset("ENG_SITE_PHOTO",true,fixture.store().orgId()),photos.get(2));
        assertThatThrownBy(()->as(engineer,()->planService.completeNode(engineer,projectId,nodeId,
                new EngPlanService.NodeRecordCommand(java.time.LocalDate.now(),"三张验收照片",null,true,bad),idem())))
                .isInstanceOf(ServiceException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_eng_site_record WHERE node_id=? AND completion=TRUE",Long.class,nodeId)).isZero();
        for(var invalid:java.util.List.of(java.util.List.<Long>of(),java.util.Collections.nCopies(10,photos.get(0)),
                java.util.Arrays.asList(photos.get(0),null),java.util.List.of(photos.get(0),photos.get(0)))) {
            assertThatThrownBy(()->as(engineer,()->planService.completeNode(engineer,projectId,nodeId,
                    new EngPlanService.NodeRecordCommand(java.time.LocalDate.now(),"无效凭据数量",null,true,invalid),idem())))
                    .isInstanceOf(ServiceException.class);
        }
        String key=idem();var command=new EngPlanService.NodeRecordCommand(java.time.LocalDate.now(),"三张验收照片",null,true,photos);
        as(engineer,()->planService.completeNode(engineer,projectId,nodeId,command,key));
        as(engineer,()->planService.completeNode(engineer,projectId,nodeId,command,key));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_eng_site_record WHERE node_id=? AND completion=TRUE",Long.class,nodeId)).isEqualTo(1);
        var detail=as(engineer,()->projectService.detail(engineer,projectId));
        @SuppressWarnings("unchecked") var records=(java.util.List<java.util.Map<String,Object>>)detail.get("siteRecords");
        var saved=records.stream().filter(r->String.valueOf(r.get("node_id")).equals(Long.toString(nodeId))&&Boolean.TRUE.equals(r.get("completion"))).findFirst().orElseThrow();
        assertThat(cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseArray(String.valueOf(saved.get("file_asset_ids")),String.class))
                .containsExactlyElementsOf(photos.stream().map(String::valueOf).toList());
        assertThatThrownBy(()->as(engineer,()->planService.completeNode(engineer,projectId,nodeId,
                new EngPlanService.NodeRecordCommand(command.date(),command.note(),null,true,java.util.List.of(photos.get(0))),key)))
                .isInstanceOf(ServiceException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM fr_eng_plan_node WHERE id=?",String.class,nodeId)).isEqualTo("DONE");
    }


    @Test
    void actualNodeDatesShareBoundsForInitialCompletionAndCorrection() {
        var today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        var startDate = today.minusDays(4);
        long asset = insertAsset("ENG_DRAWING", true, store.orgId());
        as(engineer, () -> planService.prepare(engineer, projectId, startCmd(asset), idem()));
        // Overlapping/future planned ranges do not determine the actual historical dates.
        jdbc.update("UPDATE fr_eng_plan_node SET start_date=?,end_date=CASE WHEN milestone='START' THEN ? ELSE ? END WHERE project_id=?",
                startDate, startDate, today.plusDays(30), projectId);
        Config.STAGE_COLLECTED_MAP.put(Config.SIGN_AFTER, 40800000L);
        as(engineer, () -> planService.start(engineer, projectId,
                new EngPlanService.NodeRecordCommand(startDate, "实际开工", asset, true), idem()));
        jdbc.update("UPDATE fr_eng_material SET status='CANCELLED' WHERE project_id=?", projectId);
        for (LocalDate invalid : List.of(startDate.minusDays(1), today.plusDays(1))) {
            assertThatThrownBy(() -> as(engineer, () -> planService.completeNode(engineer, projectId, nodeId,
                    new EngPlanService.NodeRecordCommand(invalid, "错误实际日期", asset, true), idem())))
                    .isInstanceOf(ServiceException.class);
        }
        assertThat(jdbc.queryForObject("SELECT completed_at FROM fr_eng_plan_node WHERE id=?", LocalDate.class, nodeId)).isNull();
        var firstDate = today.minusDays(2);
        as(engineer, () -> planService.completeNode(engineer, projectId, nodeId,
                new EngPlanService.NodeRecordCommand(firstDate, "真实前序完成", asset, true), idem()));
        long next = IDS.incrementAndGet();
        jdbc.update("INSERT INTO fr_eng_plan_node(id,company_id,project_id,node_index,name,status,start_date,end_date) VALUES(?,?,?,2,'后续施工','IN_PROGRESS',?,?)",
                next, store.orgId(), projectId, startDate, today.plusDays(30));
        for (LocalDate invalid : List.of(firstDate.minusDays(1), today.plusDays(1))) {
            assertThatThrownBy(() -> as(engineer, () -> planService.completeNode(engineer, projectId, next,
                    new EngPlanService.NodeRecordCommand(invalid, "错误后序日期", asset, true), idem())))
                    .isInstanceOf(ServiceException.class);
        }
        var completedAt = today.minusDays(1);
        as(engineer, () -> planService.completeNode(engineer, projectId, next,
                new EngPlanService.NodeRecordCommand(completedAt, "真实后序完成", asset, true), idem()));
        var original = jdbc.queryForMap("SELECT completed_at,corrections FROM fr_eng_plan_node WHERE id=?", next);
        for (LocalDate invalid : List.of(startDate.minusDays(1), firstDate.minusDays(1), today.plusDays(1))) {
            assertThatThrownBy(() -> as(engineer, () -> planService.correctNode(engineer, projectId, next,
                    new EngPlanService.NodeCorrectCommand(invalid, null, null, "错误更正日期"), idem())))
                    .isInstanceOf(ServiceException.class);
            assertThat(jdbc.queryForMap("SELECT completed_at,corrections FROM fr_eng_plan_node WHERE id=?", next)).isEqualTo(original);
        }
        String key = idem();
        var correction = new EngPlanService.NodeCorrectCommand(today, null, null, "核实实际完成日期");
        as(engineer, () -> planService.correctNode(engineer, projectId, next, correction, key));
        as(engineer, () -> planService.correctNode(engineer, projectId, next, correction, key));
        assertThat(jdbc.queryForObject("SELECT completed_at FROM fr_eng_plan_node WHERE id=?", LocalDate.class, next)).isEqualTo(today);
        assertThat(jdbc.queryForObject("SELECT jsonb_array_length(corrections) FROM fr_eng_plan_node WHERE id=?", Integer.class, next)).isOne();
        assertThat(jdbc.queryForObject("SELECT actual_start_at FROM fr_eng_project WHERE id=?", LocalDate.class, projectId)).isEqualTo(startDate);
        assertThat(jdbc.queryForObject("SELECT end_date FROM fr_eng_plan_node WHERE id=?", LocalDate.class, nodeId)).isEqualTo(today.plusDays(30));
    }

    @Test
    void deliveryCannotPrecedeLastActualCompletionOrUseFutureDate() {
        var completed = completedInspection();
        var before = jdbc.queryForMap("SELECT * FROM fr_eng_closure WHERE project_id=?", projectId);
        for (LocalDate invalid : List.of(completed.date().minusDays(1), LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1))) {
            assertThatThrownBy(() -> as(engineer, () -> closuresService.deliver(engineer, projectId,
                    new EngClosureService.DeliverCommand(invalid, "业主", null, completed.photos().get(0), "交付核对"), idem())))
                    .isInstanceOf(ServiceException.class);
            assertThat(jdbc.queryForMap("SELECT * FROM fr_eng_closure WHERE project_id=?", projectId)).isEqualTo(before);
        }
        String key = idem();
        var command = new EngClosureService.DeliverCommand(completed.date(), "业主", null, completed.photos().get(0), "历史实际交付");
        as(engineer, () -> closuresService.deliver(engineer, projectId, command, key));
        as(engineer, () -> closuresService.deliver(engineer, projectId, command, key));
        assertThat(jdbc.queryForObject("SELECT delivered_at FROM fr_eng_closure WHERE project_id=?", LocalDate.class, projectId)).isEqualTo(completed.date());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_command_event WHERE aggregate_id=? AND command='eng-deliver'", Integer.class, projectId)).isOne();
        assertThat(jdbc.queryForObject("SELECT actual_start_at FROM fr_eng_project WHERE id=?", LocalDate.class, projectId)).isEqualTo(completed.date().minusDays(1));
    }

    @Test
    void dateOnlyNodeCorrectionPreservesAllEvidenceAndReplaysWithoutAnotherHistoryEntry() {
        var completed = completedInspection();
        var correctedDate = java.time.LocalDate.now();
        String key = idem();
        var command = new EngPlanService.NodeCorrectCommand(correctedDate, null, null, "完工日期填错");
        as(engineer, () -> planService.correctNode(engineer, projectId, nodeId, command, key));
        as(engineer, () -> planService.correctNode(engineer, projectId, nodeId, command, key));
        assertCompletion(completed, completed.photos(), "原验收说明", correctedDate);
        var history = completionHistory(completed.recordId());
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("occurredAt")).isEqualTo(completed.date().toString());
        assertThat(history.get(0).get("note")).isEqualTo("原验收说明");
        assertThat((java.util.List<?>) history.get(0).get("fileAssetIds"))
                .extracting(String::valueOf).containsExactlyElementsOf(completed.photos().stream().map(String::valueOf).toList());
        assertThat(history.get(0).get("reason")).isEqualTo("完工日期填错");
        assertThatThrownBy(() -> as(engineer, () -> planService.correctNode(engineer, projectId, nodeId,
                new EngPlanService.NodeCorrectCommand(correctedDate, null, null, "同键改原因"), key)))
                .isInstanceOf(ServiceException.class);
        assertThat(completionHistory(completed.recordId())).hasSize(1);
    }

    @Test
    void selectedNodePhotoReplacementKeepsOtherPhotosAndExposesOriginalEvidenceInDetail() {
        var completed = completedInspection();
        long replacement = insertAsset("ENG_SITE_PHOTO", true, store.orgId());
        var body = new java.util.LinkedHashMap<String, Object>(java.util.Map.of(
                "completedAt", completed.date().toString(), "note", "更正后的说明", "reason", "第二张选错",
                "replacementRecordId", Long.toString(completed.recordId()),
                "replaceFileAssetId", Long.toString(completed.photos().get(1)),
                "fileAssetId", Long.toString(replacement)));
        String key = idem();
        correctThroughController(body, key);
        correctThroughController(body, key);
        assertCompletion(completed, java.util.List.of(completed.photos().get(0), replacement, completed.photos().get(2)),
                "更正后的说明", completed.date());
        var detail = as(engineer, () -> projectService.detail(engineer, projectId));
        @SuppressWarnings("unchecked")
        var records = (java.util.List<java.util.Map<String, Object>>) detail.get("siteRecords");
        var saved = records.stream().filter(row -> Long.toString(completed.recordId()).equals(String.valueOf(row.get("id"))))
                .findFirst().orElseThrow();
        var history = cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseArray(String.valueOf(saved.get("corrections")), java.util.Map.class);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("note")).isEqualTo("原验收说明");
        assertThat((java.util.List<?>) history.get(0).get("fileAssetIds"))
                .extracting(String::valueOf).containsExactlyElementsOf(completed.photos().stream().map(String::valueOf).toList());
        body.put("replaceFileAssetId", Long.toString(completed.photos().get(0)));
        assertThatThrownBy(() -> correctThroughController(body, key)).isInstanceOf(ServiceException.class);
        body.put("replaceFileAssetId", Long.toString(completed.photos().get(1)));
        body.put("fileAssetId", Long.toString(insertAsset("ENG_SITE_PHOTO", true, store.orgId())));
        assertThatThrownBy(() -> correctThroughController(body, key)).isInstanceOf(ServiceException.class);
        assertThat(completionHistory(completed.recordId())).hasSize(1);
    }

    @Test
    void nodeCorrectionRejectsAmbiguousOrForeignReplacementWithoutLosingAnyEvidence() {
        var completed = completedInspection();
        var valid = insertAsset("ENG_SITE_PHOTO", true, store.orgId());
        // A legacy client must never silently shrink an existing multi-photo record to one photo.
        assertThatThrownBy(() -> as(engineer, () -> planService.correctNode(engineer, projectId, nodeId,
                new EngPlanService.NodeCorrectCommand(completed.date(), "旧客户端说明", valid, "未指明换哪张"), idem())))
                .isInstanceOf(ServiceException.class);
        var body = new java.util.LinkedHashMap<String, Object>(java.util.Map.of(
                "completedAt", completed.date().toString(), "reason", "替换第二张",
                "replacementRecordId", Long.toString(completed.recordId()),
                "replaceFileAssetId", Long.toString(completed.photos().get(1))));
        for (long invalid : java.util.List.of(
                insertAsset("ENG_SITE_PHOTO", true, fixture.store().orgId()),
                insertAsset("ENG_SITE_PHOTO", false, store.orgId()),
                insertAsset("CONTENT_VIDEO", true, store.orgId()), IDS.incrementAndGet(), completed.photos().get(0))) {
            body.put("fileAssetId", Long.toString(invalid));
            assertThatThrownBy(() -> correctThroughController(body, idem())).isInstanceOf(ServiceException.class);
        }
        body.put("fileAssetId", Long.toString(valid));
        body.put("replacementRecordId", Long.toString(IDS.incrementAndGet()));
        assertThatThrownBy(() -> correctThroughController(body, idem())).isInstanceOf(ServiceException.class);
        body.put("replacementRecordId", Long.toString(completed.recordId()));
        body.put("replaceFileAssetId", Long.toString(valid));
        assertThatThrownBy(() -> correctThroughController(body, idem())).isInstanceOf(ServiceException.class);
        assertCompletion(completed, completed.photos(), "原验收说明", completed.date());
        assertThat(completionHistory(completed.recordId())).isEmpty();
    }

    @Test
    void dateOnlyCorrectionStillRequiresReasonAndValidNodeChronology() {
        var completed = completedInspection();
        assertThatThrownBy(() -> as(engineer, () -> planService.correctNode(engineer, projectId, nodeId,
                new EngPlanService.NodeCorrectCommand(completed.date(), null, null, ""), idem())))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> as(engineer, () -> planService.correctNode(engineer, projectId, nodeId,
                new EngPlanService.NodeCorrectCommand(completed.date().minusDays(5), null, null, "错误日期"), idem())))
                .isInstanceOf(ServiceException.class);
        long next = IDS.incrementAndGet();
        jdbc.update("INSERT INTO fr_eng_plan_node(id,company_id,project_id,node_index,name,status,create_time) "
                + "VALUES(?,?,?,2,'下一节点','IN_PROGRESS',now())", next, store.orgId(), projectId);
        jdbc.update("INSERT INTO fr_eng_site_record(id,company_id,project_id,node_id,occurred_at,note,completion,file_asset_ids,author_user_id) "
                + "VALUES(?,?,?,?,?,'下一节点已有记录',FALSE,'[]'::jsonb,?)",
                IDS.incrementAndGet(), store.orgId(), projectId, next, completed.date(), engineer.userId());
        assertThatThrownBy(() -> as(engineer, () -> planService.correctNode(engineer, projectId, nodeId,
                new EngPlanService.NodeCorrectCommand(completed.date().plusDays(1), null, null, "晚于下一节点"), idem())))
                .isInstanceOf(ServiceException.class);
        assertCompletion(completed, completed.photos(), "原验收说明", completed.date());
        assertThat(completionHistory(completed.recordId())).isEmpty();
    }

    @Test
    void legacySinglePhotoCorrectionRemainsCompatibleAndRetainsItsOriginalEvidence() {
        var completed = completedInspection();
        jdbc.update("UPDATE fr_eng_site_record SET file_asset_ids=CAST(? AS jsonb) WHERE id=?",
                EngPlanService.JsonList.of(Long.toString(completed.photos().get(0))), completed.recordId());
        long replacement = insertAsset("ENG_DRAWING", true, store.orgId());
        String key = idem();
        var command = new EngPlanService.NodeCorrectCommand(completed.date(), "旧单图客户端更正", replacement, "原图不清晰");
        as(engineer, () -> planService.correctNode(engineer, projectId, nodeId, command, key));
        as(engineer, () -> planService.correctNode(engineer, projectId, nodeId, command, key));
        assertCompletion(completed, java.util.List.of(replacement), "旧单图客户端更正", completed.date());
        assertThat(completionHistory(completed.recordId())).hasSize(1);
        assertThat((java.util.List<?>) completionHistory(completed.recordId()).get(0).get("fileAssetIds"))
                .extracting(String::valueOf).containsExactly(Long.toString(completed.photos().get(0)));
    }

    @Test
    void completedLegacyCorrectionRetriesAfterUpgradeWithoutChangingEvidenceOrAppendingHistory() {
        var completed = completedInspection();
        var command = new EngPlanService.NodeCorrectCommand(java.time.LocalDate.now(), "旧版保存的验收说明",
                insertAsset("ENG_SITE_PHOTO", true, store.orgId()), "旧版完工日期填错");
        String key = idem();
        saveLegacyCorrection(completed, command, key);
        var before = jdbc.queryForMap("SELECT revision FROM fr_eng_project WHERE id=?", projectId);

        var result = as(engineer, () -> planService.correctNode(engineer, projectId, nodeId, command, key));
        as(engineer, () -> planService.correctNode(engineer, projectId, nodeId, command, key));

        assertThat(String.valueOf(result.get("id"))).isEqualTo(Long.toString(projectId));
        assertCompletion(completed, java.util.List.of(command.fileAssetId()), command.note(), command.completedAt());
        assertThat(jdbc.queryForMap("SELECT revision FROM fr_eng_project WHERE id=?", projectId)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT jsonb_array_length(corrections) FROM fr_eng_plan_node WHERE id=?",
                Integer.class, nodeId)).isEqualTo(1);
        assertThat(completionHistory(completed.recordId())).isEmpty(); // 旧版没有凭据历史，重放不能伪造一条。
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_command_event WHERE idempotency_key=?",
                Integer.class, "franchise:" + engineer.userId() + ":" + key)).isEqualTo(1);
    }

    @Test
    void completedLegacyCorrectionRejectsChangedContentOrAnotherAggregateAfterUpgrade() {
        var completed = completedInspection();
        var command = new EngPlanService.NodeCorrectCommand(java.time.LocalDate.now(), "旧版保存的验收说明",
                insertAsset("ENG_SITE_PHOTO", true, store.orgId()), "旧版完工日期填错");
        String key = idem();
        saveLegacyCorrection(completed, command, key);
        for (var changed : java.util.List.of(
                new EngPlanService.NodeCorrectCommand(command.completedAt().minusDays(1), command.note(), command.fileAssetId(), command.reason()),
                new EngPlanService.NodeCorrectCommand(command.completedAt(), "更换说明", command.fileAssetId(), command.reason()),
                new EngPlanService.NodeCorrectCommand(command.completedAt(), command.note(), completed.photos().get(0), command.reason()),
                new EngPlanService.NodeCorrectCommand(command.completedAt(), command.note(), command.fileAssetId(), "更换原因"),
                new EngPlanService.NodeCorrectCommand(command.completedAt(), null, null, command.reason()),
                new EngPlanService.NodeCorrectCommand(command.completedAt(), command.note(), command.fileAssetId(),
                        command.reason(), completed.recordId(), completed.photos().get(0)))) {
            assertThatThrownBy(() -> as(engineer, () -> planService.correctNode(engineer, projectId, nodeId, changed, key)))
                    .isInstanceOf(ServiceException.class);
        }
        assertThatThrownBy(() -> as(engineer, () -> planService.correctNode(engineer, projectId, nodeId + 1, command, key)))
                .isInstanceOf(ServiceException.class);
        long anotherProject = insertProject("另一个工程 · 升级重试校验");
        assertThatThrownBy(() -> as(engineer, () -> planService.correctNode(engineer, anotherProject, nodeId, command, key)))
                .isInstanceOf(ServiceException.class);
        // 在拒绝非法重试后，原请求仍可以确认成功。
        as(engineer, () -> planService.correctNode(engineer, projectId, nodeId, command, key));
        assertCompletion(completed, java.util.List.of(command.fileAssetId()), command.note(), command.completedAt());
        assertThat(jdbc.queryForObject("SELECT jsonb_array_length(corrections) FROM fr_eng_plan_node WHERE id=?",
                Integer.class, nodeId)).isEqualTo(1);
        assertThat(completionHistory(completed.recordId())).isEmpty();
    }

    private void saveLegacyCorrection(CompletedInspection completed, EngPlanService.NodeCorrectCommand command, String key) {
        // 模拟 #66 之前真实写入形态：节点历史有原因，现场记录已更新为单凭据，事件只存节点与日期。
        String history = EngPlanService.JsonList.of(java.util.Map.of(
                "completedAt", completed.date().toString(), "note", "节点 完成更正", "reason", command.reason(),
                "date", java.time.LocalDate.now().toString(), "actorUserId", engineer.userId()));
        jdbc.update("UPDATE fr_eng_plan_node SET completed_at=?, corrections=CAST(? AS jsonb) WHERE id=?",
                command.completedAt(), history, nodeId);
        jdbc.update("UPDATE fr_eng_site_record SET occurred_at=?, note=?, file_asset_ids=CAST(? AS jsonb) WHERE id=?",
                command.completedAt(), command.note(), EngPlanService.JsonList.of(command.fileAssetId()), completed.recordId());
        long revision = jdbc.queryForObject("UPDATE fr_eng_project SET revision=revision+1 WHERE id=? RETURNING revision",
                Long.class, projectId);
        as(engineer, () -> {
            commands.record(engineer, EngPlanService.AGGREGATE, projectId, revision, "eng-node-correct",
                    java.util.Map.of("nodeId", nodeId, "completedAt", command.completedAt().toString()), key);
            return null;
        });
    }

    private record CompletedInspection(long recordId, java.time.LocalDate date, java.util.List<Long> photos) {}

    private CompletedInspection completedInspection() {
        long startAsset = insertAsset("ENG_DRAWING", true, store.orgId());
        as(engineer, () -> planService.prepare(engineer, projectId, startCmd(startAsset), idem()));
        jdbc.update("UPDATE fr_eng_plan_node SET start_date=CURRENT_DATE-2,end_date=CASE WHEN milestone='START' THEN CURRENT_DATE-2 ELSE CURRENT_DATE+30 END WHERE project_id=?", projectId);
        Config.STAGE_COLLECTED_MAP.put(Config.SIGN_AFTER, 40800000L);
        var date = java.time.LocalDate.now().minusDays(1);
        as(engineer, () -> planService.start(engineer, projectId,
                new EngPlanService.NodeRecordCommand(date.minusDays(1), "实际开工", startAsset, true), idem()));
        jdbc.update("UPDATE fr_eng_material SET status='CANCELLED' WHERE project_id=?", projectId);
        var photos = java.util.List.of(insertAsset("ENG_SITE_PHOTO", true, store.orgId()),
                insertAsset("ENG_SITE_PHOTO", true, store.orgId()), insertAsset("ENG_SITE_PHOTO", true, store.orgId()));
        as(engineer, () -> planService.completeNode(engineer, projectId, nodeId,
                new EngPlanService.NodeRecordCommand(date, "原验收说明", null, true, photos), idem()));
        return new CompletedInspection(jdbc.queryForObject(
                "SELECT id FROM fr_eng_site_record WHERE node_id=? AND completion=TRUE", Long.class, nodeId), date, photos);
    }

    private java.util.Map<String, Object> correctThroughController(java.util.Map<String, Object> body, String key) {
        var controller = new cn.iocoder.yudao.module.franchise.engineering.controller.admin.EngPlanController();
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "plans", planService);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "currentPost", currentPost);
        return as(engineer, () -> controller.correct(projectId, nodeId, body, key).getData());
    }

    private java.util.List<java.util.Map> completionHistory(long recordId) {
        return cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseArray(jdbc.queryForObject(
                "SELECT COALESCE(corrections,'[]'::jsonb)::text FROM fr_eng_site_record WHERE id=?", String.class, recordId),
                java.util.Map.class);
    }

    private void assertCompletion(CompletedInspection completed, java.util.List<Long> photos, String note, java.time.LocalDate date) {
        var saved = jdbc.queryForMap("SELECT occurred_at,note,file_asset_ids::text FROM fr_eng_site_record WHERE id=?", completed.recordId());
        assertThat(saved.get("note")).isEqualTo(note);
        assertThat(String.valueOf(saved.get("occurred_at"))).isEqualTo(date.toString());
        assertThat(cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseArray(String.valueOf(saved.get("file_asset_ids")), String.class))
                .containsExactlyElementsOf(photos.stream().map(String::valueOf).toList());
    }

    @Test
    void materialMovementsCanUseTheExactAcceptedBalanceWithoutChargingTheProjectAgain() {
        as(engineer, () -> materialsService.terms(engineer, supplyId,
                new EngMaterialService.TermsCommand("钢材供应商", BigDecimal.TEN, BigDecimal.ZERO, null, null, "采购合同"), idem()));
        insertArrivalForCorrection("2.002");
        as(engineer, () -> materialsService.movement(engineer, projectId,
                new EngMaterialService.MovementCommand(materialId, "DAMAGE", new BigDecimal("1.001"), "破损", null), idem()));

        var excess = new EngMaterialService.MovementCommand(materialId, "CONSUME", new BigDecimal("1.002"), "现场领用", 41L);
        assertThatThrownBy(() -> as(engineer, () -> materialsService.movement(engineer, projectId, excess, idem())))
                .isInstanceOf(ServiceException.class).hasMessageContaining("可分配材料不足");
        var remainder = new EngMaterialService.MovementCommand(materialId, "CONSUME", new BigDecimal("1.001"), "现场领用", 41L);
        String key = idem();
        as(engineer, () -> materialsService.movement(engineer, projectId, remainder, key));
        as(engineer, () -> materialsService.movement(engineer, projectId, remainder, key));
        var smallest = new EngMaterialService.MovementCommand(materialId, "RETURN", new BigDecimal("0.001"), "余料退回", null);
        assertThatThrownBy(() -> as(engineer, () -> materialsService.movement(engineer, projectId, smallest, idem())))
                .isInstanceOf(ServiceException.class).hasMessageContaining("可分配材料不足");
        assertThat(jdbc.queryForList("SELECT quantity FROM fr_eng_material_movement WHERE material_id=? ORDER BY id",
                BigDecimal.class, materialId)).hasSize(2).allSatisfy(q -> assertThat(q).isEqualByComparingTo("1.001"));
        assertThat(jdbc.queryForList("SELECT total_cents FROM fr_fin_payable WHERE project_id=?", Long.class, projectId))
                .containsExactly(10000L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_payment WHERE project_id=?", Long.class, projectId)).isZero();
    }

    @Test
    void arrivalCorrectionPreservesAllocatedQuantityUsingOnlyAcceptedReceipts() {
        long receipt = insertArrivalForCorrection("4.002");
        insertArrivalForCorrection("0.999");
        long rejected = insertArrivalForCorrection("1.500");
        jdbc.update("UPDATE fr_eng_supply_receipt SET quality='REJECTED' WHERE id=?", rejected);
        as(engineer, () -> materialsService.movement(engineer, projectId,
                new EngMaterialService.MovementCommand(materialId, "CONSUME", new BigDecimal("3.500"), "已领用", 41L), idem()));

        var tooLow = new EngMaterialService.ArrivalCorrectionCommand(new BigDecimal("2.500"), "核对送货单");
        assertThatThrownBy(() -> as(engineer, () -> materialsService.arrivalCorrection(engineer, receipt, tooLow, idem())))
                .isInstanceOf(ServiceException.class).hasMessageContaining("已登记去向");
        assertThat(jdbc.queryForObject("SELECT actual_quantity FROM fr_eng_supply_receipt WHERE id=?", BigDecimal.class, receipt))
                .isEqualByComparingTo("4.002");
        as(engineer, () -> materialsService.arrivalCorrection(engineer, receipt,
                new EngMaterialService.ArrivalCorrectionCommand(new BigDecimal("2.501"), "合格实到与领用相等"), idem()));
        as(engineer, () -> materialsService.arrivalCorrection(engineer, rejected,
                new EngMaterialService.ArrivalCorrectionCommand(new BigDecimal("0.500"), "复核未通过质检的数量"), idem()));
        assertThat(jdbc.queryForObject("SELECT actual_quantity FROM fr_eng_supply_receipt WHERE id=?", BigDecimal.class, rejected))
                .isEqualByComparingTo("0.500");
    }

    @Test
    void arrivalAndReshipReplayVerifyTheFullReceipt() {
        long asset = insertAsset("ENG_SITE_PHOTO", true, store.orgId());
        long anotherAsset = insertAsset("ENG_SITE_PHOTO", true, store.orgId());
        var date = java.time.LocalDate.now().minusDays(1);
        String firstKey = idem();
        var initial = new EngMaterialService.ArrivalCommand(null, BigDecimal.TEN, new BigDecimal("6"),
                "OK", "first-" + supplyId, asset, date, "首批", null);
        as(engineer, () -> materialsService.arrival(engineer, supplyId, initial, firstKey));
        long reship = jdbc.queryForObject("SELECT id FROM fr_eng_supply_receipt WHERE supply_id=? AND kind='RESHIP'",
                Long.class, supplyId);
        String secondKey = idem();
        var second = new EngMaterialService.ArrivalCommand(reship, null, new BigDecimal("4"),
                "OK", "second-" + supplyId, anotherAsset, date, "补发", null);
        as(engineer, () -> materialsService.arrival(engineer, supplyId, second, secondKey));

        for (boolean historical : new boolean[]{false, true}) {
            if (historical) {
                var old = new java.util.LinkedHashMap<String, Object>();
                old.put("supplyId", supplyId);
                old.put("actual", "6");
                old.put("quality", "OK");
                replaceReceiptEvent("eng-supply-arrival", old);
                old.clear();
                old.put("receiptId", reship);
                old.put("actual", "4");
                replaceReceiptEvent("eng-reship-receive", old);
            }
            verifyArrivalRetry(firstKey, initial, anotherAsset);
            verifyArrivalRetry(secondKey, second, asset);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fr_eng_supply_receipt WHERE supply_id=?",
                Integer.class, supplyId)).isEqualTo(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void partialReshipKeepsTheRemainingQuantityOnTheOriginalDelivery() {
        jdbc.update("UPDATE fr_eng_material SET quantity = 10 WHERE id = ?", materialId);
        jdbc.update("UPDATE fr_eng_supply SET quantity = 10 WHERE id = ?", supplyId);
        long proof = insertAsset("ENG_SITE_PHOTO", true, store.orgId());
        var initial = new EngMaterialService.ArrivalCommand(null, BigDecimal.TEN, new BigDecimal("5"),
                "OK", "first-partial-reship-" + supplyId, proof, null, null, null);
        as(engineer, () -> materialsService.arrival(engineer, supplyId, initial, idem()));
        long original = jdbc.queryForObject("SELECT id FROM fr_eng_supply_receipt WHERE supply_id=? AND kind='RECEIPT'",
                Long.class, supplyId);
        String pendingSql = " FROM fr_eng_supply_receipt WHERE supply_id=? AND parent_receipt_id=?"
                + " AND kind='RESHIP' AND actual_quantity IS NULL";
        long pending = jdbc.queryForObject("SELECT id" + pendingSql, Long.class, supplyId, original);

        var partial = new EngMaterialService.ArrivalCommand(pending, null, new BigDecimal("2"),
                "OK", "second-partial-reship-" + supplyId, proof, null, null, null);
        String partialKey = idem();
        as(engineer, () -> materialsService.arrival(engineer, supplyId, partial, partialKey));
        as(engineer, () -> materialsService.arrival(engineer, supplyId, partial, partialKey));
        long successor = jdbc.queryForObject("SELECT id" + pendingSql, Long.class, supplyId, original);
        assertThat(successor).isNotEqualTo(pending);
        assertThat(jdbc.queryForObject("SELECT expected_quantity" + pendingSql, BigDecimal.class, supplyId, original))
                .isEqualByComparingTo("3");
        var visiblePending = (java.util.List<java.util.Map<String,Object>>) onlyMaterialSummaryLine().get("pendingReships");
        assertThat(visiblePending).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("id", Long.toString(successor))
                    .containsEntry("parentReceiptId", Long.toString(original));
            assertThat((BigDecimal) row.get("expectedQuantity")).isEqualByComparingTo("3");
        });
        assertThat((BigDecimal) onlyMaterialSummaryLine().get("received")).isEqualByComparingTo("7");
        assertThat((BigDecimal) onlyMaterialSummaryLine().get("remaining")).isEqualByComparingTo("3");

        var finalDelivery = new EngMaterialService.ArrivalCommand(successor, null, new BigDecimal("3"),
                "OK", "final-partial-reship-" + supplyId, proof, null, null, null);
        as(engineer, () -> materialsService.arrival(engineer, supplyId, finalDelivery, idem()));
        // A lost response for the first supplement is still safe to retry after the remaining quantity arrives.
        as(engineer, () -> materialsService.arrival(engineer, supplyId, partial, partialKey));
        assertThat(jdbc.queryForObject("SELECT count(*)" + pendingSql, Integer.class, supplyId, original)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_eng_supply_receipt WHERE supply_id=?",
                Integer.class, supplyId)).isEqualTo(3);
        assertThat((BigDecimal) onlyMaterialSummaryLine().get("received")).isEqualByComparingTo("10");
        assertThat((java.util.List<?>) onlyMaterialSummaryLine().get("pendingReships")).isEmpty();
        assertThat((BigDecimal) onlyMaterialSummaryLine().get("remaining")).isEqualByComparingTo("0");
    }

    @Test
    void legacyReceiptCannotDropAnExplicitNonDefaultExpectedQuantity() {
        long asset = insertAsset("ENG_SITE_PHOTO", true, store.orgId());
        var original = new EngMaterialService.ArrivalCommand(null, new BigDecimal("7"), new BigDecimal("6"),
                "OK", "legacy-expected-" + supplyId, asset, null, "首批", null);
        String key = idem();
        as(engineer, () -> materialsService.arrival(engineer, supplyId, original, key));
        useLegacyArrival(key, original);
        as(engineer, () -> materialsService.arrival(engineer, supplyId, original, key));
        var withoutExpected = new EngMaterialService.ArrivalCommand(null, null, original.actual(), original.quality(),
                original.transactionNo(), asset, null, original.note(), null);
        assertArrivalConflict(key, withoutExpected);
    }

    @Test
    void legacyDefaultUsesThePlanAndAcceptedReceiptsAtTheOriginalRevision() {
        long asset = insertAsset("ENG_SITE_PHOTO", true, store.orgId());
        var first = new EngMaterialService.ArrivalCommand(null, BigDecimal.ONE, BigDecimal.ONE, "REJECTED",
                "rejected-" + supplyId, asset, null, "首批", null);
        as(engineer, () -> materialsService.arrival(engineer, supplyId, first, idem()));
        long firstId = jdbc.queryForObject("SELECT id FROM fr_eng_supply_receipt WHERE transaction_no=?",
                Long.class, first.transactionNo());
        as(engineer, () -> materialsService.arrival(engineer, supplyId, qualityCommand(firstId, "复验合格", asset), idem()));
        as(engineer, () -> materialsService.arrivalCorrection(engineer, firstId,
                new EngMaterialService.ArrivalCorrectionCommand(new BigDecimal("2"), "更正前批实收"), idem()));
        var partial = new EngMaterialService.ArrivalCommand(null, new BigDecimal("3"), BigDecimal.ONE, "OK",
                "partial-" + supplyId, asset, null, "分批实收", null);
        as(engineer, () -> materialsService.arrival(engineer, supplyId, partial, idem()));
        long partialId = jdbc.queryForObject("SELECT id FROM fr_eng_supply_receipt WHERE transaction_no=?",
                Long.class, partial.transactionNo());
        long pending = jdbc.queryForObject("SELECT id FROM fr_eng_supply_receipt WHERE parent_receipt_id=?",
                Long.class, partialId);
        as(engineer, () -> materialsService.arrival(engineer, supplyId, new EngMaterialService.ArrivalCommand(
                pending, null, BigDecimal.ONE, "OK", "reship-" + supplyId, asset, null, "补收", null), idem()));
        as(engineer, () -> materialsService.arrivalCorrection(engineer, partialId,
                new EngMaterialService.ArrivalCorrectionCommand(new BigDecimal("2"), "复核合格批次实到"), idem()));
        as(engineer, () -> materialsService.adjust(engineer, materialId,
                new EngMaterialService.AdjustCommand(new BigDecimal("12"), "增加需用量"), idem()));

        var target = new EngMaterialService.ArrivalCommand(null, null, new BigDecimal("3"), "OK",
                "default-before-change-" + supplyId, asset, null, "第二批", null);
        String key = idem();
        as(engineer, () -> materialsService.arrival(engineer, supplyId, target, key));
        assertThat(jdbc.queryForObject("SELECT expected_quantity FROM fr_eng_supply_receipt WHERE transaction_no=?",
                BigDecimal.class, target.transactionNo())).isEqualByComparingTo("7");
        useLegacyArrival(key, target);
        as(engineer, () -> materialsService.adjust(engineer, materialId,
                new EngMaterialService.AdjustCommand(new BigDecimal("15"), "后续增加需用量"), idem()));
        as(engineer, () -> materialsService.arrival(engineer, supplyId, target, key));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_eng_supply_receipt WHERE transaction_no=?",
                Integer.class, target.transactionNo())).isOne();
    }

    @Test
    void legacyDefaultWithoutItsOriginalPlanRequestsReconciliation() {
        long asset = insertAsset("ENG_SITE_PHOTO", true, store.orgId());
        var original = new EngMaterialService.ArrivalCommand(null, null, BigDecimal.ONE, "OK",
                "no-original-plan-" + supplyId, asset, null, "历史收货", null);
        String key = idem();
        as(engineer, () -> materialsService.arrival(engineer, supplyId, original, key));
        useLegacyArrival(key, original);
        as(engineer, () -> materialsService.adjust(engineer, materialId,
                new EngMaterialService.AdjustCommand(new BigDecimal("8"), "调整后需用量"), idem()));
        assertThatThrownBy(() -> as(engineer, () -> materialsService.arrival(engineer, supplyId, original, key)))
                .isInstanceOf(ServiceException.class).hasMessageContaining("核对原到货单");
    }

    private void useLegacyArrival(String key, EngMaterialService.ArrivalCommand command) {
        var old = new java.util.LinkedHashMap<String, Object>();
        old.put("supplyId", supplyId);
        old.put("actual", EngGuard.requireQuantity(command.actual()).toPlainString());
        old.put("quality", command.quality());
        String payload = cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(old);
        assertThat(jdbc.update("UPDATE pf_command_event SET payload=?,payload_hash=? "
                        + "WHERE aggregate_id=? AND command='eng-supply-arrival' AND idempotency_key=?",
                payload, cn.hutool.crypto.digest.DigestUtil.sha256Hex(payload), projectId,
                "franchise:" + engineer.userId() + ":" + key)).isOne();
    }

    private void verifyArrivalRetry(String key, EngMaterialService.ArrivalCommand original, long anotherAsset) {
        as(engineer, () -> materialsService.arrival(engineer, supplyId, original, key));
        Long parent = original.parentReceiptId();
        BigDecimal expected = original.expected();
        BigDecimal actual = original.actual();
        String txn = original.transactionNo();
        Long asset = original.fileAssetId();
        var date = original.occurredAt();
        String note = original.note();
        for (var changed : java.util.List.of(
                new EngMaterialService.ArrivalCommand(parent, BigDecimal.ONE, actual, "OK", txn, asset, date, note, null),
                new EngMaterialService.ArrivalCommand(parent, expected, actual, "OK", txn + "x", asset, date, note, null),
                new EngMaterialService.ArrivalCommand(parent, expected, actual, "OK", txn, anotherAsset, date, note, null),
                new EngMaterialService.ArrivalCommand(parent, expected, actual, "OK", txn, asset, date.plusDays(1), note, null),
                new EngMaterialService.ArrivalCommand(parent, expected, actual, "OK", txn, asset, date, "另一备注", null),
                new EngMaterialService.ArrivalCommand(parent, expected, actual, "REJECTED", txn, asset, date, note, null))) {
            assertArrivalConflict(key, changed);
        }
    }

    private void assertArrivalConflict(String key, EngMaterialService.ArrivalCommand changed) {
        cn.iocoder.yudao.module.franchise.test.FranchiseAssertions.assertError(
                () -> as(engineer, () -> materialsService.arrival(engineer, supplyId, changed, key)),
                cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED);
    }

    private void replaceReceiptEvent(String command, java.util.Map<String, Object> old) {
        String text = cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(old);
        assertThat(jdbc.update("UPDATE pf_command_event SET payload=?,payload_hash=? WHERE aggregate_id=? AND command=?",
                text, cn.hutool.crypto.digest.DigestUtil.sha256Hex(text), projectId, command)).isOne();
    }

    @Test
    void qualityReplayVerifiesTheResolutionNoteAndAsset() {
        long asset = insertAsset("ENG_SITE_PHOTO", true, store.orgId());
        long other = insertAsset("ENG_SITE_PHOTO", true, store.orgId());
        String txn = "rejected-" + supplyId;
        var rejected = new EngMaterialService.ArrivalCommand(null, BigDecimal.ONE, BigDecimal.ONE, "REJECTED",
                txn, asset, null, "质检不合格", null);
        as(engineer, () -> materialsService.arrival(engineer, supplyId, rejected, idem()));
        long receipt = jdbc.queryForObject("SELECT id FROM fr_eng_supply_receipt WHERE supply_id=? AND transaction_no=?",
                Long.class, supplyId, txn);
        String key = idem();
        var resolution = qualityCommand(receipt, "补件验收合格", asset);
        as(engineer, () -> materialsService.arrival(engineer, supplyId, resolution, key));
        for (boolean historical : new boolean[]{false, true}) {
            if (historical) replaceReceiptEvent("eng-supply-quality", java.util.Map.of("receiptId", receipt));
            as(engineer, () -> materialsService.arrival(engineer, supplyId, resolution, key));
            assertArrivalConflict(key, qualityCommand(receipt, "其他说明", asset));
            assertArrivalConflict(key, qualityCommand(receipt, "补件验收合格", other));
        }
        assertThat(jdbc.queryForObject("SELECT quality_resolution->>'note' FROM fr_eng_supply_receipt WHERE id=?",
                String.class, receipt)).isEqualTo("补件验收合格");
    }

    private EngMaterialService.ArrivalCommand qualityCommand(long receipt, String note, long asset) {
        return new EngMaterialService.ArrivalCommand(null, null, null, null, null, null, null, null,
                java.util.Map.of("receiptId", receipt, "fileAssetId", asset, "note", note));
    }

    @Test
    void arrivalCorrectionAcceptsExactPlanAndReplaysWithoutAnotherCorrection() {
        jdbc.update("UPDATE fr_eng_material SET quantity = 100 WHERE id = ?", materialId);
        jdbc.update("UPDATE fr_eng_supply SET quantity = 100 WHERE id = ?", supplyId);
        insertArrivalForCorrection("20");
        long receiptId = insertArrivalForCorrection("70");
        String key = idem();
        var command = new EngMaterialService.ArrivalCorrectionCommand(new BigDecimal("80"), "补正实到数量");

        as(engineer, () -> materialsService.arrivalCorrection(engineer, receiptId, command, key));
        as(engineer, () -> materialsService.arrivalCorrection(engineer, receiptId, command, key));

        assertThat(jdbc.queryForObject("SELECT SUM(actual_quantity) FROM fr_eng_supply_receipt WHERE supply_id = ?",
                BigDecimal.class, supplyId)).isEqualByComparingTo("100");
        assertThat(jdbc.queryForObject("SELECT expected_quantity FROM fr_eng_supply_receipt WHERE id = ?",
                BigDecimal.class, receiptId)).isEqualByComparingTo("80");
        assertThat(jdbc.queryForObject("SELECT jsonb_array_length(corrections) FROM fr_eng_supply_receipt WHERE id = ?",
                Integer.class, receiptId)).isEqualTo(1);
    }

    @Test
    void correctionReplayChecksOriginalReasonAndMaterialMovementChecksReceiver() {
        long receiptId = insertArrivalForCorrection("3");
        String correctKey = idem();
        var correction = new EngMaterialService.ArrivalCorrectionCommand(new BigDecimal("4"), "复核交货单");
        var wrongReason = new EngMaterialService.ArrivalCorrectionCommand(correction.actual(), "改写原因为损耗");
        as(engineer, () -> materialsService.arrivalCorrection(engineer, receiptId, correction, correctKey));
        String moveKey = idem();
        var move = new EngMaterialService.MovementCommand(materialId, "CONSUME", BigDecimal.ONE, "一层领用", 41L);
        as(engineer, () -> materialsService.movement(engineer, projectId, move, moveKey));
        for (boolean historical : new boolean[]{false, true}) {
            if (historical) {
                var old = new java.util.LinkedHashMap<String, Object>();
                old.put("receiptId", receiptId);
                old.put("to", "4");
                useOldMaterialEvent("eng-arrival-correct", old);
                old.clear();
                old.put("materialId", materialId);
                old.put("kind", "CONSUME");
                old.put("quantity", "1");
                useOldMaterialEvent("eng-material-movement", old);
            }
            as(engineer, () -> materialsService.arrivalCorrection(engineer, receiptId, correction, correctKey));
            as(engineer, () -> materialsService.movement(engineer, projectId, move, moveKey));
            var reused = cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED;
            cn.iocoder.yudao.module.franchise.test.FranchiseAssertions.assertError(
                    () -> as(engineer, () -> materialsService.arrivalCorrection(engineer, receiptId, wrongReason, correctKey)), reused);
            for (var changed : java.util.List.of(
                    new EngMaterialService.MovementCommand(materialId, move.kind(), move.quantity(), "二层领用", 41L),
                    new EngMaterialService.MovementCommand(materialId, move.kind(), move.quantity(), move.note(), 42L))) {
                cn.iocoder.yudao.module.franchise.test.FranchiseAssertions.assertError(
                        () -> as(engineer, () -> materialsService.movement(engineer, projectId, changed, moveKey)), reused);
            }
        }
        assertThat(jdbc.queryForObject("SELECT jsonb_array_length(corrections) FROM fr_eng_supply_receipt WHERE id=?",
                Integer.class, receiptId)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fr_eng_material_movement WHERE material_id=?",
                Integer.class, materialId)).isOne();
    }

    private void useOldMaterialEvent(String command, java.util.Map<String, Object> input) {
        String payload = cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(input);
        assertThat(jdbc.update("UPDATE pf_command_event SET payload=?,payload_hash=? WHERE aggregate_id=? AND command=?",
                payload, cn.hutool.crypto.digest.DigestUtil.sha256Hex(payload), projectId, command)).isOne();
    }

    @Test
    void arrivalCorrectionKeepsExactDecimalUpperAndCompletedNodeLowerBounds() {
        jdbc.update("UPDATE fr_eng_material SET quantity = 100 WHERE id = ?", materialId);
        jdbc.update("UPDATE fr_eng_supply SET quantity = 100 WHERE id = ?", supplyId);
        insertArrivalForCorrection("20");
        long receiptId = insertArrivalForCorrection("70");
        as(engineer, () -> materialsService.arrivalCorrection(engineer, receiptId,
                new EngMaterialService.ArrivalCorrectionCommand(new BigDecimal("79.999"), "未完成节点允许未到齐"), idem()));
        assertThatThrownBy(() -> as(engineer, () -> materialsService.arrivalCorrection(engineer, receiptId,
                new EngMaterialService.ArrivalCorrectionCommand(new BigDecimal("80.001"), "超过计划"), idem())))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode()).isEqualTo(1115000004));
        assertThat(jdbc.queryForObject("SELECT actual_quantity FROM fr_eng_supply_receipt WHERE id = ?",
                BigDecimal.class, receiptId)).isEqualByComparingTo("79.999");

        jdbc.update("UPDATE fr_eng_plan_node SET status = 'DONE' WHERE id = ?", nodeId);
        assertThatThrownBy(() -> as(engineer, () -> materialsService.arrivalCorrection(engineer, receiptId,
                new EngMaterialService.ArrivalCorrectionCommand(new BigDecimal("79.998"), "完成节点不能改为未到齐"), idem())))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode()).isEqualTo(1115000004));
        assertThat(jdbc.queryForObject("SELECT actual_quantity FROM fr_eng_supply_receipt WHERE id = ?",
                BigDecimal.class, receiptId)).isEqualByComparingTo("79.999");
        as(engineer, () -> materialsService.arrivalCorrection(engineer, receiptId,
                new EngMaterialService.ArrivalCorrectionCommand(new BigDecimal("80.000"), "更正到计划值"), idem()));
        assertThat(jdbc.queryForObject("SELECT SUM(actual_quantity) FROM fr_eng_supply_receipt WHERE supply_id = ?",
                BigDecimal.class, supplyId)).isEqualByComparingTo("100.000");
    }

    @Test
    @SuppressWarnings("unchecked")
    void materialSummarySeparatesUsageReturnAndOpenReshipByOriginalLineAndScope() {
        long proof = insertAsset("ENG_SITE_PHOTO", true, store.orgId());
        as(engineer, () -> materialsService.arrival(engineer, supplyId, new EngMaterialService.ArrivalCommand(
                null, new BigDecimal("4"), new BigDecimal("3"), "OK", "FLOW-" + supplyId, proof, null, null, null), idem()));
        long rejected = insertArrivalForCorrection("0.500");
        jdbc.update("UPDATE fr_eng_supply_receipt SET quality='REJECTED' WHERE id=?", rejected);
        for (String kind : java.util.List.of("CONSUME", "RETURN")) {
            as(engineer, () -> materialsService.movement(engineer, projectId, new EngMaterialService.MovementCommand(
                    materialId, kind, new BigDecimal("0.250"), "原单 " + kind, engineer.userId()), idem()));
        }
        var row = onlyMaterialSummaryLine();
        assertThat((BigDecimal) row.get("consumedQuantity")).isEqualByComparingTo("0.250");
        assertThat((BigDecimal) row.get("availableQuantity")).isEqualByComparingTo("2.500");
        var history = (java.util.List<java.util.Map<String, Object>>) row.get("movements");
        assertThat(history).extracting(m -> m.get("kind")).containsExactly("RETURN", "CONSUME");
        assertThat(history.get(0)).containsEntry("settlementStatus", "PENDING_SETTLE").containsEntry("note", "原单 RETURN")
                .containsEntry("authorUserId", Long.toString(engineer.userId())).containsEntry("receiverUserId", Long.toString(engineer.userId()));
        assertThat(history.get(0).get("id")).isInstanceOf(String.class);
        assertThat(history.get(0).get("occurredAt")).isNotNull();
        var pending = (java.util.List<java.util.Map<String, Object>>) row.get("pendingReships");
        assertThat(pending).hasSize(1);
        assertThat((BigDecimal) pending.get(0).get("expectedQuantity")).isEqualByComparingTo("1.000");
        long reship = Long.parseLong((String) pending.get(0).get("id"));
        assertThat(pending.get(0).get("parentReceiptId")).isInstanceOf(String.class);
        as(engineer, () -> materialsService.arrival(engineer, supplyId, new EngMaterialService.ArrivalCommand(
                reship, null, BigDecimal.ONE, "OK", "REPLACE-" + supplyId, proof, null, null, null), idem()));
        row = onlyMaterialSummaryLine();
        assertThat((java.util.List<?>) row.get("pendingReships")).isEmpty();
        assertThat((BigDecimal) row.get("availableQuantity")).isEqualByComparingTo("3.500");
        assertThat((BigDecimal) row.get("allocationShortfallQuantity")).isEqualByComparingTo("0");
        jdbc.update("INSERT INTO fr_eng_material_movement(id,company_id,project_id,material_id,kind,quantity,author_user_id) " +
                "VALUES (?,?,?,?,'CONSUME',4.250,?)", IDS.incrementAndGet(), store.orgId(), projectId, materialId, engineer.userId());
        row = onlyMaterialSummaryLine();
        assertThat((BigDecimal) row.get("availableQuantity")).isEqualByComparingTo("0");
        assertThat((BigDecimal) row.get("allocationShortfallQuantity")).isEqualByComparingTo("0.750");

        var sales = fixture.post(store.orgId(), "sales", null);
        var plans = (java.util.List<java.util.Map<String, Object>>) materialsService.summary(sales, projectId, null).get("plans");
        var limited = (java.util.List<java.util.Map<String, Object>>) plans.get(0).get("lines");
        assertThat(limited.get(0)).doesNotContainKeys("consumedQuantity", "availableQuantity", "allocationShortfallQuantity", "movements", "pendingReships");
        var foreign = fixture.post(fixture.store().orgId(), "finance", null);
        assertThatThrownBy(() -> materialsService.summary(foreign, projectId, null)).isInstanceOf(ServiceException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pendingReshipIsNotAnArrivalUntilItIsActuallyReceived() {
        jdbc.update("UPDATE fr_eng_material SET quantity = 10 WHERE id = ?", materialId);
        jdbc.update("UPDATE fr_eng_supply SET quantity = 10 WHERE id = ?", supplyId);
        long initialEvidence = insertAsset("ENG_SITE_PHOTO", true, store.orgId());
        as(engineer, () -> materialsService.arrival(engineer, supplyId,
                new EngMaterialService.ArrivalCommand(null, new BigDecimal("10"), new BigDecimal("6"),
                        "OK", "initial-arrival-" + supplyId, initialEvidence, null, null, null), idem()));
        long outstanding = jdbc.queryForObject(
                "SELECT id FROM fr_eng_supply_receipt WHERE supply_id=? AND kind='RESHIP'", Long.class, supplyId);
        var before = onlyMaterialSummaryLine();
        assertThat((BigDecimal) before.get("received")).isEqualByComparingTo("6");
        assertThat((BigDecimal) before.get("remaining")).isEqualByComparingTo("4");
        assertThat((java.util.List<java.util.Map<String,Object>>) before.get("receipts")).hasSize(1);

        assertThatThrownBy(() -> as(engineer, () -> materialsService.arrivalCorrection(engineer, outstanding,
                new EngMaterialService.ArrivalCorrectionCommand(new BigDecimal("4"), "记录未实收到货"), idem())))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode()).isEqualTo(1115000001));
        assertThat(jdbc.queryForObject("SELECT actual_quantity FROM fr_eng_supply_receipt WHERE id=?",
                BigDecimal.class, outstanding)).isNull();

        long arrivalEvidence = insertAsset("ENG_SITE_PHOTO", true, store.orgId());
        as(engineer, () -> materialsService.arrival(engineer, supplyId,
                new EngMaterialService.ArrivalCommand(outstanding, null, new BigDecimal("4"),
                        "OK", "replacement-arrival-" + supplyId, arrivalEvidence, null, null, null), idem()));
        var after = onlyMaterialSummaryLine();
        assertThat((BigDecimal) after.get("received")).isEqualByComparingTo("10");
        assertThat((BigDecimal) after.get("remaining")).isEqualByComparingTo("0");
        var arrivals = (java.util.List<java.util.Map<String,Object>>) after.get("receipts");
        assertThat(arrivals).hasSize(2).anySatisfy(row -> {
            assertThat(row.get("id")).isEqualTo(String.valueOf(outstanding));
            assertThat(row.get("fileAssetId")).isEqualTo(arrivalEvidence);
            assertThat((BigDecimal) row.get("actualQuantity")).isEqualByComparingTo("4");
        });
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String,Object> onlyMaterialSummaryLine() {
        var plans = (java.util.List<java.util.Map<String,Object>>) materialsService.summary(engineer, projectId, null).get("plans");
        return plans.stream().flatMap(x -> ((java.util.List<java.util.Map<String,Object>>) x.get("lines")).stream())
                .filter(row -> row.get("materialId").equals(materialId)).findFirst().orElseThrow();
    }

    private long insertArrivalForCorrection(String actual) {
        long id = IDS.incrementAndGet();
        long assetId = insertAsset("ENG_SITE_PHOTO", true, store.orgId());
        jdbc.update("INSERT INTO fr_eng_supply_receipt (id, company_id, project_id, supply_id, kind, "
                        + "transaction_no, occurred_at, file_asset_id, quality, actual_quantity, expected_quantity, author_user_id) "
                        + "VALUES (?, ?, ?, ?, 'RECEIPT', ?, CURRENT_DATE, ?, 'OK', ?, ?, ?)",
                id, store.orgId(), projectId, supplyId, "ARRIVAL-CORRECTION-" + id, assetId,
                new BigDecimal(actual), new BigDecimal(actual), engineer.userId());
        return id;
    }

    // ========== M07-T03：资产端口引用校验 ==========

    @Test
    void fakeOrForeignOrUnacceptedAssetIsRejectedOnStartPreparation() {
        long fakeId = IDS.incrementAndGet();
        long otherOrg = IDS.incrementAndGet();
        // 假 ID（不存在）
        assertThatThrownBy(() -> as(engineer, () -> planService.prepare(engineer, projectId,
                startCmd(fakeId), idem()))).isInstanceOf(ServiceException.class);
        // 未 ACCEPTED
        long unaccepted = insertAsset("ENG_SITE_PHOTO", false, store.orgId());
        assertThatThrownBy(() -> as(engineer, () -> planService.prepare(engineer, projectId,
                startCmd(unaccepted), idem()))).isInstanceOf(ServiceException.class);
        // 类型不符（DOC 类不在合同凭据允许集合）
        long wrongType = insertAsset("DOC_OTHER", true, store.orgId());
        assertThatThrownBy(() -> as(engineer, () -> planService.prepare(engineer, projectId,
                startCmd(wrongType), idem()))).isInstanceOf(ServiceException.class);
        // 跨组织（他组织属主的已验收资产）：validateReference 属主过滤拒绝（R1 P2）
        long foreign = insertAsset("ENG_SITE_PHOTO", true, otherOrg);
        assertThatThrownBy(() -> as(engineer, () -> planService.prepare(engineer, projectId,
                startCmd(foreign), idem()))).isInstanceOf(ServiceException.class);
        // 零落痕：仅 setUp 预置的 plan_version 行（insertMaterial 建行），无 prepare 新增
        Integer preps = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fr_eng_plan_version WHERE project_id = ?", Integer.class, projectId);
        assertThat(preps).isEqualTo(1);
    }

    @Test
    void deliverRejectsInvalidEvidenceAssets() {
        // M09-T01/I01：交付凭据域侧校验——前置链到交付门口（节点全 DONE+材料到齐+全累计收款）
        jdbc.update("UPDATE fr_eng_project SET customer_id = ? WHERE id = ?",
                IDS.incrementAndGet(), projectId); // 交付全累计收款门禁走统一账读侧
        long validAsset = insertAsset("ENG_SITE_PHOTO", true, store.orgId());
        as(engineer, () -> planService.prepare(engineer, projectId, startCmd(validAsset), idem()));
        jdbc.update("UPDATE fr_eng_plan_node SET start_date = CURRENT_DATE, end_date = CASE WHEN milestone='START' THEN CURRENT_DATE ELSE CURRENT_DATE + 30 END "
                + "WHERE project_id = ?", projectId);
        long total = 40800000L + 18000000L + 9200000L;
        jdbc.update("UPDATE fr_eng_collection_term SET amount_cents = 40800000 WHERE project_id = ? AND stage = 'SIGN_AFTER'", projectId);
        jdbc.update("UPDATE fr_eng_collection_term SET amount_cents = 18000000 WHERE project_id = ? AND stage = 'BASE_DONE'", projectId);
        jdbc.update("UPDATE fr_eng_collection_term SET amount_cents = 9200000 WHERE project_id = ? AND stage = 'CAP_DONE'", projectId);
        Config.STAGE_COLLECTED_MAP.put(Config.SIGN_AFTER, 40800000L);
        Config.STAGE_COLLECTED_MAP.put(Config.BASE_DONE, 18000000L);
        Config.STAGE_COLLECTED_MAP.put(Config.CAP_DONE, 9200000L); // 交付全累计收款门禁（统一账读侧）
        as(engineer, () -> planService.start(engineer, projectId,
                new EngPlanService.NodeRecordCommand(java.time.LocalDate.now(), "实际开工", validAsset, true), idem()));
        jdbc.update("UPDATE fr_eng_material SET quantity = 15.000 WHERE id = ?", materialId);
        jdbc.update("UPDATE fr_eng_supply SET quantity = 15.000 WHERE id = ?", supplyId);
        long arrivalAsset = insertAsset("ENG_SITE_PHOTO", true, store.orgId());
        jdbc.update("INSERT INTO fr_eng_supply_receipt (id, company_id, project_id, supply_id, kind, "
                        + "transaction_no, occurred_at, file_asset_id, quality, actual_quantity, "
                        + "author_user_id, create_time) "
                        + "VALUES (?, ?, ?, ?, 'RECEIPT', 'TXN-DLV', CURRENT_DATE, ?, 'OK', 15.000, ?, now())",
                IDS.incrementAndGet(), store.orgId(), projectId, supplyId, arrivalAsset, engineer.userId());
        jdbc.update("UPDATE fr_eng_plan_node SET status = 'DONE' WHERE project_id = ?", projectId);

        // 假 ID 交付凭据：拒绝（M09-T01 核心）
        long fakeId = IDS.incrementAndGet();
        assertThatThrownBy(() -> asVoid(engineer, () -> closuresService.deliver(engineer, projectId,
                new EngClosureService.DeliverCommand(java.time.LocalDate.now(), "业主-陈先生", null, fakeId, "交付"), idem())))
                .isInstanceOfSatisfying(ServiceException.class,
                        error -> assertThat(error.getCode()).isEqualTo(1_115_000_001))
                .hasMessage("提交内容不合法：交付凭据资产不存在、未验收、不属于本组织或类型不符");
        // 未 ACCEPTED：拒绝
        long unaccepted = insertAsset("ENG_SITE_PHOTO", false, store.orgId());
        assertThatThrownBy(() -> asVoid(engineer, () -> closuresService.deliver(engineer, projectId,
                new EngClosureService.DeliverCommand(java.time.LocalDate.now(), "业主-陈先生", null, unaccepted, "交付"), idem())))
                .isInstanceOf(ServiceException.class);
        // 合法凭据（交付+业主签收）：交付成功落列
        long validEvidence = insertAsset("ENG_SITE_PHOTO", true, store.orgId());
        long ownerEvidence = insertAsset("ENG_SITE_PHOTO", true, store.orgId());
        // 固定成功交付的键——后续同键换资产用其重放（R3 P2：指纹含两资产 ID）
        String deliverKey = idem();
        java.util.Map<String, Object> delivered = as(engineer, () -> closuresService.deliver(engineer, projectId,
                new EngClosureService.DeliverCommand(java.time.LocalDate.now(), "业主-陈先生", ownerEvidence, validEvidence, "交付"), deliverKey));
        assertThat(delivered).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT file_asset_id FROM fr_eng_closure WHERE project_id = ?", Long.class, projectId))
                .isEqualTo(validEvidence);

        // 同键换资产（fileAssetId 假资产）：IDEMPOTENCY_KEY_REUSED——指纹含资产 ID（R3 P2）
        long fakeSwap = IDS.incrementAndGet();
        assertThatThrownBy(() -> asVoid(engineer, () -> closuresService.deliver(engineer, projectId,
                new EngClosureService.DeliverCommand(java.time.LocalDate.now(), "业主-陈先生", ownerEvidence, fakeSwap, "交付"), deliverKey)))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode())
                .isEqualTo(cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED.getCode());
        // 同键换资产（ownerAssetId 假资产）：同样拒绝
        long fakeSwapOwner = IDS.incrementAndGet();
        assertThatThrownBy(() -> asVoid(engineer, () -> closuresService.deliver(engineer, projectId,
                new EngClosureService.DeliverCommand(java.time.LocalDate.now(), "业主-陈先生", fakeSwapOwner, validEvidence, "交付"), deliverKey)))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode())
                .isEqualTo(cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED.getCode());
        var delivery = new EngClosureService.DeliverCommand(java.time.LocalDate.now(), "业主-陈先生",
                ownerEvidence, validEvidence, "交付");
        var revised = new EngClosureService.DeliverCommand(delivery.date(), delivery.recipient(),
                ownerEvidence, validEvidence, "改写交付备注");
        var settlement = new EngClosureService.SettleCommand(delivery.date(), "核对结算", validEvidence);
        var invalidEvidence = new EngClosureService.SettleCommand(delivery.date(), "核对结算", fakeId);
        cn.iocoder.yudao.module.franchise.test.FranchiseAssertions.assertError(
                () -> as(engineer, () -> closuresService.settle(engineer, projectId, invalidEvidence, idem())),
                cn.iocoder.yudao.module.franchise.engineering.enums.FranchiseEngineeringErrorCodes.ENGINEERING_INPUT_INVALID);
        String settleKey = idem();
        as(engineer, () -> closuresService.settle(engineer, projectId, settlement, settleKey));
        for (boolean historical : new boolean[]{false, true}) {
            if (historical) {
                var old = new java.util.LinkedHashMap<String, Object>();
                old.put("projectId", projectId);
                old.put("date", delivery.date().toString());
                old.put("recipient", delivery.recipient());
                old.put("ownerAssetId", String.valueOf(ownerEvidence));
                old.put("fileAssetId", String.valueOf(validEvidence));
                useOldClosureEvent("eng-deliver", old);
                old.clear();
                old.put("date", settlement.date().toString());
                old.put("note", settlement.note());
                useOldClosureEvent("eng-settle", old);
            }
            as(engineer, () -> closuresService.deliver(engineer, projectId, delivery, deliverKey));
            as(engineer, () -> closuresService.settle(engineer, projectId, settlement, settleKey));
            var reused = cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED;
            cn.iocoder.yudao.module.franchise.test.FranchiseAssertions.assertError(
                    () -> as(engineer, () -> closuresService.deliver(engineer, projectId, revised, deliverKey)), reused);
            var otherAsset = new EngClosureService.SettleCommand(settlement.date(), settlement.note(), ownerEvidence);
            cn.iocoder.yudao.module.franchise.test.FranchiseAssertions.assertError(
                    () -> as(engineer, () -> closuresService.settle(engineer, projectId, otherAsset, settleKey)), reused);
        }
        long otherProject = insertProject("另一工程结算核对");
        cn.iocoder.yudao.module.franchise.test.FranchiseAssertions.assertError(
                () -> as(engineer, () -> closuresService.settle(engineer, otherProject, settlement, settleKey)),
                cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED);
        as(engineer, () -> closuresService.archive(engineer, projectId, idem()));
        as(engineer, () -> closuresService.settle(engineer, projectId, settlement, settleKey));
        // 库内交付行仍是原凭据（未被换资产覆盖）
        assertThat(jdbc.queryForObject(
                "SELECT file_asset_id FROM fr_eng_closure WHERE project_id = ?", Long.class, projectId))
                .isEqualTo(validEvidence);
        assertThat(jdbc.queryForObject(
                "SELECT owner_asset_id FROM fr_eng_closure WHERE project_id = ?", Long.class, projectId))
                .isEqualTo(ownerEvidence); // R4 P2：双凭据保留断言闭环
    }

    @Test
    void settlementProjectLockIsScopedToTheCompanyAndCurrentOwners() {
        Store otherStore = fixture.store();
        PostSnapshot assigned = fixture.post(store.orgId(), "engineer", store.engineeringTeam());
        PostSnapshot another = fixture.post(store.orgId(), "engineer", store.engineeringTeam());
        jdbc.update("UPDATE fr_eng_project SET assigned_engineer_user_id=? WHERE id=?", assigned.userId(), projectId);
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {
            var projects = projectService.projects();
            assertThat(projects.lockOwnedByCompany(projectId, otherStore.orgId(), engineer.userId())).isNull();
            assertThat(projects.lockOwnedByCompany(projectId, store.orgId(), another.userId())).isNull();
            assertThat(projects.lockOwnedByCompany(projectId, store.orgId(), engineer.userId()).getId())
                    .isEqualTo(projectId);
            assertThat(projects.lockOwnedByCompany(projectId, store.orgId(), assigned.userId()).getId())
                    .isEqualTo(projectId);
        });
    }

    @Test
    void simultaneousSettlementRetriesReadTheSameReceipt() throws Exception {
        long evidence = insertAsset("ENG_SITE_PHOTO", true, store.orgId());
        jdbc.update("UPDATE fr_eng_closure SET delivered_at=CURRENT_DATE,recipient=?,file_asset_id=? WHERE project_id=?",
                "项目接收人", evidence, projectId);
        String key = idem();
        var command = new EngClosureService.SettleCommand(null, "核实施工结算", null);
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        var waiting = new java.util.concurrent.atomic.AtomicInteger();
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
        try {
            var second = tx.execute(status -> {
                int firstPid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                as(engineer, () -> closuresService.settle(engineer, projectId, command, key));
                var replay = pool.submit(() -> tx.execute(other -> {
                    waiting.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    return as(engineer, () -> closuresService.settle(engineer, projectId, command, key));
                }));
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(8)).until(() ->
                        waiting.get() != 0 && Boolean.TRUE.equals(jdbc.queryForObject(
                                "SELECT ? = ANY(pg_blocking_pids(?))", Boolean.class, firstPid, waiting.get())));
                return replay;
            });
            assertThat(second.get(10, java.util.concurrent.TimeUnit.SECONDS)).isNotEmpty();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_command_event WHERE aggregate_id=? AND command='eng-settle'",
                    Integer.class, projectId)).isOne();
        } finally {
            pool.shutdownNow();
        }
    }

    private void useOldClosureEvent(String command, java.util.Map<String, Object> old) {
        String payload = cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(old);
        assertThat(jdbc.update("UPDATE pf_command_event SET payload=?,payload_hash=? WHERE aggregate_id=? AND command=?",
                payload, cn.hutool.crypto.digest.DigestUtil.sha256Hex(payload), projectId, command)).isOne();
    }

    @Test
    void validAcceptedAssetOfAllowedTypePasses() {
        long contractAsset = insertAsset("ENG_SITE_PHOTO", true, store.orgId());
        as(engineer, () -> planService.prepare(engineer, projectId,
                startCmd(contractAsset), idem()));
        assertThat(jdbc.queryForObject(
                "SELECT contract_asset_id FROM fr_eng_project WHERE id = ?", Long.class, projectId))
                .isEqualTo(contractAsset);
    }

    // ========== 辅助 ==========

    @Resource private EngProjectService projectService;

    @Resource private cn.iocoder.yudao.module.franchise.finance.FinWorkbenchService financeWorkbench;

    @Test
    void cancelledMaterialKeepsOriginalDebtAndActualCashUntilFinancialReconciliation() {
        as(engineer, () -> materialsService.terms(engineer, supplyId,
                new EngMaterialService.TermsCommand("原供应商", BigDecimal.TEN, BigDecimal.ZERO, null, null, "原采购依据"), idem()));
        long payableId = jdbc.queryForObject("SELECT payable_id FROM fr_eng_supply WHERE id=?", Long.class, supplyId);
        as(engineer, () -> materialsService.adjust(engineer, materialId,
                new EngMaterialService.AdjustCommand(BigDecimal.ZERO, "取消材料需用"), idem()));
        var summary = as(engineer, () -> closuresService.settleSummary(engineer, projectId));
        assertThat(summary).containsEntry("materialsTotalCents", 10000L)
                .containsEntry("materialsPaidCents", 0L).containsEntry("materialsRemainingCents", 10000L)
                .containsEntry("expensesTotalCents", 0L);
        assertThat(summary.get("materialsNote").toString()).contains("已取消材料", "原应付");
        assertThat(jdbc.queryForObject("SELECT total_cents FROM fr_fin_payable WHERE id=?", Long.class, payableId)).isEqualTo(10000L);
        var otherEngineer = fixture.post(store.orgId(), "engineer", store.engineeringTeam());
        assertThat(as(otherEngineer, () -> closuresService.settleSummary(otherEngineer, projectId)))
                .doesNotContainKeys("materialsTotalCents", "materialsPaidCents", "materialsNote");

        var finance = fixture.post(store.orgId(), "finance", null);
        String paymentKey = idem();
        var payment = new cn.iocoder.yudao.module.franchise.finance.FinPaymentService.PaymentCommand(
                payableId, 4000, LocalDate.now(), null, null);
        long paymentId = paymentService.register(finance, paymentKey, payment);
        assertThat(paymentService.register(finance, paymentKey, payment)).isEqualTo(paymentId);
        assertThat(as(engineer, () -> closuresService.settleSummary(engineer, projectId)))
                .containsEntry("materialsTotalCents", 10000L).containsEntry("materialsPaidCents", 4000L)
                .containsEntry("materialsRemainingCents", 6000L).containsEntry("expensesTotalCents", 0L);
        refundService.registerExpense(finance, idem(), new cn.iocoder.yudao.module.franchise.finance.FinRefundService.ExpenseRefundCommand(
                paymentId, 4000, "原采购取消后实际退回", LocalDate.now(), null, null, null));
        financeWorkbench.adjustPayable(finance, payableId, idem(),
                new cn.iocoder.yudao.module.franchise.finance.FinWorkbenchService.PayableAdjustment(0, "原采购已核对取消", null));
        assertThat(as(engineer, () -> closuresService.settleSummary(engineer, projectId)))
                .containsEntry("materialsTotalCents", 0L).containsEntry("materialsPaidCents", 0L)
                .containsEntry("materialsRemainingCents", 0L).containsEntry("materialsNote", "")
                .containsEntry("expensesTotalCents", 0L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_payable WHERE project_id=?", Integer.class, projectId)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_payment WHERE payable_id=?", Integer.class, payableId)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_eng_expense WHERE project_id=?", Integer.class, projectId)).isZero();
    }

    @Test
    void materialOverpaymentAfterCancellationCannotOffsetAnotherSuppliersDebt() {
        as(engineer, () -> materialsService.terms(engineer,supplyId,
                new EngMaterialService.TermsCommand("甲供货",BigDecimal.TEN,BigDecimal.ZERO,null,null,"原采购甲"),idem()));
        long first=jdbc.queryForObject("SELECT payable_id FROM fr_eng_supply WHERE id=?",Long.class,supplyId);
        as(engineer, () -> materialsService.submitPlan(engineer,projectId,new EngMaterialService.PlanCommand(nodeId,
                LocalDate.now(),List.of(new EngMaterialService.PlanLine("乙材料","","包",BigDecimal.ONE)),"独立供货"),idem()));
        long secondSupply=jdbc.queryForObject("SELECT id FROM fr_eng_supply WHERE project_id=? AND id<>?",Long.class,projectId,supplyId);
        as(engineer, () -> materialsService.terms(engineer,secondSupply,
                new EngMaterialService.TermsCommand("乙供货",new BigDecimal("100"),BigDecimal.ZERO,null,null,"原采购乙"),idem()));
        long second=jdbc.queryForObject("SELECT payable_id FROM fr_eng_supply WHERE id=?",Long.class,secondSupply);
        var finance=fixture.post(store.orgId(),"finance",null);
        as(engineer, () -> materialsService.adjust(engineer,materialId,
                new EngMaterialService.AdjustCommand(BigDecimal.ZERO,"甲材料取消"),idem()));
        long payment=paymentService.register(finance,idem(),new cn.iocoder.yudao.module.franchise.finance.FinPaymentService.PaymentCommand(
                first,10000,LocalDate.now(),null,null));
        financeWorkbench.adjustPayable(finance,first,idem(),
                new cn.iocoder.yudao.module.franchise.finance.FinWorkbenchService.PayableAdjustment(0,"甲合同解除待退款",null));

        var summary=as(engineer, () -> closuresService.settleSummary(engineer,projectId));
        assertThat(summary).containsEntry("materialsTotalCents",10000L)
                .containsEntry("materialsPaidCents",10000L).containsEntry("materialsRemainingCents",10000L);
        assertThat(summary.get("materialsNote").toString()).contains("超过原应付","退款");
        refundService.registerExpense(finance,idem(),new cn.iocoder.yudao.module.franchise.finance.FinRefundService.ExpenseRefundCommand(
                payment,10000,"甲付款已原路退回",LocalDate.now(),null,null,null));
        assertThat(as(engineer, () -> closuresService.settleSummary(engineer,projectId)))
                .containsEntry("materialsPaidCents",0L).containsEntry("materialsRemainingCents",10000L)
                .containsEntry("materialsNote","");
        assertThat(jdbc.queryForObject("SELECT total_cents FROM fr_fin_payable WHERE id=?",Long.class,second)).isEqualTo(10000L);
    }

    @Test
    void invalidOriginalPayableCannotFallBackToOldMaterialProcurementCost() {
        as(engineer, () -> materialsService.terms(engineer, supplyId,
                new EngMaterialService.TermsCommand("原供应商", BigDecimal.TEN, BigDecimal.ZERO, null, null, "原采购依据"), idem()));
        long payableId = jdbc.queryForObject("SELECT payable_id FROM fr_eng_supply WHERE id=?", Long.class, supplyId);
        jdbc.update("UPDATE fr_fin_payable SET deleted=TRUE WHERE id=?", payableId);
        assertThat(as(engineer, () -> closuresService.settleSummary(engineer, projectId)))
                .containsEntry("materialsTotalCents", 0L).containsEntry("materialsRemainingCents", 0L)
                .containsEntry("expensesTotalCents", 0L);
    }

    @Test
    void largeOrganizationListFiltersManagerAndAssignedEngineerBeforeReturningRows() {
        var another=fixture.post(store.orgId(),"engineer",store.engineeringTeam());
        var otherStore=fixture.store();
        long base=IDS.addAndGet(22000);
        jdbc.update("""
            INSERT INTO fr_eng_project(id,company_id,source_type,manager_user_id,assigned_engineer_user_id,
              name,author_user_id,update_time)
            SELECT ?+n,?,'MANUAL',?,CASE WHEN n<=51 THEN ? END,'测试工程-'||n,?,
                   now()+n*interval '1 second'
            FROM generate_series(1,20000) AS n
            """,base,store.orgId(),another.userId(),engineer.userId(),another.userId());
        jdbc.update("""
            INSERT INTO fr_eng_project(id,company_id,source_type,manager_user_id,name,author_user_id)
            VALUES(?,?,'MANUAL',?,'其他组织工程',?)
            """,base+20001,otherStore.orgId(),engineer.userId(),engineer.userId());
        var visible=as(engineer,()->projectService.list(engineer,"PENDING_START"));
        assertThat(visible).hasSize(52);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var counted=new JdbcTemplate(jdbc.getDataSource()) {
            @Override public java.util.List<java.util.Map<String,Object>> queryForList(String sql,Object... args) {
                calls.incrementAndGet(); return super.queryForList(sql,args);
            }
        };
        var listOnly=new EngProjectService(null,null,null,null,null,null,null,null,null,counted,null);
        assertThat(listOnly.list(engineer,"PENDING_START",1,1)).hasSize(1);
        assertThat(calls.getAndSet(0)).isEqualTo(1);
        assertThat(listOnly.list(engineer,"PENDING_START",1,50)).hasSize(50);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(as(engineer,()->projectService.list(engineer,"PENDING_START",1,20))).isEqualTo(visible.subList(0,20));
        assertThat(as(engineer,()->projectService.list(engineer,"PENDING_START",2,20))).isEqualTo(visible.subList(20,40));
        assertThat(visible.subList(0,20)).extracting(row->((Number)row.get("id")).longValue())
                .containsExactlyElementsOf(java.util.stream.LongStream.iterate(base+51,id->id-1).limit(20).boxed().toList());
        assertThat(visible.subList(50,52)).extracting(row->row.get("id")).containsExactly(base+1,projectId);
        jdbc.update("DELETE FROM fr_eng_project WHERE id>? AND id<=? AND company_id IN (?,?)",
                base,base+20001,store.orgId(),otherStore.orgId());
    }

    @Test
    void actingEngineerRemainsOnTimelineAfterManagerChanges() {
        var replacement=fixture.post(store.orgId(),"engineer",store.engineeringTeam());
        var acting=fixture.post(store.orgId(),"engineer",store.engineeringTeam());
        jdbc.update("""
            INSERT INTO pf_command_event(domain,aggregate_type,aggregate_id,org_id,revision,command,
                 actor_user_id,actor_post_id,actor_role_code,payload,payload_hash,id)
            VALUES('franchise','fr-eng-project',?,?,91,'eng-start',?,?,'engineer','{}','test',?)
            """,projectId,store.orgId(),acting.userId(),acting.postId(),IDS.incrementAndGet());
        var before=(java.util.Map<?,?>)as(engineer,()->projectService.detail(engineer,projectId)).get("timelineActors");
        assertThat(before.get("eng-start")).isNotNull();
        jdbc.update("UPDATE fr_eng_project SET manager_user_id=?,assigned_engineer_user_id=? WHERE id=?",replacement.userId(),engineer.userId(),projectId);
        var after=as(engineer,()->projectService.detail(engineer,projectId));
        assertThat(after.get("timelineActors")).isEqualTo(before);
    }

    private long insertProject() {
        return insertProject("陈先生 · 乡墅");
    }

    private long insertProject(String name) {
        long id = IDS.incrementAndGet();
        jdbc.update("INSERT INTO fr_eng_project (id, company_id, customer_id, customer_name, name, status, "
                        + "manager_user_id, assigned_engineer_user_id, source_commission_id, source_type, "
                        + "revision, author_user_id, create_time, update_time) "
                        + "VALUES (?, ?, NULL, '陈先生', ?, 'PENDING_START', ?, ?, ?, 'HANDOVER', "
                        + "1, ?, now(), now())",
                id, store.orgId(), name, engineer.userId(), engineer.userId(), IDS.incrementAndGet(), engineer.userId());
        // closure（requireActionAllowed/requireOwned 依赖）
        jdbc.update("INSERT INTO fr_eng_closure (id, company_id, project_id, create_time) "
                        + "VALUES (?, ?, ?, now())", IDS.incrementAndGet(), store.orgId(), id);
        return id;
    }

    private long insertNode() {
        // Existing projects receive the same index-0 START node in migration 950.
        // Keep its real evidence separate from the material/inspection node below.
        jdbc.update("INSERT INTO fr_eng_plan_node (id,company_id,project_id,node_index,name,status,milestone,create_time) "
                + "VALUES (?,?,?,0,'正式开工','PENDING','START',now())", IDS.incrementAndGet(),store.orgId(),projectId);
        long id = IDS.incrementAndGet();
        jdbc.update("INSERT INTO fr_eng_plan_node (id, company_id, project_id, node_index, name, status, create_time) "
                + "VALUES (?, ?, ?, 1, '主体施工', 'PENDING', now())", id, store.orgId(), projectId);
        return id;
    }

    private long insertMaterial() {
        long materialId = IDS.incrementAndGet();
        long planId = IDS.incrementAndGet();
        jdbc.update("INSERT INTO fr_eng_plan_version (id, company_id, project_id, version, nodes, source, author_user_id, create_time) "
                + "VALUES (?, ?, ?, 1, '{}', 'MANUAL', ?, now())", planId, store.orgId(), projectId, engineer.userId());
        jdbc.update("INSERT INTO fr_eng_material (id, company_id, project_id, material_plan_id, node_id, "
                        + "name, unit, quantity, status, create_time) "
                        + "VALUES (?, ?, ?, ?, ?, '螺纹钢', '吨', 10.000, 'SUBMITTED', now())",
                materialId, store.orgId(), projectId, planId, nodeId);
        jdbc.update("INSERT INTO fr_eng_supply (id, company_id, project_id, material_id, quantity, payer, create_time) "
                        + "VALUES (?, ?, ?, ?, 10.000, 'STORE', now())",
                IDS.incrementAndGet(), store.orgId(), projectId, materialId);
        return materialId;
    }

    /** orgId=属主组织（测试约定=服务账号 id）；跨组织资产传他组织 id 即被拒 */
    private long insertAsset(String type, boolean accepted, long orgId) {
        long id = IDS.incrementAndGet();
        jdbc.update("INSERT INTO eng_test_asset (id, asset_type, accepted, org_id) VALUES (?, ?, ?, ?)",
                id, type, accepted, orgId);
        return id;
    }

    private EngPlanService.PrepareCommand startCmd(long assetId) {
        return startCmd(assetId, 40800000L, 18000000L, 9200000L);
    }

    /** 三期（分）：签约后/基础完成后/封顶后——默认 Σ=680000 元 与 fixture 总额一致 */
    private EngPlanService.PrepareCommand startCmd(long assetId, long signAfter, long baseDone, long capDone) {
        return new EngPlanService.PrepareCommand(new BigDecimal("680000"),
                new BigDecimal(signAfter).movePointLeft(2), new BigDecimal(baseDone).movePointLeft(2),
                new BigDecimal(capDone).movePointLeft(2),
                assetId, "开工准备：场地与合同就绪", null, null);
    }

    private BigDecimal quantityOf(long materialId) {
        return jdbc.queryForObject("SELECT quantity FROM fr_eng_material WHERE id = ?",
                BigDecimal.class, materialId);
    }

    private void asVoid(PostSnapshot post, Runnable action) {
        currentPost.use(post);
        try {
            action.run();
        } finally {
            currentPost.clear();
        }
    }

    private <T> T as(PostSnapshot post, java.util.function.Supplier<T> action) {
        currentPost.use(post);
        try {
            return action.get();
        } finally {
            currentPost.clear();
        }
    }

    @Resource private EngExpenseService expensesService;

    @Test
    void archivedOriginalExpenseCanCompleteTermsAndPayOnceWithoutReopeningConstruction() {
        var expense = as(engineer, () -> expensesService.register(engineer, projectId,
                new EngExpenseService.RegisterCommand("归档前劳务", "LABOR", new BigDecimal("1000"), "原费用"), idem()));
        long expenseId = Long.parseLong((String) expense.get("expenseId"));
        archiveExpenseProject();
        var closure = jdbc.queryForMap("SELECT * FROM fr_eng_closure WHERE project_id=?", projectId);
        var nodes = jdbc.queryForList("SELECT * FROM fr_eng_plan_node WHERE project_id=? ORDER BY node_index", projectId);
        var versions = jdbc.queryForList("SELECT * FROM fr_eng_plan_version WHERE project_id=? ORDER BY version", projectId);
        var original = jdbc.queryForMap("SELECT name,purpose,amount_cents,status,note,create_time FROM fr_eng_expense WHERE id=?", expenseId);
        String key = idem();
        var terms = new EngExpenseService.TermsCommand("原劳务班组", "原费用结算凭据");
        var saved = as(engineer, () -> expensesService.terms(engineer, expenseId, terms, key));
        assertThat(as(engineer, () -> expensesService.terms(engineer, expenseId, terms, key))).isEqualTo(saved);
        long payableId = Long.parseLong((String) saved.get("payableId"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_payable WHERE project_id=?", Integer.class, projectId)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_command_event WHERE aggregate_id=? AND command='eng-expense-terms'", Integer.class, projectId)).isOne();
        assertThat(as(engineer, () -> expensesService.listByProject(engineer, projectId)).get(0))
                .containsEntry("canCompleteTerms", false);
        var finance = fixture.post(store.orgId(), "finance", null);
        var firstPayment = new cn.iocoder.yudao.module.franchise.finance.FinPaymentService.PaymentCommand(
                payableId, 40000, java.time.LocalDate.now(), null, null);
        String paymentKey = idem();
        long paid = paymentService.register(finance, paymentKey, firstPayment);
        assertThat(paymentService.register(finance, paymentKey, firstPayment)).isEqualTo(paid);
        paymentService.register(finance, idem(), new cn.iocoder.yudao.module.franchise.finance.FinPaymentService.PaymentCommand(
                payableId, 60000, java.time.LocalDate.now(), null, null));
        assertThat(as(engineer, () -> closuresService.settleSummary(engineer, projectId)))
                .containsEntry("expensesTotalCents", 100000L).containsEntry("expensesPaidCents", 100000L)
                .containsEntry("expensesRemainingCents", 0L);
        assertThatThrownBy(() -> as(engineer, () -> expensesService.register(engineer, projectId,
                new EngExpenseService.RegisterCommand("归档后新费用", "OTHER", BigDecimal.TEN, ""), idem())))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> as(engineer, () -> expensesService.revise(engineer, expenseId, BigDecimal.TEN, "修改施工成本", idem())))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> as(engineer, () -> { expensesService.cancel(engineer, expenseId, "取消施工成本", idem()); return null; }))
                .isInstanceOf(ServiceException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_eng_expense WHERE project_id=?", Integer.class, projectId)).isOne();
        assertThat(jdbc.queryForObject("SELECT status FROM fr_eng_project WHERE id=?", String.class, projectId)).isEqualTo("ARCHIVED");
        assertThat(jdbc.queryForMap("SELECT * FROM fr_eng_closure WHERE project_id=?", projectId)).isEqualTo(closure);
        assertThat(jdbc.queryForList("SELECT * FROM fr_eng_plan_node WHERE project_id=? ORDER BY node_index", projectId)).isEqualTo(nodes);
        assertThat(jdbc.queryForList("SELECT * FROM fr_eng_plan_version WHERE project_id=? ORDER BY version", projectId)).isEqualTo(versions);
        assertThat(jdbc.queryForMap("SELECT name,purpose,amount_cents,status,note,create_time FROM fr_eng_expense WHERE id=?", expenseId)).isEqualTo(original);
    }

    @Test
    void archivedTermsRejectCancelledLateAndUnauthorizedExpenseRows() {
        var ids = new java.util.ArrayList<Long>();
        for (String name : java.util.List.of("归档前原单", "已取消原单", "异常晚建单")) {
            var row = as(engineer, () -> expensesService.register(engineer, projectId,
                    new EngExpenseService.RegisterCommand(name, "OTHER", BigDecimal.TEN, ""), idem()));
            ids.add(Long.parseLong((String) row.get("expenseId")));
        }
        as(engineer, () -> { expensesService.cancel(engineer, ids.get(1), "误录", idem()); return null; });
        archiveExpenseProject();
        jdbc.update("UPDATE fr_eng_expense SET create_time=(SELECT archived_at+interval '1 second' FROM fr_eng_closure WHERE project_id=?) WHERE id=?", projectId, ids.get(2));
        var terms = new EngExpenseService.TermsCommand("收款方", "原凭据");
        for (long invalid : ids.subList(1, 3)) {
            assertThatThrownBy(() -> as(engineer, () -> expensesService.terms(engineer, invalid, terms, idem())))
                    .isInstanceOf(ServiceException.class);
        }
        var colleague = fixture.post(store.orgId(), "engineer", store.engineeringTeam());
        var otherStore = fixture.store();
        var outsider = fixture.post(otherStore.orgId(), "engineer", otherStore.engineeringTeam());
        for (var unauthorized : java.util.List.of(colleague, outsider)) {
            assertThatThrownBy(() -> as(unauthorized, () -> expensesService.terms(unauthorized, ids.get(0), terms, idem())))
                    .isInstanceOf(ServiceException.class);
        }
        var rows = as(engineer, () -> expensesService.listByProject(engineer, projectId));
        assertThat(rows.stream().filter(row -> Boolean.TRUE.equals(row.get("canCompleteTerms"))).map(row -> row.get("expenseId")).toList())
                .containsExactly(Long.toString(ids.get(0)));
        assertThat(as(colleague, () -> expensesService.listByProject(colleague, projectId)))
                .allMatch(row -> !row.containsKey("canCompleteTerms"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_payable WHERE project_id=?", Integer.class, projectId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_eng_expense WHERE project_id=? AND payable_id IS NOT NULL", Integer.class, projectId)).isZero();
    }

    private void archiveExpenseProject() {
        jdbc.update("UPDATE fr_eng_closure SET delivered_at=CURRENT_DATE,recipient='业主',file_asset_id=1,settled_at=CURRENT_DATE,archived_at=now(),archived_by=? WHERE project_id=?", engineer.userId(), projectId);
        jdbc.update("UPDATE fr_eng_project SET status='ARCHIVED' WHERE id=?", projectId);
    }

    @Test
    void revisingAnUnpaidExpenseSynchronizesItsExactPayableAndProjectSettlement() {
        var expense = as(engineer, () -> expensesService.register(engineer, projectId,
                new EngExpenseService.RegisterCommand("劳务", "LABOR", new BigDecimal("1000"), ""), idem()));
        long expenseId = Long.parseLong((String) expense.get("expenseId"));
        var withPayable = as(engineer, () -> expensesService.terms(engineer, expenseId,
                new EngExpenseService.TermsCommand("收款人", "原凭据"), idem()));
        long payableId = Long.parseLong((String) withPayable.get("payableId"));
        String key = "e".repeat(80);
        for (int i=0;i<2;i++) {
            String retryKey = i == 0 ? key : " " + key + " ";
            var edited=as(engineer, () -> expensesService.revise(engineer, expenseId, new BigDecimal("800"), "核减", retryKey));
            assertThat(edited.get("payableId")).isEqualTo(Long.toString(payableId));
            assertThat(((Number) edited.get("amountCents")).longValue()).isEqualTo(80000);
        }
        assertThat(jdbc.queryForObject("SELECT total_cents FROM fr_fin_payable WHERE id=?",Long.class,payableId)).isEqualTo(80000);
        assertThat(jdbc.queryForObject("SELECT agreement_ref FROM fr_fin_payable WHERE id=?",String.class,payableId)).isEqualTo("原凭据");
        assertThat(jdbc.queryForObject("SELECT recipient FROM fr_fin_payable WHERE id=?",String.class,payableId)).isEqualTo("收款人");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_payable WHERE project_id=?",Long.class,projectId)).isEqualTo(1);
        assertThat(((Number) as(engineer,()->closuresService.settleSummary(engineer,projectId)).get("expensesTotalCents"))
                .longValue()).isEqualTo(80000);
        var finance=fixture.post(store.orgId(),"finance",null);
        long paymentId = paymentService.register(finance,idem(),new cn.iocoder.yudao.module.franchise.finance.FinPaymentService.PaymentCommand(
                payableId,100000,java.time.LocalDate.now(),null,null));
        // 实际付出多于已修应付时保留实付证据，仅超出部分进入待关联。
        assertThat(jdbc.queryForObject("SELECT pending_link_cents FROM fr_fin_payment WHERE id=?",Long.class,paymentId)).isEqualTo(20000);
        as(engineer, () -> expensesService.revise(engineer, expenseId, new BigDecimal("700"), "已付款后核减", idem()));
        assertThat(jdbc.queryForObject("SELECT total_cents FROM fr_fin_payable WHERE id=?",Long.class,payableId)).isEqualTo(80000);
        assertThat(jdbc.queryForObject("SELECT sum(amount_cents) FROM fr_fin_payment WHERE payable_id=? AND status='已核实'",
                Long.class,payableId)).isEqualTo(100000);
        var other=fixture.store();var alien=fixture.post(other.orgId(),"engineer",other.engineeringTeam());
        assertThatThrownBy(()->as(alien,()->expensesService.revise(alien,expenseId,BigDecimal.TEN,"不同公司",idem())))
                .isInstanceOf(ServiceException.class);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        assertThatThrownBy(()->tx.executeWithoutResult(status->payablePort.syncUnpaidExpenseAmount(
                engineer,projectId,expenseId+1,payableId,60000,idem()))).isInstanceOf(ServiceException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_command_event WHERE command='engineering-expense-unpaid-sync' AND aggregate_id=?",
                Long.class,payableId)).isEqualTo(1);
    }

    @Test
    void settlementSeparatelyShowsPaidExpenseRevisionWithoutChangingTheLedger() {
        var expense = as(engineer, () -> expensesService.register(engineer, projectId,
                new EngExpenseService.RegisterCommand("劳务核减", "LABOR", new BigDecimal("1000"), ""), idem()));
        String expenseId = (String) expense.get("expenseId");
        var withPayable = as(engineer, () -> expensesService.terms(engineer, Long.parseLong(expenseId),
                new EngExpenseService.TermsCommand("收款人", "原凭据"), idem()));
        long payable = Long.parseLong((String) withPayable.get("payableId"));
        var finance = fixture.post(store.orgId(), "finance", null);
        long payment = paymentService.register(finance, idem(),
                new cn.iocoder.yudao.module.franchise.finance.FinPaymentService.PaymentCommand(
                        payable, 100000, java.time.LocalDate.now(), null, null));
        assertThat(as(engineer, () -> closuresService.settleSummary(engineer, projectId)))
                .doesNotContainKey("expenseReviewRows");
        as(engineer, () -> expensesService.revise(engineer, Long.parseLong(expenseId),
                new BigDecimal("800"), "现场确认减项", idem()));

        var expected = java.util.Map.<String, Object>of("expenseId", expenseId, "name", "劳务核减",
                "revisedCents", 80000L, "bookedCents", 100000L, "netPaidCents", 100000L,
                "netAboveRevisedCents", 20000L);
        assertThat(as(finance, () -> closuresService.settleSummary(finance, projectId)))
                .containsEntry("expensesTotalCents", 100000L)
                .containsEntry("expensesPaidCents", 100000L)
                .containsEntry("expenseReviewRows", java.util.List.of(expected));
        var nonOwner = fixture.post(store.orgId(), "engineer", store.engineeringTeam());
        assertThat(as(nonOwner, () -> closuresService.settleSummary(nonOwner, projectId)))
                .doesNotContainKeys("expenseReviewRows", "expensesPaidCents");
        var anotherStore = fixture.store();
        var outsider = fixture.post(anotherStore.orgId(), "finance", null);
        assertThatThrownBy(() -> as(outsider, () -> closuresService.settleSummary(outsider, projectId)))
                .isInstanceOf(ServiceException.class);

        refundService.registerExpense(finance, idem(),
                new cn.iocoder.yudao.module.franchise.finance.FinRefundService.ExpenseRefundCommand(
                        payment, 20000, "核实的支出退回", java.time.LocalDate.now(), null, null, null));
        var returned = new java.util.HashMap<>(expected);
        returned.put("netPaidCents", 80000L);
        returned.put("netAboveRevisedCents", 0L);
        assertThat(as(engineer, () -> closuresService.settleSummary(engineer, projectId)))
                .containsEntry("expensesTotalCents", 100000L)
                .containsEntry("expensesPaidCents", 80000L)
                .containsEntry("expenseReviewRows", java.util.List.of(returned));
        assertThat(jdbc.queryForObject("SELECT total_cents FROM fr_fin_payable WHERE id=?", Long.class, payable))
                .isEqualTo(100000L);
        assertThat(jdbc.queryForObject("SELECT amount_cents FROM fr_fin_payment WHERE id=?", Long.class, payment))
                .isEqualTo(100000L);
    }

    private static String idem() {
        return "idem-" + IDS.incrementAndGet();
    }

    @Test
    void unpaidTermsEditKeepsSinglePayableAndRejectsEditAfterPayment() {
        var initial=new EngMaterialService.TermsCommand("原供应商",new BigDecimal("10"),BigDecimal.ZERO,"张",java.time.LocalDate.now(),"原合同");
        var revised=new EngMaterialService.TermsCommand("新供应商",new BigDecimal("20"),new BigDecimal("5"),"李",java.time.LocalDate.now(),"新合同");
        as(engineer,()->materialsService.terms(engineer,supplyId,initial,idem()));
        long payable=jdbc.queryForObject("SELECT payable_id FROM fr_eng_supply WHERE id=?",Long.class,supplyId);
        String key=idem();
        as(engineer,()->materialsService.terms(engineer,supplyId,revised,key));
        as(engineer,()->materialsService.terms(engineer,supplyId,revised,key));
        for(var conflict:java.util.List.of(
                new EngMaterialService.TermsCommand("新供应商",new BigDecimal("20"),new BigDecimal("5"),"李",java.time.LocalDate.now(),"换凭据"),
                new EngMaterialService.TermsCommand("新供应商",new BigDecimal("20"),new BigDecimal("5"),"换联系人",java.time.LocalDate.now(),"新合同"),
                new EngMaterialService.TermsCommand("新供应商",new BigDecimal("20"),new BigDecimal("5"),"李",java.time.LocalDate.now().plusDays(1),"新合同"))) {
            assertThatThrownBy(()->as(engineer,()->materialsService.terms(engineer,supplyId,conflict,key)))
                    .isInstanceOfSatisfying(ServiceException.class,error->assertThat(error.getCode()).isEqualTo(
                            cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED.getCode()));
        }
        assertThat(jdbc.queryForObject("SELECT contact FROM fr_eng_supply WHERE id=?",String.class,supplyId)).isEqualTo("李");
        assertThat(jdbc.queryForObject("SELECT promise_date FROM fr_eng_supply WHERE id=?",java.time.LocalDate.class,supplyId)).isEqualTo(java.time.LocalDate.now());
        assertThat(jdbc.queryForObject("SELECT payable_id FROM fr_eng_supply WHERE id=?",Long.class,supplyId)).isEqualTo(payable);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_payable WHERE project_id=?",Long.class,projectId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT total_cents FROM fr_fin_payable WHERE id=?",Long.class,payable)).isEqualTo(20500);
        assertThat(jdbc.queryForObject("SELECT recipient FROM fr_fin_payable WHERE id=?",String.class,payable)).isEqualTo("新供应商");
        assertThat(jdbc.queryForObject("SELECT agreement_ref FROM fr_fin_payable WHERE id=?",String.class,payable)).isEqualTo("新合同");
        var finance=fixture.post(store.orgId(),"finance",null);
        paymentService.register(finance,idem(),new cn.iocoder.yudao.module.franchise.finance.FinPaymentService.PaymentCommand(payable,1,java.time.LocalDate.now(),null,null));
        assertThatThrownBy(()->as(engineer,()->materialsService.terms(engineer,supplyId,initial,idem()))).isInstanceOf(ServiceException.class);
        assertThat(jdbc.queryForObject("SELECT total_cents FROM fr_fin_payable WHERE id=?",Long.class,payable)).isEqualTo(20500);
        var other=fixture.store();var alien=fixture.post(other.orgId(),"engineer",other.engineeringTeam());
        assertThatThrownBy(()->as(alien,()->materialsService.terms(alien,supplyId,revised,idem()))).isInstanceOf(ServiceException.class);
    }

    @Resource private cn.iocoder.yudao.module.franchise.finance.FinPaymentService paymentService;
    @Resource private org.springframework.transaction.PlatformTransactionManager transactions;
    @Resource private cn.iocoder.yudao.module.franchise.api.EngineeringPayablePort payablePort;
    @Resource private cn.iocoder.yudao.module.franchise.api.EngineeringLedgerQueryPort ledgerPort;
    @Resource private cn.iocoder.yudao.module.franchise.finance.FinRefundService refundService;

    @Test
    @SuppressWarnings("unchecked")
    void materialViewsExposeExactPayableIdsWithoutCrossingSupplyOrAmountVisibility() {
        as(engineer,()->materialsService.terms(engineer,supplyId,
                new EngMaterialService.TermsCommand("第一供货",BigDecimal.TEN,BigDecimal.ZERO,null,null,"依据一"),idem()));
        long firstPayable=jdbc.queryForObject("SELECT payable_id FROM fr_eng_supply WHERE id=?",Long.class,supplyId);
        as(engineer,()->materialsService.submitPlan(engineer,projectId,new EngMaterialService.PlanCommand(nodeId,
                java.time.LocalDate.now(),java.util.List.of(new EngMaterialService.PlanLine("第二材料","","包",BigDecimal.ONE)),"独立供货"),idem()));
        long secondSupply=jdbc.queryForObject("SELECT id FROM fr_eng_supply WHERE project_id=? AND id<>?",Long.class,projectId,supplyId);
        var secondResponse=as(engineer,()->materialsService.terms(engineer,secondSupply,
                new EngMaterialService.TermsCommand("第二供货",new BigDecimal("30"),BigDecimal.ZERO,null,null,"依据二"),idem()));
        long secondPayable=jdbc.queryForObject("SELECT payable_id FROM fr_eng_supply WHERE id=?",Long.class,secondSupply);
        assertThat(secondPayable).isNotEqualTo(firstPayable);
        assertThat(secondResponse.get("payableId")).isEqualTo(String.valueOf(secondPayable));
        assertThat(materialsService.supplyView(engineer,supply()).get("payableId")).isEqualTo(String.valueOf(firstPayable));
        var plans=(java.util.List<java.util.Map<String,Object>>)materialsService.summary(engineer,projectId,null).get("plans");
        var lines=plans.stream().flatMap(p->((java.util.List<java.util.Map<String,Object>>)p.get("lines")).stream()).toList();
        assertThat(lines.stream().filter(l->String.valueOf(l.get("supplyId")).equals(String.valueOf(supplyId))).findFirst().orElseThrow().get("payableId")).isEqualTo(String.valueOf(firstPayable));
        assertThat(lines.stream().filter(l->String.valueOf(l.get("supplyId")).equals(String.valueOf(secondSupply))).findFirst().orElseThrow().get("payableId")).isEqualTo(String.valueOf(secondPayable));
        var nonOwner=fixture.post(store.orgId(),"engineer",store.engineeringTeam());
        assertThat(materialsService.supplyView(nonOwner,supply())).doesNotContainKey("payableId");
        var limited=(java.util.List<java.util.Map<String,Object>>)materialsService.summary(nonOwner,projectId,null).get("plans");
        for(var plan:limited) for(var line:(java.util.List<java.util.Map<String,Object>>)plan.get("lines")) assertThat(line).doesNotContainKey("payableId");
    }

    @Test
    void fullExpenseReturnDoesNotUnlockTermsButVoidedPaymentDoes() {
        var initial=new EngMaterialService.TermsCommand("原供应商",BigDecimal.TEN,BigDecimal.ZERO,null,null,"原合同");
        var revised=new EngMaterialService.TermsCommand("新供应商",new BigDecimal("20"),new BigDecimal("5"),null,null,"新合同");
        as(engineer,()->materialsService.terms(engineer,supplyId,initial,idem()));
        long payable=jdbc.queryForObject("SELECT payable_id FROM fr_eng_supply WHERE id=?",Long.class,supplyId);
        var finance=fixture.post(store.orgId(),"finance",null);
        long payment=paymentService.register(finance,idem(),new cn.iocoder.yudao.module.franchise.finance.FinPaymentService.PaymentCommand(payable,10000,java.time.LocalDate.now(),null,null));
        long refund=refundService.registerExpense(finance,idem(),new cn.iocoder.yudao.module.franchise.finance.FinRefundService.ExpenseRefundCommand(payment,10000,"全额真实支出退回",java.time.LocalDate.now(),null,null,null));
        assertThat(ledgerPort.paidCents(engineer,payable)).isZero();
        assertThat(ledgerPort.hasActivePayment(engineer,payable)).isTrue();
        assertThat(ledgerPort.activePaymentsByPayableIds(engineer,java.util.List.of(payable,-1L))).containsEntry(payable,true).containsEntry(-1L,false);
        var other=fixture.store();var alien=fixture.post(other.orgId(),"finance",null);
        assertThat(ledgerPort.hasActivePayment(alien,payable)).isFalse();
        assertThatThrownBy(()->as(engineer,()->materialsService.terms(engineer,supplyId,revised,idem()))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(()->new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx->
                payablePort.updateUnpaid(engineer,payable,new cn.iocoder.yudao.module.franchise.api.EngineeringPayablePort.PayableCommand(
                        projectId,"engineering-payable:"+projectId+":"+supplyId,"新供应商",20500,"新合同"),idem())))
                .isInstanceOf(ServiceException.class);
        assertThat(jdbc.queryForObject("SELECT total_cents FROM fr_fin_payable WHERE id=?",Long.class,payable)).isEqualTo(10000);
        assertThat(jdbc.queryForObject("SELECT recipient FROM fr_fin_payable WHERE id=?",String.class,payable)).isEqualTo("原供应商");
        assertThat(jdbc.queryForObject("SELECT supplier FROM fr_eng_supply WHERE id=?",String.class,supplyId)).isEqualTo("原供应商");
        assertThat(materialsService.supplyView(engineer,supply()).get("termsLocked")).isEqualTo(true);
        assertThat(summaryLine().get("termsLocked")).isEqualTo(true);
        assertThat(summaryLine().get("paidCents")).isEqualTo(0L);
        refundService.void_(finance,refund,"更正退回事实");
        paymentService.void_(finance,payment,"原付款更正");
        assertThat(ledgerPort.hasActivePayment(engineer,payable)).isFalse();
        assertThat(ledgerPort.activePaymentsByPayableIds(engineer,java.util.List.of(payable))).containsEntry(payable,false);
        as(engineer,()->materialsService.terms(engineer,supplyId,revised,idem()));
        assertThat(materialsService.supplyView(engineer,supply()).get("termsLocked")).isEqualTo(false);
        assertThat(summaryLine().get("termsLocked")).isEqualTo(false);
        assertThat(jdbc.queryForObject("SELECT total_cents FROM fr_fin_payable WHERE id=?",Long.class,payable)).isEqualTo(20500);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_payable WHERE project_id=?",Long.class,projectId)).isEqualTo(1);
    }

    private cn.iocoder.yudao.module.franchise.engineering.dal.FrEngSupplyDO supply() {
        return supplyMapper.selectById(supplyId);
    }
    @Resource private cn.iocoder.yudao.module.franchise.engineering.dal.FrEngSupplyMapper supplyMapper;

    @SuppressWarnings("unchecked")
    private java.util.Map<String,Object> summaryLine() {
        var plans=(java.util.List<java.util.Map<String,Object>>)materialsService.summary(engineer,projectId,null).get("plans");
        return ((java.util.List<java.util.Map<String,Object>>)plans.get(0).get("lines")).get(0);
    }

    @Test
    void unpaidPayableUpdateRejectsForgedOrganizationProjectAndSource() {
        var initial=new EngMaterialService.TermsCommand("原供应商",BigDecimal.TEN,BigDecimal.ZERO,null,null,"原合同");
        as(engineer,()->materialsService.terms(engineer,supplyId,initial,idem()));
        long payable=jdbc.queryForObject("SELECT payable_id FROM fr_eng_supply WHERE id=?",Long.class,supplyId);
        String source="engineering-payable:"+projectId+":"+supplyId;
        var command=new cn.iocoder.yudao.module.franchise.api.EngineeringPayablePort.PayableCommand(projectId,source,"改名",50000,"新依据");
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        var other=fixture.store();var alien=fixture.post(other.orgId(),"engineer",other.engineeringTeam());
        assertThatThrownBy(()->tx.executeWithoutResult(s->payablePort.updateUnpaid(alien,payable,command,idem()))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(()->tx.executeWithoutResult(s->payablePort.updateUnpaid(engineer,payable,
                new cn.iocoder.yudao.module.franchise.api.EngineeringPayablePort.PayableCommand(projectId+1,source,"改名",50000,"新依据"),idem()))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(()->tx.executeWithoutResult(s->payablePort.updateUnpaid(engineer,payable,
                new cn.iocoder.yudao.module.franchise.api.EngineeringPayablePort.PayableCommand(projectId,source+"0","改名",50000,"新依据"),idem()))).isInstanceOf(ServiceException.class);
        assertThat(jdbc.queryForObject("SELECT total_cents FROM fr_fin_payable WHERE id=?",Long.class,payable)).isEqualTo(10000);
        assertThat(jdbc.queryForObject("SELECT recipient FROM fr_fin_payable WHERE id=?",String.class,payable)).isEqualTo("原供应商");
    }

    @Test
    void paymentHoldingPayableLockMakesTermsWaitThenReject() throws Exception {
        assertPaymentTermsSerialization(true);
    }

    @Test
    void termsHoldingPayableLockMakesPaymentWaitAndUseUpdatedAmount() throws Exception {
        assertPaymentTermsSerialization(false);
    }

    private void assertPaymentTermsSerialization(boolean paymentFirst) throws Exception {
        var initial=new EngMaterialService.TermsCommand("原供应商",BigDecimal.TEN,BigDecimal.ZERO,null,null,"原合同");
        var revised=new EngMaterialService.TermsCommand("新供应商",new BigDecimal("20"),new BigDecimal("5"),null,null,"新合同");
        as(engineer,()->materialsService.terms(engineer,supplyId,initial,idem()));
        long payable=jdbc.queryForObject("SELECT payable_id FROM fr_eng_supply WHERE id=?",Long.class,supplyId);
        var finance=fixture.post(store.orgId(),"finance",null);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var locked=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        try {
            var first=pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(transactions).execute(tx->{
                if(paymentFirst) paymentService.register(finance,idem(),new cn.iocoder.yudao.module.franchise.finance.FinPaymentService.PaymentCommand(payable,1,java.time.LocalDate.now(),null,null));
                else as(engineer,()->materialsService.terms(engineer,supplyId,revised,idem()));
                locked.countDown();
                try {if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("test transaction timeout");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                return true;
            }));
            assertThat(locked.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var second=pool.submit(()->{
                try {
                    if(paymentFirst) as(engineer,()->materialsService.terms(engineer,supplyId,revised,idem()));
                    else paymentService.register(finance,idem(),new cn.iocoder.yudao.module.franchise.finance.FinPaymentService.PaymentCommand(payable,20500,java.time.LocalDate.now(),null,null));
                    return (Throwable)null;
                } catch(Throwable e){return e;}
            });
            boolean waiting=false;
            for(int i=0;i<100;i++) {
                if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_locks WHERE NOT granted)",Boolean.class)){waiting=true;break;}
                Thread.sleep(20);
            }
            assertThat(waiting).isTrue();assertThat(second.isDone()).isFalse();
            release.countDown();assertThat(first.get(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            Throwable result=second.get(5,java.util.concurrent.TimeUnit.SECONDS);
            if(paymentFirst) assertThat(result).isInstanceOf(ServiceException.class);else assertThat(result).isNull();
            assertThat(jdbc.queryForObject("SELECT total_cents FROM fr_fin_payable WHERE id=?",Long.class,payable)).isEqualTo(paymentFirst?10000:20500);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_payable WHERE project_id=?",Long.class,projectId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COALESCE(sum(pending_link_cents),0) FROM fr_fin_payment WHERE payable_id=?",Long.class,payable)).isZero();
        } finally {release.countDown();pool.shutdownNow();}
    }
}
