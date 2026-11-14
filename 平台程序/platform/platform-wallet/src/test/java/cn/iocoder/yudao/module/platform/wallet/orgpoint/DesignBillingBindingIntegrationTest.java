package cn.iocoder.yudao.module.platform.wallet.orgpoint;

import cn.iocoder.yudao.module.franchise.api.OrgPointPort;
import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批 C 设计域接入验收（24 号 D6 v2 §2.1/§2.3-A）：计费归属绑定 + 按业务引用反查 + 图片终态结算。
 * 覆盖：quoteCredits 与 reserve 同源、binding 幂等、findOperationByRef、ACCEPTED=0 全退 / 部分退差 / 全交付不退。
 */
@TestPropertySource(properties = "platform.test.extra-flyway-locations=,classpath:db/migration/platform-wallet")
class DesignBillingBindingIntegrationTest extends PlatformPgIntegrationTest {

    private static final long ORG = 5001L;
    private static final long USER = 7L;

    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;
    private JdbcTemplate jdbc;
    private OrgPointService service;

    @BeforeEach
    void clean() {
        jdbc = new JdbcTemplate(dataSource);
        service = new OrgPointService(dataSource, transactionManager, message -> 0L,
                new OrgPointPriceService(dataSource, transactionManager));
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
    void bindingReplayRejectsDifferentOwnershipOrOperationIntent() {
        long operationId = reserveJob(9011L, "ai_flat", 1);

        assertThatThrownBy(() -> service.bindOperation("BRIDGE", ORG + 1, USER, null,
                "ai_job", 9011L, "ai_flat", operationId, "design:job:9011:ai_flat"))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThatThrownBy(() -> service.bindOperation("BRIDGE", ORG, USER + 1, null,
                "ai_job", 9011L, "ai_flat", operationId, "design:job:9011:ai_flat"))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThatThrownBy(() -> service.bindOperation("BRIDGE", ORG, USER, null,
                "ai_job", 9011L, "ai_flat", operationId + 1, "design:job:9011:ai_flat"))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThatThrownBy(() -> service.bindOperation("BRIDGE", ORG, USER, null,
                "ai_job", 9011L, "ai_flat", operationId, "different-key"))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThat(service.findOperationByRef("ai_job", 9011L, "ai_flat")).isEqualTo(operationId);
    }

    @Test
    void firstBindingRejectsMissingOrMismatchedReserveOwnership() {
        String bizKey = "design:job:9012:ai_flat";
        long operationId = service.reserve(new OrgPointPort.ReserveCommand(
                bizKey, ORG, USER, 81L, "ai_flat", 1, "ai_job", 9012L));

        assertThatThrownBy(() -> service.bindOperation("BRIDGE", ORG + 1, USER, 81L,
                "ai_job", 9012L, "ai_flat", operationId, bizKey)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.bindOperation("BRIDGE", ORG, USER + 1, 81L,
                "ai_job", 9012L, "ai_flat", operationId, bizKey)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.bindOperation("BRIDGE", ORG, USER, 82L,
                "ai_job", 9012L, "ai_flat", operationId, bizKey)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.bindOperation("BRIDGE", ORG, USER, 81L,
                "other_ref", 9012L, "ai_flat", operationId, bizKey)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.bindOperation("BRIDGE", ORG, USER, 81L,
                "ai_job", 9013L, "ai_flat", operationId, bizKey)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.bindOperation("BRIDGE", ORG, USER, 81L,
                "ai_job", 9012L, "ai_elevation", operationId, bizKey)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.bindOperation("BRIDGE", ORG, USER, 81L,
                "ai_job", 9012L, "ai_flat", operationId, "different-key"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.bindOperation("BRIDGE", ORG, USER, 81L,
                "ai_job", 9012L, "ai_flat", operationId + 1, bizKey)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_billing_binding", Integer.class)).isZero();
    }

    @Test
    void reserveAndInvalidBindingRollBackTogetherInOuterTransaction() {
        long before = service.findAccount(ORG).balance();
        TransactionTemplate outer = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
            String bizKey = "design:job:9014:ai_flat";
            long operationId = service.reserve(new OrgPointPort.ReserveCommand(
                    bizKey, ORG, USER, null, "ai_flat", 1, "ai_job", 9014L));
            service.bindOperation("BRIDGE", ORG + 1, USER, null,
                    "ai_job", 9014L, "ai_flat", operationId, bizKey);
        })).isInstanceOf(RuntimeException.class);

        assertThat(service.findAccount(ORG).balance()).isEqualTo(before);
        assertThat(service.findOperationByBizKey("design:job:9014:ai_flat")).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_billing_binding WHERE ref_id=9014",
                Integer.class)).isZero();
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
    void succeededPromptReceiptIsImmutableAndCannotDowngrade() {
        String hash = "a".repeat(64);
        service.savePromptReceipt(9021L, 1, "call-9021", hash, "SUCCEEDED", 2001L);
        service.savePromptReceipt(9021L, 1, "call-9021", hash, "SUCCEEDED", 2001L);

        assertThatThrownBy(() -> service.savePromptReceipt(
                9021L, 1, "call-9021", hash, "UNKNOWN", 2001L)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.savePromptReceipt(
                9021L, 1, "call-other", hash, "SUCCEEDED", 2001L)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.savePromptReceipt(
                9021L, 1, "call-9021", "b".repeat(64), "SUCCEEDED", 2001L)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.savePromptReceipt(
                9021L, 1, "call-9021", hash, "SUCCEEDED", 2002L)).isInstanceOf(RuntimeException.class);
        assertThat(service.findPromptReceipt(9021L, 1))
                .isEqualTo(new OrgPointPort.PromptReceipt("call-9021", hash, "SUCCEEDED", 2001L));
    }

    @Test
    void unknownReceiptMayOnlyAdvanceToConsistentSuccess() {
        service.savePromptReceipt(9022L, 1, "call-9022", null, "UNKNOWN", null);
        service.savePromptReceipt(9022L, 1, "call-9022", "c".repeat(64), "SUCCEEDED", 2003L);
        assertThat(service.findPromptReceipt(9022L, 1).outcome()).isEqualTo("SUCCEEDED");

        service.savePromptReceipt(9023L, 1, "call-9023", null, "UNKNOWN", null);
        assertThatThrownBy(() -> service.savePromptReceipt(
                9023L, 1, "different-call", "d".repeat(64), "SUCCEEDED", 2004L))
                .isInstanceOf(RuntimeException.class);
        assertThat(service.findPromptReceipt(9023L, 1).outcome()).isEqualTo("UNKNOWN");
    }

    @Test
    void notSentRetryMayAdvanceWithNewOperation() {
        service.savePromptReceipt(9024L, 1, null, null, "NOT_SENT", 2101L);
        service.savePromptReceipt(9024L, 1, "call-9024", "e".repeat(64), "SUCCEEDED", 2102L);
        assertThat(service.findPromptReceipt(9024L, 1))
                .isEqualTo(new OrgPointPort.PromptReceipt(
                        "call-9024", "e".repeat(64), "SUCCEEDED", 2102L));
    }

    @Test
    void dispatchedReceiptMayAdvanceToUnknownWithoutChangingIdentity() {
        service.savePromptReceipt(9025L, 1, "call-9025", null, "DISPATCHED", 2201L);
        service.savePromptReceipt(9025L, 1, "call-9025", null, "UNKNOWN", 2201L);

        assertThat(service.findPromptReceipt(9025L, 1))
                .isEqualTo(new OrgPointPort.PromptReceipt("call-9025", null, "UNKNOWN", 2201L));
    }

    @Test
    void concurrentFirstPromptReceiptIsIdempotentForSameIntentAndRejectsConflict() throws Exception {
        runConcurrentReceipts(9026L,
                new OrgPointPort.PromptReceipt("call-9026", null, "DISPATCHED", 2301L),
                new OrgPointPort.PromptReceipt("call-9026", null, "DISPATCHED", 2301L), 2);
        assertThat(service.findPromptReceipt(9026L, 1))
                .isEqualTo(new OrgPointPort.PromptReceipt("call-9026", null, "DISPATCHED", 2301L));

        runConcurrentReceipts(9027L,
                new OrgPointPort.PromptReceipt("call-9027-a", null, "DISPATCHED", 2302L),
                new OrgPointPort.PromptReceipt("call-9027-b", null, "DISPATCHED", 2302L), 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_prompt_receipt WHERE job_id=9027",
                Integer.class)).isEqualTo(1);
    }

    private void runConcurrentReceipts(long jobId, OrgPointPort.PromptReceipt first,
                                       OrgPointPort.PromptReceipt second, int expectedSuccesses) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> one = executor.submit(() -> saveAfter(start, jobId, first));
            Future<Boolean> two = executor.submit(() -> saveAfter(start, jobId, second));
            start.countDown();
            assertThat((one.get() ? 1 : 0) + (two.get() ? 1 : 0)).isEqualTo(expectedSuccesses);
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean saveAfter(CountDownLatch start, long jobId, OrgPointPort.PromptReceipt receipt)
            throws InterruptedException {
        start.await();
        try {
            service.savePromptReceipt(jobId, 1, receipt.callId(), receipt.responseHash(),
                    receipt.outcome(), receipt.operationId());
            return true;
        } catch (RuntimeException e) {
            return false;
        }
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
