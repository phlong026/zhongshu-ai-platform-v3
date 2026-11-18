package cn.iocoder.yudao.module.franchise.sales.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface SaleContractMapper extends BaseMapperX<SaleContractDO> {

    @org.apache.ibatis.annotations.Select(cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort.SIGNED_CONTRACT
            + "AND k.company_id=#{company} AND k.customer_id=#{customer}"
            + cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort.LATEST_CONTRACT)
    Long signedContractId(@org.apache.ibatis.annotations.Param("company") long company,
                          @org.apache.ibatis.annotations.Param("customer") long customer);

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
