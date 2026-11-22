package cn.iocoder.yudao.module.franchise.commission;

import cn.iocoder.yudao.module.franchise.org.FranchiseRoles;
import cn.iocoder.yudao.module.franchise.commission.enums.FranchiseCommissionErrorCodes;
import cn.iocoder.yudao.module.platform.org.DepartureHandoverPort;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.platform.org.enums.PlatformOrgErrorCodes.*;

/** 客户归属随 CRM 转移；这里只承接最新待接收移交包的当前工程接收责任。 */
@Component
public class CommissionDepartureHandoverPort implements DepartureHandoverPort {

    private static final String CURRENT = """
            h.company_id = :org AND c.company_id = :org AND h.manager_user_id = :from
            AND h.status = 'PENDING' AND c.status = 'HANDOVER_SUBMITTED'
            AND h.version = c.handover_version
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final StaffPostService staffPosts;

    public CommissionDepartureHandoverPort(JdbcTemplate jdbc, StaffPostService staffPosts) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
        this.staffPosts = staffPosts;
    }

    @Override public String domain() { return "franchise-commission"; }
    @Override public String label() { return "设计委托"; }

    @Override
    public long scanInFlight(long fromUserId, long orgId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM fr_commission_handover h "
                + "JOIN fr_commission c ON c.id = h.commission_id WHERE " + CURRENT,
                args(fromUserId, orgId), Long.class);
    }

    @Override
    public void validateReceiver(long toUserId, long orgId) {
        boolean valid = staffPosts.listActivePosts(toUserId).stream()
                .filter(p -> p.getOrgId() == orgId)
                .filter(p -> FranchiseRoles.ENGINEER.equals(p.getRoleCode())
                        || FranchiseRoles.PROJECT_MANAGER.equals(p.getRoleCode()))
                .anyMatch(p -> staffPosts.findActivePost(toUserId, p.getId()).isPresent());
        if (!valid) throw exception(HANDOVER_TARGET_ROLE_MISMATCH);
    }

    @Override
    public int bulkTransfer(long fromUserId, long toUserId, long orgId, long operatorUserId) {
        var params = args(fromUserId, orgId).addValue("to", toUserId);
        try {
            // 与接收/退回的 h → c 写入顺序一致；有并发办理时回滚重试，不与其它路径互等。
            List<Long> ids = jdbc.queryForList("SELECT h.id FROM fr_commission_handover h "
                    + "JOIN fr_commission c ON c.id = h.commission_id WHERE " + CURRENT
                    + " ORDER BY h.id FOR UPDATE OF h NOWAIT", params, Long.class);
            if (ids.isEmpty()) return 0;
            params.addValue("ids", ids);
            jdbc.queryForList("SELECT c.id FROM fr_commission c JOIN fr_commission_handover h "
                    + "ON h.commission_id = c.id WHERE h.id IN (:ids) AND c.company_id = :org "
                    + "ORDER BY c.id FOR UPDATE OF c NOWAIT", params, Long.class);
            long ownSubmissions = jdbc.queryForObject("SELECT COUNT(*) FROM fr_commission_handover h "
                    + "JOIN fr_commission c ON c.id = h.commission_id WHERE h.id IN (:ids) AND "
                    + CURRENT + " AND h.submitted_by_user_id = :to", params, Long.class);
            if (ownSubmissions > 0) {
                // 与原始移交相同：兼任工程岗的提交人也不能接收自己提交的包。
                throw exception(FranchiseCommissionErrorCodes.HANDOVER_TARGET_INVALID);
            }
            // 两行已锁定后重核当前版本；revision 推进使锁前打开的接收表单不能覆盖新责任。
            return jdbc.update("""
                    WITH moved AS (
                        UPDATE fr_commission_handover h SET manager_user_id = :to
                        FROM fr_commission c
                        WHERE c.id = h.commission_id AND h.id IN (:ids) AND
                    """ + CURRENT + """
                        RETURNING h.commission_id
                    )
                    UPDATE fr_commission c SET revision = c.revision + 1, update_time = now()
                    WHERE c.company_id = :org AND c.id IN (SELECT commission_id FROM moved)
                    """, params);
        } catch (PessimisticLockingFailureException conflict) {
            throw exception(HANDOVER_TRANSFER_INCOMPLETE);
        }
    }

    private static MapSqlParameterSource args(long fromUserId, long orgId) {
        return new MapSqlParameterSource("org", orgId).addValue("from", fromUserId);
    }
}
