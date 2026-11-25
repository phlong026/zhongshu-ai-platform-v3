package cn.iocoder.yudao.module.franchise.finance;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Set;

/** 核算是 JDBC 只读投影/确认，显式校验真实租户、公司、用户及当前任职。 */
@Component
public class FinProjectAccountingAccess {
    private final CurrentPost current;
    private final JdbcTemplate jdbc;
    public FinProjectAccountingAccess(CurrentPost current,JdbcTemplate jdbc){this.current=current;this.jdbc=jdbc;}

    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public void require(PostSnapshot expected,boolean write) {
        Long tenant=TenantContextHolder.getTenantId(),user=SecurityFrameworkUtils.getLoginUserId();
        if(tenant==null || user==null)throw new ServiceException(401,"请先登录并选择本公司工作身份");
        var post=current.require();
        if(expected==null || !expected.equals(post) || post.userId()!=user || !"store".equals(post.orgType())
            || !Set.of("boss","owner","finance","finance-lead").contains(post.roleCode()))throw denied();
        if(write && !canConfirm(post))throw new ServiceException(403,"仅本公司财务可确认项目核算，老板可查看核算结果");
        Boolean active=jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM pf_staff_post p
              JOIN system_users u ON u.id=p.user_id AND u.tenant_id=? AND u.status=0 AND u.deleted=0
              JOIN pf_org_unit o ON o.dept_id=p.org_id AND o.org_id=o.dept_id AND o.kind='ORG'
                AND o.org_type='store' AND o.status='ACTIVE'
              JOIN system_dept d ON d.id=o.dept_id AND d.tenant_id=u.tenant_id AND d.status=0 AND d.deleted=0
              WHERE p.id=? AND p.user_id=? AND p.org_id=? AND p.role_code=? AND p.status='ACTIVE')
            """,Boolean.class,tenant,post.postId(),user,post.orgId(),post.roleCode());
        if(!Boolean.TRUE.equals(active))throw denied();
    }
    static boolean canConfirm(PostSnapshot post){return post!=null && "store".equals(post.orgType()) && Set.of("finance","finance-lead").contains(post.roleCode());}
    /** HQ 只读端口外层负责 HQ 角色授权，这里防传入其他租户或已失效公司。 */
    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public void company(long tenant,long company) {
        if(!java.util.Objects.equals(TenantContextHolder.getTenantId(),tenant))throw denied();
        Boolean active=jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM pf_org_unit o JOIN system_dept d ON d.id=o.dept_id
              WHERE o.dept_id=? AND o.org_id=o.dept_id AND o.kind='ORG' AND o.org_type='store' AND o.status='ACTIVE'
                AND d.tenant_id=? AND d.status=0 AND d.deleted=0)
            """,Boolean.class,company,tenant);
        if(!Boolean.TRUE.equals(active))throw denied();
    }
    private static ServiceException denied(){return new ServiceException(403,"当前岗位不能读取或确认该公司的项目核算");}
}
