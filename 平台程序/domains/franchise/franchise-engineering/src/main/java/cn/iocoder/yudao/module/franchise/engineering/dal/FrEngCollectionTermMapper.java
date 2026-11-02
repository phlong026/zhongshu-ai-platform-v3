package cn.iocoder.yudao.module.franchise.engineering.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FrEngCollectionTermMapper extends BaseMapperX<FrEngCollectionTermDO> {

    default java.util.List<FrEngCollectionTermDO> selectByProject(long companyId, long projectId) {
        return selectList(new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<FrEngCollectionTermDO>()
                .eq(FrEngCollectionTermDO::getCompanyId, companyId)
                .eq(FrEngCollectionTermDO::getProjectId, projectId));
    }
}
