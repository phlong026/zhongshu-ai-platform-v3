package cn.iocoder.yudao.module.platform.org;

import cn.iocoder.yudao.module.platform.org.dal.OrgTypeMapper;
import cn.iocoder.yudao.module.platform.org.dal.OrgUnitDO;
import cn.iocoder.yudao.module.platform.org.dal.OrgUnitMapper;
import cn.iocoder.yudao.module.platform.org.dal.PostTemplateDO;
import cn.iocoder.yudao.module.platform.org.dal.PostTemplateMapper;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.platform.org.enums.PlatformOrgErrorCodes.*;

@Service
public class OrgServiceImpl implements OrgService {

    @Resource
    private OrgTypeMapper orgTypeMapper;
    @Resource
    private OrgUnitMapper orgUnitMapper;
    @Resource
    private PostTemplateMapper postTemplateMapper;
    @Resource
    private SystemDirectory systemDirectory;
    /** 钱包会话撤权回调（franchise-org 实现；组装裁剪时静默跳过） */
    @Resource
    private ObjectProvider<WalletSessionRevocation> walletSessionRevocation;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long createOrg(String orgType, String name, Long parentDeptId) {
        if (orgTypeMapper.selectById(orgType) == null) {
            throw exception(ORG_TYPE_NOT_REGISTERED);
        }
        long deptId = systemDirectory.createDept(parentDeptId, name);
        orgUnitMapper.insert(new OrgUnitDO().setDeptId(deptId).setKind(OrgUnitDO.KIND_ORG).setOrgId(deptId)
                .setOrgType(orgType).setStatus(OrgUnitDO.STATUS_ACTIVE));
        return deptId;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long createTeam(long orgId, Long parentTeamId, String name, String line) {
        requireOrg(orgId);
        long parent = orgId;
        if (parentTeamId != null) {
            OrgUnitDO parentTeam = orgUnitMapper.selectById(parentTeamId);
            if (parentTeam == null || parentTeam.isOrg() || parentTeam.getOrgId() != orgId) {
                throw exception(TEAM_NOT_IN_ORG);
            }
            parent = parentTeamId;
        }
        long deptId = systemDirectory.createDept(parent, name);
        orgUnitMapper.insert(new OrgUnitDO().setDeptId(deptId).setKind(OrgUnitDO.KIND_TEAM).setOrgId(orgId)
                .setLine(line).setStatus(OrgUnitDO.STATUS_ACTIVE));
        return deptId;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setOrgActive(long orgId, boolean active) {
        // 09 钱包会话桥接撤权协议：组织行排他锁（发卡/岗位新增的组织共享锁互斥），状态修改与钱包会话吊销同事务
        orgUnitMapper.lockOrgForUpdate(orgId);
        requireOrg(orgId);
        orgUnitMapper.updateById(new OrgUnitDO().setDeptId(orgId)
                .setStatus(active ? OrgUnitDO.STATUS_ACTIVE : OrgUnitDO.STATUS_DISABLED)
                .setUpdateTime(OffsetDateTime.now()));
        if (!active) {
            walletSessionRevocation.ifAvailable(cb -> cb.revokeForOrg(orgId));
        }
    }

    @Override
    public OrgUnitDO getUnit(long deptId) {
        return orgUnitMapper.selectById(deptId);
    }

    @Override
    public List<OrgUnitDO> listTeams(long orgId) {
        return orgUnitMapper.selectActiveTeamsByOrg(orgId);
    }

    @Override
    public List<PostTemplateDO> listPostTemplates(long orgId) {
        String orgType = requireOrg(orgId).getOrgType();
        return postTemplateMapper.selectList().stream()
                .filter(template -> template.allows(orgType))
                .sorted(Comparator.comparing(PostTemplateDO::getRoleCode))
                .toList();
    }

    @Override
    public List<Long> listActiveOrgIds(String orgType) {
        return orgUnitMapper.selectActiveOrgIds(orgType);
    }

    private OrgUnitDO requireOrg(long orgId) {
        OrgUnitDO org = orgUnitMapper.selectById(orgId);
        if (org == null || !org.isOrg()) {
            throw exception(ORG_NOT_FOUND);
        }
        return org;
    }

}
