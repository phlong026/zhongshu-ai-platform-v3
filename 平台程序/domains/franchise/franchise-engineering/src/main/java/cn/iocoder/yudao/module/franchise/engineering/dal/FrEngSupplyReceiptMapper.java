package cn.iocoder.yudao.module.franchise.engineering.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FrEngSupplyReceiptMapper extends BaseMapperX<FrEngSupplyReceiptDO> {

    default java.util.List<FrEngSupplyReceiptDO> selectByProject(long companyId, long projectId) {
        return selectList(new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<FrEngSupplyReceiptDO>()
                .eq(FrEngSupplyReceiptDO::getCompanyId, companyId)
                .eq(FrEngSupplyReceiptDO::getProjectId, projectId)
                .orderByAsc(FrEngSupplyReceiptDO::getOccurredAt));
    }

    default java.util.List<FrEngSupplyReceiptDO> selectBySupply(long companyId, long supplyId) {
        return selectList(new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<FrEngSupplyReceiptDO>()
                .eq(FrEngSupplyReceiptDO::getCompanyId, companyId)
                .eq(FrEngSupplyReceiptDO::getSupplyId, supplyId)
                .orderByAsc(FrEngSupplyReceiptDO::getOccurredAt));
    }
}
