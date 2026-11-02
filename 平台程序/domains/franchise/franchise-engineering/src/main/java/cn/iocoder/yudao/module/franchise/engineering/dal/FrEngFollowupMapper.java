package cn.iocoder.yudao.module.franchise.engineering.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FrEngFollowupMapper extends BaseMapperX<FrEngFollowupDO> {

    default java.util.List<FrEngFollowupDO> selectByProject(long companyId, long projectId) {
        return selectList(new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<FrEngFollowupDO>()
                .eq(FrEngFollowupDO::getCompanyId, companyId)
                .eq(FrEngFollowupDO::getProjectId, projectId)
                .orderByDesc(FrEngFollowupDO::getCreateTime));
    }
}
