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
@org.springframework.context.annotation.Import({DesignFinanceLocks.class,FinTransactionIdentityGuard.class})
public class FinPaymentService {
    @Resource private DesignFinanceLocks designLocks;

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
    @Resource
    private cn.iocoder.yudao.module.franchise.finance.dal.FinTxnNoMapper transactionNumbers;
    @Resource private FinTransactionIdentityGuard transactionIdentity;
    @Resource private org.springframework.jdbc.core.JdbcTemplate jdbc;

    public record PayableCommand(long companyId, Long projectId, String stage, long totalCents,
                                 String recipient, String agreementRef) {
    }

    public record PaymentCommand(long payableId, long amountCents, LocalDate payDate,
                                 String transactionNo, Long voucherFileId, String fundingType, Long payerUserId, String payerName, String accountName) {
        public PaymentCommand(long payableId,long amountCents,LocalDate payDate,String transactionNo,Long voucherFileId) {
            this(payableId,amountCents,payDate,transactionNo,voucherFileId,"COMPANY",null,null,null);
        }
    }

    private record LegacyPaymentCommand(long payableId,long amountCents,LocalDate payDate,String transactionNo,Long voucherFileId) {}

    public record EngineeringPayableUpdateCommand(long payableId,long projectId,String sourceRef,
                                                  String recipient,long totalCents,String basis) {}

    /** 工程外事务持供应行锁；与付款共用应付行锁后复核无已核实付款，退回不解锁。 */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void updateUnpaidEngineeringPayable(PostSnapshot post,String idempotencyKey,EngineeringPayableUpdateCommand cmd) {
        idempotencyKey=commands.requireIdempotencyKey(idempotencyKey);
        designLocks.command(post,idempotencyKey);
        if(commands.replayed(post,idempotencyKey,cmd).isPresent()) return;
        var payable=payableMapper.lockById(cmd.payableId());
        boolean sourceMatches=Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM fr_eng_supply WHERE company_id=? AND project_id=? AND payable_id=?
                AND ('engineering-payable:' || project_id || ':' || id)=?)
            """,Boolean.class,post.orgId(),cmd.projectId(),cmd.payableId(),cmd.sourceRef()));
        if(payable==null || payable.getCompanyId()!=post.orgId()
                || !java.util.Objects.equals(payable.getProjectId(),cmd.projectId())
                || !"engineering".equals(payable.getStage()) || !sourceMatches
                || (payable.getSourceType()!=null && (!"engineering".equals(payable.getSourceType())
                    || !java.util.Objects.equals(payable.getSourceRef(),cmd.sourceRef())))
                || !"进行中".equals(payable.getStatus())) throw exception(PAYABLE_NOT_FOUND);
        if(cmd.totalCents()<=0 || cn.hutool.core.util.StrUtil.isBlank(cmd.recipient())
                || cn.hutool.core.util.StrUtil.isBlank(cmd.basis())) throw exception(PAYMENT_EXCEED_PAYABLE);
        if(paymentMapper.selectActiveByPayable(cmd.payableId()).stream().anyMatch(p->p.getCompanyId()==post.orgId()))
            throw exception(PAYMENT_EXCEED_PAYABLE);
        payableMapper.updateById(new FinPayableDO().setId(cmd.payableId()).setTotalCents(cmd.totalCents())
                .setRecipient(cmd.recipient()).setAgreementRef(cmd.basis())
                .setSourceType("engineering").setSourceRef(cmd.sourceRef()));
        long revision=jdbc.queryForObject("SELECT COALESCE(max(revision),0)+1 FROM pf_command_event WHERE domain='franchise' AND aggregate_type='fin-payable' AND aggregate_id=?",Long.class,cmd.payableId());
        commands.record(post,"fin-payable",cmd.payableId(),revision,"engineering-payable-unpaid-update",cmd,idempotencyKey);
    }

    @Transactional
    public long registerPayable(PostSnapshot post, String idempotencyKey, PayableCommand cmd) {
        idempotencyKey = commands.requireIdempotencyKey(idempotencyKey);
        designLocks.command(post,idempotencyKey);
        var replayed = commands.replayed(post, idempotencyKey, cmd);
        if (replayed.isPresent()) {
            return replayed.get().getAggregateId();
        }
        if(cmd.totalCents()<=0 || cn.hutool.core.util.StrUtil.isBlank(cmd.recipient())) throw exception(PAYMENT_EXCEED_PAYABLE);
        if(cmd.projectId()!=null && !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM fr_eng_project WHERE id=? AND company_id=?)",Boolean.class,cmd.projectId(),post.orgId()))) throw exception(ORG_MISMATCH);
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
        idempotencyKey = commands.requireIdempotencyKey(idempotencyKey);
        designLocks.command(post,idempotencyKey);
        Object fingerprint=(cmd.fundingType()==null || "COMPANY".equals(cmd.fundingType())) && cmd.payerUserId()==null && cmd.payerName()==null && cmd.accountName()==null
            ?new LegacyPaymentCommand(cmd.payableId(),cmd.amountCents(),cmd.payDate(),cmd.transactionNo(),cmd.voucherFileId()):cmd;
        var replayed = commands.replayed(post, idempotencyKey, fingerprint);
        if (replayed.isPresent()) {
            return replayed.get().getAggregateId();
        }
        if (cmd.payableId() <= 0) {
            throw exception(PAYMENT_PAYABLE_REQUIRED);
        }
        BusinessDateGuard.requireValid(cmd.payDate()); // M08-T05/R04
        if (cmd.voucherFileId() != null && cmd.voucherFileId() > 0) {
            FinReceiptService.requireVoucherAsset(post, cmd.voucherFileId(), assets, voucherFiles, serviceAccounts);
        }
        FinPayableDO source = payableMapper.selectById(cmd.payableId());
        if(source!=null) designLocks.source(source.getSourceType(),source.getSourceRef());
        FinPayableDO payable = payableMapper.lockById(cmd.payableId());
        if (payable == null || payable.getCompanyId() != post.orgId()) {
            throw exception(PAYABLE_NOT_FOUND);
        }
        if(cmd.amountCents()<=0) throw exception(PAYMENT_EXCEED_PAYABLE);
        String funding=cmd.fundingType()==null?"COMPANY":cmd.fundingType();
        if(!java.util.Set.of("COMPANY","EMPLOYEE").contains(funding)) throw exception(FIN_SOURCE_FORBIDDEN);
        if("EMPLOYEE".equals(funding)) {
            if(!java.util.Set.of("design","manual").contains(String.valueOf(payable.getSourceType())) || cmd.payerUserId()==null || cn.hutool.core.util.StrUtil.isBlank(cmd.payerName())
                || !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pf_staff_post WHERE org_id=? AND user_id=? AND status='ACTIVE')",Boolean.class,post.orgId(),cmd.payerUserId()))) throw exception(FIN_SOURCE_FORBIDDEN);
        }
        String transactionNo=FinReceiptService.normalize(cmd.transactionNo());
        transactionIdentity.requireNoPayment(post.orgId(),transactionNo);
        if(transactionNo!=null && transactionNumbers.find(post.orgId(),transactionNo)!=null) {
            throw exception(TXN_NO_OCCUPIED);
        }
        // R2 P1-4：paid 有效账口径=Σ(amount-pending_link_cents)——PENDING_LINK 超额部分不计入
        // 有效余额（不冲减其他应付；reconcile 转正后计入）
        long paid = paymentMapper.selectActiveByPayable(cmd.payableId()).stream()
                .mapToLong(p -> amount(p) - (p.getPendingLinkCents() == null ? 0 : p.getPendingLinkCents())).sum();
        // M10-T05/R1 P1-4：超余额部分转「已核待关联现金」独立异常留痕（不拒绝——真实资金留痕优先，
        // 不绕有效账上限：有效账只计额度内部分；reconcile 后可转正）
        long expenseRefunded=paymentMapper.selectActiveByPayable(cmd.payableId()).stream()
            .mapToLong(p->Math.max(0,refundMapper.selectActiveByPayment(p.getId()).stream().mapToLong(r->r.getAmountCents()).sum()-(p.getPendingLinkCents()==null?0:p.getPendingLinkCents()))).sum();
        long effectiveTotal=payable.getTotalCents();
        if("reimbursement".equals(payable.getSourceType())) {
            // 原垫付行锁串行化报销与退款；公司报销不再结算平台费用。
            long originId=Long.parseLong(payable.getSourceRef());
            var origin=paymentMapper.lockById(originId);
            if(origin==null || !"已核实".equals(origin.getStatus()) || origin.getCompanyId()!=post.orgId()) throw exception(PAYABLE_NOT_FOUND);
            effectiveTotal=origin.getAmountCents()-refundMapper.selectActiveByPayment(originId).stream().filter(r->"EMPLOYEE".equals(r.getFundingType())).mapToLong(r->r.getAmountCents()).sum();
            long recovered=jdbc.queryForObject("SELECT COALESCE(sum(amount_cents),0) FROM fr_fin_receipt WHERE company_id=? AND source_ref=? AND status='已登记'",Long.class,post.orgId(),"employee-recovery:"+payable.getId());
            paid-=recovered;
            if(cmd.amountCents()>effectiveTotal-paid+expenseRefunded) throw exception(PAYMENT_EXCEED_PAYABLE);
        }
        long overCents = Math.max(0, paid-expenseRefunded + cmd.amountCents() - effectiveTotal);
        if("design".equals(payable.getSourceType())) {
            overCents=Math.max(0,paid-expenseRefunded+cmd.amountCents()-payable.getTotalCents());
            if(cmd.amountCents()<=0 || overCents>0) throw exception(PAYMENT_EXCEED_PAYABLE);
        }
        overCents=Math.min(cmd.amountCents(),overCents);
        long linkedCents = cmd.amountCents() - overCents;
        FinPaymentDO payment = new FinPaymentDO()
                .setPayableId(cmd.payableId()).setProjectId(payable.getProjectId()).setCompanyId(post.orgId())
                .setAmountCents(cmd.amountCents()).setStatus("已核实")
                .setFundingType(funding).setPayerUserId(cmd.payerUserId()).setPayerName(cmd.payerName()).setAccountName(cmd.accountName())
                .setPendingLinkCents(overCents)
                .setLinkStatus(overCents > 0 ? "PENDING_LINK" : "LINKED")
                .setPayDate(cmd.payDate()).setOperatorUserId(post.userId())
                .setTransactionNo(transactionNo)
                .setVoucherFileId(cmd.voucherFileId());
        paymentMapper.insert(payment);
        if(transactionNo!=null) {
            try {
                transactionNumbers.insert(new cn.iocoder.yudao.module.franchise.finance.dal.FinTxnNoDO()
                        .setCompanyId(post.orgId()).setTxnNo(transactionNo).setOwnerType("payment")
                        .setOwnerId(payment.getId()).setClosed(false).setCreateTime(OffsetDateTime.now()));
            } catch(org.springframework.dao.DuplicateKeyException occupied) {
                // 不同应付并发抢同一流水：唯一身份兜底，失败付款与本事务一起回滚。
                throw exception(TXN_NO_OCCUPIED);
            }
        }
        if("EMPLOYEE".equals(funding)) {
            FinPayableDO reimbursement=new FinPayableDO().setCompanyId(post.orgId()).setProjectId(payable.getProjectId())
                .setBookId(payable.getBookId()).setCategory("员工往来").setStage("design".equals(payable.getSourceType())?"设计费报销":payable.getStage()+"报销").setTotalCents(cmd.amountCents())
                .setRecipient(cmd.payerName()).setStatus("进行中").setSourceType("reimbursement").setSourceRef(String.valueOf(payment.getId()))
                .setBusinessDate(cmd.payDate()).setAgreementRef("原设计费垫付 "+payment.getId());
            payableMapper.insert(reimbursement);
        }
        commands.record(post, "fin-payment", payment.getId(), 1, "fin-payment-register", fingerprint, idempotencyKey);
        log.info("[register][companyId={} payment={} payable={} amount={}]", post.orgId(), payment.getId(), cmd.payableId(), cmd.amountCents());
        return payment.getId();
    }

    /** 作废（仅 finance-lead，权限码 franchise:fin:payment-void）；释放额度并留痕；重复作废幂等。
     * M08-T08/R2 P1：与支出退回共用付款行锁并在锁内拒绝作废存在有效退回的付款——
     * 封闭「退回持锁提交→作废无条件覆盖」与「作废先提交→退回状态复核拒绝」的双向交错。 */
    @Transactional
    public void void_(PostSnapshot post, long paymentId, String reason) {
        void_(post,paymentId,reason,null);
    }

    @Transactional
    public void void_(PostSnapshot post,long paymentId,String reason,String idempotencyKey) {
        boolean design=designLocks.designPayment(paymentId);
        if(design) {
            idempotencyKey=commands.requireIdempotencyKey(idempotencyKey);
            designLocks.command(post,idempotencyKey);
            var input=java.util.Map.of("paymentId",paymentId,"reason",reason);
            if(commands.replayed(post,idempotencyKey,input).isPresent()) return;
            designLocks.payment(paymentId);
        }
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
        if("EMPLOYEE".equals(payment.getFundingType()) && Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM fr_fin_payable q JOIN fr_fin_payment m ON m.payable_id=q.id WHERE q.company_id=? AND q.source_type='reimbursement' AND q.source_ref=? AND m.status='已核实')",Boolean.class,post.orgId(),String.valueOf(paymentId)))) throw exception(PAYMENT_HAS_ACTIVE_REFUND);
        if (!refundMapper.selectActiveByPayment(paymentId).isEmpty()) {
            throw exception(PAYMENT_HAS_ACTIVE_REFUND); // 先作废退回记录，再作废付款
        }
        if(designLocks.designPayment(paymentId) && designLocks.hasNetDesignReceipts(paymentId)) throw exception(PAYMENT_HAS_DESIGN_RECEIPT);
        paymentMapper.updateById(new FinPaymentDO().setId(paymentId).setStatus("已作废")
                .setVoidByUserId(post.userId()).setVoidReason(reason).setVoidedAt(OffsetDateTime.now()));
        if(design) commands.record(post,"fin-payment",paymentId,2,"fin-payment-void",java.util.Map.of("paymentId",paymentId,"reason",reason),idempotencyKey);
        log.info("[void][companyId={} payment={} by={} reason={}]", post.orgId(), paymentId, post.userId(), reason);
    }

    static long amount(FinPaymentDO payment) {
        return payment.getAmountCents() == null ? 0 : payment.getAmountCents();
    }

}
