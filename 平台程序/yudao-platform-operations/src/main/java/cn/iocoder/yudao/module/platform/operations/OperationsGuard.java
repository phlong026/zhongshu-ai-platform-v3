package cn.iocoder.yudao.module.platform.operations;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 总部超级管理员的显式身份校验，不能用普通老板任职替代。 */
@Component
public class OperationsGuard {
    private final JdbcTemplate jdbc;
    private final long headquarters;
    public OperationsGuard(JdbcTemplate jdbc,@Value("${zhongshu.org-point.headquarters-tenant-id}") long headquarters) {
        this.jdbc=jdbc;this.headquarters=headquarters;
    }
    public record Actor(long tenantId,long userId) { }
    public Actor require() {
        Long tenant=TenantContextHolder.getTenantId(),user=SecurityFrameworkUtils.getLoginUserId();
        if(user==null)throw new ServiceException(401,"请先登录");
        if(tenant==null||tenant!=headquarters)throw new ServiceException(403,"仅限平台总部管理员");
        Boolean allowed=jdbc.queryForObject("""
          SELECT EXISTS(SELECT 1 FROM system_users u
          JOIN system_user_role ur ON ur.user_id=u.id AND ur.tenant_id=u.tenant_id AND ur.deleted=0
          JOIN system_role r ON r.id=ur.role_id AND r.tenant_id=u.tenant_id AND r.deleted=0 AND r.status=0 AND r.code='super_admin'
          WHERE u.id=? AND u.tenant_id=? AND u.status=0 AND u.deleted=0)
          """,Boolean.class,user,tenant);
        if(!Boolean.TRUE.equals(allowed))throw new ServiceException(403,"仅限平台超级管理员");
        return new Actor(tenant,user);
    }
    public void organization(Actor actor,long org,boolean writable) {
        var rows=jdbc.queryForList("""
          SELECT d.deleted,o.status FROM pf_org_unit o JOIN system_dept d ON d.id=o.dept_id
          WHERE o.dept_id=? AND o.kind='ORG' AND d.tenant_id=?
          """,org,actor.tenantId());
        if(rows.isEmpty())throw new ServiceException(404,"组织不存在");
        if(writable&&((Number)rows.get(0).get("deleted")).intValue()!=0)
            throw new ServiceException(409,"已删除组织不能修改");
    }
    public static void text(String value,int max,String label) {
        if(value==null||value.isBlank()||value.trim().length()>max)throw new ServiceException(400,"请正确填写"+label);
    }
}
