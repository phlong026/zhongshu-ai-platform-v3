package cn.iocoder.yudao.module.franchise.finance;

import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.franchise.api.CommissionQueryPort;
import cn.iocoder.yudao.module.franchise.finance.dal.FinReceiptDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinReceiptMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinReceiptRevisionDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinReceiptRevisionMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinVoucherMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinRefundMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinTxnNoDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinTxnNoMapper;
import cn.iocoder.yudao.module.franchise.finance.enums.FranchiseFinErrorCodes;
import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED;
import cn.iocoder.yudao.module.franchise.api.BusinessReceiptPort;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;

import java.util.Map;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.finance.enums.FranchiseFinErrorCodes.*;

/**
 * 实收账簿：登记即生效（唯一有效实收状态）；修订走行锁 + baseRevisionVersion 乐观锁 + 全留痕；
 * 交易身份 fr_fin_txn_no 永久防重（软删/作废不释放）；来源门禁见需求文档 §五。
 */
@Service
@Slf4j
@org.springframework.context.annotation.Import({DesignFinanceLocks.class,FinTransactionIdentityGuard.class})
public class FinReceiptService {
    @Resource private DesignFinanceLocks designLocks;
    @Resource private cn.iocoder.yudao.module.franchise.finance.dal.FinPaymentMapper designPayments;
    @Resource private cn.iocoder.yudao.module.franchise.finance.dal.FinPayableMapper designPayables;
    @Resource private org.springframework.jdbc.core.JdbcTemplate designJdbc;
    @Resource private FinTransactionIdentityGuard transactionIdentity;

    public static final String STATUS_PENDING = "待登记";
    public static final String STATUS_REGISTERED = "已登记";
    public static final String STATUS_VOIDED = "已撤销";

    @Resource
    private FinReceiptMapper receiptMapper;
    @Resource
    private FinReceiptRevisionMapper revisionMapper;
    @Resource
    private FinRefundMapper refundMapper;
    @Resource
    private FinTxnNoMapper txnNoMapper;
    @Resource
    private CommissionQueryPort commissions;
    @Resource
    private cn.iocoder.yudao.module.franchise.api.AssetAccessPort assets;
    @Resource
    private FinVoucherMapper voucherFileMapper;
    @Resource
    private cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort serviceAccounts;
    @Resource
    private FranchiseCommands commands;

    public record RegisterCommand(String sourceType, String sourceRef, String name, long amountCents,
                                  LocalDate receivedDate, String method, String transactionNo,
                                  Long voucherFileId, Long projectId, Long customerId,
                                  Long designCommissionId, String designStage, Long originPaymentId) {
        public RegisterCommand(String sourceType,String sourceRef,String name,long amountCents,LocalDate receivedDate,
                String method,String transactionNo,Long voucherFileId,Long projectId,Long customerId,Long designCommissionId,String designStage) {
            this(sourceType,sourceRef,name,amountCents,receivedDate,method,transactionNo,voucherFileId,projectId,customerId,designCommissionId,designStage,null);
        }
    }

    @Transactional
    public long register(PostSnapshot post, String idempotencyKey, RegisterCommand cmd) {
        idempotencyKey = commands.requireIdempotencyKey(idempotencyKey);
        designLocks.command(post,idempotencyKey);
        var replayed = commands.replayed(post, idempotencyKey, cmd);
        if (replayed.isPresent()) {
            return replayed.get().getAggregateId(); // 幂等重放：返回首次登记的收款
        }
        String sourceType = cmd.sourceType() == null ? "manual" : cmd.sourceType();
        if (!FinPermissions.MANUAL_SOURCE_TYPES.contains(sourceType)) {
            throw exception(FIN_SOURCE_FORBIDDEN);
        }
        long companyId;
        String name = cmd.name();
        long amountCents = cmd.amountCents();
        String sourceRef = cmd.sourceRef();
        Long customerId=cmd.customerId();
        Long projectId=cmd.projectId();
        if ("design".equals(sourceType)) {
            CommissionQueryPort.CommissionFeeView fee = designGate(post, cmd);
            companyId = fee.designOrgId(); // 总部收款侧
            sourceRef = fee.commissionId() + ":" + fee.submissionVersion() + ":" + cmd.designStage().toUpperCase(java.util.Locale.ROOT);
            designLocks.source("design",sourceRef);
            if(amountCents<=0) throw exception(DESIGN_RECEIPT_EXCEED_PAYMENT);
            if(cmd.originPaymentId()==null) throw exception(DESIGN_SOURCE_INVALID);
            var origin=designPayments.lockById(cmd.originPaymentId());
            var payable=origin==null?null:designPayables.selectById(origin.getPayableId());
            if(origin==null || payable==null || !"已核实".equals(origin.getStatus()) || origin.getCompanyId()!=fee.companyId()
                    || payable.getCompanyId()!=fee.companyId() || !java.util.Objects.equals(payable.getDesignOrgId(),fee.designOrgId())
                    || !"design".equals(payable.getSourceType()) || !sourceRef.equals(payable.getSourceRef())) throw exception(DESIGN_SOURCE_INVALID);
            requireDesignReceiptCapacity(origin.getId(),amountCents,null);
            customerId=fee.customerId(); projectId=payable.getProjectId();
            name = "设计" + cmd.designStage().toUpperCase(java.util.Locale.ROOT) + " · " + (name==null?"核收":name);
        } else {
            companyId = post.orgId();
            if(projectId!=null && !Boolean.TRUE.equals(designJdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM fr_eng_project WHERE id=? AND company_id=?)",Boolean.class,projectId,companyId))) throw exception(ORG_MISMATCH);
        }
        BusinessDateGuard.requireValid(cmd.receivedDate()); // M08-T05/R04：日期必填拒未来
        requireVoucherAsset(post, cmd.voucherFileId()); // M5 R2 P1-2：基-1 引用校验（21 号 V3）
        requireTxnNoFree(companyId, cmd.transactionNo(), null);
        FinReceiptDO receipt = new FinReceiptDO()
                .setCompanyId(companyId).setProjectId(projectId).setCustomerId(customerId)
                .setOriginPaymentId("design".equals(sourceType)?cmd.originPaymentId():null)
                .setSourceType(sourceType).setSourceRef(sourceRef).setName(name)
                .setAmountCents(amountCents).setStatus(STATUS_REGISTERED)
                .setReceivedDate(cmd.receivedDate()).setRegisteredAt(OffsetDateTime.now())
                .setRegistrarUserId(post.userId()).setMethod(cmd.method() == null ? "bank" : cmd.method())
                .setTransactionNo(normalize(cmd.transactionNo())).setVoucherFileId(cmd.voucherFileId())
                .setRevisionVersion(1);
        receiptMapper.insert(receipt);
        if (cmd.transactionNo() != null && !cmd.transactionNo().isBlank()) {
            txnNoMapper.insert(new FinTxnNoDO().setCompanyId(companyId)
                    .setTxnNo(normalize(cmd.transactionNo())).setOwnerType("receipt").setOwnerId(receipt.getId())
                    .setClosed(false).setCreateTime(OffsetDateTime.now()));
        }
        commands.record(post, "fin-receipt", receipt.getId(), 1, "fin-receipt-register", cmd, idempotencyKey);
        log.info("[register][companyId={} receipt={} amount={} source={}]", companyId, receipt.getId(), amountCents, sourceType);
        return receipt.getId();
    }

    /**
     * M5 R2 P1-2：凭证引用校验（21 号 V3；R3 兼容口径微调）——
     * ①基-1 资产：validateReference（存在+ACCEPTED+本组织属主）；
     * ②双轨兼容期：fr_fin_file 同组织存在（旧 files 通道）亦接受——新上传暂走 files multipart（QUERY 权限），
     *   基-1 切换随 COS 批字节通道（14 文档 D-基1-2 完整执行延后登记）；两者皆 miss → VOUCHER_INVALID_FIN。
     */
    void requireVoucherAsset(PostSnapshot post, Long voucherFileId) {
        if (voucherFileId == null || voucherFileId <= 0) {
            return;
        }
        requireVoucherAsset(post, voucherFileId, assets, voucherFileMapper, serviceAccounts);
    }

    /** 收付款共用：新资产的错误类型/状态不能通过同 ID 历史文件回退。 */
    static void requireVoucherAsset(PostSnapshot post, long fileId,
                                    cn.iocoder.yudao.module.franchise.api.AssetAccessPort assets,
                                    FinVoucherMapper files,
                                    cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort accounts) {
        long accountId = accounts.requireServiceAccount(post.orgId());
        var actor = new cn.iocoder.yudao.module.franchise.api.AssetAccessPort.Actor(
                post.orgId(), accountId, post.userId(), post.postId());
        if (assets.validateReference(actor, fileId, java.util.Set.of("FIN_VOUCHER"))) return;
        if (!assets.legacyReferenceAllowed(actor, fileId)) throw exception(VOUCHER_INVALID_FIN);
        var file = files.selectById(fileId);
        Long tenant = cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder.getTenantId();
        if (file == null || !Objects.equals(file.getCompanyId(), post.orgId()) || tenant == null
                || !Objects.equals(file.getTenantId(), tenant) || file.getContentType() == null
                || !java.util.Set.of("image/jpeg", "image/png", "application/pdf").contains(file.getContentType())) {
            throw exception(VOUCHER_INVALID_FIN);
        }
    }

    /** design 来源五项门禁（需求文档 §五） */
    private CommissionQueryPort.CommissionFeeView designGate(PostSnapshot post, RegisterCommand cmd) {
        if (cmd.designCommissionId() == null || cmd.designStage() == null) {
            throw exception(DESIGN_SOURCE_INVALID);
        }
        CommissionQueryPort.CommissionFeeView fee = commissions.findCurrentFee(cmd.designCommissionId());
        if (fee == null) {
            throw exception(COMMISSION_NOT_APPROVED);
        }
        if (!fee.feeApproved()) {
            throw exception(COMMISSION_NOT_APPROVED); // 未批准/已驳回
        }
        if (!fee.validStage(cmd.designStage())) {
            throw exception(COMMISSION_STAGE_INVALID);
        }
        // 操作者必须属于收款归属组织（总部 designOrgId）
        if (post.orgId() != fee.designOrgId()) {
            throw exception(ORG_MISMATCH);
        }
        return fee;
    }

    /** 修订：行锁 + baseRevisionVersion 乐观锁 + I-1 修订分支（Σ已核实退款 ≤ 新金额）+ 全留痕 */
    @Transactional
    public int revision(PostSnapshot post, long receiptId, Integer baseRevisionVersion, long newAmountCents, String note) {
        return revision(post,receiptId,baseRevisionVersion,newAmountCents,note,null);
    }

    @Transactional
    public int revision(PostSnapshot post,long receiptId,Integer baseRevisionVersion,long newAmountCents,String note,String idempotencyKey) {
        var input=new java.util.LinkedHashMap<String,Object>();
        input.put("receiptId",receiptId); input.put("baseRevisionVersion",baseRevisionVersion); input.put("newAmountCents",newAmountCents); input.put("note",note);
        boolean design=designLocks.designReceipt(receiptId);
        if(design) {
            idempotencyKey=commands.requireIdempotencyKey(idempotencyKey); designLocks.command(post,idempotencyKey);
            var replay=commands.replayed(post,idempotencyKey,input); if(replay.isPresent()) return replay.get().getRevision().intValue();
            designLocks.receipt(receiptId);
        }
        FinReceiptDO receipt = receiptMapper.lockById(receiptId);
        if (receipt == null || !"已登记".equals(receipt.getStatus())) {
            throw exception(RECEIPT_STATUS_CONFLICT);
        }
        if ("business".equals(receipt.getSourceType())) {
            throw exception(BUSINESS_RECEIPT_IMMUTABLE); // 业务回款由交易流管理，不能直接修订（Codex R1）
        }
        if (receipt.getCompanyId() != post.orgId()) {
            throw exception(ORG_MISMATCH);
        }
        if (baseRevisionVersion == null || !baseRevisionVersion.equals(receipt.getRevisionVersion())) { // Integer 引用比较越界即恒真（同评审基线 #12 类）
            throw exception(RECEIPT_REVISION_STALE);
        }
        long refunded = refundMapper.selectActiveByReceipt(receiptId).stream()
                .mapToLong(FinRefundAmount::of).sum();
        if (refunded > newAmountCents) {
            throw exception(REFUND_EXCEED_BALANCE); // 已退款超过新金额，修订拒绝
        }
        if("design".equals(receipt.getSourceType())) {
            if(receipt.getOriginPaymentId()==null) throw exception(DESIGN_SOURCE_INVALID);
            requireDesignReceiptCapacity(receipt.getOriginPaymentId(),newAmountCents-refunded,receiptId);
        }
        int base = baseRevisionVersion;
        receiptMapper.updateById(new FinReceiptDO().setId(receiptId)
                .setAmountCents(newAmountCents).setRevisionVersion(base + 1));
        revisionMapper.insert(new FinReceiptRevisionDO()
                .setReceiptId(receiptId).setBaseRevisionVersion(base)
                .setOriginalAmountCents(receipt.getAmountCents()).setNewAmountCents(newAmountCents)
                .setNote(note).setOperatorUserId(post.userId()));
        if(design) commands.record(post,"fin-receipt",receiptId,base+1,"fin-receipt-revision",input,idempotencyKey);
        log.info("[revision][receipt={} {}->{} by={}]", receiptId, receipt.getAmountCents(), newAmountCents, post.userId());
        return baseRevisionVersion + 1;
    }

    void requireDesignReceiptCapacity(long paymentId,long newNetAmount,Long excludedReceiptId) {
        var rows=designJdbc.queryForList("SELECT amount_cents-COALESCE(pending_link_cents,0)-COALESCE((SELECT sum(amount_cents) FROM fr_fin_refund WHERE payment_id=m.id AND status='已核实'),0) FROM fr_fin_payment m WHERE id=? AND status='已核实'",Long.class,paymentId);
        Long paid=rows.isEmpty()?null:rows.get(0);
        long received=designJdbc.queryForObject("SELECT COALESCE(sum(r.amount_cents-COALESCE((SELECT sum(f.amount_cents) FROM fr_fin_refund f WHERE f.receipt_id=r.id AND f.status='已核实'),0)),0) FROM fr_fin_receipt r WHERE origin_payment_id=? AND status='已登记' AND r.id<>COALESCE(?::bigint,0)",Long.class,paymentId,excludedReceiptId);
        if(paid==null || newNetAmount<0 || received+newNetAmount>paid) throw exception(DESIGN_RECEIPT_EXCEED_PAYMENT);
    }

    @Transactional
    public void voidDesign(PostSnapshot post,long receiptId,String reason,String idempotencyKey) {
        idempotencyKey=commands.requireIdempotencyKey(idempotencyKey);
        designLocks.command(post,idempotencyKey);
        var input=Map.of("receiptId",receiptId,"reason",reason);
        if(commands.replayed(post,idempotencyKey,input).isPresent()) return;
        designLocks.receipt(receiptId);
        var receipt=receiptMapper.lockById(receiptId);
        if(receipt==null || receipt.getCompanyId()!=post.orgId()) throw exception(ORG_MISMATCH);
        if(!"design".equals(receipt.getSourceType()) || receipt.getOriginPaymentId()==null) throw exception(RECEIPT_NOT_VOIDABLE_SOURCE);
        if(STATUS_VOIDED.equals(receipt.getStatus())) return;
        if(!refundMapper.selectActiveByReceipt(receiptId).isEmpty()) throw exception(REFUND_SOURCE_INVALID);
        int version=receipt.getRevisionVersion()+1;
        receiptMapper.updateById(new FinReceiptDO().setId(receiptId).setStatus(STATUS_VOIDED).setRevisionVersion(version)
                .setVoidReason(reason).setVoidByUserId(post.userId()));
        commands.record(post,"fin-receipt",receiptId,version,"design-receipt-void",input,idempotencyKey);
    }

    /**
     * 业务回款登记（B2 板块 B / P-2，BusinessReceiptPort 内部专用入口，手工 REST 不放宽）：
     * 复用 B1 既有登记链（fr_fin_txn_no 永久流水身份）；幂等键=biz-receipt:{sourceRef}（独立命名空间）；
     * source_ref 查重（唯一索引兜底并发，ON CONFLICT 目标限定 business 索引）+ 命中比对完整资金内容（不含 date）；
     * business 来源收据禁止直接修订（BUSINESS_RECEIPT_IMMUTABLE）。
     */
    @Transactional
    public BusinessReceiptPort.BusinessReceiptSaved registerBusinessReceipt(PostSnapshot post,
                                                                            String idempotencyKey,
                                                                            BusinessReceiptPort.BusinessReceipt cmd) {
        String idemKey = "biz-receipt:" + cmd.sourceRef();
        // 兼容既有命令摘要；完整资金内容仍在下方原收据上复核。
        commands.replayed(post,idemKey,Map.of("sourceRef",cmd.sourceRef(),"amountCents",cmd.amountCents(),"customerId",cmd.customerId()));
        // source_ref 查重（唯一索引兜底并发；同事务 SELECT，禁异常后重查）
        var existing = receiptMapper.selectList(new LambdaQueryWrapperX<FinReceiptDO>()
                .eq(FinReceiptDO::getSourceType, "business")
                .eq(FinReceiptDO::getSourceRef, cmd.sourceRef()));
        if (!existing.isEmpty()) {
            FinReceiptDO prior = existing.get(0);
            boolean same = prior.getAmountCents() == cmd.amountCents()
                    && java.util.Objects.equals(prior.getName(), cmd.name())
                    && java.util.Objects.equals(prior.getMethod(), cmd.method() == null ? "bank" : cmd.method())
                    && java.util.Objects.equals(prior.getTransactionNo(), normalize(cmd.transactionNo()))
                    && java.util.Objects.equals(prior.getCustomerId(), cmd.customerId());
            if (!same) {
                throw exception(IDEMPOTENCY_KEY_REUSED);
            }
            return new BusinessReceiptPort.BusinessReceiptSaved(prior.getId(), true);
        }
        requireTxnNoFree(post.orgId(), cmd.transactionNo(), null);
        requireVoucherAsset(post,cmd.voucherFileId());
        FinReceiptDO receipt = new FinReceiptDO()
                .setCompanyId(post.orgId()).setCustomerId(cmd.customerId())
                .setVoucherFileId(cmd.voucherFileId()).setAccountName(cmd.accountName())
                .setSourceType("business").setSourceRef(cmd.sourceRef()).setName(cmd.name())
                .setAmountCents(cmd.amountCents()).setStatus(STATUS_REGISTERED)
                .setReceivedDate(cmd.receivedDate()).setRegisteredAt(OffsetDateTime.now())
                .setRegistrarUserId(post.userId())
                .setMethod(cmd.method() == null ? "bank" : cmd.method())
                .setTransactionNo(normalize(cmd.transactionNo()))
                .setRevisionVersion(1);
        receiptMapper.insert(receipt);
        if (cmd.transactionNo() != null && !cmd.transactionNo().isBlank()) {
            txnNoMapper.insert(new FinTxnNoDO().setCompanyId(post.orgId())
                    .setTxnNo(normalize(cmd.transactionNo())).setOwnerType("receipt").setOwnerId(receipt.getId())
                    .setClosed(false).setCreateTime(OffsetDateTime.now()));
        }
        commands.record(post, "fin-receipt", receipt.getId(), 1, "fin-receipt-register-business",
                Map.of("sourceRef", cmd.sourceRef(), "amountCents", cmd.amountCents(), "customerId", cmd.customerId()),
                idemKey);
        log.info("[registerBusinessReceipt][companyId={} receipt={} sourceRef={}]",
                post.orgId(), receipt.getId(), cmd.sourceRef());
        return new BusinessReceiptPort.BusinessReceiptSaved(receipt.getId(), false);
    }

    /** PAID 重放专用：按 sourceRef 查既有 business 收据（内容比对在调用方——collect 的指纹已先行） */
    public BusinessReceiptPort.BusinessReceiptSaved replayBySourceRef(PostSnapshot post, String sourceRef) {
        var existing = receiptMapper.selectList(new LambdaQueryWrapperX<FinReceiptDO>()
                .eq(FinReceiptDO::getSourceType, "business")
                .eq(FinReceiptDO::getSourceRef, sourceRef));
        if (existing.isEmpty()) {
            throw exception(REFERENCE_INVALID_FIN);
        }
        return new BusinessReceiptPort.BusinessReceiptSaved(existing.get(0).getId(), true);
    }

    /** 撤销跨端预期应收（仅待登记；财务负责人，controller 层挂 payment-void 之外的校验） */
    @Transactional
    public void voidPending(PostSnapshot post, long receiptId, String reason) {
        // 设计核收仅走有原付款和共同阶段锁的正式入口，不能借跨端预期应收路径改账。
        var source=receiptMapper.selectById(receiptId);
        if(source!=null && "design".equals(source.getSourceType())) throw exception(DESIGN_SOURCE_INVALID);
        FinReceiptDO receipt = receiptMapper.lockById(receiptId);
        if (receipt == null || !STATUS_PENDING.equals(receipt.getStatus())) {
            throw exception(RECEIPT_NOT_VOIDABLE_SOURCE);
        }
        if (receipt.getCompanyId() != post.orgId()) {
            throw exception(ORG_MISMATCH);
        }
        receiptMapper.updateById(new FinReceiptDO().setId(receiptId).setStatus(STATUS_VOIDED)
                .setVoidReason(reason).setVoidByUserId(post.userId()));
    }

    /** 退款登记与修订共用的可退余额口径（行锁内调用） */
    public long refundableBalance(FinReceiptDO receipt) {
        long refunded = refundMapper.selectActiveByReceipt(receipt.getId()).stream()
                .mapToLong(FinRefundAmount::of).sum();
        return receipt.getAmountCents() - refunded;
    }

    /** 占用流水号：同公司内已被占用（且 owner 非本次记录）即拒绝；幂等重放由 Idempotency-Key 上游处理 */
    void requireTxnNoFree(long companyId, String transactionNo, Long selfId) {
        String normalized = normalize(transactionNo);
        if (normalized == null) {
            return;
        }
        transactionIdentity.requireNoPayment(companyId,normalized);
        FinTxnNoDO existing = txnNoMapper.find(companyId, normalized);
        if (existing != null && !Objects.equals(existing.getOwnerId(), selfId)) {
            throw exception(TXN_NO_OCCUPIED);
        }
    }

    public static String normalize(String txn) {
        return txn == null || txn.isBlank() ? null : txn.trim();
    }

    /** Long 包装的金额求和助手（Mapper 返回 DO，金额取 amountCents） */
    static final class FinRefundAmount {
        static long of(cn.iocoder.yudao.module.franchise.finance.dal.FinRefundDO refund) {
            return refund.getAmountCents() == null ? 0 : refund.getAmountCents();
        }
    }

}
