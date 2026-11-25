package cn.iocoder.yudao.module.platform.headquarters;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.ArrayList;
import java.util.Collections;

/** Explicit headquarters reads; ordinary organization SQL scopes and command permissions stay unchanged. */
@Component
public class HeadquartersReadGuard {
    private static final Set<String> ROLES = Set.of("hq-admin", "finance", "finance-lead");
    private final CurrentPost current;
    private final JdbcTemplate jdbc;

    public HeadquartersReadGuard(CurrentPost current, JdbcTemplate jdbc) { this.current = current; this.jdbc = jdbc; }

    public record Actor(long tenantId, long userId, long postId, long orgId, String roleCode) { }

    // A large export's repeatable-read snapshot must not conceal a post revoked while it is being prepared.
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Actor require() {
        Long tenant = TenantContextHolder.getTenantId(), user = SecurityFrameworkUtils.getLoginUserId();
        if (tenant == null || user == null) throw new ServiceException(401, "请先登录并选择总部工作身份");
        var post = current.require();
        if (post.userId() != user || !"headquarters".equals(post.orgType()) || !ROLES.contains(post.roleCode())) throw denied();
        Boolean valid = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM pf_staff_post p
                  JOIN system_users u ON u.id=p.user_id AND u.tenant_id=? AND u.status=0 AND u.deleted=0
                  JOIN pf_org_unit o ON o.dept_id=p.org_id AND o.org_id=o.dept_id AND o.kind='ORG'
                    AND o.org_type='headquarters' AND o.status='ACTIVE'
                  JOIN system_dept d ON d.id=o.dept_id AND d.tenant_id=u.tenant_id AND d.status=0 AND d.deleted=0
                  WHERE p.id=? AND p.user_id=? AND p.org_id=? AND p.role_code=? AND p.status='ACTIVE')
                """, Boolean.class, tenant, post.postId(), user, post.orgId(), post.roleCode());
        if (!Boolean.TRUE.equals(valid)) throw denied();
        return new Actor(tenant, user, post.postId(), post.orgId(), post.roleCode());
    }

    public void company(Actor actor, long company) {
        Boolean valid = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM pf_org_unit o JOIN system_dept d ON d.id=o.dept_id
                  WHERE o.dept_id=? AND o.org_id=o.dept_id AND o.kind='ORG' AND o.status='ACTIVE'
                    AND o.org_type IN ('store','headquarters') AND d.tenant_id=? AND d.status=0 AND d.deleted=0)
                """, Boolean.class, company, actor.tenantId());
        if (!Boolean.TRUE.equals(valid)) throw missing();
    }

    /** Run outside the export snapshot so company transfers/disablement cannot release stale tenant data. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void requireCompanies(Actor expected, Set<Long> companies) {
        if (!expected.equals(require())) throw denied();
        if (companies.isEmpty()) return;
        var params = new ArrayList<Object>();
        params.add(expected.tenantId()); params.addAll(companies);
        Long found = jdbc.queryForObject("""
                SELECT count(*) FROM pf_org_unit o JOIN system_dept d ON d.id=o.dept_id
                  WHERE o.org_id=o.dept_id AND o.kind='ORG' AND o.status='ACTIVE'
                    AND o.org_type IN ('store','headquarters') AND d.tenant_id=? AND d.status=0 AND d.deleted=0
                    AND d.id IN (
                """ + String.join(",", Collections.nCopies(companies.size(), "?")) + ")", Long.class, params.toArray());
        if (found == null || found != companies.size()) throw denied();
    }

    static ServiceException denied() { return new ServiceException(403, "仅总部管理员和总部财务可查看全部品牌业务"); }
    static ServiceException missing() { return new ServiceException(404, "记录不存在或当前工作身份无权查看"); }
}
