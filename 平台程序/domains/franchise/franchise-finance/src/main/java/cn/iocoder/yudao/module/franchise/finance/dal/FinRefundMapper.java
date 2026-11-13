package cn.iocoder.yudao.module.franchise.finance.dal;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface FinRefundMapper extends BaseMapperX<FinRefundDO> {
    default List<FinRefundDO> selectActiveByReceipt(Long receiptId) {
        return selectList(new LambdaQueryWrapperX<FinRefundDO>()
                .eq(FinRefundDO::getReceiptId, receiptId).eq(FinRefundDO::getStatus, "已核实"));
    }

    /** M08-T08：支出退回余额口径——该付款单下已核实且未被更正替代的退回（行锁内调用） */
    default List<FinRefundDO> selectActiveByPayment(Long paymentId) {
        return selectList(new LambdaQueryWrapperX<FinRefundDO>()
                .eq(FinRefundDO::getPaymentId, paymentId).eq(FinRefundDO::getStatus, "已核实"));
    }

    default List<FinRefundDO> selectByCompany(Long companyId) {
        return selectList(new LambdaQueryWrapperX<FinRefundDO>()
                .eq(FinRefundDO::getCompanyId, companyId)
                .orderByDesc(FinRefundDO::getCreateTime).orderByDesc(FinRefundDO::getId));
    }

    /** Web 财务核对页只读分页：期间按退款日期 date；receiptId 保留旧端点「按原收款单过滤」语义。 */
    default PageResult<FinRefundDO> selectPage(PageParam page, Long companyId, String status,
                                               LocalDate dateFrom, LocalDate dateTo, Long receiptId,
                                               String refundDirection, String keyword) {
        LambdaQueryWrapperX<FinRefundDO> query = new LambdaQueryWrapperX<FinRefundDO>()
                .eq(FinRefundDO::getCompanyId, companyId)
                .eqIfPresent(FinRefundDO::getStatus, status)
                .geIfPresent(FinRefundDO::getDate, dateFrom)
                .leIfPresent(FinRefundDO::getDate, dateTo)
                .eqIfPresent(FinRefundDO::getReceiptId, receiptId)
                .eqIfPresent(FinRefundDO::getRefundDirection, refundDirection);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            query.and(q -> q.like(FinRefundDO::getReason, kw).or().like(FinRefundDO::getTransactionNo, kw));
        }
        return selectPage(page, query.orderByDesc(FinRefundDO::getCreateTime).orderByDesc(FinRefundDO::getId));
    }
}