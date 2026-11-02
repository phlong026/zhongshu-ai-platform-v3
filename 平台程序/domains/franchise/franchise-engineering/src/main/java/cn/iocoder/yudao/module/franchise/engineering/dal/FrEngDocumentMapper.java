package cn.iocoder.yudao.module.franchise.engineering.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FrEngDocumentMapper extends BaseMapperX<FrEngDocumentDO> {

    default java.util.List<FrEngDocumentDO> selectByProject(long companyId, long projectId) {
        return selectList(new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<FrEngDocumentDO>()
                .eq(FrEngDocumentDO::getCompanyId, companyId)
                .eq(FrEngDocumentDO::getProjectId, projectId));
    }
}
