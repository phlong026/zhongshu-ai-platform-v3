package cn.iocoder.yudao.module.franchise.engineering.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FrEngCollectionMapper extends BaseMapperX<FrEngCollectionDO> {

    default java.util.List<FrEngCollectionDO> selectByProject(long companyId, long projectId) {
        return selectList(new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<FrEngCollectionDO>()
                .eq(FrEngCollectionDO::getCompanyId, companyId)
                .eq(FrEngCollectionDO::getProjectId, projectId)
                .orderByAsc(FrEngCollectionDO::getOccurredAt));
    }
}
