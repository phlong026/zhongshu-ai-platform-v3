package cn.iocoder.yudao.module.franchise.sales.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface SaleRequirementMapper extends BaseMapperX<SaleRequirementDO> {

    default SaleRequirementDO selectLatest(Long customerId) {
        List<SaleRequirementDO> rows = selectList(new LambdaQueryWrapperX<SaleRequirementDO>()
                .eq(SaleRequirementDO::getCustomerId, customerId)
                .orderByDesc(SaleRequirementDO::getVersion)
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    default List<SaleRequirementDO> selectByCustomer(Long customerId) {
        return selectList(new LambdaQueryWrapperX<SaleRequirementDO>()
                .eq(SaleRequirementDO::getCustomerId, customerId)
                .orderByDesc(SaleRequirementDO::getVersion));
    }

}
