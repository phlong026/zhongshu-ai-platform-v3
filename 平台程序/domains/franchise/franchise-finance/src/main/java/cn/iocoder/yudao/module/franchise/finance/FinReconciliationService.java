package cn.iocoder.yudao.module.franchise.finance;

import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.franchise.finance.dal.FinAbnormalFundDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinAbnormalFundMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinReceiptDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinReceiptMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinRefundDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinRefundMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.finance.enums.FranchiseFinErrorCodes.RECONCILIATION_MONTH_INVALID;

/**
 * M08-T08（批次#34）对账视图：毛额/退款/净额/异常四口径——供财务月度对账与 M09-T03 净额门槛、
 * M10-T05 提成分状态引用。口径与有效账一致（FinProjectAccountService 同源语义）：
 * 毛额=Σ已登记收款（月内）；退款=Σ client 方向已核实且未被更正替代（月内）；净额=毛额−退款；
 * 异常=已核待关联资金全量（独立留痕不落月，R05）+ 其中日期待核子集单列（待归月，M08-T05 契约）。
 * 支出退回（expense 回流）属支出侧对账，不进收入四口径。
 */
@Service
public class FinReconciliationService {

    @Resource
    private FinReceiptMapper receiptMapper;
    @Resource
    private FinRefundMapper refundMapper;
    @Resource
    private FinAbnormalFundMapper abnormalFundMapper;

    public record MonthReconciliation(String month, long grossReceiptsCents, long clientRefundsCents,
                                      long netReceivedCents, long abnormalPendingCents, int abnormalPendingCount,
                                      long abnormalDatePendingCents, int abnormalDatePendingCount) {
    }

    public MonthReconciliation monthReconciliation(long companyId, String month) {
        if (month == null || !month.matches("\\d{4}-(0[1-9]|1[0-2])")) {
            throw exception(RECONCILIATION_MONTH_INVALID);
        }
        LocalDate start = LocalDate.parse(month + "-01");
        LocalDate end = start.plusMonths(1);

        long gross = receiptMapper.selectList(new LambdaQueryWrapperX<FinReceiptDO>()
                        .eq(FinReceiptDO::getCompanyId, companyId)
                        .eq(FinReceiptDO::getStatus, FinReceiptService.STATUS_REGISTERED)
                        .ge(FinReceiptDO::getReceivedDate, start).lt(FinReceiptDO::getReceivedDate, end))
                .stream().mapToLong(FinReconciliationService::cents).sum();

        // 退款只计 client 方向、未被更正替代的已核实记录（更正链旧行已随作废出口径）
        long refunded = refundMapper.selectList(new LambdaQueryWrapperX<FinRefundDO>()
                        .eq(FinRefundDO::getCompanyId, companyId)
                        .eq(FinRefundDO::getStatus, "已核实")
                        .eq(FinRefundDO::getRefundDirection, "client")
                        .isNull(FinRefundDO::getReplacedById)
                        .ge(FinRefundDO::getDate, start).lt(FinRefundDO::getDate, end))
                .stream().mapToLong(FinReconciliationService::cents).sum();

        // 异常资金独立留痕不落月：全量已核待关联 + 日期待核（business_date 空）子集单列
        List<FinAbnormalFundDO> pending = abnormalFundMapper.selectByCompany(
                companyId, FinAbnormalFundDO.STATUS_PENDING_LINK, false);
        long pendingCents = pending.stream().mapToLong(FinReconciliationService::cents).sum();
        long datePendingCents = pending.stream()
                .filter(f -> f.getBusinessDate() == null)
                .mapToLong(FinReconciliationService::cents).sum();

        return new MonthReconciliation(month, gross, refunded, gross - refunded,
                pendingCents, pending.size(),
                datePendingCents, (int) pending.stream().filter(f -> f.getBusinessDate() == null).count());
    }

    private static long cents(FinReceiptDO row) {
        return row.getAmountCents() == null ? 0 : row.getAmountCents();
    }

    private static long cents(FinRefundDO row) {
        return row.getAmountCents() == null ? 0 : row.getAmountCents();
    }

    private static long cents(FinAbnormalFundDO row) {
        return row.getAmountCents() == null ? 0 : row.getAmountCents();
    }
}
