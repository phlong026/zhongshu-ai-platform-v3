package cn.iocoder.yudao.module.platform.announcement;

import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;

/** Announcement authorization is independent of design administration and role unions. */
@Component
public class AnnouncementAccess {
    public static final String QUERY = "platform:announcement:query";
    public static final String MANAGE = "platform:announcement:manage";
    private static final Set<String> HQ_READERS = Set.of("hq-admin", "finance", "finance-lead");
    private final CurrentPost posts;
    private final SystemDirectory directory;
    private final JdbcTemplate jdbc;
    private final long headquartersTenant;

    public AnnouncementAccess(CurrentPost posts, SystemDirectory directory, JdbcTemplate jdbc,
            @Value("${zhongshu.org-point.headquarters-tenant-id}") long headquartersTenant) {
        this.posts = posts;
        this.directory = directory;
        this.jdbc = jdbc;
        this.headquartersTenant = headquartersTenant;
    }

    public record Reader(long tenantId, long userId, Long postId, boolean allBrands,
                         Long brandId, boolean canAdminRead, boolean canWrite) { }

    public Reader reader() {
        var login = SecurityFrameworkUtils.getLoginUser();
        Long tenant = TenantContextHolder.getTenantId();
        if (login == null || login.getId() == null || tenant == null || !Objects.equals(login.getTenantId(), tenant)
                || !Objects.equals(login.getUserType(), UserTypeEnum.ADMIN.getValue())) {
            throw new ServiceException(401, "请先登录");
        }
        // An invalid/ambiguous requested post throws here; it must never fall back to a union role.
        var selected = posts.find();
        if (selected.isEmpty()) {
            if (tenant == headquartersTenant && activeSuperAdministrator(tenant, login.getId())) {
                return new Reader(tenant, login.getId(), null, true, null, true, true);
            }
            throw new ServiceException(403, "当前账号没有有效的公告阅读身份");
        }
        PostSnapshot post = selected.get();
        if (post.userId() != login.getId() || !activePost(tenant, post)) {
            throw new ServiceException(403, "当前工作身份已失效");
        }
        boolean headquarters = "headquarters".equals(post.orgType());
        boolean all = headquarters && HQ_READERS.contains(post.roleCode());
        boolean query = all && directory.roleHasPermission(post.roleCode(), QUERY);
        boolean write = query && "hq-admin".equals(post.roleCode())
                && directory.roleHasPermission(post.roleCode(), MANAGE);
        Long brand = null;
        if ("store".equals(post.orgType())) {
            var brands = jdbc.queryForList("""
                    SELECT DISTINCT l.brand_id FROM pf_store_location l
                    JOIN pf_map_brand b ON b.tenant_id=l.tenant_id AND b.id=l.brand_id
                    WHERE l.tenant_id=? AND l.org_id=?
                    """, Long.class, tenant, post.orgId());
            // Missing/ambiguous brand assignments expose only global announcements.
            if (brands.size() == 1) brand = brands.get(0);
        } else if (!headquarters) {
            throw new ServiceException(403, "当前工作身份无权查看公司公告");
        }
        return new Reader(tenant, login.getId(), post.postId(), all, brand, query, write);
    }

    public Reader admin(boolean write) {
        Reader reader = reader();
        if (!reader.canAdminRead() || (write && !reader.canWrite())) {
            throw new ServiceException(403, write ? "无公告发布管理权限" : "无公告管理查看权限");
        }
        return reader;
    }

    private boolean activePost(long tenant, PostSnapshot post) {
        // pf_staff_post itself has no tenant_id: the department/user joins are essential.
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM pf_staff_post p
                    JOIN pf_org_unit o ON o.dept_id=p.org_id AND o.kind='ORG' AND o.status='ACTIVE'
                    JOIN system_dept d ON d.id=o.dept_id AND d.tenant_id=? AND d.status=0 AND d.deleted=0
                    JOIN system_users u ON u.id=p.user_id AND u.tenant_id=d.tenant_id AND u.status=0 AND u.deleted=0
                    JOIN system_role r ON r.code=p.role_code AND r.tenant_id=d.tenant_id AND r.status=0 AND r.deleted=0
                    WHERE p.id=? AND p.user_id=? AND p.org_id=? AND p.role_code=?
                      AND p.status='ACTIVE' AND o.org_type=?)
                """, Boolean.class, tenant, post.postId(), post.userId(), post.orgId(), post.roleCode(), post.orgType()));
    }

    private boolean activeSuperAdministrator(long tenant, long user) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM system_users u
                    JOIN system_user_role ur ON ur.user_id=u.id AND ur.tenant_id=u.tenant_id AND ur.deleted=0
                    JOIN system_role r ON r.id=ur.role_id AND r.tenant_id=u.tenant_id
                      AND r.status=0 AND r.deleted=0 AND r.code='super_admin'
                    WHERE u.id=? AND u.tenant_id=? AND u.status=0 AND u.deleted=0)
                """, Boolean.class, user, tenant));
    }
}
