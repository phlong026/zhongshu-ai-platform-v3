package cn.iocoder.yudao.module.platform.authz.service;

import cn.iocoder.yudao.module.platform.authz.enums.PlatformAuthzErrorCodes;
import cn.iocoder.yudao.module.platform.org.OrgService;
import cn.iocoder.yudao.module.platform.org.dal.OrgUnitDO;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import cn.iocoder.yudao.module.platform.org.dal.StaffPostDO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.platform.org.enums.PlatformOrgErrorCodes.*;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import cn.iocoder.yudao.module.platform.org.enums.PlatformOrgErrorCodes;

/**
 * M01-T06 / R02：正式组织开通编排（平台管理员经 Web 执行；生产不依赖开发 fixture）。
 *
 * <p>幂等：同 orgType+orgName 已存在 ACTIVE 组织时复用（不重复建组织），并确保首管理员 boss 任职；
 * 编排同一事务——建号后指派失败整体回滚（M01-T05 同款原子语义）。
 * 首管理员角色固定 boss（岗位模板 org_types 含 headquarters,store，通用两类组织）。
 */
@Service
public class OrgOnboardService {

    private static final String ADMIN_ROLE_CODE = "boss";

    @Resource
    private OrgService orgService;
    @Resource
    private StaffPostService staffPostService;
    @Resource
    private SystemDirectory systemDirectory;
    @Resource
    private cn.iocoder.yudao.module.platform.org.dal.OrgUnitMapper orgUnitMapper;

    public record OnboardResult(long orgId, long adminUserId, long postId, boolean reusedOrg) {
    }

    @Transactional(rollbackFor = Exception.class)
    public OnboardResult onboard(String orgType, String orgName, String adminName, String adminMobile, long operatorUserId) {
        // Codex R1 P1-1：业务键事务锁先行——并发开通串行化，锁后二次查重命中复用，杜绝重复建组织
        orgUnitMapper.lockOrgKeyForOnboard("org-onboard:" + orgType + ":" + orgName);
        // 幂等查重：同名根部门下的 ACTIVE 组织——同类型复用（重复开通不重复建组织），异类型显式拒绝（016）
        Long reused = null;
        for (Long deptId : systemDirectory.findRootDeptIdsByName(orgName)) {
            OrgUnitDO unit = orgService.getUnit(deptId);
            if (unit != null && unit.isOrg() && unit.isActive()) {
                if (!orgType.equals(unit.getOrgType())) {
                    throw exception(ORG_NAME_TAKEN);
                }
                reused = unit.getOrgId();
                break;
            }
        }
        long orgId = reused != null ? reused : orgService.createOrg(orgType, orgName, null);
        // 首管理员：手机号已存在复用账号（停用账号显式拒绝——Codex R1 P2-2），否则新建（同一事务）
        Long existing = systemDirectory.userIdByMobile(adminMobile);
        if (existing != null && !systemDirectory.userUsable(existing)) {
            throw exception(STAFF_GLOBAL_RESUME_FORBIDDEN);
        }
        long adminUserId = existing != null ? existing : systemDirectory.createUser(adminName, adminMobile, orgId);
        // boss 任职幂等（同岗位同 team=null 一致即幂等；被占不同 team 时 015 显式拒绝）
        long postId = staffPostService.assignPostInOrg(adminUserId, orgId, ADMIN_ROLE_CODE, null, operatorUserId);
        return new OnboardResult(orgId, adminUserId, postId, reused != null);
    }
}
