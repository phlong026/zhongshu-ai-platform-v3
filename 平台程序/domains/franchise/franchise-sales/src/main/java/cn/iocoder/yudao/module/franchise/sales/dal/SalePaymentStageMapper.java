package cn.iocoder.yudao.module.franchise.sales.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface SalePaymentStageMapper extends BaseMapperX<SalePaymentStageDO> {

    default List<SalePaymentStageDO> selectByContract(Long contractId) {
        return selectList(new LambdaQueryWrapperX<SalePaymentStageDO>()
                .eq(SalePaymentStageDO::getContractId, contractId)
                .orderByAsc(SalePaymentStageDO::getStage));
    }

    default List<SalePaymentStageDO> selectPendingByOrg(Long companyId) {
        return selectList(new LambdaQueryWrapperX<SalePaymentStageDO>()
                .eq(SalePaymentStageDO::getCompanyId, companyId)
                .eq(SalePaymentStageDO::getStatus, SalePaymentStageDO.STATUS_PENDING)
                .eq(SalePaymentStageDO::getDeleted, false));
    }

    default SalePaymentStageDO lockById(Long id) {
        return selectOne(new LambdaQueryWrapperX<SalePaymentStageDO>()
                .eq(SalePaymentStageDO::getId, id).last("FOR UPDATE"));
    }

}
