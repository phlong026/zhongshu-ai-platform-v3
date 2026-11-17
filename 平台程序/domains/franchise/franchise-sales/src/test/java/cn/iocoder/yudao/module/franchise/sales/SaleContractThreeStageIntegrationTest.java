package cn.iocoder.yudao.module.franchise.sales;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.franchise.api.BusinessReceiptPort;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.sales.controller.admin.SalesController;
import cn.iocoder.yudao.module.franchise.sales.dal.SaleContractDO;
import cn.iocoder.yudao.module.franchise.sales.dal.SalePaymentStageDO;
import cn.iocoder.yudao.module.franchise.test.FranchiseFixture.Store;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.command.CommandLogImpl;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import jakarta.annotation.Resource;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static cn.iocoder.yudao.module.franchise.org.FranchiseRoles.BOSS;
import static cn.iocoder.yudao.module.franchise.org.FranchiseRoles.SALES;
import static cn.iocoder.yudao.module.franchise.sales.enums.FranchiseSalesErrorCodes.*;
import static cn.iocoder.yudao.module.franchise.test.FranchiseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M06-T04 统一建房合同三期总闸（Testcontainers PG）：
 * 1. 三期创建——Σ三期=合同总额；签订后>0；基础完成后/封顶后允许 0=未约定（期行全建，不发明固定比例）；
 *    同 C 唯一活跃合同守卫（作废后方可重签）。
 * 2. 分笔核销——每笔金额≤期剩余可收额（正常归单限额）；一笔 receipt 至多归一期；
 *    PENDING→OPEN→SETTLED 状态推进；收满后拒绝；0 元拒绝；未签订拒绝。
 * 3. stageSummary 统一视图契约——各期约定额/累计分笔/余额/状态（M06-T02 双端与 M10 统计引用冻结）。
 * 存量两期迁移映射（first→SIGN_AFTER/tail→CAP_DONE/PAID→SETTLED/旧 paid_receipt_id→分笔行）
 * 由 V20261104.812 迁移承担，测试库随 Flyway 执行，旧值行在新 CHECK 下不可再造——映射正确性
 * 由迁移语句审查+undo 对称性覆盖（登记边界）。
 */
@Import({SaleContractService.class, SaleRequirementService.class, SaleQuoteService.class,
        SalesAccess.class, FranchiseCommands.class, CommandLogImpl.class,
        SaleContractThreeStageIntegrationTest.Config.class})
class SaleContractThreeStageIntegrationTest extends FranchisePgIntegrationTest {

    @TestConfiguration
    static class Config {
        /** 内部专用收款入口的真实实现（finance 域）；手工 REST 不放宽 */
        @Bean
        BusinessReceiptPort businessReceiptPort(JdbcTemplate jdbc) {
            return new cn.iocoder.yudao.module.franchise.test.BusinessReceiptPortTestStub(jdbc);
        }

        /** AssetAccessPort/StoreServiceAccountPort 动态代理桩：本测试 assetId 恒 null 不触达（validateAsset 早退） */
        @Bean
        cn.iocoder.yudao.module.franchise.api.AssetAccessPort assetAccessPort() {
            return untouchedStub(cn.iocoder.yudao.module.franchise.api.AssetAccessPort.class);
        }

        @Bean
        cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort storeServiceAccountPort() {
            return untouchedStub(cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort.class);
        }

        @SuppressWarnings("unchecked")
        private static <T> T untouchedStub(Class<T> type) {
            return (T) java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                    (proxy, method, args) -> {
                        throw new UnsupportedOperationException("本测试不触达 " + type.getSimpleName() + "." + method.getName());
                    });
        }

        /** SalesAccess 客户组织校验桩：jdbc 直查（客户存在且同组织即过——CustomerAccess 语义的组织部分） */
        @Bean
        cn.iocoder.yudao.module.franchise.api.CustomerSchemePort customerSchemePort(JdbcTemplate jdbc) {
            return new cn.iocoder.yudao.module.franchise.api.CustomerSchemePort() {
                @Override
                public void requireAccessible(PostSnapshot post, long customerId) {
                    Integer count = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM fr_customer WHERE id = ? AND company_id = ?",
                            Integer.class, customerId, post.orgId());
                    if (count == null || count == 0) {
                        throw new ServiceException(
                                cn.iocoder.yudao.module.franchise.sales.enums.FranchiseSalesErrorCodes.REFERENCE_INVALID);
                    }
                }

                @Override
                public SchemeSaved saveScheme(PostSnapshot post, long customerId, SchemeSave command) {
                    throw new UnsupportedOperationException("本测试不触达方案回写");
                }

                @Override
                public void requireWritable(PostSnapshot post, long customerId) {
                    requireAccessible(post, customerId);
                }

                @Override
                public java.util.List<Long> listDesignProjectIds(PostSnapshot post, long customerId) {
                    return java.util.List.of();
                }

                @Override
                public CustomerBrief getCustomerBrief(PostSnapshot post, long customerId) {
                    return new CustomerBrief(customerId, "陈先生", "");
                }

                @Override
                public SchemeSnapshot getSchemeSnapshot(PostSnapshot post, long schemeId) {
                    return null;
                }

                @Override
                public SchemeSnapshot promoteToFormal(PostSnapshot post, long schemeId, String idempotencyKey) {
                    throw new UnsupportedOperationException("本测试不触达方案升级");
                }
            };
        }
    }

    private static final AtomicLong PHONES = new AtomicLong(19_000_000_000L + System.nanoTime() % 800_000_000L);

    @Resource
    private SaleContractService contracts;
    @Resource
    private JdbcTemplate jdbc;

    private Store store;
    private PostSnapshot salesA1;
    private PostSnapshot boss;
    private PostSnapshot finance;

    @BeforeEach
    void organization() {
        store = fixture.store();
        salesA1 = fixture.post(store.orgId(), SALES, store.salesTeamA());
        boss = fixture.post(store.orgId(), BOSS, null);
        finance = fixture.post(store.orgId(), "finance", null);
    }

    @Test
    void depositAllocationUsesTheExistingReceiptOnly(){
        long customerId=newCustomer();var c=as(salesA1,()->contracts.create(salesA1,contract(customerId,100000,100000,0,0),idem()));
        as(salesA1,()->contracts.sign(salesA1,c.getId(),new SaleContractService.SignCommand(java.time.LocalDate.now(),null),revisionOf(c.getId()),idem()));
        long receipt=PHONES.incrementAndGet();
        jdbc.update("INSERT INTO fr_fin_receipt(id,company_id,source_type,name,amount_cents,status,received_date,revision_version,registrar_user_id) VALUES(?,?,'manual','前期定金',20000,'已登记',CURRENT_DATE,1,?)",receipt,finance.orgId(),finance.userId());
        String key=idem();long stage=stageIdOf(c.getId(),"SIGN_AFTER");
        as(finance,()->{contracts.allocateReceipt(finance,stage,receipt,key);return true;});
        as(finance,()->{contracts.allocateReceipt(finance,stage,receipt,key);return true;});
        assertThat(contracts.stageCollected(finance,stage)).isEqualTo(20000);
        assertThat(jdbc.queryForObject("SELECT source_type FROM fr_fin_receipt WHERE id=?",String.class,receipt)).isEqualTo("business");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_receipt WHERE company_id=?",Long.class,finance.orgId())).isEqualTo(1);
    }

    @Test
    void returnedDeclarationCannotBeConfirmedAndCanBeResubmitted(){
        long customerId=newCustomer();var c=as(salesA1,()->contracts.create(salesA1,contract(customerId,100000,100000,0,0),idem()));
        as(salesA1,()->contracts.sign(salesA1,c.getId(),new SaleContractService.SignCommand(java.time.LocalDate.now(),null),revisionOf(c.getId()),idem()));
        long stage=stageIdOf(c.getId(),"SIGN_AFTER");var cmd=new SaleContractService.CollectCommand(10000,"bank",txn(),java.time.LocalDate.now());
        long declaration=((Number)as(salesA1,()->contracts.collect(salesA1,stage,cmd,idem())).get("declarationId")).longValue();
        contracts.returnDeclaration(finance,declaration,"补充实际到账信息",idem());
        assertThat(contracts.contractCollectionDeclarations(salesA1,c.getId())).hasSize(1);
        assertThat(contracts.contractCollectionDeclarations(salesA1,c.getId()).get(0).get("return_reason")).isEqualTo("补充实际到账信息");
        assertThat(contracts.contractCollectionDeclarations(finance,c.getId())).isEmpty();
        assertThatThrownBy(()->contracts.confirmDeclaration(finance,declaration,idem())).isInstanceOf(ServiceException.class);
        as(salesA1,()->contracts.collect(salesA1,stage,cmd,idem()));
        assertThat(jdbc.queryForObject("SELECT status FROM fr_sale_collection_declaration WHERE id=?",String.class,declaration)).isEqualTo("SUPERSEDED");
        assertThat(contracts.stageCollected(finance,stage)).isZero();
    }

    @Test
    void salesDeclarationDoesNotCollectUntilFinanceConfirms(){
        long customerId=newCustomer();
        var c=as(salesA1,()->contracts.create(salesA1,contract(customerId,100000,100000,0,0),idem()));
        as(salesA1,()->contracts.sign(salesA1,c.getId(),new SaleContractService.SignCommand(java.time.LocalDate.now(),null),revisionOf(c.getId()),idem()));
        long stage=stageIdOf(c.getId(),"SIGN_AFTER");String key=idem();
        var cmd=new SaleContractService.CollectCommand(50000,"bank",txn(),java.time.LocalDate.now(),null,"公司银行");
        var declaration=as(salesA1,()->contracts.collect(salesA1,stage,cmd,key));
        assertThat(declaration.get("stageStatus")).isEqualTo("PENDING_CONFIRMATION");
        assertThat(contracts.stageCollected(finance,stage)).isZero();
        assertThat(as(salesA1,()->contracts.collect(salesA1,stage,cmd,key)).get("declarationId")).isEqualTo(declaration.get("declarationId"));
        long id=((Number)declaration.get("declarationId")).longValue();
        var pending=contracts.collectionDeclarations(finance).stream().filter(r->String.valueOf(id).equals(r.get("id"))).findFirst().orElseThrow();
        assertThat(pending.get("customer_name")).isEqualTo("陈先生");
        assertThat(pending.get("customer_id")).isEqualTo(String.valueOf(customerId));
        assertThat(pending.get("account_name")).isEqualTo("公司银行");
        assertThat(pending.get("transaction_no")).isEqualTo(cmd.transactionNo());
        assertThat(contracts.financialStages(finance)).anySatisfy(r->{
            assertThat(r.get("id")).isEqualTo(String.valueOf(stage));
            assertThat(r.get("customer_id")).isEqualTo(String.valueOf(customerId));
            assertThat(r.get("customer_name")).isEqualTo("陈先生");
        });
        as(finance,()->contracts.confirmDeclaration(finance,id,idem()));
        assertThat(contracts.stageCollected(finance,stage)).isEqualTo(50000);
        as(finance,()->contracts.confirmDeclaration(finance,id,idem()));
        assertThat(contracts.stageCollected(finance,stage)).isEqualTo(50000);
    }
    @Test
    void confirmedChangeAddsAfterDeliveryTailWithoutCopyingReceipts(){
        long customerId=newCustomer();var c=as(salesA1,()->contracts.create(salesA1,contract(customerId,100000,100000,0,0),idem()));
        as(salesA1,()->contracts.sign(salesA1,c.getId(),new SaleContractService.SignCommand(java.time.LocalDate.now(),null),revisionOf(c.getId()),idem()));
        contracts.changeAmounts(finance,c.getId(),new SaleContractService.ContractChange(80000,0,0,30000,java.time.LocalDate.now().plusMonths(1),"业主确认增项及尾款约定"),idem());
        assertThat(contracts.financialStages(finance)).anySatisfy(r->{assertThat(r.get("stage")).isEqualTo("tail");assertThat(r.get("after_delivery")).isEqualTo(true);});
        assertThat(jdbc.queryForObject("SELECT total_cents FROM fr_sale_contract WHERE id=?",Long.class,c.getId())).isEqualTo(110000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_sale_stage_receipt WHERE contract_id=?",Long.class,c.getId())).isZero();
    }

    // ========== 三期创建 ==========

    @Test
    void createsContractWithThreeStagesSummingToTotal() {
        long customerId = newCustomer();
        // 总额 68 万：签约后 40 万、基础完成后 18 万、封顶后 10 万（合同约定，非固定比例）
        SaleContractDO created = as(salesA1, () -> contracts.create(salesA1, contract(
                customerId, 68_000_000L, 40_000_000L, 18_000_000L, 10_000_000L), idem()));

        assertThat(created.getStatus()).isEqualTo(SaleContractDO.STATUS_WAIT_SIGN);
        var summary = as(salesA1, () -> contracts.stageSummary(salesA1, created.getId()));
        assertThat(summary).hasSize(3);
        assertThat(summary).extracting(r -> r.get("stage"))
                .containsExactly("SIGN_AFTER", "BASE_DONE", "CAP_DONE");
        assertThat(summary).extracting(r -> ((Number) r.get("amountCents")).longValue())
                .containsExactly(40_000_000L, 18_000_000L, 10_000_000L);
        // 全部未收：累计 0、余额=约定额
        assertThat(summary).allSatisfy(r -> {
            assertThat(((Number) r.get("collectedCents")).longValue()).isZero();
            assertThat(((Number) r.get("balanceCents")).longValue()).isEqualTo(((Number) r.get("amountCents")).longValue());
        });
    }

    @Test
    void allowsZeroAmountStagesAsUnagreed() {
        long customerId = newCustomer();
        // 封顶后 0=未约定（合同约定无此期款）：期行照建、余额 0
        SaleContractDO created = as(salesA1, () -> contracts.create(salesA1, contract(
                customerId, 50_000_000L, 30_000_000L, 20_000_000L, 0L), idem()));

        var summary = as(salesA1, () -> contracts.stageSummary(salesA1, created.getId()));
        assertThat(summary).extracting(r -> r.get("stage")).contains("CAP_DONE");
        assertThat(summary.stream().filter(r -> "CAP_DONE".equals(r.get("stage"))).findFirst().orElseThrow())
                .satisfies(r -> {
                    assertThat(((Number) r.get("amountCents")).longValue()).isZero();
                    assertThat(((Number) r.get("balanceCents")).longValue()).isZero();
                });
    }

    @Test
    void rejectsStageSumNotEqualToTotal() {
        long customerId = newCustomer();
        // Σ三期(40+18+10=68)≠总额(70)：拒绝，不落任何行
        assertError(() -> as(salesA1, () -> contracts.create(salesA1, contract(
                customerId, 70_000_000L, 40_000_000L, 18_000_000L, 10_000_000L), idem())),
                CONTRACT_DUPLICATE);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM fr_sale_contract WHERE customer_id = ?", Long.class, customerId)).isZero();
    }

    @Test
    void keepsOneActiveContractPerCustomer() {
        long customerId = newCustomer();
        as(salesA1, () -> contracts.create(salesA1, contract(
                customerId, 68_000_000L, 40_000_000L, 18_000_000L, 10_000_000L), idem()));
        // 同 C 唯一活跃合同：重复创建拒绝（作废后方可重签）
        assertError(() -> as(salesA1, () -> contracts.create(salesA1, contract(
                customerId, 68_000_000L, 40_000_000L, 18_000_000L, 10_000_000L), idem())),
                CONTRACT_DUPLICATE);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM fr_sale_contract WHERE customer_id = ?", Long.class, customerId)).isEqualTo(1);
    }

    // ========== 分笔核销 ==========

    @Test
    void collectsStageInTwoInstallmentsUntilSettled() {
        long customerId = newCustomer();
        SaleContractDO created = as(salesA1, () -> contracts.create(salesA1, contract(
                customerId, 68_000_000L, 40_000_000L, 18_000_000L, 10_000_000L), idem()));
        as(salesA1, () -> contracts.sign(salesA1, created.getId(),
                new SaleContractService.SignCommand(java.time.LocalDate.now(), null),
                        revisionOf(created.getId()), idem()));
        long signStageId = stageIdOf(created.getId(), "SIGN_AFTER");

        // 第一笔 25 万：PENDING→OPEN
        var first = as(finance, () -> contracts.collect(finance, signStageId,
                new SaleContractService.CollectCommand(25_000_000L, "bank", txn(), java.time.LocalDate.now()), idem()));
        assertThat(first.get("stageStatus")).isEqualTo("OPEN");
        assertThat(((Number) first.get("collectedCents")).longValue()).isEqualTo(25_000_000L);

        // 第二笔 15 万收满：→SETTLED
        var second = as(finance, () -> contracts.collect(finance, signStageId,
                new SaleContractService.CollectCommand(15_000_000L, "bank", txn(), java.time.LocalDate.now()), idem()));
        assertThat(second.get("stageStatus")).isEqualTo("SETTLED");

        // 分笔关联两行、一笔 receipt 至多归一期；期状态 SETTLED
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM fr_sale_stage_receipt WHERE stage_id = ?", Long.class, signStageId)).isEqualTo(2);
        assertThat(stageStatus(created.getId(), "SIGN_AFTER")).isEqualTo("SETTLED");
        // 三期各一 receipt：finance 侧两行资金事实
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM fr_fin_receipt WHERE source_ref LIKE 'sale:' || ? || ':%'",
                Long.class, created.getId())).isEqualTo(2);
    }

    @Test
    void rejectsInstallmentExceedingStageBalance() {
        long customerId = newCustomer();
        SaleContractDO created = as(salesA1, () -> contracts.create(salesA1, contract(
                customerId, 68_000_000L, 40_000_000L, 18_000_000L, 10_000_000L), idem()));
        as(salesA1, () -> contracts.sign(salesA1, created.getId(),
                new SaleContractService.SignCommand(java.time.LocalDate.now(), null),
                        revisionOf(created.getId()), idem()));
        long signStageId = stageIdOf(created.getId(), "SIGN_AFTER");

        // 首笔 30 万后，第二笔 11 万超剩余 10 万 → 拒绝且不落资金/关联行
        as(finance, () -> contracts.collect(finance, signStageId,
                new SaleContractService.CollectCommand(30_000_000L, "bank", txn(), java.time.LocalDate.now()), idem()));
        assertError(() -> as(finance, () -> contracts.collect(finance, signStageId,
                new SaleContractService.CollectCommand(11_000_000L, "bank", txn(), java.time.LocalDate.now()), idem())),
                STAGE_AMOUNT_EXCEED);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM fr_sale_stage_receipt WHERE stage_id = ?", Long.class, signStageId)).isEqualTo(1);
        assertThat(stageStatus(created.getId(), "SIGN_AFTER")).isEqualTo("OPEN");
    }

    @Test
    void rejectsZeroOrNegativeInstallmentAndUnsignedContract() {
        long customerId = newCustomer();
        SaleContractDO created = as(salesA1, () -> contracts.create(salesA1, contract(
                customerId, 68_000_000L, 40_000_000L, 18_000_000L, 10_000_000L), idem()));
        long signStageId = stageIdOf(created.getId(), "SIGN_AFTER");
        // 0 元拒绝（STAGE_AMOUNT_INVALID 先于未签订校验——实现顺序：金额校验在 requireSIGNED 前）
        assertError(() -> as(finance, () -> contracts.collect(finance, signStageId,
                new SaleContractService.CollectCommand(0L, "bank", txn(), java.time.LocalDate.now()), idem())),
                STAGE_AMOUNT_INVALID);
        // 未签订拒绝（WAIT_SIGN 不可核销——M06-T02 语义前置）
        assertError(() -> as(finance, () -> contracts.collect(finance, signStageId,
                new SaleContractService.CollectCommand(10_000_000L, "bank", txn(), java.time.LocalDate.now()), idem())),
                CONTRACT_NOT_SIGNED);
        assertThat(stageStatus(created.getId(), "SIGN_AFTER")).isEqualTo("PENDING");
    }

    @Test
    void settledStageRejectsFurtherInstallments() {
        long customerId = newCustomer();
        SaleContractDO created = as(salesA1, () -> contracts.create(salesA1, contract(
                customerId, 30_000_000L, 30_000_000L, 0L, 0L), idem()));
        as(salesA1, () -> contracts.sign(salesA1, created.getId(),
                new SaleContractService.SignCommand(java.time.LocalDate.now(), null),
                        revisionOf(created.getId()), idem()));
        long signStageId = stageIdOf(created.getId(), "SIGN_AFTER");
        // 一次收满 → SETTLED
        as(finance, () -> contracts.collect(finance, signStageId,
                new SaleContractService.CollectCommand(30_000_000L, "bank", txn(), java.time.LocalDate.now()), idem()));
        // 再收 → 拒绝（收满），且不产生新资金行
        assertError(() -> as(finance, () -> contracts.collect(finance, signStageId,
                new SaleContractService.CollectCommand(1_000_000L, "bank", txn(), java.time.LocalDate.now()), idem())),
                STAGE_ALREADY_PAID);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM fr_sale_stage_receipt WHERE stage_id = ?", Long.class, signStageId)).isEqualTo(1);
    }

    @Test
    void idempotentReplayReturnsSameReceiptForSameKeyAndContent() {
        long customerId = newCustomer();
        SaleContractDO created = as(salesA1, () -> contracts.create(salesA1, contract(
                customerId, 68_000_000L, 40_000_000L, 18_000_000L, 10_000_000L), idem()));
        as(salesA1, () -> contracts.sign(salesA1, created.getId(),
                new SaleContractService.SignCommand(java.time.LocalDate.now(), null),
                revisionOf(created.getId()), idem()));
        long signStageId = stageIdOf(created.getId(), "SIGN_AFTER");
        String key = idem();
        var cmd = new SaleContractService.CollectCommand(25_000_000L, "bank", txn(), java.time.LocalDate.now());

        var first = as(finance, () -> contracts.collect(finance, signStageId, cmd, key));
        // 同键同内容重试：返回同一 receiptId（R1 P1：replay/record 规范化输入一致）
        var retry = as(finance, () -> contracts.collect(finance, signStageId, cmd, key));
        assertThat(retry.get("replayed")).isEqualTo(true);
        assertThat(((Number) retry.get("receiptId")).longValue())
                .isEqualTo(((Number) first.get("receiptId")).longValue());
        // 同键异内容：键复用拒绝（金额不同）
        var mutated = new SaleContractService.CollectCommand(26_000_000L, "bank", txn(), java.time.LocalDate.now());
        assertThatThrownBy(() -> as(finance, () -> contracts.collect(finance, signStageId, mutated, key)))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode())
                .isEqualTo(cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED.getCode());
        // 资金事实仍只有一笔
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM fr_sale_stage_receipt WHERE stage_id = ?", Long.class, signStageId)).isEqualTo(1);
    }

    @Test
    void refundReducesNetAndReopensStageForBackfill() {
        // M09-T03/R2：退款后净额 < 期额 → SETTLED 期恢复补收（SETTLED 保持，不违反 CHECK）；
        // stageSummary 四列对账（gross/refunded/net/remaining）
        long customerId = newCustomer();
        SaleContractDO created = as(salesA1, () -> contracts.create(salesA1, contract(
                customerId, 30_000_000L, 30_000_000L, 0L, 0L), idem()));
        as(salesA1, () -> contracts.sign(salesA1, created.getId(),
                new SaleContractService.SignCommand(java.time.LocalDate.now(), null),
                revisionOf(created.getId()), idem()));
        long signStageId = stageIdOf(created.getId(), "SIGN_AFTER");
        as(finance, () -> contracts.collect(finance, signStageId,
                new SaleContractService.CollectCommand(30_000_000L, "bank", txn(), java.time.LocalDate.now()), idem()));
        assertThat(stageStatus(created.getId(), "SIGN_AFTER")).isEqualTo("SETTLED");

        // 退款 1000 万（fr_fin_refund 行预置，关联该分笔 receipt；status=已核实）
        Long receiptId = jdbc.queryForObject(
                "SELECT receipt_id FROM fr_sale_stage_receipt WHERE stage_id = ? LIMIT 1", Long.class, signStageId);
        jdbc.update("INSERT INTO fr_fin_refund (id, receipt_id, company_id, amount_cents, status, reason, "
                        + "operator_user_id, create_time) VALUES (?, ?, ?, 10000000, '已核实', '金额误录', 99, now())",
                PHONES.incrementAndGet(), receiptId, store.orgId());

        // 净口径：stageCollected = 3000 万 - 1000 万 = 2000 万 < 期额 3000 万 → SETTLED 恢复可补收
        // R3 P2：分两笔补收——首笔后断言仍 SETTLED 且 paid_* 保持原值（检出 wasSettled 删除故障：
        // 若删，首笔后转 OPEN 违反 paid_* 非空 CHECK 直接报错，测试即红）
        long firstBackfill = 5_000_000L;
        var backfill1 = as(finance, () -> contracts.collect(finance, signStageId,
                new SaleContractService.CollectCommand(firstBackfill, "bank", txn(), java.time.LocalDate.now()), idem()));
        assertThat(backfill1.get("stageStatus")).isEqualTo("SETTLED"); // 首笔补收后仍 SETTLED（wasSettled 路径）
        assertThat(jdbc.queryForObject("SELECT status FROM fr_sale_payment_stage WHERE id = ?",
                String.class, signStageId)).isEqualTo("SETTLED");
        // 分笔态 SETTLED 行 paid_* 恒 NULL（存量整期核销行才冻结保留 paid_*——分笔协议不写，误写即检）
        assertThat(jdbc.queryForObject(
                "SELECT paid_receipt_id IS NULL FROM fr_sale_payment_stage WHERE id = ?", Boolean.class, signStageId))
                .isTrue();

        // 第二笔补收后净累计=期额（补收完成）
        long secondBackfill = 5_000_000L;
        var backfill2 = as(finance, () -> contracts.collect(finance, signStageId,
                new SaleContractService.CollectCommand(secondBackfill, "bank", txn(), java.time.LocalDate.now()), idem()));
        assertThat(backfill2.get("stageStatus")).isEqualTo("SETTLED");
        // 净累计核对：3000 万（净）+500 万+500 万 = 4000 万？——净口径=毛额-退款：毛 4000 万-退款 1000 万=3000 万=期额
        Long net = jdbc.queryForObject(
                "SELECT COALESCE(SUM(r.amount_cents),0) - COALESCE((SELECT SUM(g.amount_cents) FROM fr_fin_refund g "
                        + "JOIN fr_sale_stage_receipt r2 ON r2.receipt_id = g.receipt_id WHERE r2.stage_id = ? "
                        + "AND g.status = '已核实' AND g.replaced_by_id IS NULL),0) "
                        + "FROM fr_sale_stage_receipt r WHERE r.stage_id = ?", Long.class, signStageId, signStageId);
        assertThat(net).isEqualTo(30_000_000L);

        // 四列对账：gross=4000 万/refunded=1000 万/net=3000 万/remaining=0
        var summary = as(salesA1, () -> contracts.stageSummary(salesA1, created.getId()));
        var signRow = summary.stream().filter(r -> "SIGN_AFTER".equals(r.get("stage"))).findFirst().orElseThrow();
        System.out.println("[DBG-keys] " + signRow.keySet());
        assertThat(((Number) signRow.get("grossCents")).longValue()).isEqualTo(40_000_000L);
        assertThat(((Number) signRow.get("refundedCents")).longValue()).isEqualTo(10_000_000L);
        assertThat(((Number) signRow.get("collectedCents")).longValue()).isEqualTo(30_000_000L);
        assertThat(((Number) signRow.get("balanceCents")).longValue()).isZero();
    }

    // ========== 统一视图契约 ==========

    @Test
    void summaryReflectsCollectedAndBalanceAcrossStages() {
        long customerId = newCustomer();
        SaleContractDO created = as(salesA1, () -> contracts.create(salesA1, contract(
                customerId, 68_000_000L, 40_000_000L, 18_000_000L, 10_000_000L), idem()));
        as(salesA1, () -> contracts.sign(salesA1, created.getId(),
                new SaleContractService.SignCommand(java.time.LocalDate.now(), null),
                        revisionOf(created.getId()), idem()));
        as(finance, () -> contracts.collect(finance, stageIdOf(created.getId(), "SIGN_AFTER"),
                new SaleContractService.CollectCommand(25_000_000L, "bank", txn(), java.time.LocalDate.now()), idem()));

        var summary = as(boss, () -> contracts.stageSummary(boss, created.getId()));
        var signRow = summary.stream().filter(r -> "SIGN_AFTER".equals(r.get("stage"))).findFirst().orElseThrow();
        assertThat(((Number) signRow.get("collectedCents")).longValue()).isEqualTo(25_000_000L);
        assertThat(((Number) signRow.get("balanceCents")).longValue()).isEqualTo(15_000_000L);
        assertThat(signRow.get("status")).isEqualTo("OPEN");
        assertThat(signRow.get("label")).isEqualTo("签约后");
        // 全合同累计=Σ期累计（统一实收账口径：销售/工程共用，不重复计）
        long totalCollected = summary.stream()
                .mapToLong(r -> ((Number) r.get("collectedCents")).longValue()).sum();
        assertThat(totalCollected).isEqualTo(25_000_000L);
    }

    // ========== 辅助 ==========

    private SaleContractService.ContractCommand contract(long customerId, long total, long signAfter,
                                                         long baseDone, long capDone) {
        return new SaleContractService.ContractCommand(customerId, total, signAfter, baseDone, capDone,
                java.time.LocalDate.now(), null, requirementOf(customerId));
    }

    /** 需求前置：合同创建必须挂客户需求（requirementId）——每客户插一条 version=1 */
    private long requirementOf(long customerId) {
        jdbc.update("INSERT INTO fr_sale_requirement (id, company_id, customer_id, author_user_id, version, note) "
                        + "VALUES (?, ?, ?, ?, 1, '两层乡墅新建需求') ON CONFLICT DO NOTHING",
                PHONES.incrementAndGet(), store.orgId(), customerId, salesA1.userId());
        return jdbc.queryForObject(
                "SELECT id FROM fr_sale_requirement WHERE customer_id = ? LIMIT 1", Long.class, customerId);
    }

    private long newCustomer() {
        long id = PHONES.incrementAndGet();
        jdbc.update("INSERT INTO fr_customer (id, company_id, owner_user_id, name, phone, source, status, create_time, update_time) "
                        + "VALUES (?, ?, ?, '陈先生', ?, '视频号咨询', 'REGISTERED', now(), now())",
                id, store.orgId(), salesA1.userId(), String.valueOf(PHONES.incrementAndGet()));
        return id;
    }

    /** MP insert 不回填 DB 默认值：revision 从库读（sign 乐观锁入参） */
    private long revisionOf(long contractId) {
        return jdbc.queryForObject("SELECT revision FROM fr_sale_contract WHERE id = ?", Long.class, contractId);
    }

    private long stageIdOf(long contractId, String stage) {
        return jdbc.queryForObject(
                "SELECT id FROM fr_sale_payment_stage WHERE contract_id = ? AND stage = ?",
                Long.class, contractId, stage);
    }

    private String stageStatus(long contractId, String stage) {
        return jdbc.queryForObject(
                "SELECT status FROM fr_sale_payment_stage WHERE contract_id = ? AND stage = ?",
                String.class, contractId, stage);
    }

    private <T> T as(PostSnapshot post, java.util.function.Supplier<T> action) {
        currentPost.use(post);
        try {
            return action.get();
        } finally {
            currentPost.clear();
        }
    }

    private static String idem() {
        return "idem-" + PHONES.incrementAndGet();
    }

    private static String txn() {
        return "TXN" + PHONES.incrementAndGet();
    }
}
