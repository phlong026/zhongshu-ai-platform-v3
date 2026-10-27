package cn.iocoder.yudao.module.franchise.crm;

import cn.iocoder.yudao.module.franchise.crm.dal.CustomerDO;
import cn.iocoder.yudao.module.franchise.org.FranchiseRoles;
import cn.iocoder.yudao.module.platform.authz.Access;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 客户的对象级规则（对应小程序 authority-v2.js 的 customer / teamAccess）。
 * <ul>
 *   <li>业务员：本人负责的客户；</li>
 *   <li>业务负责人：所管团队（所在团队及下级）的客户与本人客户；未分团队的负责人管理全店业务；</li>
 *   <li>老板、门店负责人：全店只读。</li>
 * </ul>
 */
@Component
public class CustomerPolicy {

    private static final Set<String> STORE_MANAGERS = Set.of(FranchiseRoles.BOSS, FranchiseRoles.OWNER);

    @Resource
    private StaffPostService staffPostService;

    public boolean canRead(PostSnapshot post, CustomerDO c) {
        return Access.sameOrg(post, c.getCompanyId())
                && (STORE_MANAGERS.contains(post.roleCode()) || canHandle(post, c));
    }

    /** 能否办理（跟进、提交委托等业务动作）：本人负责的客户，或所管团队的客户 */
    public boolean canHandle(PostSnapshot post, CustomerDO c) {
        if (!Access.sameOrg(post, c.getCompanyId()) || !isSales(post)) {
            return false;
        }
        return Access.isSelf(post, c.getOwnerUserId()) || managesSalesOf(post, c.getTeamId());
    }

    public boolean isSales(PostSnapshot post) {
        return staffPostService.roleHas(post.roleCode(), FranchiseRoles.SALES);
    }

    public boolean isStoreManager(PostSnapshot post) {
        return STORE_MANAGERS.contains(post.roleCode());
    }

    /** 业务负责人是否管理该团队的业务；未分团队的负责人管理全店 */
    public boolean managesSalesOf(PostSnapshot post, Long teamId) {
        if (!FranchiseRoles.SALES_LEAD.equals(post.roleCode())) {
            return false;
        }
        return post.teamId() == null || Access.managesTeam(post, teamId);
    }

}
