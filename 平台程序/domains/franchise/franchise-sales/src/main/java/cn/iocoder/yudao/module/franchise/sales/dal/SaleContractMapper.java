package cn.iocoder.yudao.module.franchise.sales.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface SaleContractMapper extends BaseMapperX<SaleContractDO> {

    default SaleContractDO selectActiveByCustomer(Long customerId) {
        List<SaleContractDO> rows = selectList(new LambdaQueryWrapperX<SaleContractDO>()
                .eq(SaleContractDO::getCustomerId, customerId)
                .ne(SaleContractDO::getStatus, SaleContractDO.STATUS_VOIDED)
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    default SaleContractDO lockById(Long id) {
        return selectOne(new LambdaQueryWrapperX<SaleContractDO>()
                .eq(SaleContractDO::getId, id).last("FOR UPDATE"));
    }

    default List<SaleContractDO> selectByCustomer(Long customerId) {
        return selectList(new LambdaQueryWrapperX<SaleContractDO>()
                .eq(SaleContractDO::getCustomerId, customerId)
                .orderByDesc(SaleContractDO::getId));
    }

}
