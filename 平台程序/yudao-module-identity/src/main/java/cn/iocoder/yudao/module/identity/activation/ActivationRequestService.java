package cn.iocoder.yudao.module.identity.activation;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.identity.enums.ErrorCodeConstants.ACTIVATION_REQUEST_ALREADY_HANDLED;
import static cn.iocoder.yudao.module.identity.enums.ErrorCodeConstants.ACTIVATION_REQUEST_ALREADY_ACTIVE;
import static cn.iocoder.yudao.module.identity.enums.ErrorCodeConstants.ACTIVATION_REQUEST_ALREADY_PENDING;
import static cn.iocoder.yudao.module.identity.enums.ErrorCodeConstants.ACTIVATION_REQUEST_NOT_FOUND;

/**
 * 开通申请（design_activation_request）：无授权码自助申请-开通链路
 *
 * 合同（2026-11-04 产品决策：取消审批环节，提交即开通）：
 * - 提交：登录账号即可申请；单事务内落 APPROVED 终态申请（handled_by=系统自动）并幂等插入
 *   ACTIVE grant（与授权码兑换同一张授权表）；已有 ACTIVE grant 报 ALREADY_ACTIVE，无需申请；
 * - 人工通过/驳回仅服务上线前的存量 PENDING 单：通过=锁行、校验 PENDING、幂等开通
 *   （账号已有授权只落 APPROVED 终态），驳回留原因、用户可重新提交（即自动开通）；
 * - 充值设计点不在本服务：identity 与 commerce 互不依赖（架构 §3.2 端口解耦），
 *   管理端通过人工调点（MANUAL_CREDIT）另行入账。
 */
@Slf4j
@Service
public class ActivationRequestService {

    public record ActivationRequestRow(long id, long accountId, String companyName, String contactName,
                                       String contactPhone, String status, String rejectReason, String note,
                                       String handledBy, Instant requestedAt, Instant handledAt) {
    }

    public record DecisionResult(long accountId, boolean granted) {
    }

    private final JdbcTemplate jdbcTemplate;

    private final TransactionTemplate txTemplate;

    public ActivationRequestService(DataSource dataSource, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 提交开通申请，进入待审批（PENDING）：不再自动开通，须由管理员在后台「开通申请」页
     * 点通过后才写入 ACTIVE 授权（2026-11-10 恢复审批制）。
     * 同账号已有 ACTIVE grant 报 ALREADY_ACTIVE；已有待审批单报 ALREADY_PENDING。
     */
    public ActivationRequestRow submit(String appid, String openid, long accountId,
                                       String companyName, String contactName, String contactPhone) {
        var activeAccount = jdbcTemplate.queryForList("SELECT status FROM account WHERE id=? AND deleted=FALSE",
                String.class, accountId);
        if (activeAccount.isEmpty() || !"ACTIVE".equals(activeAccount.get(0))) {
            throw new ServiceException(403, "账号已停用或关闭");
        }
        if (hasActiveGrant(accountId)) {
            throw exception(ACTIVATION_REQUEST_ALREADY_ACTIVE);
        }
        if (findLatestByStatus(accountId, "PENDING").isPresent()) {
            throw exception(ACTIVATION_REQUEST_ALREADY_PENDING);
        }
        ActivationRequestRow row = insertPending(appid, openid, accountId, companyName, contactName, contactPhone);
        log.info("[submit][account={} 提交开通申请待审批 request={} company={}]", accountId, row.id(), companyName);
        return row;
    }

    private ActivationRequestRow insertPending(String appid, String openid, long accountId,
                                               String companyName, String contactName, String contactPhone) {
        long id = IdWorker.getId();
        jdbcTemplate.update(
                "INSERT INTO design_activation_request (id, account_id, appid, openid, "
                        + "company_name, contact_name, contact_phone, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING')",
                id, accountId, appid, openid, companyName, contactName, contactPhone);
        return findById(id).orElseThrow();
    }

    /** 本人最新一条申请（REJECTED 后重新提交则返回新行；从未申请返回 empty） */
    public Optional<ActivationRequestRow> latestOf(long accountId) {
        return findLatestByStatus(accountId, null);
    }

    /** 按编号查询（审批后回显终态用） */
    public Optional<ActivationRequestRow> findById(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, account_id, company_name, contact_name, contact_phone, status, "
                        + "reject_reason, note, handled_by, create_time, handled_at "
                        + "FROM design_activation_request WHERE id = ? AND deleted = FALSE", id);
        return queryOne(rows);
    }

    private Optional<ActivationRequestRow> findLatestByStatus(long accountId, String status) {
        boolean filtered = status != null && !status.isBlank();
        String sql = "SELECT id, account_id, company_name, contact_name, contact_phone, status, "
                + "reject_reason, note, handled_by, create_time, handled_at "
                + "FROM design_activation_request WHERE account_id = ? AND deleted = FALSE "
                + (filtered ? "AND status = ? " : "")
                + "ORDER BY id DESC LIMIT 1";
        Object[] args = filtered ? new Object[]{accountId, status} : new Object[]{accountId};
        return queryOne(jdbcTemplate.queryForList(sql, args));
    }

    public long count(String status) {
        boolean filtered = status != null && !status.isBlank();
        Long value = filtered
                ? jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM design_activation_request WHERE deleted = FALSE AND status = ?",
                        Long.class, status)
                : jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM design_activation_request WHERE deleted = FALSE", Long.class);
        return value == null ? 0 : value;
    }

    public Map<String, Long> statusCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("PENDING", count("PENDING"));
        counts.put("APPROVED", count("APPROVED"));
        counts.put("REJECTED", count("REJECTED"));
        return counts;
    }

    public List<ActivationRequestRow> page(String status, int pageNo, int pageSize) {
        String sql = "SELECT id, account_id, company_name, contact_name, contact_phone, status, "
                + "reject_reason, note, handled_by, create_time, handled_at "
                + "FROM design_activation_request WHERE deleted = FALSE "
                + (status == null || status.isBlank() ? "" : "AND status = ? ")
                + "ORDER BY id DESC LIMIT " + Math.min(pageSize, 100) + " OFFSET " + (long) (pageNo - 1) * pageSize;
        Object[] args = status == null || status.isBlank() ? new Object[0] : new Object[]{status};
        return jdbcTemplate.queryForList(sql, args).stream().map(ActivationRequestService::toRow).toList();
    }

    /** 人工通过（仅存量 PENDING 单）：开通使用权；grant 幂等：账号已有 ACTIVE 授权时不重复插，只落 APPROVED 终态 */
    public DecisionResult approve(long requestId, String operator, String note) {
        return txTemplate.execute(status -> decideInTx(requestId, operator, "APPROVED",
                note == null ? null : note.trim(), true));
    }

    /** 驳回：留原因，用户可重新提交 */
    public DecisionResult reject(long requestId, String operator, String reason) {
        return txTemplate.execute(status -> decideInTx(requestId, operator, "REJECTED",
                reason == null ? null : reason.trim(), false));
    }

    private DecisionResult decideInTx(long requestId, String operator, String targetStatus,
                                      String comment, boolean grantAccess) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, account_id, status FROM design_activation_request "
                        + "WHERE id = ? AND deleted = FALSE FOR UPDATE", requestId);
        if (rows.isEmpty()) {
            throw exception(ACTIVATION_REQUEST_NOT_FOUND);
        }
        Map<String, Object> request = rows.get(0);
        long accountId = ((Number) request.get("account_id")).longValue();
        if (!"PENDING".equals(request.get("status"))) {
            throw exception(ACTIVATION_REQUEST_ALREADY_HANDLED);
        }
        boolean granted = false;
        if (grantAccess) {
            // 审批期间账号可能已通过兑换码激活：已有 ACTIVE 授权只落终态，不重复插
            granted = !hasActiveGrant(accountId);
            if (granted) {
                jdbcTemplate.update(
                        "INSERT INTO design_access_grant (id, account_id, status) VALUES (?, ?, 'ACTIVE') "
                                + "ON CONFLICT (account_id) WHERE status = 'ACTIVE' DO NOTHING",
                        IdWorker.getId(), accountId);
            }
        }
        // 驳回原因与通过备注分列落库，语义不混用
        String commentColumn = "REJECTED".equals(targetStatus) ? "reject_reason" : "note";
        jdbcTemplate.update(
                "UPDATE design_activation_request SET status = ?, " + commentColumn + " = ?, "
                        + "handled_by = ?, handled_at = now(), update_time = now() WHERE id = ?",
                targetStatus, comment, operator, requestId);
        log.info("[decide][request={} account={} {} by={} granted={}]", requestId, accountId, targetStatus, operator, granted);
        return new DecisionResult(accountId, granted);
    }

    private boolean hasActiveGrant(long accountId) {
        List<Integer> rows = jdbcTemplate.query(
                "SELECT 1 FROM design_access_grant WHERE account_id = ? AND status = 'ACTIVE' AND deleted = FALSE",
                (rs, i) -> 1, accountId);
        return !rows.isEmpty();
    }

    private static Optional<ActivationRequestRow> queryOne(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toRow(rows.get(0)));
    }

    private static ActivationRequestRow toRow(Map<String, Object> row) {
        Timestamp requestedAt = (Timestamp) row.get("create_time");
        Timestamp handledAt = (Timestamp) row.get("handled_at");
        return new ActivationRequestRow(
                ((Number) row.get("id")).longValue(),
                ((Number) row.get("account_id")).longValue(),
                (String) row.get("company_name"),
                (String) row.get("contact_name"),
                (String) row.get("contact_phone"),
                (String) row.get("status"),
                (String) row.get("reject_reason"),
                (String) row.get("note"),
                (String) row.get("handled_by"),
                requestedAt == null ? null : requestedAt.toInstant(),
                handledAt == null ? null : handledAt.toInstant());
    }

}
