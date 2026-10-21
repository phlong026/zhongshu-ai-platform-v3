package cn.iocoder.yudao.module.platform.org;

import cn.iocoder.yudao.module.platform.org.dal.OrgTypeMapper;
import cn.iocoder.yudao.module.platform.org.dal.OrgUnitDO;
import cn.iocoder.yudao.module.platform.org.dal.OrgUnitMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.platform.org.enums.PlatformOrgErrorCodes.*;

@Service
public class OrgServiceImpl implements OrgService {

    @Resource
    private OrgTypeMapper orgTypeMapper;
    @Resource
    private OrgUnitMapper orgUnitMapper;
    @Resource
    private SystemDirectory systemDirectory;

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
    public void setOrgActive(long orgId, boolean active) {
        requireOrg(orgId);
        orgUnitMapper.updateById(new OrgUnitDO().setDeptId(orgId)
                .setStatus(active ? OrgUnitDO.STATUS_ACTIVE : OrgUnitDO.STATUS_DISABLED)
                .setUpdateTime(OffsetDateTime.now()));
    }

    @Override
    public OrgUnitDO getUnit(long deptId) {
        return orgUnitMapper.selectById(deptId);
    }

    private OrgUnitDO requireOrg(long orgId) {
        OrgUnitDO org = orgUnitMapper.selectById(orgId);
        if (org == null || !org.isOrg()) {
            throw exception(ORG_NOT_FOUND);
        }
        return org;
    }

}
