package cn.iocoder.yudao.module.franchise.performance.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface FrPerfCommissionReleaseMapper extends BaseMapperX<FrPerfCommissionReleaseDO> {

    default FrPerfCommissionReleaseDO lockByPayable(long companyId, long payableId) {
        return selectOne(new LambdaQueryWrapperX<FrPerfCommissionReleaseDO>()
                .eq(FrPerfCommissionReleaseDO::getCompanyId, companyId)
                .eq(FrPerfCommissionReleaseDO::getPayableId, payableId).last("FOR UPDATE"));
    }

    default FrPerfCommissionReleaseDO selectByProjectAndStage(long companyId, long projectId, String stage) {
        List<FrPerfCommissionReleaseDO> rows = selectList(new LambdaQueryWrapperX<FrPerfCommissionReleaseDO>()
                .eq(FrPerfCommissionReleaseDO::getCompanyId, companyId)
                .eq(FrPerfCommissionReleaseDO::getProjectId, projectId)
                .eq(FrPerfCommissionReleaseDO::getStage, stage).last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    default List<FrPerfCommissionReleaseDO> selectByPolicy(long companyId, long policyId) {
        return selectList(new LambdaQueryWrapperX<FrPerfCommissionReleaseDO>()
                .eq(FrPerfCommissionReleaseDO::getCompanyId, companyId)
                .eq(FrPerfCommissionReleaseDO::getPolicyId, policyId));
    }
}
