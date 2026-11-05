package cn.iocoder.yudao.module.franchise.finance;

import cn.iocoder.yudao.module.franchise.finance.dal.FinPaymentDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinPayableDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinPayableMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinPaymentMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinVoucherMapper;
import cn.iocoder.yudao.module.franchise.finance.enums.FranchiseFinErrorCodes;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.finance.enums.FranchiseFinErrorCodes.*;

/**
 * 应付与付款：付款登记即已核实（verify 独立复核为封存逻辑不建）；I-2 行锁内校验累计≤应付总额；
 * payable_id 强制关联；作废释放额度并留痕（void 仅 finance-lead）。
 */
@Service
@Slf4j
public class FinPaymentService {

    @Resource
    private FinPaymentMapper paymentMapper;
    @Resource
    private FinPayableMapper payableMapper;
    @Resource
    private FranchiseCommands commands;
    @Resource
    private cn.iocoder.yudao.module.franchise.api.AssetAccessPort assets;
    @Resource
    private FinVoucherMapper voucherFiles;
    @Resource
    private cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort serviceAccounts;
    @Resource
    private cn.iocoder.yudao.module.franchise.finance.dal.FinRefundMapper refundMapper;

    public record PayableCommand(long companyId, Long projectId, String stage, long totalCents,
                                 String recipient, String agreementRef) {
    }

    public record PaymentCommand(long payableId, long amountCents, LocalDate payDate,
                                 String transactionNo, Long voucherFileId) {
    }

    @Transactional
    public long registerPayable(PostSnapshot post, String idempotencyKey, PayableCommand cmd) {
        var replayed = commands.replayed(post, idempotencyKey, cmd);
        if (replayed.isPresent()) {
            return replayed.get().getAggregateId();
        }
        FinPayableDO payable = new FinPayableDO()
                .setCompanyId(post.orgId()).setProjectId(cmd.projectId()).setStage(cmd.stage())
                .setTotalCents(cmd.totalCents()).setRecipient(cmd.recipient())
                .setStatus("进行中").setAgreementRef(cmd.agreementRef());
        payableMapper.insert(payable);
        commands.record(post, "fin-payable", payable.getId(), 1, "fin-payable-register", cmd, idempotencyKey);
        return payable.getId();
    }

    /** 付款登记（登记即已核实）；I-2：Σ已核实（行锁内含本笔）≤ 应付总额 */
    @Transactional
    public long register(PostSnapshot post, String idempotencyKey, PaymentCommand cmd) {
        var replayed = commands.replayed(post, idempotencyKey, cmd);
        if (replayed.isPresent()) {
            return replayed.get().getAggregateId();
        }
        if (cmd.payableId() <= 0) {
            throw exception(PAYMENT_PAYABLE_REQUIRED);
        }
        BusinessDateGuard.requireValid(cmd.payDate()); // M08-T05/R04
        if (cmd.voucherFileId() != null && cmd.voucherFileId() > 0) {
            // M5 R2 P1-2+R3 兼容口径：基-1 资产或 fr_fin_file 同组织存在（双轨兼容期，切基-1 随 COS 批）
            long accountId = serviceAccounts.requireServiceAccount(post.orgId());
            if (!assets.validateReference(accountId, cmd.voucherFileId())
                    && !voucherFiles.existsByIdAndCompany(cmd.voucherFileId(), post.orgId())) {
                throw exception(VOUCHER_INVALID_FIN);
            }
        }
        FinPayableDO payable = payableMapper.lockById(cmd.payableId());
        if (payable == null || payable.getCompanyId() != post.orgId()) {
            throw exception(PAYABLE_NOT_FOUND);
        }
        // R2 P1-4：paid 有效账口径=Σ(amount-pending_link_cents)——PENDING_LINK 超额部分不计入
        // 有效余额（不冲减其他应付；reconcile 转正后计入）
        long paid = paymentMapper.selectActiveByPayable(cmd.payableId()).stream()
                .mapToLong(p -> amount(p) - (p.getPendingLinkCents() == null ? 0 : p.getPendingLinkCents())).sum();
        // M10-T05/R1 P1-4：超余额部分转「已核待关联现金」独立异常留痕（不拒绝——真实资金留痕优先，
        // 不绕有效账上限：有效账只计额度内部分；reconcile 后可转正）
        long overCents = Math.max(0, paid + cmd.amountCents() - payable.getTotalCents());
        long linkedCents = cmd.amountCents() - overCents;
        FinPaymentDO payment = new FinPaymentDO()
                .setPayableId(cmd.payableId()).setProjectId(payable.getProjectId()).setCompanyId(post.orgId())
                .setAmountCents(cmd.amountCents()).setStatus("已核实")
                .setPendingLinkCents(overCents)
                .setLinkStatus(overCents > 0 ? "PENDING_LINK" : "LINKED")
                .setPayDate(cmd.payDate()).setOperatorUserId(post.userId())
                .setTransactionNo(FinReceiptService.normalize(cmd.transactionNo()))
                .setVoucherFileId(cmd.voucherFileId());
        paymentMapper.insert(payment);
        commands.record(post, "fin-payment", payment.getId(), 1, "fin-payment-register", cmd, idempotencyKey);
        log.info("[register][companyId={} payment={} payable={} amount={}]", post.orgId(), payment.getId(), cmd.payableId(), cmd.amountCents());
        return payment.getId();
    }

    /** 作废（仅 finance-lead，权限码 franchise:fin:payment-void）；释放额度并留痕；重复作废幂等。
     * M08-T08/R2 P1：与支出退回共用付款行锁并在锁内拒绝作废存在有效退回的付款——
     * 封闭「退回持锁提交→作废无条件覆盖」与「作废先提交→退回状态复核拒绝」的双向交错。 */
    @Transactional
    public void void_(PostSnapshot post, long paymentId, String reason) {
        FinPaymentDO payment = paymentMapper.lockById(paymentId);
        if (payment == null) {
            throw exception(PAYMENT_NOT_FOUND);
        }
        if (payment.getCompanyId() != post.orgId()) {
            throw exception(ORG_MISMATCH);
        }
        if ("已作废".equals(payment.getStatus())) {
            return; // 幂等
        }
        if (!refundMapper.selectActiveByPayment(paymentId).isEmpty()) {
            throw exception(PAYMENT_HAS_ACTIVE_REFUND); // 先作废退回记录，再作废付款
        }
        paymentMapper.updateById(new FinPaymentDO().setId(paymentId).setStatus("已作废")
                .setVoidByUserId(post.userId()).setVoidReason(reason).setVoidedAt(OffsetDateTime.now()));
        log.info("[void][companyId={} payment={} by={} reason={}]", post.orgId(), paymentId, post.userId(), reason);
    }

    static long amount(FinPaymentDO payment) {
        return payment.getAmountCents() == null ? 0 : payment.getAmountCents();
    }

}
