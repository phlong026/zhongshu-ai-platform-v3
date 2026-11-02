package cn.iocoder.yudao.module.franchise.engineering.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FrEngMaterialMapper extends BaseMapperX<FrEngMaterialDO> {

    default java.util.List<FrEngMaterialDO> selectByProject(long companyId, long projectId) {
        return selectList(new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<FrEngMaterialDO>()
                .eq(FrEngMaterialDO::getCompanyId, companyId)
                .eq(FrEngMaterialDO::getProjectId, projectId));
    }

    default java.util.List<FrEngMaterialDO> selectByPlan(long companyId, long materialPlanId) {
        return selectList(new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<FrEngMaterialDO>()
                .eq(FrEngMaterialDO::getCompanyId, companyId)
                .eq(FrEngMaterialDO::getMaterialPlanId, materialPlanId));
    }
}
