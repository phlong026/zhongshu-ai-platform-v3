package cn.iocoder.yudao.module.franchise.engineering.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FrEngSiteRecordMapper extends BaseMapperX<FrEngSiteRecordDO> {

    default java.util.List<FrEngSiteRecordDO> selectByProject(long companyId, long projectId) {
        return selectList(new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<FrEngSiteRecordDO>()
                .eq(FrEngSiteRecordDO::getCompanyId, companyId)
                .eq(FrEngSiteRecordDO::getProjectId, projectId)
                .orderByAsc(FrEngSiteRecordDO::getOccurredAt));
    }
}
