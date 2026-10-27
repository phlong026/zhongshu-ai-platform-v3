package cn.iocoder.yudao.module.franchise.commission;

import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionDO;
import cn.iocoder.yudao.module.franchise.crm.CustomerAccess;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerDO;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerMapper;
import cn.iocoder.yudao.module.franchise.org.FranchiseRoles;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.function.Consumer;

import static cn.iocoder.yudao.module.franchise.org.FranchiseRoles.DESIGN_ADMIN;
import static cn.iocoder.yudao.module.franchise.org.FranchiseRoles.ENGINEER;

/**
 * 委托的对象规则，移植自小程序 authority-v2.js（commission、designOrg、handover）。
 * 组织隔离由平台 OrgScopeRule 在 SQL 层保证（fr_customer 对总部不可见，正好实现字段裁剪），
 * 这里按当前岗位再校验一次。
 */
@Component
public class CommissionAccess {

    private final CustomerMapper customerMapper;

    public CommissionAccess(CustomerMapper customerMapper) {
        this.customerMapper = customerMapper;
    }

    /**
     * 委托可见（§6）：同组织老板类；总部设计行政（委托的设计组织）；能读该客户的人；任一版移交的接收人。
     * 接收人分支由 Service 持有移交数据时判断 {@link #isHandoverManager}。
     */
    public boolean canRead(PostSnapshot post, CommissionDO commission) {
        if (commission.getCompanyId() == post.orgId() && FranchiseRoles.isBoss(post)) {
            return true;
        }
        if (isDesignAdminOf(post, commission)) {
            return true;
        }
        CustomerDO customer = customerMapper.selectById(commission.getCustomerId());
        return customer != null && CustomerAccess.canRead(post, customer);
    }

    /** 委托可写（提交、修改意见、移交）：对客户 canWrite —— 经办业务或其负责人，门店业务线 */
    public boolean canWrite(PostSnapshot post, CommissionDO commission) {
        if (commission.getCompanyId() != post.orgId()) {
            return false;
        }
        CustomerDO customer = customerMapper.selectById(commission.getCustomerId());
        return customer != null && CustomerAccess.canWrite(post, customer);
    }

    /** 设计完成 / 成果下发：总部设计行政，且委托的设计组织是本人所在组织 */
    public boolean isDesignAdminOf(PostSnapshot post, CommissionDO commission) {
        return DESIGN_ADMIN.equals(post.roleCode()) && FranchiseRoles.atHeadquarters(post)
                && commission.getDesignOrgId() == post.orgId();
    }

    /** 核查接收 / 退回：工程线岗位 + 本版移交指定的接收人本人 + 同组织 */
    public boolean isHandoverManager(PostSnapshot post, long storeOrgId, Long managerUserId) {
        return FranchiseRoles.has(post, ENGINEER) && storeOrgId == post.orgId()
                && managerUserId != null && managerUserId == post.userId();
    }

    /**
     * 列表可见范围（SQL 条件）：老板全店；设计行政按设计组织；业务岗按可见客户集合；
     * 移交接收人的委托集合由 Service 查询后并入。
     */
    public Consumer<LambdaQueryWrapperX<CommissionDO>> visibleTo(PostSnapshot post, Set<Long> visibleCustomerIds,
                                                                 Set<Long> managedCommissionIds) {
        return query -> {
            boolean any = false;
            if (FranchiseRoles.isBoss(post)) {
                query.eq(CommissionDO::getCompanyId, post.orgId());
                any = true;
            } else if (DESIGN_ADMIN.equals(post.roleCode()) && FranchiseRoles.atHeadquarters(post)) {
                query.eq(CommissionDO::getDesignOrgId, post.orgId());
                any = true;
            }
            boolean byCustomer = visibleCustomerIds != null && !visibleCustomerIds.isEmpty();
            boolean byHandover = managedCommissionIds != null && !managedCommissionIds.isEmpty();
            if (byCustomer || byHandover) {
                query.and(q -> {
                    if (byCustomer) {
                        q.in(CommissionDO::getCustomerId, visibleCustomerIds);
                    }
                    if (byHandover) {
                        if (byCustomer) {
                            q.or();
                        }
                        q.in(CommissionDO::getId, managedCommissionIds);
                    }
                });
                any = true;
            }
            if (!any) {
                query.apply("1 = 0");
            }
        };
    }

}
