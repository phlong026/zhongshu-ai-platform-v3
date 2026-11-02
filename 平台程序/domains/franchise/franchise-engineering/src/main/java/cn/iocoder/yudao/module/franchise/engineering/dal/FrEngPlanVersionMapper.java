package cn.iocoder.yudao.module.franchise.engineering.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FrEngPlanVersionMapper extends BaseMapperX<FrEngPlanVersionDO> {

    default java.util.List<FrEngPlanVersionDO> selectByProject(long companyId, long projectId) {
        return selectList(new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<FrEngPlanVersionDO>()
                .eq(FrEngPlanVersionDO::getCompanyId, companyId)
                .eq(FrEngPlanVersionDO::getProjectId, projectId));
    }
}
