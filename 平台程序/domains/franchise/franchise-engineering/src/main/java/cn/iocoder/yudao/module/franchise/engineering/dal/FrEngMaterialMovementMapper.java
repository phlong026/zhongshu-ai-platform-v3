package cn.iocoder.yudao.module.franchise.engineering.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

@Mapper
public interface FrEngMaterialMovementMapper extends BaseMapperX<FrEngMaterialMovementDO> {

    @Select("""
            SELECT COALESCE(SUM(quantity), 0) FROM fr_eng_material_movement
            WHERE company_id = #{companyId} AND project_id = #{projectId} AND material_id = #{materialId}
            """)
    BigDecimal allocatedQuantity(@Param("companyId") long companyId, @Param("projectId") long projectId,
                                 @Param("materialId") long materialId);

    default java.util.List<FrEngMaterialMovementDO> selectByProject(long companyId, long projectId) {
        return selectList(new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<FrEngMaterialMovementDO>()
                .eq(FrEngMaterialMovementDO::getCompanyId, companyId)
                .eq(FrEngMaterialMovementDO::getProjectId, projectId));
    }
}
