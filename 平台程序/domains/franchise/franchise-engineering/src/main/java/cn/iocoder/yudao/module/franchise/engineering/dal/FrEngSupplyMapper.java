package cn.iocoder.yudao.module.franchise.engineering.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FrEngSupplyMapper extends BaseMapperX<FrEngSupplyDO> {

    default java.util.List<FrEngSupplyDO> selectByProject(long companyId, long projectId) {
        return selectList(new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<FrEngSupplyDO>()
                .eq(FrEngSupplyDO::getCompanyId, companyId)
                .eq(FrEngSupplyDO::getProjectId, projectId));
    }

    default FrEngSupplyDO selectByMaterial(long companyId, long materialId) {
        java.util.List<FrEngSupplyDO> rows = selectList(
                new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<FrEngSupplyDO>()
                        .eq(FrEngSupplyDO::getCompanyId, companyId)
                        .eq(FrEngSupplyDO::getMaterialId, materialId)
                        .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }
}
