package cn.iocoder.yudao.module.franchise.crm;

import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerDO;
import cn.iocoder.yudao.module.franchise.crm.dal.LeadDO;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;

import java.util.Objects;
import java.util.function.Consumer;

import static cn.iocoder.yudao.module.franchise.org.FranchiseRoles.*;

/**
 * 客户与客资的对象规则，移植自小程序 authority-v2.js（customer、teamAccess）与 growth.js（findStoreCustomer、manages）。
 * 组织隔离由平台 OrgScopeRule 在 SQL 层保证，这里再校验一次同组织，防止误用忽略数据权限的查询。
 */
public final class CustomerAccess {

    private CustomerAccess() {
    }

    /**
     * 负责人管理范围：负责人未分团队时管理本组织整条线；否则管理所在团队及其下级团队。
     * 小程序 authority-v2.js context() 中未配置团队的负责人默认管理整条线。
     */
    public static boolean teamAccess(PostSnapshot post, Long teamId) {
        if (!post.lead()) {
            return false;
        }
        return post.teamId() == null || teamId != null && post.managedTeamIds().contains(teamId);
    }

    public static boolean sameTeam(PostSnapshot post, Long teamId) {
        return Objects.equals(post.teamId(), teamId);
    }

    public static boolean isOwner(PostSnapshot post, CustomerDO customer) {
        return customer.getOwnerUserId() != null && customer.getOwnerUserId() == post.userId();
    }

    private static boolean sameOrg(PostSnapshot post, Long companyId) {
        return companyId != null && companyId == post.orgId();
    }

    public static boolean canRead(PostSnapshot post, CustomerDO customer) {
        if (!sameOrg(post, customer.getCompanyId())) {
            return false;
        }
        if (isBoss(post)) {
            return true;
        }
        if (SALES_LEAD.equals(post.roleCode())) {
            return isOwner(post, customer) || teamAccess(post, customer.getTeamId());
        }
        return SALES.equals(post.roleCode()) && isOwner(post, customer);
    }

    /** 跟进、编辑、方案、提交委托、修改意见、提交移交：经办业务或其负责人；老板只看不办 */
    public static boolean canWrite(PostSnapshot post, CustomerDO customer) {
        return atStore(post) && has(post, SALES) && sameOrg(post, customer.getCompanyId())
                && (isOwner(post, customer) || SALES_LEAD.equals(post.roleCode()) && teamAccess(post, customer.getTeamId()));
    }

    /** 登记客资时能否直接关联这位同手机号原客户（growth.js findStoreCustomer） */
    public static boolean canAutoLink(PostSnapshot post, CustomerDO customer) {
        return sameOrg(post, customer.getCompanyId()) && (isBoss(post) || isOwner(post, customer)
                || sameTeam(post, customer.getTeamId()) || teamAccess(post, customer.getTeamId()));
    }

    /** 待关联客资由老板或管理该团队的业务负责人处理（growth.js manages） */
    public static boolean canHandleLead(PostSnapshot post, LeadDO lead) {
        return sameOrg(post, lead.getCompanyId())
                && (isBoss(post) || SALES_LEAD.equals(post.roleCode()) && teamAccess(post, lead.getTeamId()));
    }

    /** 关联时原客户也须在处理人管理范围内（growth.js existingCustomer） */
    public static boolean canLinkTo(PostSnapshot post, CustomerDO customer) {
        return sameOrg(post, customer.getCompanyId()) && (isBoss(post) || teamAccess(post, customer.getTeamId()));
    }

    public static boolean canTransfer(PostSnapshot post, CustomerDO customer) {
        return sameOrg(post, customer.getCompanyId())
                && (isBoss(post) || SALES_LEAD.equals(post.roleCode()) && teamAccess(post, customer.getTeamId()));
    }

    /** 列表的可见范围，写成 SQL 条件以保证分页正确；与 canRead 一致 */
    public static Consumer<LambdaQueryWrapperX<CustomerDO>> visibleTo(PostSnapshot post) {
        return query -> {
            if (isBoss(post)) {
                return;
            }
            if (SALES_LEAD.equals(post.roleCode())) {
                if (post.teamId() == null) {
                    return;
                }
                query.and(q -> q.eq(CustomerDO::getOwnerUserId, post.userId())
                        .or().in(CustomerDO::getTeamId, post.managedTeamIds()));
                return;
            }
            if (SALES.equals(post.roleCode())) {
                query.eq(CustomerDO::getOwnerUserId, post.userId());
                return;
            }
            query.apply("1 = 0");
        };
    }

    /** 可办理范围（经办或负责人管理），用于业务待办；与 canWrite 一致 */
    public static Consumer<LambdaQueryWrapperX<CustomerDO>> writableBy(PostSnapshot post) {
        return query -> {
            if (!atStore(post) || !has(post, SALES)) {
                query.apply("1 = 0");
                return;
            }
            if (SALES_LEAD.equals(post.roleCode())) {
                visibleTo(post).accept(query);
                return;
            }
            query.eq(CustomerDO::getOwnerUserId, post.userId());
        };
    }

}
