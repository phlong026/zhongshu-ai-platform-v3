package cn.iocoder.yudao.module.identity.mapping;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 旧设计账号 → 员工 → 组织 身份映射（24 号任务书 B-15）。
 *
 * <p>背景（R1 评审）：`design_access_code_batch` **没有 org_id**，设计会话用 `account_id`，
 * 员工体系用 `system_users.id`，是两套身份。本服务只产出**候选映射**与**未匹配过渡清单**，
 * 由人工确认（CONFIRMED）后才可作为 B-14 退役 / B-16 迁移的依据；未匹配者保留 grant 只读续期，不自动断。
 *
 * <p>匹配键：`access_code_redemption.contact_phone` → `system_users.mobile`（唯一可靠桥）
 * → `pf_staff_post(user_id, org_id, status='ACTIVE')`。多岗位时取最早任职并标注歧义。
 */
@Slf4j
@Service
public class DesignIdentityMappingService {

    private final JdbcTemplate jdbc;

    public DesignIdentityMappingService(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public record MappingRow(long accountId, String contactPhone, Long batchId, String batchIssuedBy,
                             String batchPurposeNote, Long matchedUserId, Long matchedOrgId,
                             String matchType, String status, String note) {
    }

    public record GenerateResult(int scanned, int created, int matched, int unmatched, int skipped) {
    }

    public record MappingReport(int total, int confirmed, int candidate, int unmatched, int rejected,
                                Map<String, Integer> byMatchType, List<MappingRow> unmatchedRows) {
    }

    /**
     * 扫描已兑换记录并生成候选映射（幂等：已存在行跳过，不覆盖人工结论）。
     */
    public GenerateResult generate() {
        boolean hasStaffTable = exists("system_users");
        // P1①：全集 = 已兑换账号 ∪ 有效授予账号（人工审批开通不产生 redemption）；
        // P2①：手机号按账号聚合——规范化后去重，多号冲突/空串一律不作为匹配证据。
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT a.account_id, "
                        + "  (SELECT count(DISTINCT regexp_replace(trim(r0.contact_phone), '[^0-9]', '', 'g')) "
                        + "     FROM access_code_redemption r0 WHERE r0.account_id = a.account_id "
                        + "     AND r0.deleted = FALSE AND nullif(trim(r0.contact_phone),'') IS NOT NULL) AS phone_count, "
                        + "  (SELECT min(regexp_replace(trim(r1.contact_phone), '[^0-9]', '', 'g')) "
                        + "     FROM access_code_redemption r1 WHERE r1.account_id = a.account_id "
                        + "     AND r1.deleted = FALSE AND nullif(trim(r1.contact_phone),'') IS NOT NULL) AS phone, "
                        + "  (SELECT min(c.batch_id) FROM access_code_redemption r2 "
                        + "     JOIN design_access_code c ON c.id = r2.code_id AND c.deleted = FALSE "
                        + "     WHERE r2.account_id = a.account_id AND r2.deleted = FALSE) AS batch_id, "
                        + "  (SELECT b.issued_by FROM access_code_redemption r3 "
                        + "     JOIN design_access_code c3 ON c3.id = r3.code_id AND c3.deleted = FALSE "
                        + "     JOIN design_access_code_batch b ON b.id = c3.batch_id AND b.deleted = FALSE "
                        + "     WHERE r3.account_id = a.account_id AND r3.deleted = FALSE "
                        + "     ORDER BY r3.id LIMIT 1) AS issued_by, "
                        + "  (SELECT b.purpose_note FROM access_code_redemption r4 "
                        + "     JOIN design_access_code c4 ON c4.id = r4.code_id AND c4.deleted = FALSE "
                        + "     JOIN design_access_code_batch b ON b.id = c4.batch_id AND b.deleted = FALSE "
                        + "     WHERE r4.account_id = a.account_id AND r4.deleted = FALSE "
                        + "     ORDER BY r4.id LIMIT 1) AS purpose_note "
                        + "FROM (SELECT account_id FROM access_code_redemption WHERE deleted = FALSE "
                        + "      UNION SELECT account_id FROM design_access_grant "
                        + "           WHERE status = 'ACTIVE' AND deleted = FALSE) a "
                        + "ORDER BY a.account_id");
        int created = 0;
        int matched = 0;
        int unmatched = 0;
        int skipped = 0;
        for (Map<String, Object> row : rows) {
            long accountId = ((Number) row.get("account_id")).longValue();
            if (existsMapping(accountId)) {
                skipped++;
                continue;
            }
            int phoneCount = intOf(row.get("phone_count"));
            String rawPhone = row.get("phone") == null ? null : String.valueOf(row.get("phone"));
            String phone = normalizeMobile(rawPhone);
            Long userId = null;
            Long orgId = null;
            String note;
            if (phoneCount > 1) {
                note = "同一账号存在多个不同手机号，冲突待人工核对";
            } else if (phone == null) {
                note = row.get("batch_id") == null
                        ? "无兑换记录（人工审批开通）且缺有效手机号，无法自动匹配"
                        : "缺少有效手机号（空或非 11 位），无法自动匹配";
            } else if (!hasStaffTable) {
                note = "缺少员工表，无法匹配";
            } else {
                userId = findUserByMobile(phone);
                orgId = userId == null ? null : findActiveOrg(userId);
                note = buildNote(phone, userId, orgId);
            }
            String matchType = userId == null ? "NONE" : "PHONE";
            String status = userId == null || orgId == null ? "UNMATCHED" : "CANDIDATE";
            int inserted = jdbc.update("INSERT INTO design_identity_mapping (id, account_id, contact_phone, "
                            + "batch_id, batch_issued_by, batch_purpose_note, matched_user_id, matched_org_id, "
                            + "match_type, status, note) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                            + "ON CONFLICT (account_id) DO NOTHING",
                    IdWorker.getId(), accountId, phone,
                    row.get("batch_id") == null ? null : ((Number) row.get("batch_id")).longValue(),
                    row.get("issued_by"), row.get("purpose_note"),
                    userId, orgId, matchType, status, note);
            if (inserted == 0) {
                skipped++; // 并发/已存在：不计入 created（P2②）
                continue;
            }
            created++;
            if ("CANDIDATE".equals(status)) {
                matched++;
            } else {
                unmatched++;
            }
        }
        log.info("[generate][映射生成] scanned={} created={} matched={} unmatched={} skipped={}",
                rows.size(), created, matched, unmatched, skipped);
        return new GenerateResult(rows.size(), created, matched, unmatched, skipped);
    }

    /** 人工确认（把候选或未匹配行钉死到指定员工/组织）。 */
    public void confirm(long accountId, long userId, long orgId, String note, String operator) {
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("确认必须填写依据说明");
        }
        // P1②：确认前校验员工存续且在目标组织有有效任职（确认是 B-16 迁移的唯一依据）
        Integer userOk = jdbc.queryForObject(
                "SELECT count(*) FROM system_users WHERE id = ? AND deleted = 0", Integer.class, userId);
        if (userOk == null || userOk == 0) {
            throw new IllegalArgumentException("员工不存在或已删除：" + userId);
        }
        Integer postOk = jdbc.queryForObject(
                "SELECT count(*) FROM pf_staff_post WHERE user_id = ? AND org_id = ? AND status = 'ACTIVE'",
                Integer.class, userId, orgId);
        if (postOk == null || postOk == 0) {
            throw new IllegalArgumentException("员工在该组织无有效任职：" + userId + "@" + orgId);
        }
        // P2③：仅未处置状态可确认（CAS），已确认/已驳回需走显式纠错流程
        int updated = jdbc.update("UPDATE design_identity_mapping SET matched_user_id = ?, "
                        + "matched_org_id = ?, match_type = 'MANUAL', status = 'CONFIRMED', "
                        + "note = ?, operator = ?, update_time = now() "
                        + "WHERE account_id = ? AND status IN ('CANDIDATE','UNMATCHED')",
                userId, orgId, note, operator, accountId);
        if (updated == 0) {
            throw new IllegalStateException("当前状态不允许确认（不存在或已处置）：" + accountId);
        }
    }

    /** 人工驳回（确认该账号无法归属，进入过渡清单，授权只读续期）。 */
    public void reject(long accountId, String note, String operator) {
        int updated = jdbc.update("UPDATE design_identity_mapping SET status = 'REJECTED', "
                        + "note = ?, operator = ?, update_time = now() "
                        + "WHERE account_id = ? AND status IN ('CANDIDATE','UNMATCHED')",
                note, operator, accountId);
        if (updated == 0) {
            throw new IllegalStateException("当前状态不允许驳回（不存在或已处置）：" + accountId);
        }
    }

    public List<MappingRow> list(String status, Long orgId, int page, int size) {
        StringBuilder sql = new StringBuilder("SELECT * FROM design_identity_mapping WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (orgId != null) {
            sql.append(" AND matched_org_id = ?");
            args.add(orgId);
        }
        sql.append(" ORDER BY account_id LIMIT ? OFFSET ?");
        args.add(Math.min(Math.max(size, 1), 200));
        args.add((long) Math.max(page, 1) * Math.min(Math.max(size, 1), 200)
                - Math.min(Math.max(size, 1), 200));
        return jdbc.query(sql.toString(), (rs, i) -> new MappingRow(
                rs.getLong("account_id"), rs.getString("contact_phone"),
                rs.getObject("batch_id", Long.class), rs.getString("batch_issued_by"),
                rs.getString("batch_purpose_note"),
                rs.getObject("matched_user_id", Long.class), rs.getObject("matched_org_id", Long.class),
                rs.getString("match_type"), rs.getString("status"), rs.getString("note")),
                args.toArray());
    }

    /** 验收报告（任务书 B-15：映射报告 + 未匹配清单）。 */
    public MappingReport report() {
        Map<String, Object> counts = jdbc.queryForMap(
                "SELECT count(*) AS total, "
                        + "count(*) FILTER (WHERE status='CONFIRMED') AS confirmed, "
                        + "count(*) FILTER (WHERE status='CANDIDATE') AS candidate, "
                        + "count(*) FILTER (WHERE status='UNMATCHED') AS unmatched, "
                        + "count(*) FILTER (WHERE status='REJECTED') AS rejected "
                        + "FROM design_identity_mapping");
        Map<String, Integer> byType = new LinkedHashMap<>();
        jdbc.query("SELECT match_type, count(*) AS c FROM design_identity_mapping GROUP BY match_type",
                rs -> {
                    byType.put(rs.getString("match_type"), rs.getInt("c"));
                });
        List<MappingRow> unmatchedRows = jdbc.query(
                "SELECT * FROM design_identity_mapping WHERE status IN ('UNMATCHED','REJECTED') "
                        + "ORDER BY account_id LIMIT 500",
                (rs, i) -> new MappingRow(rs.getLong("account_id"), rs.getString("contact_phone"),
                        rs.getObject("batch_id", Long.class), rs.getString("batch_issued_by"),
                        rs.getString("batch_purpose_note"),
                        rs.getObject("matched_user_id", Long.class),
                        rs.getObject("matched_org_id", Long.class), rs.getString("match_type"),
                        rs.getString("status"), rs.getString("note")));
        return new MappingReport(intOf(counts.get("total")), intOf(counts.get("confirmed")),
                intOf(counts.get("candidate")), intOf(counts.get("unmatched")),
                intOf(counts.get("rejected")), byType, unmatchedRows);
    }

    // ---------------- 内部 ----------------

    private boolean existsMapping(long accountId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM design_identity_mapping WHERE account_id = ?", Integer.class, accountId);
        return n != null && n > 0;
    }

    private boolean exists(String table) {
        Boolean present = jdbc.queryForObject("SELECT to_regclass('public." + table + "') IS NOT NULL",
                Boolean.class);
        return Boolean.TRUE.equals(present);
    }

    private Long findUserByMobile(String phone) {
        List<Long> ids = jdbc.query(
                "SELECT id FROM system_users WHERE mobile = ? AND deleted = 0 ORDER BY id LIMIT 2",
                (rs, i) -> rs.getLong(1), phone);
        return ids.size() == 1 ? ids.get(0) : null; // 无匹配或多匹配一律留待人工
    }

    private Long findActiveOrg(long userId) {
        List<Long> orgs = jdbc.query(
                "SELECT org_id FROM pf_staff_post WHERE user_id = ? AND status = 'ACTIVE' "
                        + "ORDER BY create_time, id LIMIT 2",
                (rs, i) -> rs.getLong(1), userId);
        return orgs.size() == 1 ? orgs.get(0) : null; // 多岗位歧义交人工
    }

    private static String buildNote(String phone, Long userId, Long orgId) {
        if (phone == null || phone.isBlank()) {
            return "缺少联系手机号，无法自动匹配";
        }
        if (userId == null) {
            return "手机号未匹配到唯一员工账号";
        }
        if (orgId == null) {
            return "员工存在但无唯一有效任职（多岗或无岗），需人工确认";
        }
        return "手机号唯一匹配员工与有效任职，待人工确认";
    }

    /** 手机号归一：仅保留数字且必须为 11 位大陆手机号；否则视为无有效证据。 */
    private static String normalizeMobile(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("[^0-9]", "");
        return digits.matches("1\\d{10}") ? digits : null;
    }

    private static int intOf(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }
}
