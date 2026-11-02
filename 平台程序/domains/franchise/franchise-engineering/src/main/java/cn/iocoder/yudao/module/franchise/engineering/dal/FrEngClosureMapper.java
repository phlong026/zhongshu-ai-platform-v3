package cn.iocoder.yudao.module.franchise.engineering.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FrEngClosureMapper extends BaseMapperX<FrEngClosureDO> {

    default FrEngClosureDO selectByProject(long companyId, long projectId) {
        java.util.List<FrEngClosureDO> rows = selectList(
                new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<FrEngClosureDO>()
                        .eq(FrEngClosureDO::getCompanyId, companyId)
                        .eq(FrEngClosureDO::getProjectId, projectId)
                        .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }
}
