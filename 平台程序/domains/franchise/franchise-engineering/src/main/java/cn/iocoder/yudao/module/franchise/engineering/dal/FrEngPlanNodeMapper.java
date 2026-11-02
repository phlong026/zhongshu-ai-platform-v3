package cn.iocoder.yudao.module.franchise.engineering.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FrEngPlanNodeMapper extends BaseMapperX<FrEngPlanNodeDO> {

    default java.util.List<FrEngPlanNodeDO> selectByProject(long companyId, long projectId) {
        return selectList(new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<FrEngPlanNodeDO>()
                .eq(FrEngPlanNodeDO::getCompanyId, companyId)
                .eq(FrEngPlanNodeDO::getProjectId, projectId)
                .orderByAsc(FrEngPlanNodeDO::getNodeIndex));
    }
}
