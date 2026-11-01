package cn.iocoder.yudao.module.franchise.finance;

import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.franchise.api.CommissionQueryPort;
import cn.iocoder.yudao.module.franchise.finance.dal.FinReceiptDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinReceiptMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinReceiptRevisionDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinReceiptRevisionMapper;
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
public class FinReceiptService {

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
    private FranchiseCommands commands;

    public record RegisterCommand(String sourceType, String sourceRef, String name, long amountCents,
                                  LocalDate receivedDate, String method, String transactionNo,
                                  Long voucherFileId, Long projectId, Long customerId,
                                  Long designCommissionId, String designStage) {
    }

    @Transactional
    public long register(PostSnapshot post, String idempotencyKey, RegisterCommand cmd) {
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
        if ("design".equals(sourceType)) {
            CommissionQueryPort.CommissionFeeView fee = designGate(post, cmd);
            companyId = fee.designOrgId(); // 总部收款侧
            amountCents = fee.stageCents(cmd.designStage());
            sourceRef = fee.commissionId() + ":" + fee.submissionVersion() + ":" + cmd.designStage();
            name = "设计" + ("first".equals(cmd.designStage()) ? "首款" : "尾款") + " · " + name;
        } else {
            companyId = post.orgId();
        }
        requireTxnNoFree(companyId, cmd.transactionNo(), null);
        FinReceiptDO receipt = new FinReceiptDO()
                .setCompanyId(companyId).setProjectId(cmd.projectId()).setCustomerId(cmd.customerId())
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

    /** design 来源五项门禁（需求文档 §五） */
    private CommissionQueryPort.CommissionFeeView designGate(PostSnapshot post, RegisterCommand cmd) {
        if (cmd.designCommissionId() == null || cmd.designStage() == null
                || !List.of("first", "tail").contains(cmd.designStage())) {
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
        if (baseRevisionVersion == null || receipt.getRevisionVersion() != baseRevisionVersion) {
            throw exception(RECEIPT_REVISION_STALE);
        }
        long refunded = refundMapper.selectActiveByReceipt(receiptId).stream()
                .mapToLong(FinRefundAmount::of).sum();
        if (refunded > newAmountCents) {
            throw exception(REFUND_EXCEED_BALANCE); // 已退款超过新金额，修订拒绝
        }
        int base = baseRevisionVersion;
        receiptMapper.updateById(new FinReceiptDO().setId(receiptId)
                .setAmountCents(newAmountCents).setRevisionVersion(base + 1));
        revisionMapper.insert(new FinReceiptRevisionDO()
                .setReceiptId(receiptId).setBaseRevisionVersion(base)
                .setOriginalAmountCents(receipt.getAmountCents()).setNewAmountCents(newAmountCents)
                .setNote(note).setOperatorUserId(post.userId()));
        log.info("[revision][receipt={} {}->{} by={}]", receiptId, receipt.getAmountCents(), newAmountCents, post.userId());
        return baseRevisionVersion + 1;
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
        var replayed = commands.replayed(post, idemKey,
                Map.of("sourceRef", cmd.sourceRef(), "amountCents", cmd.amountCents(), "name", cmd.name(),
                        "method", cmd.method() == null ? "bank" : cmd.method(),
                        "transactionNo", cmd.transactionNo() == null ? "" : cmd.transactionNo(),
                        "customerId", cmd.customerId()));
        if (replayed.isPresent()) {
            return new BusinessReceiptPort.BusinessReceiptSaved(replayed.get().getAggregateId(), true);
        }
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
        FinReceiptDO receipt = new FinReceiptDO()
                .setCompanyId(post.orgId()).setCustomerId(cmd.customerId())
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
