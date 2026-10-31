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

    default List<FinRefundDO> selectByCompany(Long companyId) {
        return selectList(new LambdaQueryWrapperX<FinRefundDO>()
                .eq(FinRefundDO::getCompanyId, companyId)
                .orderByDesc(FinRefundDO::getCreateTime).orderByDesc(FinRefundDO::getId));
    }
}