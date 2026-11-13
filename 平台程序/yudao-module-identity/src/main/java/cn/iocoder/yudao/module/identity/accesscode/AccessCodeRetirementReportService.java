package cn.iocoder.yudao.module.identity.accesscode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/**
 * 激活码退役清单（24 号 B-14）：只读统计，供运营确认"未兑换码作废范围"与既有授权连续性。
 * 不修改任何码/授权状态。
 */
@Service
public class AccessCodeRetirementReportService {

    private final JdbcTemplate jdbc;

    public AccessCodeRetirementReportService(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public record BatchBreakdown(long batchId, String issuedBy, String purposeNote,
                                 int activeUnused, int consumed, int disabled, int expired) {
    }

    public record RetirementReport(int totalCodes, int activeUnused, int consumed, int disabled, int expired,
                                   int activeGrants, List<BatchBreakdown> byBatch) {
    }

    public RetirementReport report() {
        Map<String, Object> totals = jdbc.queryForMap(
                "SELECT count(*) AS total, "
                        + "count(*) FILTER (WHERE c.status = 'ACTIVE' AND c.consumed_at IS NULL) AS active_unused, "
                        + "count(*) FILTER (WHERE c.consumed_at IS NOT NULL) AS consumed, "
                        + "count(*) FILTER (WHERE c.status = 'DISABLED') AS disabled, "
                        + "count(*) FILTER (WHERE c.expires_at IS NOT NULL AND c.expires_at < now() "
                        + "  AND c.status = 'ACTIVE' AND c.consumed_at IS NULL) AS expired "
                        + "FROM design_access_code c "
                        + "JOIN design_access_code_batch b ON b.id = c.batch_id AND b.deleted = FALSE "
                        + "WHERE c.deleted = FALSE");
        Integer grants = jdbc.queryForObject(
                "SELECT count(*) FROM design_access_grant WHERE status = 'ACTIVE' AND deleted = FALSE",
                Integer.class);
        List<BatchBreakdown> byBatch = jdbc.query(
                "SELECT b.id, b.issued_by, b.purpose_note, "
                        + "count(c.id) FILTER (WHERE c.status='ACTIVE' AND c.consumed_at IS NULL) AS active_unused, "
                        + "count(c.id) FILTER (WHERE c.consumed_at IS NOT NULL) AS consumed, "
                        + "count(c.id) FILTER (WHERE c.status='DISABLED') AS disabled, "
                        + "count(c.id) FILTER (WHERE c.expires_at IS NOT NULL AND c.expires_at < now() "
                        + "  AND c.status='ACTIVE' AND c.consumed_at IS NULL) AS expired "
                        + "FROM design_access_code_batch b "
                        + "LEFT JOIN design_access_code c ON c.batch_id = b.id AND c.deleted = FALSE "
                        + "WHERE b.deleted = FALSE GROUP BY b.id, b.issued_by, b.purpose_note "
                        + "ORDER BY b.id",
                (rs, i) -> new BatchBreakdown(rs.getLong("id"), rs.getString("issued_by"),
                        rs.getString("purpose_note"), rs.getInt("active_unused"),
                        rs.getInt("consumed"), rs.getInt("disabled"), rs.getInt("expired")));
        return new RetirementReport(intOf(totals.get("total")), intOf(totals.get("active_unused")),
                intOf(totals.get("consumed")), intOf(totals.get("disabled")),
                intOf(totals.get("expired")), grants == null ? 0 : grants, byBatch);
    }

    private static int intOf(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }
}
