package cn.iocoder.yudao.module.platform.org;

import cn.iocoder.yudao.module.platform.org.dal.OrgUnitDO;

import java.util.List;

/**
 * 组织与团队。组织、团队都是 yudao 部门，本服务负责标注与校验。
 * 调用方需自行完成权限校验（谁能开通组织、建团队），本服务只保证数据规则。
 */
public interface OrgService {

    /** 新建组织：在 parentDeptId 下建部门并标注为 ORG，返回组织编号（即部门编号） */
    long createOrg(String orgType, String name, Long parentDeptId);

    /** 在组织内建团队；parentTeamId 为空时直接挂在组织下，否则必须是本组织的团队 */
    long createTeam(long orgId, Long parentTeamId, String name, String line);

    /** 启用或停用组织；停用后该组织的任职不能再作为工作身份使用 */
    void setOrgActive(long orgId, boolean active);

    /** 部门对应的组织单元；未标注返回 null */
    OrgUnitDO getUnit(long deptId);

    /** 某类型下全部启用的组织编号，按编号升序（如业务域查找唯一的总部组织） */
    List<Long> listActiveOrgIds(String orgType);

}
