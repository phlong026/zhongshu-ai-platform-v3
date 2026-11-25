package cn.iocoder.yudao.module.franchise.sales.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface SalePaymentStageMapper extends BaseMapperX<SalePaymentStageDO> {

    default List<SalePaymentStageDO> selectByContract(Long contractId) {
        // M06-T04：按业务期次排序（签约后→基础完成后→封顶后；存量两期值排最后）
        return selectList(new LambdaQueryWrapperX<SalePaymentStageDO>()
                .eq(SalePaymentStageDO::getContractId, contractId)
                .orderByAsc(SalePaymentStageDO::getSequence, SalePaymentStageDO::getId));
    }

    /** 欠款期（R1 P2）：PENDING+OPEN 纳入（分笔首笔后尚欠余额的期不得从待办消失），零额期排除 */
    default List<SalePaymentStageDO> selectOwingByOrg(Long companyId) {
        return selectList(new LambdaQueryWrapperX<SalePaymentStageDO>()
                .eq(SalePaymentStageDO::getCompanyId, companyId)
                .in(SalePaymentStageDO::getStatus, SalePaymentStageDO.STATUS_PENDING, SalePaymentStageDO.STATUS_OPEN)
                .gt(SalePaymentStageDO::getAmountCents, 0L)
                .eq(SalePaymentStageDO::getDeleted, false));
    }

    default SalePaymentStageDO lockById(Long id) {
        return selectOne(new LambdaQueryWrapperX<SalePaymentStageDO>()
                .eq(SalePaymentStageDO::getId, id).last("FOR UPDATE"));
    }

}
