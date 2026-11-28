package cn.iocoder.yudao.module.franchise.finance;

import cn.iocoder.yudao.module.franchise.finance.dal.FinPaymentDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinPaymentMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinPayableMapper;
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
@org.springframework.context.annotation.Import({DesignFinanceLocks.class,FinTransactionIdentityGuard.class})
public class FinRefundService {
    @Resource private DesignFinanceLocks designLocks;
    @Resource private FinTransactionIdentityGuard transactionIdentity;

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
    @Resource
    private FinPayableMapper payableMapper;
    @Resource private org.springframework.jdbc.core.JdbcTemplate jdbc;

    /** 与登记持有同一命令锁，查询只返回当前岗位提交的该方向原单。 */
    @Transactional
    public java.util.Map<String,String> registrationResult(PostSnapshot post, String key, boolean expense) {
        key = commands.requireIdempotencyKey(key);
        designLocks.command(post, key);
        String aggregate = expense ? "fin-expense-refund" : "fin-refund";
        String command = expense ? "fin-expense-refund" : "fin-refund-register";
        var ids = jdbc.queryForList("""
                SELECT aggregate_id FROM pf_command_event WHERE domain='franchise' AND command=?
                   AND aggregate_type=? AND org_id=? AND actor_user_id=? AND actor_post_id=? AND idempotency_key=?
                """, Long.class, command, aggregate, post.orgId(), post.userId(), post.postId(),
                "franchise:" + post.userId() + ":" + key);
        return ids.isEmpty() ? java.util.Map.of("state", "NOT_REGISTERED")
                : java.util.Map.of("refundId", Long.toString(ids.get(0)));
    }

    public record RegisterCommand(long receiptId, long amountCents, String reason, LocalDate date,
                                  String transactionNo, Long voucherFileId, Long replacesId) {
    }

    /** M08-T08：支出退回命令（供应商/劳务方退回材料/劳务款，冲减已付现金）；replacesId 走更正链（R2 P1-3） */
    public record ExpenseRefundCommand(long paymentId,long amountCents,String reason,LocalDate date,String transactionNo,Long voucherFileId,Long replacesId,String fundingType) {
        public ExpenseRefundCommand(long paymentId,long amountCents,String reason,LocalDate date,String transactionNo,Long voucherFileId,Long replacesId){this(paymentId,amountCents,reason,date,transactionNo,voucherFileId,replacesId,null);}
    }

    @Transactional
    public long register(PostSnapshot post, String idempotencyKey, RegisterCommand cmd) {
        idempotencyKey = commands.requireIdempotencyKey(idempotencyKey);
        designLocks.command(post,idempotencyKey);
        var replayed = commands.replayed(post, idempotencyKey, cmd);
        if (replayed.isPresent()) {
            return replayed.get().getAggregateId();
        }
        if(cmd.amountCents()<=0) throw exception(REFUND_EXCEED_BALANCE);
        BusinessDateGuard.requireValid(cmd.date()); // M08-T05/R04
        designLocks.receipt(cmd.receiptId());
        // Optional evidence uses the same organization/type/status guard for every source.
        // Validate before locking cash rows, preserving the design stage -> receipt lock order.
        receiptService.requireVoucherAsset(post,cmd.voucherFileId());
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
        transactionIdentity.requireNoPayment(post.orgId(),txn);
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
            try { txnNoMapper.insert(new FinTxnNoDO().setCompanyId(post.orgId()).setTxnNo(txn)
                    .setOwnerType("refund").setOwnerId(refund.getId()).setClosed(false)
                    .setCreateTime(OffsetDateTime.now())); }
            catch (org.springframework.dao.DuplicateKeyException occupied) { throw exception(TXN_NO_OCCUPIED); }
        } else if (txn != null && cmd.replacesId() != null) {
            transferIdentity(post.orgId(), txn, "refund", cmd.replacesId(), refund.getId());
        }
        commands.record(post, "fin-refund", refund.getId(), 1, "fin-refund-register", cmd, idempotencyKey);
        log.info("[register][companyId={} refund={} receipt={} amount={} replaces={}]",
                post.orgId(), refund.getId(), cmd.receiptId(), cmd.amountCents(), cmd.replacesId());
        return refund.getId();
    }

    /** M08-T08：支出退回——供应商/劳务方退回材料/劳务款，冲减已付现金（方向=expense）。
     * 不变量（行锁内，R1 P2）：仅"已核实"付款可退回（作废/并发退回由 lockById 互斥+状态复核挡住）；
     * Σ该付款单已核实支出退回 + 本次 ≤ 该付款单金额——超上限拒绝（退多付不减正确费用，不形成新欠款）；
     * 流水号同进 fr_fin_txn_no 防重表；更正链（R2 P1-3）：作废旧退回→重登携 replacesId（须同付款单）
     * CAS 争抢替代权，同号更正原子切换 identity owner——不释放流水号供任意复用。 */
    @Transactional
    public long registerExpense(PostSnapshot post, String idempotencyKey, ExpenseRefundCommand cmd) {
        idempotencyKey = commands.requireIdempotencyKey(idempotencyKey);
        designLocks.command(post,idempotencyKey);
        var replayed = commands.replayed(post, idempotencyKey, cmd);
        if (replayed.isPresent()) return replayed.get().getAggregateId();
        if(cmd.amountCents()<=0) throw exception(EXPENSE_REFUND_EXCEED);
        BusinessDateGuard.requireValid(cmd.date());
        designLocks.payment(cmd.paymentId());
        receiptService.requireVoucherAsset(post,cmd.voucherFileId());
        payableMapper.lockBillsForPayment(post.orgId(), cmd.paymentId());
        FinPaymentDO payment = paymentMapper.lockById(cmd.paymentId());
        if (payment == null) throw exception(PAYMENT_NOT_FOUND_EXPENSE);
        if (payment.getCompanyId() != post.orgId()) throw exception(ORG_MISMATCH);
        if (!"已核实".equals(payment.getStatus())) throw exception(EXPENSE_REFUND_SOURCE_INVALID);
        long refunded = refundMapper.selectActiveByPayment(cmd.paymentId()).stream()
                .mapToLong(FinRefundDO::getAmountCents).sum();
        if (payment.getAmountCents() == null || cmd.amountCents() > payment.getAmountCents() - refunded) {
            throw exception(EXPENSE_REFUND_EXCEED);
        }
        String txn = FinReceiptService.normalize(cmd.transactionNo());
        transactionIdentity.requireNoPayment(post.orgId(),txn);
        FinTxnNoDO identity = txn == null ? null : txnNoMapper.find(post.orgId(), txn);
        if (identity != null && cmd.replacesId() == null) {
            throw exception(TXN_NO_OCCUPIED);
        }
        String returnedTo=cmd.fundingType()==null?(payment.getFundingType()==null?"COMPANY":payment.getFundingType()):cmd.fundingType();
        if(!java.util.Set.of("COMPANY","EMPLOYEE").contains(returnedTo) || ("EMPLOYEE".equals(returnedTo) && !"EMPLOYEE".equals(payment.getFundingType())))throw exception(FIN_SOURCE_FORBIDDEN);
        FinRefundDO refund = new FinRefundDO()
                .setPaymentId(cmd.paymentId()).setCompanyId(post.orgId())
                .setFundingType(returnedTo)
                .setAmountCents(cmd.amountCents()).setStatus("已核实")
                .setReason(cmd.reason()).setOperatorUserId(post.userId())
                .setDate(cmd.date()).setTransactionNo(txn)
                .setVoucherFileId(cmd.voucherFileId())
                .setReplacesId(cmd.replacesId())
                .setRefundDirection("expense");
        refundMapper.insert(refund); // 先插新记录（replaced_by_id 为空）
        if (cmd.replacesId() != null) {
            FinRefundDO replaced = refundMapper.selectById(cmd.replacesId());
            if (replaced == null || !"已作废".equals(replaced.getStatus())
                    || replaced.getCompanyId() != post.orgId()
                    || replaced.getPaymentId() == null || replaced.getPaymentId() != cmd.paymentId()
                    || !"expense".equals(replaced.getRefundDirection())) {
                throw exception(REFUND_NOT_FOUND); // 更正上下文不合法（须同付款单的已作废支出退回）
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
            try { txnNoMapper.insert(new FinTxnNoDO().setCompanyId(post.orgId()).setTxnNo(txn)
                    .setOwnerType("expense-refund").setOwnerId(refund.getId()).setClosed(false)
                    .setCreateTime(OffsetDateTime.now())); }
            catch (org.springframework.dao.DuplicateKeyException occupied) { throw exception(TXN_NO_OCCUPIED); }
        } else if (txn != null && cmd.replacesId() != null) {
            transferIdentity(post.orgId(), txn, "expense-refund", cmd.replacesId(), refund.getId());
        }
        commands.record(post, "fin-expense-refund", refund.getId(), 1, "fin-expense-refund", cmd, idempotencyKey);
        log.info("[registerExpense][companyId={} refund={} payment={} amount={} replaces={}]",
                post.orgId(), refund.getId(), cmd.paymentId(), cmd.amountCents(), cmd.replacesId());
        return refund.getId();
    }

    private void transferIdentity(long companyId, String txn, String type, long original, long replacement) {
        // 原归属类型和记录同时匹配；失败会回滚新退款及替代权。
        if (txnNoMapper.transfer(companyId, txn, type, original, replacement) != 1) {
            throw exception(TXN_NO_OCCUPIED);
        }
    }

    /** 作废（finance-lead/boss/owner；void_reason 必填；重复作废无二次效果）；余额随状态回补 */
    @Transactional
    public void void_(PostSnapshot post, long refundId, String reason) {
        void_(post,refundId,reason,null);
    }

    @Transactional
    public void void_(PostSnapshot post,long refundId,String reason,String idempotencyKey) {
        FinRefundDO refund = refundMapper.selectById(refundId);
        if (refund == null) {
            throw exception(REFUND_NOT_FOUND);
        }
        if (refund.getCompanyId() != post.orgId()) {
            throw exception(ORG_MISMATCH);
        }
        boolean design=refund.getPaymentId()!=null?designLocks.designPayment(refund.getPaymentId()):designLocks.designReceipt(refund.getReceiptId());
        if(design) {
            idempotencyKey=commands.requireIdempotencyKey(idempotencyKey); designLocks.command(post,idempotencyKey);
            if(commands.replayed(post,idempotencyKey,java.util.Map.of("refundId",refundId,"reason",reason)).isPresent()) return;
            if(refund.getPaymentId()!=null) designLocks.payment(refund.getPaymentId()); else designLocks.receipt(refund.getReceiptId());
        }
        if (refund.getPaymentId() != null) payableMapper.lockBillsForPayment(post.orgId(), refund.getPaymentId());
        refund = refundMapper.lockById(refundId);
        if ("已作废".equals(refund.getStatus())) {
            return; // 幂等：重复作废无二次效果
        }
        if(design && refund.getPaymentId()!=null) designLocks.requireExpenseRefundVoidCapacity(refund.getPaymentId(),refund.getAmountCents());
        if(design && refund.getReceiptId()!=null) {
            var receipt=receiptMapper.lockById(refund.getReceiptId());
            if(receipt==null || !FinReceiptService.STATUS_REGISTERED.equals(receipt.getStatus()) || receipt.getOriginPaymentId()==null) throw exception(REFUND_SOURCE_INVALID);
            long active=refundMapper.selectActiveByReceipt(receipt.getId()).stream().mapToLong(FinRefundDO::getAmountCents).sum();
            receiptService.requireDesignReceiptCapacity(receipt.getOriginPaymentId(),receipt.getAmountCents()-active+refund.getAmountCents(),receipt.getId());
        }
        refundMapper.updateById(new FinRefundDO().setId(refundId).setStatus("已作废")
                .setVoidByUserId(post.userId()).setVoidReason(reason).setVoidedAt(OffsetDateTime.now()));
        if(design) commands.record(post,"fin-refund",refundId,2,"fin-refund-void",java.util.Map.of("refundId",refundId,"reason",reason),idempotencyKey);
        log.info("[void][companyId={} refund={} by={} reason={}]", post.orgId(), refundId, post.userId(), reason);
    }

}
