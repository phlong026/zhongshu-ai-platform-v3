package cn.iocoder.yudao.module.franchise.performance.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface FrPerfCommissionPolicyMapper extends BaseMapperX<FrPerfCommissionPolicyDO> {

    default FrPerfCommissionPolicyDO selectByProject(long companyId, long projectId) {
        List<FrPerfCommissionPolicyDO> rows = selectList(new LambdaQueryWrapperX<FrPerfCommissionPolicyDO>()
                .eq(FrPerfCommissionPolicyDO::getCompanyId, companyId)
                .eq(FrPerfCommissionPolicyDO::getProjectId, projectId).last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    default List<FrPerfCommissionPolicyDO> selectAll(long companyId) {
        return selectList(new LambdaQueryWrapperX<FrPerfCommissionPolicyDO>()
                .eq(FrPerfCommissionPolicyDO::getCompanyId, companyId)
                .orderByDesc(FrPerfCommissionPolicyDO::getUpdateTime));
    }
}
