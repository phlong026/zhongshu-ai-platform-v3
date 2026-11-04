package cn.iocoder.yudao.module.franchise.sales.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface SaleStageReceiptMapper extends BaseMapperX<SaleStageReceiptDO> {

    /** 某期的全部分笔（按落账时间升序；累计=Σamount_cents） */
    default List<SaleStageReceiptDO> selectByStage(Long stageId) {
        return selectList(new LambdaQueryWrapperX<SaleStageReceiptDO>()
                .eq(SaleStageReceiptDO::getStageId, stageId)
                .orderByAsc(SaleStageReceiptDO::getCreateTime));
    }

    /** 合同全部分笔（统一实收视图） */
    default List<SaleStageReceiptDO> selectByContract(Long contractId) {
        return selectList(new LambdaQueryWrapperX<SaleStageReceiptDO>()
                .eq(SaleStageReceiptDO::getContractId, contractId)
                .orderByAsc(SaleStageReceiptDO::getCreateTime));
    }
}
