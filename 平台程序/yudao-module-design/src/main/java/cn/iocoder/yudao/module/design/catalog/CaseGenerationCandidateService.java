package cn.iocoder.yudao.module.design.catalog;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.RESOURCE_FORBIDDEN;
import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.STATE_VERSION_CONFLICT;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;

/** Read-only real case retrieval in the existing business domain 0 (independent of admin login tenant). */
@Service
public class CaseGenerationCandidateService {
    public record Candidate(String caseId, String caseVersionId, long version, String title,
                            int floorCount, int buildingArea, BigDecimal faceWidth, BigDecimal depth,
                            String dimensionBasis, String coverAssetId,
                            List<CaseCatalogService.FloorPlan> floorPlans) { }

    public record Candidates(List<Candidate> candidates, String matchMode, String notice,
                             String policyVersion, boolean strictFloorCount) { }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate readTx;
    private final CaseMatchProperties policy;

    public CaseGenerationCandidateService(DataSource dataSource, PlatformTransactionManager transactionManager,
                                          CaseMatchProperties policy) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        this.readTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.policy = policy;
    }

    private static final String SELECT = """
            SELECT c.id AS case_id, v.id AS version_id, v.version, v.title,
                   v.floor_count, v.building_area, v.face_width, v.depth, v.dimension_basis,
                   (SELECT ca.asset_id FROM design_case_asset ca
                    JOIN asset cover ON cover.id = ca.asset_id AND cover.deleted = FALSE AND cover.tenant_id = 0
                    WHERE ca.case_version_id = v.id AND ca.asset_role = 'COVER' AND ca.deleted = FALSE AND ca.tenant_id = 0
                      AND cover.upload_status = 'ACCEPTED' AND cover.security_scan_status = 'PASSED'
                      AND cover.moderation_status = 'PASSED'
                      AND EXISTS (SELECT 1 FROM asset_rights_grant g WHERE g.asset_id = ca.asset_id AND g.tenant_id = 0
                          AND g.scope = 'PUBLIC_DISPLAY' AND g.status = 'ACTIVE' AND g.deleted = FALSE
                          AND g.effective_at <= now() AND (g.expires_at IS NULL OR g.expires_at > now()))
                    ORDER BY ca.id LIMIT 1) AS cover_asset_id
            FROM design_case c JOIN design_case_version v ON v.id = c.current_version_id AND v.case_id = c.id
            WHERE c.tenant_id = 0 AND v.tenant_id = 0
              AND c.deleted = FALSE AND v.deleted = FALSE AND c.publication_status = 'PUBLISHED'
              AND c.source_type = 'COMPANY'
              AND EXISTS (SELECT 1 FROM design_case_asset a WHERE a.case_version_id = v.id
                  AND a.asset_role = 'FLOOR_PLAN' AND a.deleted = FALSE AND a.tenant_id = 0)
              AND NOT EXISTS (
                  SELECT 1 FROM design_case_asset a LEFT JOIN asset f ON f.id = a.asset_id AND f.deleted = FALSE AND f.tenant_id = 0
                  WHERE a.case_version_id = v.id AND a.asset_role = 'FLOOR_PLAN' AND a.deleted = FALSE
                    AND (a.tenant_id <> 0 OR f.id IS NULL OR f.upload_status IS DISTINCT FROM 'ACCEPTED'
                      OR f.security_scan_status IS DISTINCT FROM 'PASSED' OR f.moderation_status IS DISTINCT FROM 'PASSED'
                      OR NOT EXISTS (SELECT 1 FROM asset_rights_grant g WHERE g.asset_id = a.asset_id AND g.tenant_id = 0
                          AND g.scope = 'GENERATION_REFERENCE' AND g.status = 'ACTIVE' AND g.deleted = FALSE
                          AND g.effective_at <= now() AND (g.expires_at IS NULL OR g.expires_at > now()))
                      OR NOT EXISTS (SELECT 1 FROM asset_rights_grant g WHERE g.asset_id = a.asset_id AND g.tenant_id = 0
                          AND g.scope = 'PUBLIC_DISPLAY' AND g.status = 'ACTIVE' AND g.deleted = FALSE
                          AND g.effective_at <= now() AND (g.expires_at IS NULL OR g.expires_at > now()))))
            """;

    public Candidates find(int floorCount, BigDecimal faceWidth, BigDecimal depth, String dimensionBasis) {
        if (floorCount < 1 || floorCount > 4) throw new ServiceException(400, "层数需为 1~4");
        CaseDimensionBasis basis = CaseDimensionBasis.parse(dimensionBasis);
        BigDecimal width = checkedDimension(faceWidth);
        BigDecimal length = checkedDimension(depth);
        boolean precise = basis != CaseDimensionBasis.UNKNOWN && width != null && length != null
                && policy.hasConfirmedTolerance();
        return readTx.execute(status -> {
            StringBuilder sql = new StringBuilder(SELECT);
            var args = new ArrayList<Object>();
            if (policy.isStrictFloorCount()) {
                sql.append(" AND v.floor_count = ?");
                args.add(floorCount);
            }
            if (precise) {
                BigDecimal tolerance = policy.getDimensionToleranceMeters();
                sql.append(" AND v.dimension_basis = ? AND v.face_width BETWEEN ? AND ? AND v.depth BETWEEN ? AND ?");
                args.add(basis.name());
                args.add(width.subtract(tolerance)); args.add(width.add(tolerance));
                args.add(length.subtract(tolerance)); args.add(length.add(tolerance));
            }
            sql.append(" ORDER BY (v.floor_count = ?) DESC");
            args.add(floorCount);
            if (precise) {
                sql.append(", (ABS(v.face_width - ?) + ABS(v.depth - ?)) ASC");
                args.add(width); args.add(length);
            }
            sql.append(", c.id ASC, v.id ASC LIMIT 5");
            List<Candidate> found = query(sql.toString(), args.toArray());
            String notice = precise
                    ? "按已确认的相同尺寸口径筛选；候选不足时如实显示，不扩大尺寸范围。"
                    : "当前未启用精确尺寸匹配，以下仅供参考，不表示尺寸适配；请核对图纸后选择。";
            notice += policy.isStrictFloorCount() ? "仅显示相同层数。" : "相同层数优先，其他层数会明确展示。";
            return new Candidates(found, precise ? "DIMENSION_MATCH" : "REFERENCE_ONLY", notice,
                    policy.getPolicyVersion(), policy.isStrictFloorCount());
        });
    }

    /** Revalidate the selected version and current grants on every preview/confirmation. */
    public Candidate requireCandidate(long caseId, long caseVersionId) {
        if (caseId <= 0 || caseVersionId <= 0) throw new ServiceException(400, "案例及版本编号无效");
        return readTx.execute(status -> {
            List<Candidate> rows = query(SELECT + " AND c.id = ? AND v.id = ?", caseId, caseVersionId);
            if (!rows.isEmpty()) return rows.get(0);
            List<Long> current = jdbc.queryForList("SELECT current_version_id FROM design_case "
                    + "WHERE id = ? AND tenant_id = 0 AND deleted = FALSE AND publication_status = 'PUBLISHED'", Long.class, caseId);
            if (!current.isEmpty() && current.get(0) != null && current.get(0) != caseVersionId) {
                throw exception(STATE_VERSION_CONFLICT);
            }
            throw exception(RESOURCE_FORBIDDEN);
        });
    }

    private List<Candidate> query(String sql, Object... args) {
        return jdbc.query(sql, (rs, index) -> {
            long versionId = rs.getLong("version_id");
            List<CaseCatalogService.FloorPlan> plans = jdbc.query(
                    "SELECT asset_id, floor_no FROM design_case_asset WHERE case_version_id = ? "
                            + "AND asset_role = 'FLOOR_PLAN' AND deleted = FALSE AND tenant_id = 0 ORDER BY floor_no NULLS FIRST, id",
                    (plan, i) -> new CaseCatalogService.FloorPlan(plan.getString("asset_id"),
                            (Integer) plan.getObject("floor_no")), versionId);
            return new Candidate(rs.getString("case_id"), String.valueOf(versionId), rs.getLong("version"),
                    rs.getString("title"), rs.getInt("floor_count"), rs.getInt("building_area"),
                    rs.getBigDecimal("face_width"), rs.getBigDecimal("depth"), rs.getString("dimension_basis"),
                    rs.getString("cover_asset_id"), List.copyOf(plans));
        }, args);
    }

    private static BigDecimal checkedDimension(BigDecimal value) {
        if (value == null) return null;
        if (value.scale() < -3 || value.scale() > 6 || value.compareTo(BigDecimal.valueOf(3)) < 0
                || value.compareTo(BigDecimal.valueOf(40)) > 0) {
            throw new ServiceException(400, "面宽、进深需为 3~40 米，最多 6 位小数");
        }
        return value;
    }
}
