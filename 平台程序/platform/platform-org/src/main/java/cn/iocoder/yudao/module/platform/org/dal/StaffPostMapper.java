package cn.iocoder.yudao.module.platform.org.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface StaffPostMapper extends BaseMapperX<StaffPostDO> {

    default List<StaffPostDO> selectActiveByUser(Long userId) {
        return selectList(new LambdaQueryWrapperX<StaffPostDO>()
                .eq(StaffPostDO::getUserId, userId)
                .eq(StaffPostDO::getStatus, StaffPostDO.STATUS_ACTIVE)
                .orderByAsc(StaffPostDO::getCreateTime));
    }

    default List<StaffPostDO> selectActiveByOrg(Long orgId) {
        return selectList(new LambdaQueryWrapperX<StaffPostDO>()
                .eq(StaffPostDO::getOrgId, orgId)
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

    /** 用户任职过的全部组织（含已撤销），用于审计记录分发到各组织 */
    default List<Long> selectOrgIdsEverHeld(Long userId) {
        return selectList(new LambdaQueryWrapperX<StaffPostDO>()
                .select(StaffPostDO::getOrgId)
                .eq(StaffPostDO::getUserId, userId))
                .stream().map(StaffPostDO::getOrgId).distinct().sorted().toList();
    }

    default boolean existsActive(Long userId, Long orgId, String roleCode) {
        return selectCount(new LambdaQueryWrapperX<StaffPostDO>()
                .eq(StaffPostDO::getUserId, userId)
                .eq(StaffPostDO::getOrgId, orgId)
                .eq(StaffPostDO::getRoleCode, roleCode)
                .eq(StaffPostDO::getStatus, StaffPostDO.STATUS_ACTIVE)) > 0;
    }

    // ===== 行锁（09 钱包会话桥接撤权协议）：发卡 FOR SHARE 复核 / 撤权 FOR UPDATE 改撤，锁序=用户→组织→岗位 =====

    /** 用户级共享锁：锁该用户全部岗位行（发卡/岗位新增复核用，与成员停用的排他锁互斥） */
    @Select("SELECT id FROM pf_staff_post WHERE user_id = #{userId} FOR SHARE")
    List<Long> lockUserPostsForShare(@Param("userId") Long userId);

    /** 用户级排他锁：成员停用改状态前取锁（覆盖其全部组织，堵跨组织「新增岗位×发卡」窗口） */
    @Select("SELECT id FROM pf_staff_post WHERE user_id = #{userId} FOR UPDATE")
    List<Long> lockUserPostsForUpdate(@Param("userId") Long userId);

    /** 岗位级排他锁：岗位撤销改状态前取锁 */
    @Select("SELECT id FROM pf_staff_post WHERE id = #{postId} FOR UPDATE")
    Long lockPostForUpdate(@Param("postId") Long postId);

}
