package cn.iocoder.yudao.module.franchise.sales.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface SaleQuoteMapper extends BaseMapperX<SaleQuoteDO> {

    default List<SaleQuoteDO> selectWorkflowOptions(long companyId, long customerId) {
        return selectList(new LambdaQueryWrapperX<SaleQuoteDO>()
                .select(SaleQuoteDO::getId, SaleQuoteDO::getCustomerId, SaleQuoteDO::getCompanyId, SaleQuoteDO::getTotalCents,
                        SaleQuoteDO::getBudgetVersion, SaleQuoteDO::getPriceVersion, SaleQuoteDO::getCreateTime)
                .eq(SaleQuoteDO::getCompanyId, companyId).eq(SaleQuoteDO::getCustomerId, customerId)
                .gt(SaleQuoteDO::getTotalCents, 0).orderByDesc(SaleQuoteDO::getId));
    }

    default List<SaleQuoteDO> selectByCustomer(Long customerId) {
        return selectList(new LambdaQueryWrapperX<SaleQuoteDO>()
                .eq(SaleQuoteDO::getCustomerId, customerId)
                .orderByDesc(SaleQuoteDO::getId));
    }

}
