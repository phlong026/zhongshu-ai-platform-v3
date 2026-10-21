package cn.iocoder.yudao.module.platform.org.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface StaffPostMapper extends BaseMapperX<StaffPostDO> {

    default List<StaffPostDO> selectActiveByUser(Long userId) {
        return selectList(new LambdaQueryWrapperX<StaffPostDO>()
                .eq(StaffPostDO::getUserId, userId)
                .eq(StaffPostDO::getStatus, StaffPostDO.STATUS_ACTIVE)
                .orderByAsc(StaffPostDO::getCreateTime));
    }

    default List<StaffPostDO> selectActiveByOrgAndRole(Long orgId, String roleCode) {
        return selectList(new LambdaQueryWrapperX<StaffPostDO>()
                .eq(StaffPostDO::getOrgId, orgId)
                .eq(StaffPostDO::getRoleCode, roleCode)
                .eq(StaffPostDO::getStatus, StaffPostDO.STATUS_ACTIVE)
                .orderByAsc(StaffPostDO::getCreateTime));
    }

    default boolean existsActive(Long userId, Long orgId, String roleCode) {
        return selectCount(new LambdaQueryWrapperX<StaffPostDO>()
                .eq(StaffPostDO::getUserId, userId)
                .eq(StaffPostDO::getOrgId, orgId)
                .eq(StaffPostDO::getRoleCode, roleCode)
                .eq(StaffPostDO::getStatus, StaffPostDO.STATUS_ACTIVE)) > 0;
    }

}
