package cn.iocoder.yudao.module.franchise.sales.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface SaleDeliveryMapper extends BaseMapperX<SaleDeliveryDO> {

    default SaleDeliveryDO selectByContract(Long contractId) {
        List<SaleDeliveryDO> rows = selectList(new LambdaQueryWrapperX<SaleDeliveryDO>()
                .eq(SaleDeliveryDO::getContractId, contractId)
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

}
