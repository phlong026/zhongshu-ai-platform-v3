package cn.iocoder.yudao.module.platform.org.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface OrgUnitMapper extends BaseMapperX<OrgUnitDO> {

    // ===== 行锁（09 钱包会话桥接撤权协议，锁序=用户→组织→岗位）=====

    /** 组织级共享锁：发卡/岗位新增复核组织状态用（与组织停用的排他锁互斥） */
    @Select("SELECT dept_id FROM pf_org_unit WHERE dept_id = #{orgId} FOR SHARE")
    Long lockOrgForShare(@Param("orgId") Long orgId);

    /** 组织级排他锁：组织停用改状态前取锁 */
    @Select("SELECT dept_id FROM pf_org_unit WHERE dept_id = #{orgId} FOR UPDATE")
    Long lockOrgForUpdate(@Param("orgId") Long orgId);

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


    /** M01-T06（Codex R1 P1-1）：开通业务键（orgType:orgName）事务 advisory 锁——并发开通串行化，
     * 锁后二次查重命中复用，杜绝重复建组织。键空间与 StaffPostMapper 的 userId 键空间不重叠
     * （本键经 hashtext 派生，风险自评接受；如需严格隔离可扩 int 键空间段）。 */
    @org.apache.ibatis.annotations.Select("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext(#{key}))) t")
    Integer lockOrgKeyForOnboard(@org.apache.ibatis.annotations.Param("key") String key);
}