package cn.iocoder.yudao.module.franchise.finance;

import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.franchise.finance.dal.FinPaymentDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinPaymentMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinReceiptDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinReceiptMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinRefundDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinRefundMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 项目账与设计费净额：实时聚合（不物化进度）；有效实收仅统计 status=已登记；退款只计已核实。
 * DesignFee 净额单侧口径（B1）：仅统计 source_type=design 的总部侧记录，按 stage 分列。
 */
@Service
public class FinProjectAccountService {

    @Resource
    private FinReceiptMapper receiptMapper;
    @Resource
    private FinRefundMapper refundMapper;
    @Resource
    private FinPaymentMapper paymentMapper;

    public record ProjectAccount(long projectId, long receivableRegistered, long receivedConfirmed,
                                 long refundedNet, long paidConfirmed) {
    }

    public ProjectAccount projectAccount(long companyId, long projectId) {
        List<FinReceiptDO> receipts = receiptMapper.selectList(new LambdaQueryWrapperX<FinReceiptDO>()
                .eq(FinReceiptDO::getCompanyId, companyId).eq(FinReceiptDO::getProjectId, projectId));
        long receivable = receipts.stream().filter(r -> "待登记".equals(r.getStatus()))
                .mapToLong(FinProjectAccountService::cents).sum();
        long received = receipts.stream().filter(r -> "已登记".equals(r.getStatus()))
                .mapToLong(FinProjectAccountService::cents).sum();
        List<Long> receiptIds = receipts.stream().filter(r -> "已登记".equals(r.getStatus()))
                .map(FinReceiptDO::getId).toList();
        long refunded = receiptIds.isEmpty() ? 0 : refundMapper.selectList(new LambdaQueryWrapperX<FinRefundDO>()
                        .in(FinRefundDO::getReceiptId, receiptIds).eq(FinRefundDO::getStatus, "已核实"))
                .stream().mapToLong(FinProjectAccountService::refundCents).sum();
        List<FinPaymentDO> projectPayments = paymentMapper.selectList(new LambdaQueryWrapperX<FinPaymentDO>()
                .eq(FinPaymentDO::getCompanyId, companyId).eq(FinPaymentDO::getProjectId, projectId)
                .eq(FinPaymentDO::getStatus, "已核实"));
        long paid = projectPayments.stream().filter(p -> !"EMPLOYEE".equals(p.getFundingType())).mapToLong(FinPaymentService::amount).sum();
        // M08-T08/R2 P1：支出退回冲减已付现金——该项目付款单的已核实退回（expense 行 payment_id 非空、
        // receipt_id 必空，828 CHECK 保证按 payment_id 聚合天然只含支出退回）
        List<Long> paymentIds = projectPayments.stream().filter(p -> !"EMPLOYEE".equals(p.getFundingType())).map(FinPaymentDO::getId).toList();
        long expenseRefunded = paymentIds.isEmpty() ? 0 : refundMapper.selectList(new LambdaQueryWrapperX<FinRefundDO>()
                        .in(FinRefundDO::getPaymentId, paymentIds).eq(FinRefundDO::getStatus, "已核实"))
                .stream().mapToLong(FinProjectAccountService::refundCents).sum();
        paid -= expenseRefunded;
        return new ProjectAccount(projectId, receivable, received, refunded, paid);
    }

    public record DesignFeeProgress(long commissionId, int submissionVersion, long firstReceived,
                                    long firstRefunded, long tailReceived, long tailRefunded) {
    }

    /** 设计费净额（总部侧，source_ref=commissionId:version:stage 实时聚合） */
    public DesignFeeProgress designFeeProgress(long designOrgId, long commissionId) {
        List<FinReceiptDO> rows = receiptMapper.selectList(new LambdaQueryWrapperX<FinReceiptDO>()
                .eq(FinReceiptDO::getCompanyId, designOrgId)
                .eq(FinReceiptDO::getSourceType, "design")
                .likeRight(FinReceiptDO::getSourceRef, commissionId + ":"));
        long firstReceived = 0;
        long firstRefunded = 0;
        long tailReceived = 0;
        long tailRefunded = 0;
        for (FinReceiptDO row : rows) {
            if (!"已登记".equals(row.getStatus())) {
                continue;
            }
            boolean first = String.valueOf(row.getSourceRef()).endsWith(":first");
            if (first) {
                firstReceived += cents(row);
                firstRefunded += refundedOf(row);
            } else {
                tailReceived += cents(row);
                tailRefunded += refundedOf(row);
            }
        }
        return new DesignFeeProgress(commissionId, currentVersion(rows), firstReceived, firstRefunded, tailReceived, tailRefunded);
    }

    private long refundedOf(FinReceiptDO row) {
        return refundMapper.selectActiveByReceipt(row.getId()).stream()
                .mapToLong(FinProjectAccountService::refundCents).sum();
    }

    private static int currentVersion(List<FinReceiptDO> rows) {
        return rows.stream().mapToInt(FinProjectAccountService::versionOf).max().orElse(0);
    }

    private static int versionOf(FinReceiptDO row) {
        String ref = String.valueOf(row.getSourceRef());
        String[] parts = ref.split(":");
        try {
            return Integer.parseInt(parts[1]);
        } catch (Exception e) {
            return 0;
        }
    }

    private static long cents(FinReceiptDO row) {
        return row.getAmountCents() == null ? 0 : row.getAmountCents();
    }

    private static long refundCents(FinRefundDO row) {
        return row.getAmountCents() == null ? 0 : row.getAmountCents();
    }

}
