package cn.iocoder.yudao.module.franchise.finance;

import cn.iocoder.yudao.module.franchise.finance.dal.FinPaymentDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinPaymentMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinReceiptDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinReceiptMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinRefundDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinRefundMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinTxnNoDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinTxnNoMapper;
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
 * 退款：线下事实登记制（用户 2026-10-31 拍板）——财务登记即"已核实"，不建申请审批链。
 * 资金不变量 I-1（行锁内）：Σ已核实退款 + 本次 ≤ 当前实收；仅"已登记"收款可退。
 * 更正链：作废（replaced_by_id 保持 NULL 指向）→ 重登携带 replaces_id 并 CAS 争抢原记录替代权
 * （UPDATE ... SET replaced_by_id=新 WHERE id=原 AND replaced_by_id IS NULL，行数=1 否则全回滚）。
 */
@Service
@Slf4j
public class FinRefundService {

    @Resource
    private FinRefundMapper refundMapper;
    @Resource
    private FinReceiptMapper receiptMapper;
    @Resource
    private FinReceiptService receiptService;
    @Resource
    private FinTxnNoMapper txnNoMapper;
    @Resource
    private FranchiseCommands commands;
    @Resource
    private FinPaymentMapper paymentMapper;

    public record RegisterCommand(long receiptId, long amountCents, String reason, LocalDate date,
                                  String transactionNo, Long voucherFileId, Long replacesId) {
    }

    /** M08-T08：支出退回命令（供应商/劳务方退回材料/劳务款，冲减已付现金） */
    public record ExpenseRefundCommand(long paymentId, long amountCents, String reason, LocalDate date,
                                       String transactionNo, Long voucherFileId) {
    }

    @Transactional
    public long register(PostSnapshot post, String idempotencyKey, RegisterCommand cmd) {
        var replayed = commands.replayed(post, idempotencyKey, cmd);
        if (replayed.isPresent()) {
            return replayed.get().getAggregateId();
        }
        BusinessDateGuard.requireValid(cmd.date()); // M08-T05/R04
        FinReceiptDO receipt = receiptMapper.lockById(cmd.receiptId());
        if (receipt == null) {
            throw exception(RECEIPT_NOT_FOUND);
        }
        if (receipt.getCompanyId() != post.orgId()) {
            throw exception(ORG_MISMATCH);
        }
        if (!FinReceiptService.STATUS_REGISTERED.equals(receipt.getStatus())) {
            throw exception(REFUND_SOURCE_INVALID); // 待登记/已撤销不可退
        }
        long balance = receiptService.refundableBalance(receipt);
        if (cmd.amountCents() > balance) {
            throw exception(REFUND_EXCEED_BALANCE);
        }
        String txn = FinReceiptService.normalize(cmd.transactionNo());
        FinTxnNoDO identity = txn == null ? null : txnNoMapper.find(post.orgId(), txn);
        if (identity != null && cmd.replacesId() == null) {
            throw exception(TXN_NO_OCCUPIED);
        }

        FinRefundDO refund = new FinRefundDO()
                .setReceiptId(cmd.receiptId()).setCompanyId(post.orgId())
                .setAmountCents(cmd.amountCents()).setStatus("已核实")
                .setReason(cmd.reason()).setOperatorUserId(post.userId())
                .setReplacesId(cmd.replacesId()).setDate(cmd.date())
                .setTransactionNo(txn).setVoucherFileId(cmd.voucherFileId());
        refundMapper.insert(refund); // 先插新记录（replaced_by_id 为空）
        if (cmd.replacesId() != null) {
            FinRefundDO replaced = refundMapper.selectById(cmd.replacesId());
            if (replaced == null || !"已作废".equals(replaced.getStatus())
                    || replaced.getCompanyId() != post.orgId()
                    || replaced.getReceiptId() != cmd.receiptId()) {
                throw exception(REFUND_NOT_FOUND); // 更正上下文不合法
            }
            // CAS 争抢替代权：同一被作废记录至多一个有效替代
            int claimed = refundMapper.update(new FinRefundDO().setReplacedById(refund.getId()),
                    new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<FinRefundDO>()
                            .eq(FinRefundDO::getId, cmd.replacesId())
                            .isNull(FinRefundDO::getReplacedById));
            if (claimed != 1) {
                throw exception(REFUND_ALREADY_REPLACED); // 并发更正败者整笔回滚
            }
        }
        if (txn != null && identity == null) {
            txnNoMapper.insert(new FinTxnNoDO().setCompanyId(post.orgId()).setTxnNo(txn)
                    .setOwnerType("refund").setOwnerId(refund.getId()).setClosed(false)
                    .setCreateTime(OffsetDateTime.now()));
        } else if (txn != null && cmd.replacesId() != null) {
            // 同号更正：身份行 owner 原子切换到新记录
            identity.setOwnerType("refund").setOwnerId(refund.getId());
            txnNoMapper.updateById(identity);
        }
        commands.record(post, "fin-refund", refund.getId(), 1, "fin-refund-register", cmd, idempotencyKey);
        log.info("[register][companyId={} refund={} receipt={} amount={} replaces={}]",
                post.orgId(), refund.getId(), cmd.receiptId(), cmd.amountCents(), cmd.replacesId());
        return refund.getId();
    }

    /** M08-T08：支出退回——供应商/劳务方退回材料/劳务款，冲减已付现金（方向=expense） */
    @Transactional
    public long registerExpense(PostSnapshot post, String idempotencyKey, ExpenseRefundCommand cmd) {
        var replayed = commands.replayed(post, idempotencyKey, cmd);
        if (replayed.isPresent()) return replayed.get().getAggregateId();
        BusinessDateGuard.requireValid(cmd.date());
        FinPaymentDO payment = paymentMapper.selectById(cmd.paymentId());
        if (payment == null) throw exception(PAYMENT_NOT_FOUND_EXPENSE);
        if (payment.getCompanyId() != post.orgId()) throw exception(ORG_MISMATCH);
        long paidTotal = paymentMapper.selectList(null).stream()
                .filter(p2 -> p2.getPayableId() == payment.getPayableId() && "已核实".equals(p2.getStatus()))
                .mapToLong(FinPaymentDO::getAmountCents).sum();
        if (cmd.amountCents() > paidTotal) throw exception(EXPENSE_REFUND_EXCEED);
        var txn = FinReceiptService.normalize(cmd.transactionNo());
        FinRefundDO refund = new FinRefundDO()
                .setPaymentId(cmd.paymentId()).setCompanyId(post.orgId())
                .setAmountCents(cmd.amountCents()).setStatus("已核实")
                .setReason(cmd.reason()).setOperatorUserId(post.userId())
                .setDate(cmd.date()).setTransactionNo(txn)
                .setVoucherFileId(cmd.voucherFileId())
                .setRefundDirection("expense");
        refundMapper.insert(refund);
        commands.record(post, "fin-expense-refund", refund.getId(), 1, "fin-expense-refund", cmd, idempotencyKey);
        return refund.getId();
    }

    /** 作废（finance-lead/boss/owner；void_reason 必填；重复作废无二次效果）；余额随状态回补 */
    @Transactional
    public void void_(PostSnapshot post, long refundId, String reason) {
        FinRefundDO refund = refundMapper.selectById(refundId);
        if (refund == null) {
            throw exception(REFUND_NOT_FOUND);
        }
        if (refund.getCompanyId() != post.orgId()) {
            throw exception(ORG_MISMATCH);
        }
        if ("已作废".equals(refund.getStatus())) {
            return; // 幂等：重复作废无二次效果
        }
        refundMapper.updateById(new FinRefundDO().setId(refundId).setStatus("已作废")
                .setVoidByUserId(post.userId()).setVoidReason(reason).setVoidedAt(OffsetDateTime.now()));
        log.info("[void][companyId={} refund={} by={} reason={}]", post.orgId(), refundId, post.userId(), reason);
    }

}
