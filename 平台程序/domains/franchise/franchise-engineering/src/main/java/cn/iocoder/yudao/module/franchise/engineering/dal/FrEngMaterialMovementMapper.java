package cn.iocoder.yudao.module.franchise.engineering.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FrEngMaterialMovementMapper extends BaseMapperX<FrEngMaterialMovementDO> {

    default java.util.List<FrEngMaterialMovementDO> selectByProject(long companyId, long projectId) {
        return selectList(new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<FrEngMaterialMovementDO>()
                .eq(FrEngMaterialMovementDO::getCompanyId, companyId)
                .eq(FrEngMaterialMovementDO::getProjectId, projectId));
    }
}
