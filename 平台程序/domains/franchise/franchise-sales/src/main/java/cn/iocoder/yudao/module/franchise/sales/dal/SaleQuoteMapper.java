package cn.iocoder.yudao.module.franchise.sales.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface SaleQuoteMapper extends BaseMapperX<SaleQuoteDO> {

    default List<SaleQuoteDO> selectByCustomer(Long customerId) {
        return selectList(new LambdaQueryWrapperX<SaleQuoteDO>()
                .eq(SaleQuoteDO::getCustomerId, customerId)
                .orderByDesc(SaleQuoteDO::getId));
    }

}
