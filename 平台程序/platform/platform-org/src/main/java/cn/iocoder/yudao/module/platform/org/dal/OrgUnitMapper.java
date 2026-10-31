package cn.iocoder.yudao.module.platform.org.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface OrgUnitMapper extends BaseMapperX<OrgUnitDO> {

    default List<OrgUnitDO> selectActiveTeamsByOrg(long orgId) {
        return selectList(new LambdaQueryWrapperX<OrgUnitDO>()
                .eq(OrgUnitDO::getKind, OrgUnitDO.KIND_TEAM)
                .eq(OrgUnitDO::getOrgId, orgId)
                .eq(OrgUnitDO::getStatus, OrgUnitDO.STATUS_ACTIVE)
                .orderByAsc(OrgUnitDO::getDeptId));
    }

    default List<Long> selectActiveOrgIds(String orgType) {
        return selectList(new LambdaQueryWrapperX<OrgUnitDO>()
                .eq(OrgUnitDO::getKind, OrgUnitDO.KIND_ORG)
                .eq(OrgUnitDO::getOrgType, orgType)
                .eq(OrgUnitDO::getStatus, OrgUnitDO.STATUS_ACTIVE)
                .orderByAsc(OrgUnitDO::getDeptId))
                .stream().map(OrgUnitDO::getDeptId).toList();
    }

}
