package cn.iocoder.yudao.module.franchise.org.point;

import cn.iocoder.yudao.module.franchise.api.OrgPointPort;
import cn.iocoder.yudao.module.franchise.org.FranchiseRoles;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.RESOURCE_FORBIDDEN;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;

/** 公司积分池的 B 端访问边界；组织和操作者只取当前有效任职，不接受请求参数覆盖。 */
@Service
public class CompanyPointAccess {

    private static final int MAX_SUBJECTS = 32;
    private static final int MAX_SUBJECT_LENGTH = 64;

    private final OrgPointPort points;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public CompanyPointAccess(OrgPointPort points, JdbcTemplate jdbc,
                              PlatformTransactionManager transactionManager) {
        this.points = points;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** 老板开通零余额积分池；赠点、调点仍由总部专用运维能力处理。 */
    public OrgPointPort.PoolSummary activate(PostSnapshot post) {
        return tx.execute(status -> {
            PostSnapshot active = lockManager(post);
            return points.ensurePool(active.orgId());
        });
    }

    /** 所有当前有效员工可看本公司余额和当月总用量。未开通时返回 null。 */
    public OrgPointPort.PoolSummary summary(PostSnapshot post) {
        return tx.execute(status -> {
            PostSnapshot active = lockActive(post);
            return points.summary(active.orgId());
        });
    }

    /** 员工仅能查看本人用量；固定月度额度字段由积分端口固定返回 null。 */
    public OrgPointPort.MyUsage myUsage(PostSnapshot post, String month, int page, int size) {
        requirePage(page, size);
        return tx.execute(status -> {
            PostSnapshot active = lockActive(post);
            return points.myUsage(active.orgId(), active.userId(), month, page, size);
        });
    }

    public List<OrgPointPort.LedgerEntry> ledger(PostSnapshot post, Long actorUserId, String subject,
                                                 String month, int page, int size) {
        requirePage(page, size);
        return tx.execute(status -> {
            PostSnapshot active = lockManager(post);
            return points.ledger(active.orgId(), actorUserId, subject, month, page, size);
        });
    }

    public List<OrgPointPort.OperationView> operations(PostSnapshot post, int page, int size) {
        requirePage(page, size);
        return tx.execute(status -> {
            PostSnapshot active = lockManager(post);
            return points.operations(active.orgId(), page, size);
        });
    }

    public List<OrgPointPort.MemberPermission> memberPermissions(PostSnapshot post) {
        return tx.execute(status -> points.memberPermissions(lockManager(post).orgId()));
    }

    public void setMemberPermission(PostSnapshot post, long targetUserId, List<String> allowedSubjects) {
        List<String> subjects = requireSubjects(allowedSubjects);
        tx.executeWithoutResult(status -> {
            lockUsers(post == null ? null : post.userId(), targetUserId);
            PostSnapshot active = lockManagerAfterUserLock(post);
            lockActiveMember(active.orgId(), targetUserId);
            for (String subject : subjects) {
                if (!points.isKnownSubject(subject)) {
                    throw new cn.iocoder.yudao.framework.common.exception.ServiceException(400, "业务科目无效");
                }
                // 目录校验与动态价格解析分开：价格配置/数据库故障必须保留原异常和日志。
                points.quoteCredits(subject, 1);
            }
            points.setMemberPermission(active.orgId(), targetUserId, subjects, active.userId());
        });
    }

    public void clearMemberPermission(PostSnapshot post, long targetUserId) {
        tx.executeWithoutResult(status -> {
            lockUsers(post == null ? null : post.userId(), targetUserId);
            PostSnapshot active = lockManagerAfterUserLock(post);
            lockActiveMember(active.orgId(), targetUserId);
            points.clearMemberPermission(active.orgId(), targetUserId, active.userId());
        });
    }

    private PostSnapshot lockManager(PostSnapshot post) {
        lockUsers(post == null ? null : post.userId());
        return lockManagerAfterUserLock(post);
    }

    private PostSnapshot lockManagerAfterUserLock(PostSnapshot post) {
        PostSnapshot active = lockActiveAfterUserLock(post);
        if (!FranchiseRoles.isBoss(active)) {
            throw exception(RESOURCE_FORBIDDEN);
        }
        return active;
    }

    private PostSnapshot lockActive(PostSnapshot post) {
        lockUsers(post == null ? null : post.userId());
        return lockActiveAfterUserLock(post);
    }

    private PostSnapshot lockActiveAfterUserLock(PostSnapshot post) {
        if (post == null) {
            throw exception(RESOURCE_FORBIDDEN);
        }
        long tenantId = TenantContextHolder.getRequiredTenantId();
        List<String> roles = jdbc.queryForList("SELECT p.role_code FROM pf_staff_post p "
                        + "JOIN system_users u ON u.id=p.user_id "
                        + "JOIN pf_post_template t ON t.role_code=p.role_code "
                        + "WHERE p.id=? AND p.user_id=? AND p.org_id=? AND p.role_code=? "
                        + "AND p.status='ACTIVE' AND u.status=0 AND u.deleted=0 AND u.tenant_id=? "
                        + "FOR SHARE OF p,u",
                String.class, post.postId(), post.userId(), post.orgId(), post.roleCode(), tenantId);
        if (roles.size() != 1 || !roles.get(0).equals(post.roleCode())) {
            throw exception(RESOURCE_FORBIDDEN);
        }
        List<String> orgs = jdbc.queryForList("SELECT o.org_type FROM pf_org_unit o "
                        + "JOIN system_dept d ON d.id=o.dept_id "
                        + "WHERE o.dept_id=? AND o.org_id=o.dept_id AND o.kind='ORG' "
                        + "AND o.status='ACTIVE' AND d.status=0 AND d.deleted=0 AND d.tenant_id=? "
                        + "FOR SHARE OF o,d",
                String.class, post.orgId(), tenantId);
        if (orgs.size() != 1 || !java.util.Objects.equals(orgs.get(0), post.orgType())) {
            throw exception(RESOURCE_FORBIDDEN);
        }
        return post;
    }

    private void lockActiveMember(long orgId, long userId) {
        long tenantId = TenantContextHolder.getRequiredTenantId();
        List<Long> rows = jdbc.queryForList("SELECT p.id FROM pf_staff_post p "
                        + "JOIN system_users u ON u.id=p.user_id "
                        + "JOIN pf_post_template t ON t.role_code=p.role_code "
                        + "WHERE p.org_id=? AND p.user_id=? AND p.status='ACTIVE' "
                        + "AND u.status=0 AND u.deleted=0 AND u.tenant_id=? FOR SHARE OF p,u",
                Long.class, orgId, userId, tenantId);
        if (rows.isEmpty()) {
            throw exception(RESOURCE_FORBIDDEN);
        }
    }

    private static List<String> requireSubjects(List<String> subjects) {
        if (subjects == null || subjects.size() > MAX_SUBJECTS) {
            throw exception(RESOURCE_FORBIDDEN);
        }
        if (subjects.stream().anyMatch(subject -> subject == null || subject.isBlank()
                || subject.length() > MAX_SUBJECT_LENGTH)) {
            throw exception(RESOURCE_FORBIDDEN);
        }
        return subjects.stream().map(String::trim).distinct().sorted().toList();
    }

    private void lockUsers(Long... userIds) {
        if (userIds == null || java.util.Arrays.stream(userIds).anyMatch(java.util.Objects::isNull)) {
            throw exception(RESOURCE_FORBIDDEN);
        }
        java.util.Arrays.stream(userIds).distinct().sorted()
                .forEach(userId -> jdbc.queryForObject(
                        "SELECT 1 FROM (SELECT pg_advisory_xact_lock(?)) locked", Integer.class, userId));
    }

    private static void requirePage(int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw exception(RESOURCE_FORBIDDEN);
        }
    }
}
