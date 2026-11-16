package cn.iocoder.yudao.module.platform.authz.service;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.platform.org.OrgService;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** 平台管理员的组织目录；租户、软删除及老板账号过滤均在数据库中执行。 */
@Service
@Transactional(readOnly = true)
public class OrgDirectoryService {
    @Resource private JdbcTemplate jdbc;
    @Resource private OrgService orgService;

    public record Organization(long orgId, String orgName, String orgType, String status,
                               String ownerName, String mobile, String loginName, Integer accountStatus,
                               String brand, String province, String city, String area, String remark,
                               OffsetDateTime createdAt, String accountId) { }
    public record Detail(Organization organization, List<String> aliases) { }

    private static final String FROM = """
            FROM pf_org_unit o
            JOIN system_dept d ON d.id=o.dept_id
            LEFT JOIN pf_franchise_account a ON a.account_id=(
                SELECT fa.account_id FROM pf_franchise_account fa
                WHERE fa.tenant_id=d.tenant_id AND fa.org_id=o.dept_id AND fa.account_type='BOSS'
                ORDER BY fa.created_at, fa.account_id LIMIT 1)
            LEFT JOIN system_users u ON u.id=COALESCE(a.user_id, (
                SELECT p.user_id FROM pf_staff_post p
                WHERE p.org_id=o.dept_id AND p.role_code='boss' AND p.status='ACTIVE'
                ORDER BY p.id LIMIT 1)) AND u.tenant_id=d.tenant_id AND u.deleted=0
            WHERE o.kind='ORG' AND d.tenant_id=?
            """;
    private static final String COLUMNS = """
            SELECT o.dept_id, d.name, o.org_type,
                CASE WHEN d.deleted<>0 THEN 'DELETED' WHEN o.status='ACTIVE' AND d.status=0 THEN 'ACTIVE' ELSE 'DISABLED' END AS org_status,
                COALESCE(a.owner_name,u.nickname) AS owner_name, u.mobile,
                COALESCE(a.login_name,u.username) AS login_name, u.status AS account_status,
                a.brand, a.province, a.city, a.area, a.remark, o.create_time, a.account_id
            """;
    private static final RowMapper<Organization> ROW = (rs, row) -> new Organization(
            rs.getLong("dept_id"), rs.getString("name"), rs.getString("org_type"), rs.getString("org_status"),
            rs.getString("owner_name"), rs.getString("mobile"), rs.getString("login_name"),
            rs.getObject("account_status", Integer.class), rs.getString("brand"), rs.getString("province"),
            rs.getString("city"), rs.getString("area"), rs.getString("remark"),
            rs.getObject("create_time", OffsetDateTime.class), rs.getString("account_id"));

    public PageResult<Organization> page(String keyword, String orgType, int pageNo, int pageSize) {
        return page(keyword, orgType, pageNo, pageSize, false);
    }

    public PageResult<Organization> page(String keyword, String orgType, int pageNo, int pageSize, boolean includeDeleted) {
        if (pageNo < 1 || pageNo > 100000 || pageSize < 1 || pageSize > 100) {
            throw new ServiceException(400, "分页参数无效");
        }
        List<Object> args = new ArrayList<>();
        args.add(TenantContextHolder.getRequiredTenantId());
        String filter = includeDeleted ? "" : " AND d.deleted=0";
        if (orgType != null && !orgType.isBlank()) {
            filter += " AND o.org_type=?";
            args.add(orgType.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            if (keyword.length() > 100) throw new ServiceException(400, "搜索内容最多100字");
            filter += " AND LOWER(CONCAT_WS(' ',d.name,a.owner_name,u.nickname,u.mobile,a.login_name,u.username)) LIKE ? ESCAPE '!'";
            args.add("%" + keyword.trim().toLowerCase(Locale.ROOT).replace("!", "!!")
                    .replace("%", "!%").replace("_", "!_") + "%");
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) " + FROM + filter, Long.class, args.toArray());
        args.add(pageSize);
        args.add((pageNo - 1) * pageSize);
        var list = jdbc.query(COLUMNS + FROM + filter + " ORDER BY o.create_time DESC,o.dept_id DESC LIMIT ? OFFSET ?",
                ROW, args.toArray());
        return new PageResult<>(list, total == null ? 0L : total);
    }

    public Detail detail(long orgId) {
        var rows = jdbc.query(COLUMNS + FROM + " AND o.dept_id=?", ROW,
                TenantContextHolder.getRequiredTenantId(), orgId);
        if (rows.isEmpty()) throw new ServiceException(404, "组织不存在或已删除");
        var org = rows.get(0);
        List<String> aliases = org.accountId() == null ? List.of() : jdbc.queryForList(
                "SELECT alias FROM pf_franchise_account_alias WHERE tenant_id=? AND account_id=? ORDER BY alias",
                String.class, TenantContextHolder.getRequiredTenantId(), org.accountId());
        return new Detail(org, aliases);
    }

    public PageResult<Map<String, Object>> members(long orgId, int pageNo, int pageSize) {
        detail(orgId);
        String from = """
                FROM pf_staff_post p JOIN system_users u ON u.id=p.user_id AND u.deleted=0 AND u.tenant_id=?
                LEFT JOIN system_role r ON r.code=p.role_code AND r.tenant_id=u.tenant_id AND r.deleted=0
                LEFT JOIN system_dept t ON t.id=p.team_id AND t.tenant_id=u.tenant_id AND t.deleted=0
                WHERE p.org_id=? AND p.status='ACTIVE'
                """;
        Long total = jdbc.queryForObject("SELECT COUNT(DISTINCT u.id) " + from, Long.class,
                TenantContextHolder.getRequiredTenantId(), orgId);
        var rows = jdbc.queryForList("""
                SELECT u.id::text AS id,u.nickname AS name,u.mobile,u.username AS login_name,u.status,
                    string_agg(DISTINCT COALESCE(r.name,p.role_code),'、') AS roles,
                    string_agg(DISTINCT t.name,'、') AS teams
                """ + from + " GROUP BY u.id,u.nickname,u.mobile,u.username,u.status ORDER BY u.id LIMIT ? OFFSET ?",
                TenantContextHolder.getRequiredTenantId(), orgId, size(pageSize), offset(pageNo, pageSize));
        return new PageResult<>(rows, total == null ? 0L : total);
    }

    public Map<String, Object> pointSummary(long orgId) {
        detail(orgId);
        String month = java.time.YearMonth.now(java.time.ZoneId.of("Asia/Shanghai")).toString();
        return jdbc.queryForMap("""
                SELECT (SELECT balance::text FROM org_point_account WHERE org_id=?) AS balance,
                    (SELECT status FROM org_point_account WHERE org_id=?) AS status,
                    (SELECT COALESCE(-SUM(r.available_delta),0)::text FROM org_point_ledger r
                      WHERE r.org_id=? AND r.type='RESERVE' AND NOT EXISTS
                        (SELECT 1 FROM org_point_ledger t WHERE t.operation_id=r.operation_id AND t.type IN ('SETTLE','RELEASE'))) AS in_flight,
                    (SELECT COALESCE(-SUM(available_delta),0)::text FROM org_point_ledger
                      WHERE org_id=? AND billing_month=? AND type IN ('RESERVE','SETTLE','RELEASE')) AS month_used,
                    (SELECT COALESCE(-SUM(available_delta),0)::text FROM org_point_ledger
                      WHERE org_id=? AND type IN ('RESERVE','SETTLE','RELEASE')) AS total_used
                """, orgId, orgId, orgId, orgId, month, orgId);
    }

    public PageResult<Map<String, Object>> ledger(long orgId, int pageNo, int pageSize) {
        detail(orgId);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM org_point_ledger WHERE org_id=?", Long.class, orgId);
        var rows = jdbc.queryForList("""
                SELECT l.id::text AS id,l.type,l.subject,l.available_delta::text AS delta,l.note,l.create_time,
                       COALESCE(u.nickname,'系统') AS actor_name,l.operation_id::text AS operation_id
                FROM org_point_ledger l LEFT JOIN system_users u ON u.id=l.actor_user_id AND u.tenant_id=? AND u.deleted=0
                WHERE l.org_id=? ORDER BY l.create_time DESC,l.id DESC LIMIT ? OFFSET ?
                """, TenantContextHolder.getRequiredTenantId(), orgId, size(pageSize), offset(pageNo, pageSize));
        return new PageResult<>(rows, total == null ? 0L : total);
    }

    public PageResult<Map<String, Object>> generations(long orgId, int pageNo, int pageSize) {
        detail(orgId);
        String from = """
                FROM (
                    SELECT id::text AS id,'content' AS category,kind AS operation,status,
                        author_user_id AS user_id,failed_reason AS error,create_time,complete_time,
                        pool_reserved_units::text AS reserved_units
                    FROM fr_content_task WHERE company_id=?
                    UNION ALL
                    SELECT command_id::text,'design',operation,state,
                        (actor_json->>'userId')::bigint,error_message,created_at,updated_at,NULL::text
                    FROM fr_design_remote_command WHERE org_id=? AND tenant_id=?
                ) g
                """;
        long tenant = TenantContextHolder.getRequiredTenantId();
        Long total = jdbc.queryForObject("SELECT COUNT(*) " + from, Long.class, orgId, orgId, tenant);
        var rows = jdbc.queryForList("""
                SELECT g.id,g.category,g.operation,g.status,LEFT(g.error,300) AS error,g.create_time,g.complete_time,
                    g.reserved_units,COALESCE(u.nickname,'—') AS actor_name
                """ + from + " LEFT JOIN system_users u ON u.id=g.user_id AND u.tenant_id=? AND u.deleted=0"
                + " ORDER BY g.create_time DESC,g.id DESC LIMIT ? OFFSET ?",
                orgId, orgId, tenant, tenant, size(pageSize), offset(pageNo, pageSize));
        return new PageResult<>(rows, total == null ? 0L : total);
    }

    public PageResult<Map<String, Object>> audits(long orgId, int pageNo, int pageSize) {
        detail(orgId);
        long tenant = TenantContextHolder.getRequiredTenantId();
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM pf_org_admin_audit WHERE tenant_id=? AND org_id=?",
                Long.class, tenant, orgId);
        var rows = jdbc.queryForList("""
                SELECT a.id,a.action,a.reason,a.create_time,COALESCE(u.nickname,'已删除用户') AS actor_name
                FROM pf_org_admin_audit a LEFT JOIN system_users u ON u.id=a.actor_id AND u.tenant_id=a.tenant_id AND u.deleted=0
                WHERE a.tenant_id=? AND a.org_id=? ORDER BY a.create_time DESC,a.id DESC LIMIT ? OFFSET ?
                """, tenant, orgId, size(pageSize), offset(pageNo, pageSize));
        return new PageResult<>(rows, total == null ? 0L : total);
    }

    @Transactional(rollbackFor = Exception.class)
    public Detail lifecycle(long orgId, String action, String confirmedName, String reason, String requestKey, long actorId) {
        if (reason == null || reason.isBlank() || reason.length() > 500 || requestKey == null
                || requestKey.isBlank() || requestKey.length() > 100) throw new ServiceException(400, "请填写操作原因");
        if (action == null || !List.of("close", "reopen", "delete", "restore").contains(action)) throw new ServiceException(400, "操作无效");
        long tenant = TenantContextHolder.getRequiredTenantId();
        orgService.lockOrgForCommand(orgId);
        jdbc.queryForList("SELECT id FROM system_dept WHERE id=? AND tenant_id=? FOR UPDATE", orgId, tenant);
        var org = detail(orgId).organization();
        if (!org.orgName().equals(confirmedName)) throw new ServiceException(400, "组织名称不匹配");
        var replay = jdbc.queryForList("SELECT org_id,actor_id,action,reason FROM pf_org_admin_audit WHERE tenant_id=? AND request_key=?", tenant, requestKey);
        if (!replay.isEmpty()) {
            var old = replay.get(0);
            if (((Number)old.get("org_id")).longValue()!=orgId || ((Number)old.get("actor_id")).longValue()!=actorId
                    || !action.equals(old.get("action")) || !reason.trim().equals(old.get("reason"))) {
                throw new ServiceException(409, "该请求已用于其他操作");
            }
            return detail(orgId);
        }
        if ("close".equals(action) || "delete".equals(action)) {
            Long pending = jdbc.queryForObject("SELECT COUNT(*) FROM org_point_ledger r WHERE r.org_id=? AND r.type='RESERVE' AND NOT EXISTS (SELECT 1 FROM org_point_ledger t WHERE t.operation_id=r.operation_id AND t.type IN ('SETTLE','RELEASE'))", Long.class, orgId);
            if (pending != null && pending > 0) throw new ServiceException(409, "有生成任务尚未结算，请完成后操作");
        }
        switch (action) {
            case "close" -> {
                if (!"ACTIVE".equals(org.status())) throw new ServiceException(409, "组织当前不可关闭");
                orgService.setOrgActive(orgId, false);
                jdbc.update("UPDATE system_dept SET status=1,update_time=now() WHERE id=? AND tenant_id=?", orgId, tenant);
            }
            case "reopen" -> {
                if (!"DISABLED".equals(org.status())) throw new ServiceException(409, "组织当前不可启用");
                jdbc.update("UPDATE system_dept SET status=0,update_time=now() WHERE id=? AND tenant_id=?", orgId, tenant);
                orgService.setOrgActive(orgId, true);
            }
            case "delete" -> {
                if (!"DISABLED".equals(org.status())) throw new ServiceException(409, "请先关闭组织");
                Long balance = jdbc.queryForObject("SELECT COALESCE((SELECT balance FROM org_point_account WHERE org_id=?),0)", Long.class, orgId);
                Long adjustments = jdbc.queryForObject("SELECT COUNT(*) FROM org_point_hq_adjustment WHERE org_id=? AND status='SUBMITTED'", Long.class, orgId);
                if (balance != null && balance != 0 || adjustments != null && adjustments > 0) throw new ServiceException(409, "请先结清积分余额和待审核申请");
                orgService.setOrgActive(orgId, false);
                jdbc.update("UPDATE system_dept SET deleted=1,status=1,update_time=now() WHERE id=? AND tenant_id=?", orgId, tenant);
            }
            case "restore" -> {
                if (!"DELETED".equals(org.status())) throw new ServiceException(409, "组织当前不可恢复");
                jdbc.update("UPDATE system_dept SET deleted=0,status=1,update_time=now() WHERE id=? AND tenant_id=?", orgId, tenant);
                orgService.setOrgActive(orgId, false);
            }
        }
        jdbc.update("INSERT INTO pf_org_admin_audit(id,tenant_id,org_id,actor_id,action,reason,request_key) VALUES(?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), tenant, orgId, actorId, action, reason.trim(), requestKey);
        return detail(orgId);
    }

    private static int size(int size) {
        if (size<1 || size>100) throw new ServiceException(400,"分页参数无效");
        return size;
    }
    private static int offset(int page, int size) {
        if (page<1 || page>100000) throw new ServiceException(400,"分页参数无效");
        return (page-1)*size(size);
    }
}
