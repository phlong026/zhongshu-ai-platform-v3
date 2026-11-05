package cn.iocoder.yudao.module.franchise.finance;

import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.franchise.finance.dal.FinAbnormalFundDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinAbnormalFundMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinPaymentDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinPaymentMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinReceiptDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinReceiptMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinRefundDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinRefundMapper;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.finance.enums.FranchiseFinErrorCodes.*;

/**
 * 异常资金登记（M08-T08/R05）：原单缺失、超原单余额、凭据或日期待核的线下真实资金事实。
 * 独立留痕不凑平：本载体不进任何有效账口径（有效实收/已付/净额只读三张正常单据表），
 * 也不放宽正常退款/付款接口上限；日期待核（business_date 空）读侧单列「待归月」（M08-T05 契约承接）。
 * link 转正仅做关联标注（目标同组织、未撤销），不改任何有效账数字——不虚构关联。
 */
@Service
@Slf4j
public class FinAbnormalFundService {

    /** R05：资金方向三分类（收进/退给客户/供应商退回） */
    public static final Set<String> DIRECTIONS = Set.of("客户收款", "客户退款", "支出退回");
    /** 转正关联目标类型 */
    public static final Set<String> LINK_TYPES = Set.of("receipt", "refund", "payment");
    /** R05-B4/R2 P1-4：同一资金事实转正——方向与目标单据类型强映射（客户收款↔收款单/客户退款↔客户退款/支出退回↔付款单） */
    private static final java.util.Map<String, String> DIRECTION_LINK_TYPE =
            java.util.Map.of("客户收款", "receipt", "客户退款", "refund", "支出退回", "payment");

    @Resource
    private FinAbnormalFundMapper fundMapper;
    @Resource
    private FinReceiptMapper receiptMapper;
    @Resource
    private FinRefundMapper refundMapper;
    @Resource
    private FinPaymentMapper paymentMapper;
    @Resource
    private FranchiseCommands commands;

    public record RegisterCommand(String fundDirection, long amountCents, String counterparty,
                                  Long relatedProjectId, Long relatedCommissionId, String stageOrCategory,
                                  LocalDate businessDate, String method, String transactionNo,
                                  String reason, Long voucherFileId) {
    }

    @Transactional
    public long register(PostSnapshot post, String idempotencyKey, RegisterCommand cmd) {
        var replayed = commands.replayed(post, idempotencyKey, cmd);
        if (replayed.isPresent()) {
            return replayed.get().getAggregateId();
        }
        if (cmd.fundDirection() == null || !DIRECTIONS.contains(cmd.fundDirection())) {
            throw exception(ABNORMAL_DIRECTION_INVALID);
        }
        if (cmd.businessDate() != null) {
            BusinessDateGuard.requireValid(cmd.businessDate()); // 填了须合法；不填=日期待核→待归月
        }
        FinAbnormalFundDO fund = new FinAbnormalFundDO()
                .setCompanyId(post.orgId()).setFundDirection(cmd.fundDirection())
                .setAmountCents(cmd.amountCents())
                .setCounterparty(blankToNull(cmd.counterparty()))
                .setRelatedProjectId(cmd.relatedProjectId())
                .setRelatedCommissionId(cmd.relatedCommissionId())
                .setStageOrCategory(blankToNull(cmd.stageOrCategory()))
                .setBusinessDate(cmd.businessDate())
                .setMethod(blankToNull(cmd.method()))
                .setTransactionNo(blankToNull(cmd.transactionNo()))
                .setReason(blankToNull(cmd.reason()))
                .setVoucherFileId(cmd.voucherFileId())
                .setStatus(FinAbnormalFundDO.STATUS_PENDING_LINK)
                .setOperatorUserId(post.userId());
        fundMapper.insert(fund);
        // transaction_no 有意不进 fr_fin_txn_no 防重表——异常登记是原单缺失时的占位留痕，
        // 占用流水号会挡住后续真实原单登记（R05：关联异常资金不虚构关联）；表内部分唯一索引防同号重复登记。
        commands.record(post, "fin-abnormal-fund", fund.getId(), 1, "fin-abnormal-fund-register", cmd, idempotencyKey);
        log.info("[register][companyId={} fund={} direction={} amount={} datePending={}]",
                post.orgId(), fund.getId(), cmd.fundDirection(), cmd.amountCents(), cmd.businessDate() == null);
        return fund.getId();
    }

    /** 转正关联：异常资金被后续正常单据解释（CAS 防并发双关联）。
     * R2 P1-4：目标类型须与资金方向同身份（客户收款→receipt/客户退款→client 退款/支出退回→payment），
     * 且须构成同一资金事实——金额相等、已知流水号一致、已知项目归属一致；目标单据不得被其他异常资金占用。
     * 不支持部分关联（拆分解释留待 #34 对账视图）；无关单据不得关闭待关联状态（不虚构关联）。 */
    @Transactional
    public void link(PostSnapshot post, long fundId, String linkedType, long linkedId) {
        FinAbnormalFundDO fund = fundMapper.selectById(fundId);
        if (fund == null) {
            throw exception(ABNORMAL_FUND_NOT_FOUND);
        }
        if (fund.getCompanyId() != post.orgId()) {
            throw exception(ORG_MISMATCH);
        }
        if (linkedType == null || !LINK_TYPES.contains(linkedType)
                || !linkedType.equals(DIRECTION_LINK_TYPE.get(fund.getFundDirection()))) {
            throw exception(ABNORMAL_LINK_TARGET_INVALID); // 方向与目标类型不同一资金身份
        }
        requireLinkableTarget(post, linkedType, linkedId, fund);
        requireNotOccupied(post, linkedType, linkedId, fundId);
        int claimed = fundMapper.update(new FinAbnormalFundDO().setStatus(FinAbnormalFundDO.STATUS_LINKED)
                        .setLinkedType(linkedType).setLinkedId(linkedId),
                new LambdaQueryWrapperX<FinAbnormalFundDO>()
                        .eq(FinAbnormalFundDO::getId, fundId)
                        .eq(FinAbnormalFundDO::getStatus, FinAbnormalFundDO.STATUS_PENDING_LINK));
        if (claimed != 1) {
            throw exception(ABNORMAL_FUND_ALREADY_LINKED); // 并发关联败者整笔回滚
        }
        log.info("[link][companyId={} fund={} -> {}:{} by={}]", post.orgId(), fundId, linkedType, linkedId, post.userId());
    }

    /** R3：同一目标单据至多解释一笔异常资金（占用检查，防两笔异常凑到同一张单上） */
    private void requireNotOccupied(PostSnapshot post, String linkedType, long linkedId, long selfFundId) {
        Long occupied = fundMapper.selectCount(new LambdaQueryWrapperX<FinAbnormalFundDO>()
                .eq(FinAbnormalFundDO::getCompanyId, post.orgId())
                .eq(FinAbnormalFundDO::getStatus, FinAbnormalFundDO.STATUS_LINKED)
                .eq(FinAbnormalFundDO::getLinkedType, linkedType)
                .eq(FinAbnormalFundDO::getLinkedId, linkedId)
                .ne(FinAbnormalFundDO::getId, selfFundId));
        if (occupied != null && occupied > 0) {
            throw exception(ABNORMAL_LINK_TARGET_INVALID);
        }
    }

    private void requireLinkableTarget(PostSnapshot post, String linkedType, long linkedId, FinAbnormalFundDO fund) {
        switch (linkedType) {
            case "receipt" -> {
                FinReceiptDO row = receiptMapper.selectById(linkedId);
                if (row == null || row.getCompanyId() != post.orgId()
                        || !FinReceiptService.STATUS_REGISTERED.equals(row.getStatus())) {
                    throw exception(ABNORMAL_LINK_TARGET_INVALID); // 待登记/已撤销不能关闭异常（已登记=已核实事实）
                }
                requireSameFundFact(fund, row.getAmountCents(), row.getTransactionNo(), row.getProjectId());
            }
            case "refund" -> {
                FinRefundDO row = refundMapper.selectById(linkedId);
                if (row == null || row.getCompanyId() != post.orgId() || "已作废".equals(row.getStatus())
                        || !"client".equals(row.getRefundDirection())) {
                    throw exception(ABNORMAL_LINK_TARGET_INVALID); // 客户退款异常只能被 client 退款解释
                }
                // R3 P1：客户退款本身不带项目——经原收款（同组织）取真实项目归属参与同一性比对
                Long projectId = null;
                if (row.getReceiptId() != null) {
                    FinReceiptDO origin = receiptMapper.selectById(row.getReceiptId());
                    if (origin != null && origin.getCompanyId() == post.orgId()) {
                        projectId = origin.getProjectId();
                    }
                }
                requireSameFundFact(fund, row.getAmountCents(), row.getTransactionNo(), projectId);
            }
            case "payment" -> {
                FinPaymentDO row = paymentMapper.selectById(linkedId);
                if (row == null || row.getCompanyId() != post.orgId() || !"已核实".equals(row.getStatus())) {
                    throw exception(ABNORMAL_LINK_TARGET_INVALID);
                }
                requireSameFundFact(fund, row.getAmountCents(), row.getTransactionNo(), row.getProjectId());
            }
            default -> throw exception(ABNORMAL_LINK_TARGET_INVALID);
        }
    }

    /** R2 P1-4：同一资金事实核对——金额相等（不支持部分关联）；异常登记已填的流水号/项目归属须与目标一致 */
    private void requireSameFundFact(FinAbnormalFundDO fund, Long targetAmountCents, String targetTxnNo, Long targetProjectId) {
        if (targetAmountCents == null || fund.getAmountCents() == null
                || !fund.getAmountCents().equals(targetAmountCents)) {
            throw exception(ABNORMAL_LINK_TARGET_INVALID); // 金额不一致=不同一资金事实
        }
        if (fund.getTransactionNo() != null && !fund.getTransactionNo().equals(targetTxnNo)) {
            throw exception(ABNORMAL_LINK_TARGET_INVALID); // 已知流水号不一致
        }
        if (fund.getRelatedProjectId() != null && !fund.getRelatedProjectId().equals(targetProjectId)) {
            throw exception(ABNORMAL_LINK_TARGET_INVALID); // 已知项目归属不一致
        }
    }

    /** 作废（误录撤销留痕；重复作废幂等无二次效果；并发作废经状态条件 CAS——败者幂等返回不覆盖首次留痕） */
    @Transactional
    public void void_(PostSnapshot post, long fundId, String reason) {
        FinAbnormalFundDO fund = fundMapper.selectById(fundId);
        if (fund == null) {
            throw exception(ABNORMAL_FUND_NOT_FOUND);
        }
        if (fund.getCompanyId() != post.orgId()) {
            throw exception(ORG_MISMATCH);
        }
        if (FinAbnormalFundDO.STATUS_VOIDED.equals(fund.getStatus())) {
            return; // 幂等
        }
        int claimed = fundMapper.update(new FinAbnormalFundDO().setStatus(FinAbnormalFundDO.STATUS_VOIDED)
                        .setVoidByUserId(post.userId()).setVoidReason(reason).setVoidedAt(OffsetDateTime.now()),
                new LambdaQueryWrapperX<FinAbnormalFundDO>()
                        .eq(FinAbnormalFundDO::getId, fundId)
                        .ne(FinAbnormalFundDO::getStatus, FinAbnormalFundDO.STATUS_VOIDED));
        if (claimed != 1) {
            log.info("[void][companyId={} fund={}] 并发作废败者幂等返回", post.orgId(), fundId);
        } else {
            log.info("[void][companyId={} fund={} by={} reason={}]", post.orgId(), fundId, post.userId(), reason);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
