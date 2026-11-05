package cn.iocoder.yudao.module.franchise.finance.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

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
}