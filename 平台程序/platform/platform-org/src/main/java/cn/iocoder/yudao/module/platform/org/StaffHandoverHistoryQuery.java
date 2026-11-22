package cn.iocoder.yudao.module.platform.org;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/** 只查询本组织曾任职人员，供无待分配记录的历史离职员工补交接；调用方校验当前岗位组织范围。 */
@Service
public class StaffHandoverHistoryQuery {
    private static final int PAGE_SIZE = 20;
    private final JdbcTemplate jdbc;

    public StaffHandoverHistoryQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Source(String userId, String name, String mobile) { }
    public record Page(List<Source> items, boolean hasMore) { }

    public Page search(long orgId, String keyword, int pageNo) {
        if (keyword == null || keyword.isBlank() || keyword.length() > 64 || pageNo < 1 || pageNo > 10000) {
            throw new ServiceException(400, "请输入姓名、手机号或员工编号，查询页码须为1至10000");
        }
        String like = "%" + keyword.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        List<Source> rows = jdbc.query("""
                SELECT DISTINCT p.user_id,
                    COALESCE(CASE WHEN u.deleted=0 THEN u.nickname END, '用户' || p.user_id::text) AS name,
                    COALESCE(CASE WHEN u.deleted=0 THEN u.mobile END, '') AS mobile
                FROM pf_staff_post p
                JOIN system_dept d ON d.id=p.org_id AND d.tenant_id=? AND d.deleted=0
                JOIN system_users u ON u.id=p.user_id AND u.tenant_id=d.tenant_id
                WHERE p.org_id=? AND (
                    (u.deleted=0 AND (u.nickname ILIKE ? ESCAPE '!' OR u.mobile LIKE ? ESCAPE '!'))
                    OR p.user_id::text LIKE ? ESCAPE '!')
                ORDER BY p.user_id LIMIT ? OFFSET ?
                """, (rs, index) -> new Source(rs.getString("user_id"), rs.getString("name"), rs.getString("mobile")),
                TenantContextHolder.getRequiredTenantId(), orgId, like, like, like, PAGE_SIZE + 1, (pageNo - 1) * PAGE_SIZE);
        return new Page(List.copyOf(rows.subList(0, Math.min(rows.size(), PAGE_SIZE))), rows.size() > PAGE_SIZE);
    }
}
