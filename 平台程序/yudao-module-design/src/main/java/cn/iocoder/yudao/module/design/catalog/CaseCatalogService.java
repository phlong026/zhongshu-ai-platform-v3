package cn.iocoder.yudao.module.design.catalog;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.infra.zhongshu.audit.AuditEventMessage;
import cn.iocoder.yudao.module.infra.zhongshu.audit.AuditPort;
import cn.iocoder.yudao.module.infra.zhongshu.audit.JdbcAuditPort;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.RESOURCE_FORBIDDEN;
import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.STATE_VERSION_CONFLICT;
import static cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.BAD_REQUEST;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;

/**
 * 案例目录（架构 §6.2 / 蓝图 P3B）
 *
 * 合同：
 * - 版本不可变：编辑生成新版本行并 CAS 切换 current_version_id，过期版本返回 STATE_VERSION_CONFLICT；
 * - 列表只返回 PUBLISHED，稳定游标分页（building_area ASC, id ASC），同面积不重页/漏页；
 * - 下架即时不可见；公司案例可重新上架（新发布事实），AI 案例的发布在 P7A 审核链中；
 * - 收藏 (user_id, case_id) 唯一，并发幂等；批量操作逐项返回结果不静默半成功。
 */
@Slf4j
@Service
public class CaseCatalogService {

    public record CaseSummary(long caseId, String title, String sourceType, String styleCode,
                              int floorCount, int buildingArea, String coverAssetId,
                              long version, String publicationStatus,
                              java.time.Instant updatedAt,
                              String uploaderName, String uploaderCompany,
                              boolean homeFeatured,
                              BigDecimal faceWidth, BigDecimal depth) {
    }

    public record CaseDetail(long caseId, String title, String description, String sourceType,
                             String styleCode, int floorCount, int buildingArea,
                             BigDecimal faceWidth, BigDecimal depth,
                             List<String> floorPlanAssetIds, String coverAssetId,
                             String elevationAssetId, String pdfAssetId,
                             long version, String publicationStatus,
                             List<Map<String, Object>> rooms, List<String> tags,
                             long caseVersionId, String dimensionBasis,
                             String uploaderName, String uploaderCompany) {
    }

    private final JdbcTemplate jdbcTemplate;

    private final TransactionTemplate txTemplate;

    private final AuditPort auditPort;

    public CaseCatalogService(DataSource dataSource, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.auditPort = new JdbcAuditPort(dataSource);
    }

    // ========== 后台：创建 / 编辑 / 发布 / 下架 / 批量 ==========

    /** 上传人展示信息（需求 3c：来源显示「公司 · 人名」，无公司只显示人名） */
    public record Uploader(String company, String name) {
        public static final Uploader EMPTY = new Uploader(null, null);
    }

    /**
     * 跨域库表探测：上传人解析依赖平台库表（system_users）或身份库表（account/access_code_redemption），
     * 灵感库合同测试库未建这些表。必须先探测再查询——在事务里对缺失表发 SQL 会把 Postgres 事务打成
     * aborted，catch 异常也救不回来。
     */
    private boolean tableExists(String table) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = current_schema() AND table_name = ?",
                Integer.class, table);
        return count != null && count > 0;
    }

    /**
     * 后台上传人：操作人昵称 + 所属部门名（公司）；平台用户表缺失或查无此人时返回空（前端回退默认来源标签）。
     * 注意：平台底座 system_users/system_dept 的 deleted 是 int2（smallint），
     * 与 boolean 比较在 PostgreSQL 直接报 "operator does not exist"，必须用 = 0。
     */
    public Uploader resolveAdminUploader(long adminUserId) {
        if (!tableExists("system_users")) {
            return Uploader.EMPTY;
        }
        List<Uploader> rows = jdbcTemplate.query(
                "SELECT d.name, u.nickname FROM system_users u "
                        + "LEFT JOIN system_dept d ON d.id = u.dept_id AND d.deleted = 0 "
                        + "WHERE u.id = ? AND u.deleted = 0",
                (rs, i) -> new Uploader(rs.getString(1), rs.getString(2)), adminUserId);
        return rows.isEmpty() ? Uploader.EMPTY : rows.get(0);
    }

    /** 投稿上传人：兑换登记的联系人 + 公司；未登记时回退账号昵称；身份表缺失或查无此人返回空 */
    public Uploader resolveAccountUploader(long accountId) {
        if (!tableExists("account") || !tableExists("access_code_redemption")) {
            return Uploader.EMPTY;
        }
        List<Uploader> rows = jdbcTemplate.query(
                "SELECT r.company_name, COALESCE(NULLIF(r.contact_name, ''), a.nickname) FROM account a "
                        + "LEFT JOIN access_code_redemption r ON r.account_id = a.id AND r.deleted = FALSE "
                        + "WHERE a.id = ? AND a.deleted = FALSE ORDER BY r.id DESC LIMIT 1",
                (rs, i) -> new Uploader(rs.getString(1), rs.getString(2)), accountId);
        return rows.isEmpty() ? Uploader.EMPTY : rows.get(0);
    }

    public long createCompanyCase(long adminUserId, String title, String description, String styleCode,
                                  int floorCount, int buildingArea, BigDecimal faceWidth, BigDecimal depth,
                                  List<Map<String, Object>> rooms, List<String> tags) {
        return createCompanyCase(adminUserId, title, description, styleCode, floorCount, buildingArea,
                faceWidth, depth, rooms, tags, null);
    }

    public long createCompanyCase(long adminUserId, String title, String description, String styleCode,
                                  int floorCount, int buildingArea, BigDecimal faceWidth, BigDecimal depth,
                                  List<Map<String, Object>> rooms, List<String> tags, String dimensionBasis) {
        String basis = CaseDimensionBasis.parse(dimensionBasis).name();
        Uploader uploader = resolveAdminUploader(adminUserId);
        long caseId = IdWorker.getId();
        long versionId = IdWorker.getId();
        txTemplate.execute(status -> {
            jdbcTemplate.update(
                    "INSERT INTO design_case (id, source_type, creator_user_id, current_version_id, "
                            + "publication_status, uploader_name, uploader_company) "
                            + "VALUES (?, 'COMPANY', ?, ?, 'DRAFT', ?, ?)",
                    caseId, adminUserId, versionId, uploader.name(), uploader.company());
            jdbcTemplate.update(
                    "INSERT INTO design_case_version (id, case_id, version, title, description, style_code, "
                            + "floor_count, building_area, face_width, depth, rooms, tags, dimension_basis) "
                            + "VALUES (?, ?, 1, ?, ?, ?, ?, ?, ?, ?, "
                            + "CAST(? AS jsonb), CAST(? AS jsonb), ?)",
                    versionId, caseId, title, description, styleCode, floorCount, buildingArea,
                    faceWidth, depth, toJson(rooms), toJsonStrings(tags), basis);
            return null;
        });
        return caseId;
    }

    /** 编辑：生成新版本行（旧版本不可变）并 CAS 切换；expectedVersion 不匹配抛 STATE_VERSION_CONFLICT */
    public long updateCase(long caseId, long adminUserId, long expectedVersion, String title,
                           String description, String styleCode, int floorCount, int buildingArea,
                           BigDecimal faceWidth, BigDecimal depth, List<Map<String, Object>> rooms,
                           List<String> tags) {
        return updateCase(caseId, adminUserId, expectedVersion, title, description, styleCode, floorCount,
                buildingArea, faceWidth, depth, rooms, tags, null);
    }

    /** A legacy editor omitting the new basis preserves the existing version's recorded meaning. */
    public long updateCase(long caseId, long adminUserId, long expectedVersion, String title,
                           String description, String styleCode, int floorCount, int buildingArea,
                           BigDecimal faceWidth, BigDecimal depth, List<Map<String, Object>> rooms,
                           List<String> tags, String dimensionBasis) {
        return txTemplate.execute(status -> {
            List<Map<String, Object>> caseRows = jdbcTemplate.queryForList(
                    "SELECT current_version_id, publication_status, source_type FROM design_case "
                            + "WHERE id = ? AND deleted = FALSE FOR UPDATE", caseId);
            if (caseRows.isEmpty() || caseRows.get(0).get("current_version_id") == null) {
                throw exception(RESOURCE_FORBIDDEN);
            }
            Map<String, Object> c = caseRows.get(0);
            // 2026-10-02 运营决策：AI 案例同样可编辑参数（运营纠错）；已上架免下架直接编辑（版本 CAS 留痕，即时生效）
            Long currentVersionId = ((Number) c.get("current_version_id")).longValue();
            Long currentVersion = jdbcTemplate.queryForObject(
                    "SELECT version FROM design_case_version WHERE id = ?", Long.class, currentVersionId);
            if (currentVersion == null || currentVersion != expectedVersion) {
                throw exception(STATE_VERSION_CONFLICT);
            }
            String basis = dimensionBasis == null
                    ? jdbcTemplate.queryForObject("SELECT dimension_basis FROM design_case_version WHERE id = ?",
                        String.class, currentVersionId)
                    : CaseDimensionBasis.parse(dimensionBasis).name();
            long newVersionId = IdWorker.getId();
            jdbcTemplate.update(
                    "INSERT INTO design_case_version (id, case_id, version, title, description, style_code, "
                            + "floor_count, building_area, face_width, depth, rooms, tags, creator, dimension_basis) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?)",
                    newVersionId, caseId, expectedVersion + 1, title, description, styleCode,
                    floorCount, buildingArea, faceWidth, depth, toJson(rooms), toJsonStrings(tags), String.valueOf(adminUserId), basis);
            jdbcTemplate.update(
                    "UPDATE design_case SET current_version_id = ?, updater = ?, update_time = now() "
                            + "WHERE id = ? AND current_version_id = ?",
                    newVersionId, String.valueOf(adminUserId), caseId, currentVersionId);
            // 资产关系随版本继承（资产行数少，Java 端逐行生成新 ID）
            var assets = jdbcTemplate.queryForList(
                    "SELECT asset_id, asset_role, floor_no FROM design_case_asset "
                            + "WHERE case_version_id = ? AND deleted = FALSE", currentVersionId);
            for (Map<String, Object> a : assets) {
                jdbcTemplate.update(
                        "INSERT INTO design_case_asset (id, case_version_id, asset_id, asset_role, floor_no) "
                                + "VALUES (?, ?, ?, ?, ?)",
                        IdWorker.getId(), newVersionId,
                        ((Number) a.get("asset_id")).longValue(),
                        (String) a.get("asset_role"),
                        a.get("floor_no") == null ? null : ((Number) a.get("floor_no")).intValue());
            }
            log.info("[updateCase][case={} v{} → v{}]", caseId, expectedVersion, expectedVersion + 1);
            return newVersionId;
        });
    }

    /** 上传前后都校验状态与版本，避免慢扫描期间发生覆盖。 */
    public CaseDetail requireImageEditable(long caseId, long expectedVersion, String role, Integer floorNo) {
        var detail = getAdminCase(caseId).orElseThrow(() -> exception(RESOURCE_FORBIDDEN));
        // 图纸更换仍限公司案例（AI 案例图纸来自投稿冻结版本，走审核链）；
        // 2026-10-02 决策②：已上架免下架直接换图（版本化替换留痕，即时生效）
        if (!"COMPANY".equals(detail.sourceType()))
            throw new ServiceException(RESOURCE_FORBIDDEN.getCode(), "AI 案例图纸由投稿审核流程管理，此处仅支持编辑参数");
        if (detail.version() != expectedVersion) throw exception(STATE_VERSION_CONFLICT);
        if (role == null || !List.of("COVER", "ELEVATION", "FLOOR_PLAN").contains(role)
                || ("FLOOR_PLAN".equals(role) ? floorNo == null || floorNo < 1 || floorNo > detail.floorCount() : floorNo != null))
            throw new ServiceException(RESOURCE_FORBIDDEN.getCode(), "图纸角色或楼层无效");
        return detail;
    }

    /** 换图沿用既有版本化编辑；仅替换新版本对应槽位，不覆盖历史图纸。 */
    public long replaceCompanyImage(long caseId, long adminId, long expectedVersion, long assetId, String role, Integer floorNo) {
        return txTemplate.execute(status -> {
            jdbcTemplate.queryForList("SELECT id FROM design_case WHERE id=? AND deleted=FALSE FOR UPDATE", caseId);
            var detail = requireImageEditable(caseId, expectedVersion, role, floorNo);
            boolean usable = Boolean.TRUE.equals(jdbcTemplate.queryForObject("SELECT EXISTS(SELECT 1 FROM asset WHERE id=? AND owner_user_id=? "
                    + "AND asset_type='CASE_IMAGE' AND source_type='COMPANY' AND upload_status='ACCEPTED' "
                    + "AND security_scan_status='PASSED' AND moderation_status='PASSED' AND deleted=FALSE)", Boolean.class, assetId, adminId));
            if (!usable) throw exception(RESOURCE_FORBIDDEN);
            long versionId = updateCase(caseId, adminId, expectedVersion, detail.title(), detail.description(), detail.styleCode(),
                    detail.floorCount(), detail.buildingArea(), detail.faceWidth(), detail.depth(), detail.rooms(), detail.tags());
            jdbcTemplate.update("DELETE FROM design_case_asset WHERE case_version_id=? AND asset_role=? AND floor_no IS NOT DISTINCT FROM ?", versionId, role, floorNo);
            jdbcTemplate.update("INSERT INTO design_case_asset(id,case_version_id,asset_id,asset_role,floor_no,creator) VALUES(?,?,?,?,?,?)",
                    IdWorker.getId(), versionId, assetId, role, floorNo, String.valueOf(adminId));
            return expectedVersion + 1;
        });
    }

    /** 公司上架的最低图纸门禁，不把缺图或未校验/未授权的资产发布给用户。 */
    public void requireCompanyPublishable(long caseId) {
        var detail = getAdminCase(caseId).orElseThrow(() -> exception(RESOURCE_FORBIDDEN));
        if (!"COMPANY".equals(detail.sourceType())) throw exception(RESOURCE_FORBIDDEN);
        var images = jdbcTemplate.queryForList("SELECT ca.asset_role, ca.floor_no, a.id, a.upload_status, a.security_scan_status, a.moderation_status, "
                + "EXISTS(SELECT 1 FROM asset_rights_grant g WHERE g.asset_id=a.id AND g.scope='PUBLIC_DISPLAY' AND g.status='ACTIVE' "
                + "AND g.effective_at<=now() AND (g.expires_at IS NULL OR g.expires_at>now()) AND g.deleted=FALSE) AS granted "
                + "FROM design_case_asset ca LEFT JOIN asset a ON a.id=ca.asset_id AND a.deleted=FALSE "
                + "JOIN design_case c ON c.current_version_id=ca.case_version_id WHERE c.id=? AND ca.deleted=FALSE", caseId);
        boolean cover = images.stream().anyMatch(i -> "COVER".equals(i.get("asset_role")));
        boolean plan = images.stream().anyMatch(i -> "FLOOR_PLAN".equals(i.get("asset_role")));
        if (!cover || !plan || images.stream().anyMatch(i -> i.get("id") == null || !"ACCEPTED".equals(i.get("upload_status"))
                || !"PASSED".equals(i.get("security_scan_status")) || !"PASSED".equals(i.get("moderation_status")) || !Boolean.TRUE.equals(i.get("granted"))))
            throw new ServiceException(RESOURCE_FORBIDDEN.getCode(), "上架至少需要封面和平面图，所有关联图纸须校验通过并有有效公开展示授权");
    }

    /** 上架：公司案例 DRAFT/OFFLINE → PUBLISHED；AI 案例走审核链。 */
    public boolean publish(long caseId, String operator) {
        return txTemplate.execute(status -> {
            List<String> sourceRows = jdbcTemplate.query(
                    "SELECT source_type FROM design_case WHERE id = ? AND deleted = FALSE FOR UPDATE",
                    (rs, i) -> rs.getString("source_type"), caseId);
            if (sourceRows.isEmpty()) {
                return false; // 不存在/已删：逐项失败，不抛系统异常
            }
            String sourceType = sourceRows.get(0);
            if (!"COMPANY".equals(sourceType)) {
                throw exception(RESOURCE_FORBIDDEN); // AI 案例必须经 P7A 审核与独立发布命令
            }
            Long versionId = jdbcTemplate.queryForObject(
                    "SELECT current_version_id FROM design_case WHERE id = ?", Long.class, caseId);
            requireCompanyPublishable(caseId);
            int updated = jdbcTemplate.update(
                    "UPDATE design_case SET publication_status = 'PUBLISHED', update_time = now() "
                            + "WHERE id = ? AND publication_status IN ('DRAFT','OFFLINE')", caseId);
            if (updated == 1) {
                jdbcTemplate.update(
                        "INSERT INTO case_publication (id, case_id, action, published_version_id, operator_id) "
                                + "VALUES (?, ?, 'PUBLISH', ?, ?)",
                        IdWorker.getId(), caseId, versionId, operator);
            }
            return updated == 1;
        });
    }

    /** 下架：即时不可见；留下架事实与原因 */
    public boolean offline(long caseId, String reason, String operator) {
        return txTemplate.execute(status -> {
            int updated = jdbcTemplate.update(
                    "UPDATE design_case SET publication_status = 'OFFLINE', update_time = now() "
                            + "WHERE id = ? AND publication_status = 'PUBLISHED'", caseId);
            if (updated == 1) {
                jdbcTemplate.update(
                        "INSERT INTO case_publication (id, case_id, action, reason, operator_id) "
                                + "VALUES (?, ?, 'OFFLINE', ?, ?)",
                        IdWorker.getId(), caseId, reason, operator);
            }
            return updated == 1;
        });
    }

    /** 删除仅限已下架案例：软删案例行即全端不可见；版本/资产/发布事实保留可审计，操作人写入 updater */
    public boolean deleteOfflineCase(long caseId, String operator) {
        return Boolean.TRUE.equals(txTemplate.execute(status -> {
            int updated = jdbcTemplate.update(
                    "UPDATE design_case SET deleted = TRUE, updater = ?, update_time = now() "
                            + "WHERE id = ? AND deleted = FALSE AND publication_status = 'OFFLINE'",
                    operator, caseId);
            if (updated != 1) {
                return false;
            }
            auditPort.record(AuditEventMessage.builder()
                    .eventType("ADMIN_DATA_DELETE")
                    .actorType(AuditEventMessage.ActorType.ADMIN)
                    .actorId(operator)
                    .action("DELETE")
                    .bizType("case")
                    .bizId(String.valueOf(caseId))
                    .result(AuditEventMessage.AuditResult.SUCCESS)
                    .detail(Map.of("table", "design_case"))
                    .build());
            return true;
        }));
    }

    /**
     * 管理端上传者展示名（仅后台使用，不进入小程序响应）：公司案例 creator_user_id 指向 system_users，
     * AI 案例（用户投稿）指向 identity 的 account。按 id 直连不做租户过滤（与预算服务 join account 同口径），
     * 已删用户仍按 id 带出历史名字。
     */
    public Map<Long, Uploader> mapAdminUploadersByCaseIds(List<Long> caseIds) {
        if (caseIds == null || caseIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(caseIds.size(), "?"));
        Map<Long, Uploader> uploaders = new java.util.HashMap<>();
        if (tableExists("system_users")) {
            boolean hasDepartment = tableExists("system_dept");
            List<Map<String, Object>> companyRows = jdbcTemplate.queryForList(
                    "SELECT c.id, " + (hasDepartment ? "d.name" : "NULL") + " AS company, u.nickname "
                            + "FROM design_case c JOIN system_users u ON u.id = c.creator_user_id "
                            + (hasDepartment ? "LEFT JOIN system_dept d ON d.id = u.dept_id " : "")
                            + "WHERE c.source_type = 'COMPANY' AND c.id IN (" + placeholders + ")",
                    caseIds.toArray());
            for (Map<String, Object> row : companyRows) {
                uploaders.put(((Number) row.get("id")).longValue(),
                        new Uploader((String) row.get("company"), (String) row.get("nickname")));
            }
        }
        if (tableExists("account")) {
            boolean hasRedemption = tableExists("access_code_redemption");
            List<Map<String, Object>> accountRows = jdbcTemplate.queryForList(
                    "SELECT c.id, " + (hasRedemption ? "r.company_name" : "NULL") + " AS company, "
                            + (hasRedemption ? "COALESCE(NULLIF(r.contact_name, ''), a.nickname)" : "a.nickname")
                            + " AS uploader FROM design_case c JOIN account a ON a.id = c.creator_user_id "
                            + (hasRedemption
                            ? "LEFT JOIN LATERAL (SELECT company_name, contact_name FROM access_code_redemption "
                                    + "WHERE account_id = a.id AND deleted = FALSE ORDER BY id DESC LIMIT 1) r ON TRUE "
                            : "")
                            + "WHERE c.source_type = 'AI' AND c.id IN (" + placeholders + ")",
                    caseIds.toArray());
            for (Map<String, Object> row : accountRows) {
                uploaders.put(((Number) row.get("id")).longValue(),
                        new Uploader((String) row.get("company"), (String) row.get("uploader")));
            }
        }
        return uploaders;
    }

    /** 批量操作：逐项执行、逐项返回结果（不静默半成功） */
    public List<Map<String, Object>> bulkPublicationAction(List<Long> caseIds, boolean publish,
                                                           String reason, String operator) {
        List<Map<String, Object>> items = new ArrayList<>(caseIds.size());
        for (Long caseId : caseIds) {
            String errorCode = null;
            try {
                boolean ok = publish ? publish(caseId, operator) : offline(caseId, reason, operator);
                if (!ok) {
                    errorCode = "PUBLICATION_VALIDATION_FAILED";
                }
            } catch (ServiceException e) {
                errorCode = "PUBLICATION_VALIDATION_FAILED";
            }
            items.add(Map.of("targetId", String.valueOf(caseId),
                    "success", errorCode == null,
                    "errorCode", errorCode == null ? "" : errorCode));
        }
        return items;
    }

    // ========== 小程序：列表 / 详情 / 收藏 / 首页 ==========

    public record CursorPage(List<CaseSummary> list, String nextCursor) {
    }

    public record DimensionPage(List<DimensionCase> list, String nextCursor) {
    }

    public record DimensionCase(long caseId, long caseVersionId, String title, String sourceType,
                                BigDecimal faceWidth, BigDecimal depth, String dimensionBasis,
                                int floorCount, int buildingArea, String coverAssetId,
                                String planPreviewAssetId, List<FloorPlan> floorPlans,
                                boolean generationAllowed) {
    }

    private record DimensionCaseRow(long caseId, long caseVersionId, String title, String sourceType,
                                    BigDecimal faceWidth, BigDecimal depth, String dimensionBasis,
                                    int floorCount, int buildingArea, String coverAssetId,
                                    boolean generationAllowed, String planAssetId, Integer floorNo) {
    }

    private static final String SUMMARY_SELECT =
            "SELECT c.id, v.title, c.source_type, v.style_code, v.floor_count, v.building_area, "
                    + "v.version, c.publication_status, c.uploader_name, c.uploader_company, c.home_featured, "
                    + "v.face_width, v.depth, "
                    + "(SELECT a.asset_id FROM design_case_asset a WHERE a.case_version_id = v.id "
                    + "  AND a.asset_role = 'COVER' AND a.deleted = FALSE LIMIT 1) AS cover_asset_id "
                    + "FROM design_case c JOIN design_case_version v ON v.id = c.current_version_id ";
    private static final org.springframework.jdbc.core.RowMapper<CaseSummary> SUMMARY_MAPPER =
            (rs, i) -> new CaseSummary(rs.getLong("id"), rs.getString("title"),
                    rs.getString("source_type"), rs.getString("style_code"),
                    rs.getInt("floor_count"), rs.getInt("building_area"),
                    rs.getString("cover_asset_id"), rs.getLong("version"),
                    rs.getString("publication_status"), null,
                    rs.getString("uploader_name"), rs.getString("uploader_company"),
                    rs.getBoolean("home_featured"),
                    rs.getBigDecimal("face_width"), rs.getBigDecimal("depth"));

    /**
     * 平面线路的默认参考案例（定稿 7.1-10）：已发布公司案例中"层数一致优先、建筑面积最接近"，
     * 且全部平面图已授权生成参考（否则建任务会被拒）。没有同层数的可用案例时退回全局面积最接近。
     * 只取前 50 个候选再逐个校验授权，避免授权校验逐条扫全表。
     */
    public Optional<CaseSummary> recommendForGeneration(int floorCount, int targetArea) {
        List<CaseSummary> candidates = jdbcTemplate.query(
                SUMMARY_SELECT + " WHERE c.deleted = FALSE AND c.publication_status = 'PUBLISHED' "
                        + "AND c.source_type = 'COMPANY' "
                        + "ORDER BY (v.floor_count = ?) DESC, ABS(v.building_area - ?) ASC, c.id ASC LIMIT 50",
                SUMMARY_MAPPER, floorCount, targetArea);
        return candidates.stream().filter(candidate -> canUseForGeneration(candidate.caseId())).findFirst();
    }

    private static final BigDecimal MIN_CASE_DIMENSION = new BigDecimal("3");
    private static final BigDecimal MAX_CASE_DIMENSION = new BigDecimal("40");

    /**
     * 管理端录入的面宽/进深：空值表示"未填"；合法范围 3~40 米（与用户输入口径一致），保留一位小数。
     * 越界、非数字、NaN/Infinity 一律 400——此前非法值被静默当成"未填"，管理员以为保存成功。
     * 范围判断先于任何取整运算（compareTo/scale 均 O(1)），与 {@link #requireDimensionFilter} 同一防线。
     */
    public static BigDecimal parseDimension(Object value) {
        if (value == null || (value instanceof CharSequence text && text.toString().isBlank())) {
            return null;
        }
        BigDecimal parsed;
        try {
            parsed = value instanceof BigDecimal exact ? exact : new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw invalidCaseDimension();
        }
        if (parsed.compareTo(MIN_CASE_DIMENSION) < 0 || parsed.compareTo(MAX_CASE_DIMENSION) > 0
                || parsed.scale() > 6 || parsed.scale() < -3) {
            throw invalidCaseDimension();
        }
        return parsed.setScale(1, java.math.RoundingMode.HALF_UP);
    }

    private static ServiceException invalidCaseDimension() {
        return new ServiceException(BAD_REQUEST.getCode(), "面宽/进深需为 3~40 米的数字，最多一位小数");
    }

    private static final BigDecimal ONE_METER = BigDecimal.ONE;
    /** 与 face_width/depth 的 NUMERIC(4,1) 上限一致 */
    private static final BigDecimal MAX_DIMENSION_FILTER = new BigDecimal("999.9");

    /**
     * 列表接口匿名可读，面宽/进深必须在做 ±1m 运算之前按量级拒绝：BigDecimal 的 add/subtract 会把两数标度对齐成
     * 等长整数，1E+10000000 实测耗时约 5 秒、1E+100000000 数十秒未结束，1E-10000000 同理。
     * 这里只用 signum/compareTo/scale（均为 O(1)，不展开数字）。
     */
    private static BigDecimal requireDimensionFilter(BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.signum() < 0 || value.compareTo(MAX_DIMENSION_FILTER) > 0
                || value.scale() > 6 || value.scale() < -3) {
            // 沿用框架通用的 400：错误码注册表（ZhongshuErrorCodeRegistryTest）不允许随手新增同义码
            throw new ServiceException(BAD_REQUEST.getCode(), "户型筛选：面宽/进深需在 0~999.9 米，最多 6 位小数");
        }
        return value;
    }

    /** 灵感库列表：仅 PUBLISHED，稳定游标（building_area ASC, id ASC）；
     *  faceWidth/depth 为 ±1m 尺度筛选（输入 13 检出 [12,14] 闭区间，含边界），未填尺寸的案例不参与该筛选；
     *  keyword 为服务端检索：编号精确命中 + 名称模糊 */
    public CursorPage listPublishedCases(String sourceType, String styleCode, Integer floorCount,
                                         Integer minArea, Integer maxArea,
                                         BigDecimal faceWidth, BigDecimal depth, String keyword,
                                         String cursor, int limit) {
        StringBuilder where = new StringBuilder(
                " WHERE c.deleted = FALSE AND c.publication_status = 'PUBLISHED' ");
        List<Object> args = new ArrayList<>();
        if (sourceType != null && !sourceType.isBlank()) {
            where.append(" AND c.source_type = ?");
            args.add(sourceType);
        }
        long[] cursorTuple = decodeCursor(cursor);
        if (cursorTuple != null) {
            where.append(" AND (v.building_area > ? OR (v.building_area = ? AND c.id > ?))");
            args.add(cursorTuple[0]);
            args.add(cursorTuple[0]);
            args.add(cursorTuple[1]);
        }
        if (styleCode != null && !styleCode.isBlank()) {
            where.append(" AND v.style_code = ?");
            args.add(styleCode);
        }
        if (floorCount != null) {
            where.append(" AND v.floor_count = ?");
            args.add(floorCount);
        }
        if (minArea != null) {
            where.append(" AND v.building_area >= ?");
            args.add(minArea);
        }
        if (maxArea != null) {
            where.append(" AND v.building_area <= ?");
            args.add(maxArea);
        }
        BigDecimal checkedFaceWidth = requireDimensionFilter(faceWidth);
        BigDecimal checkedDepth = requireDimensionFilter(depth);
        if (checkedFaceWidth != null) {
            where.append(" AND v.face_width >= ? AND v.face_width <= ?");
            args.add(checkedFaceWidth.subtract(ONE_METER));
            args.add(checkedFaceWidth.add(ONE_METER));
        }
        if (checkedDepth != null) {
            where.append(" AND v.depth >= ? AND v.depth <= ?");
            args.add(checkedDepth.subtract(ONE_METER));
            args.add(checkedDepth.add(ONE_METER));
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            where.append(" AND (v.title LIKE ? ESCAPE '!'");
            args.add("%" + escapeLike(kw) + "%");
            // 编号关联检索：纯数字按案例编号精确命中（雪花 ID 可达 19 位，用 parseLong 兜边界）
            Long numericId = null;
            try {
                numericId = Long.parseLong(kw);
            } catch (NumberFormatException ignored) {
                // 非编号，按纯名称检索
            }
            if (numericId != null) {
                where.append(" OR c.id = ?");
                args.add(numericId);
            }
            where.append(")");
        }
        args.add(Math.min(limit, 50));
        List<CaseSummary> list = jdbcTemplate.query(
                SUMMARY_SELECT + where + " ORDER BY v.building_area ASC, c.id ASC LIMIT ?",
                SUMMARY_MAPPER, args.toArray());
        String nextCursor = list.size() == Math.min(limit, 50) && !list.isEmpty()
                ? encodeCursor(list.get(list.size() - 1).buildingArea(), list.get(list.size() - 1).caseId())
                : null;
        return new CursorPage(list, nextCursor);
    }

    /** LIKE 通配符转义（ESCAPE '!'），配合 keyword 名称模糊检索 */
    private static String escapeLike(String input) {
        return input.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    /** 管理后台「常用风格预设」：与 Admin 案例创建页 STYLE_PRESETS 保持同一套编码 */
    private static final Map<String, String> STYLE_PRESET_LABELS;
    private static final Map<String, String> LEGACY_STYLE_LABELS = Map.of(
            "AMERICAN", "美式", "JAPANESE", "日式", "FRENCH", "法式");

    static {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("NEW_CHINESE", "新中式");
        labels.put("CHINESE", "中式");
        labels.put("EUROPEAN", "欧式");
        labels.put("NEW_ASIAN", "新亚洲");
        labels.put("SONG", "宋式");
        labels.put("COUNTRYSIDE", "田园");
        labels.put("MODERN", "现代");
        STYLE_PRESET_LABELS = Collections.unmodifiableMap(labels);
    }

    /** 风格筛选项：编码 + 中文标签；自定义风格无标签时直接以编码展示（运营录入的自定义编码通常即中文） */
    public record StyleOption(String code, String label) {}

    /** 灵感库风格筛选：当前七项预设固定在前；历史编码与自定义风格仅在已发布案例实际使用时追加。 */
    public List<StyleOption> listPublishedStyles() {
        List<String> usedCodes = jdbcTemplate.queryForList(
                "SELECT DISTINCT v.style_code FROM design_case c "
                        + "JOIN design_case_version v ON v.id = c.current_version_id "
                        + "WHERE c.deleted = FALSE AND c.publication_status = 'PUBLISHED' AND v.deleted = FALSE "
                        + "AND v.style_code IS NOT NULL AND v.style_code <> '' "
                        + "ORDER BY v.style_code",
                String.class);
        Map<String, String> merged = new LinkedHashMap<>(STYLE_PRESET_LABELS);
        for (String code : usedCodes) {
            if (!merged.containsKey(code) && merged.size() < 50) {
                merged.put(code, LEGACY_STYLE_LABELS.getOrDefault(code, code));
            }
        }
        return merged.entrySet().stream()
                .map(entry -> new StyleOption(entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * 运营标记/取消「显示到首页」；仅已上架案例可标记（下架案例标记无意义，且取消标记不受限以便摘除遗留标记）；
     * 返回是否命中未删除案例。operator 仅用于日志留痕（不涉及 PII）。
     */
    public boolean setHomeFeatured(long caseId, boolean featured, Long operator) {
        if (featured) {
            List<Boolean> status = jdbcTemplate.query(
                    "SELECT publication_status = 'PUBLISHED' FROM design_case WHERE id = ? AND deleted = FALSE",
                    (rs, i) -> rs.getBoolean(1), caseId);
            if (status.isEmpty()) {
                return false; // 不存在/已删除：与旧合同一致返回 false，由控制器转为 404/403 语义
            }
            if (!status.get(0)) {
                throw new ServiceException(BAD_REQUEST.getCode(), "仅已上架案例可显示到首页");
            }
        }
        boolean hit = jdbcTemplate.update(
                "UPDATE design_case SET home_featured = ?, home_featured_at = ? "
                        + "WHERE id = ? AND deleted = FALSE",
                featured, featured ? java.time.LocalDateTime.now() : null, caseId) > 0;
        log.warn("[setHomeFeatured][case={} operator={} featured={} hit={}]", caseId, operator, featured, hit);
        return hit;
    }

    /**
     * 首页精选补齐用的「最新发布」：该案例 case_publication 中上架动作（action='PUBLISH'，
     * 见建表 CHECK 约束 action IN ('PUBLISH','OFFLINE')）的最大 create_time。
     */
    private static final String LAST_PUBLISH_JOIN =
            " LEFT JOIN LATERAL (SELECT max(p.create_time) AS last_published_at FROM case_publication p "
                    + "WHERE p.case_id = c.id AND p.action = 'PUBLISH') pub ON TRUE ";

    /**
     * 首页精选：运营标记优先（按标记时间倒序），未标记的按「最新发布」补齐
     * （发布时间真值取 case_publication 最近一次 PUBLISH 的 create_time，倒序；同时间按 id 升序）。
     */
    public List<CaseSummary> listHomeCases(int limit) {
        return jdbcTemplate.query(
                SUMMARY_SELECT + LAST_PUBLISH_JOIN
                        + " WHERE c.deleted = FALSE AND c.publication_status = 'PUBLISHED' "
                        + "ORDER BY c.home_featured DESC, c.home_featured_at DESC NULLS LAST, "
                        + "pub.last_published_at DESC NULLS LAST, c.id ASC LIMIT ?",
                SUMMARY_MAPPER, Math.min(limit, 50));
    }

    /**
     * 外观设计平面（需求 6）：面宽/进深 ±1m 双维度命中，与输入尺寸最接近的排前面
     * （距离 = |面宽差| + |进深差|，需求方 2026-10-16 确认口径）。
     * 命中集合有界，一次取前 50 不提供游标；两面都必填（无尺寸案例不参与，与灵感库筛选同口径）。
     */
    public DimensionPage listCasesByDimension(BigDecimal faceWidth, BigDecimal depth, int limit) {
        return listCasesByDimension(faceWidth, depth, CaseDimensionBasis.BUILDING_OUTLINE.name(), limit);
    }

    public DimensionPage listCasesByDimension(BigDecimal faceWidth, BigDecimal depth,
                                               String dimensionBasis, int limit) {
        BigDecimal width = requireDimensionFilter(faceWidth);
        BigDecimal depthChecked = requireDimensionFilter(depth);
        if (width == null || depthChecked == null) {
            throw new ServiceException(BAD_REQUEST.getCode(), "请填写面宽和进深（3~999.9 米）");
        }
        CaseDimensionBasis basis = CaseDimensionBasis.parse(dimensionBasis);
        if (basis == CaseDimensionBasis.UNKNOWN) {
            throw new ServiceException(BAD_REQUEST.getCode(), "按尺寸检索必须选择 SITE 或 BUILDING_OUTLINE 口径");
        }
        String sql = """
                WITH matched_cases AS (
                    SELECT c.id AS case_id, v.id AS case_version_id, v.title, c.source_type,
                           v.face_width, v.depth, v.dimension_basis, v.floor_count, v.building_area,
                           (ABS(v.face_width - ?) + ABS(v.depth - ?)) AS match_distance,
                           (SELECT ca.asset_id FROM design_case_asset ca
                            WHERE ca.case_version_id = v.id AND ca.asset_role = 'COVER'
                              AND ca.deleted = FALSE AND ca.tenant_id = 0
                            ORDER BY ca.id LIMIT 1) AS cover_asset_id,
                           NOT EXISTS (
                               SELECT 1 FROM design_case_asset ga
                               WHERE ga.case_version_id = v.id AND ga.asset_role = 'FLOOR_PLAN'
                                 AND ga.deleted = FALSE
                                 AND NOT EXISTS (
                                     SELECT 1 FROM asset_rights_grant gg
                                     WHERE gg.asset_id = ga.asset_id AND gg.tenant_id = 0
                                       AND gg.scope = 'GENERATION_REFERENCE' AND gg.status = 'ACTIVE'
                                       AND gg.deleted = FALSE AND gg.effective_at <= now()
                                       AND (gg.expires_at IS NULL OR gg.expires_at > now())
                                 )
                           ) AS generation_allowed
                    FROM design_case c
                    JOIN design_case_version v ON v.id = c.current_version_id AND v.case_id = c.id
                    WHERE c.tenant_id = 0 AND v.tenant_id = 0
                      AND c.deleted = FALSE AND v.deleted = FALSE
                      AND c.publication_status = 'PUBLISHED'
                      AND v.dimension_basis = ?
                      AND v.face_width BETWEEN ? AND ? AND v.depth BETWEEN ? AND ?
                      AND EXISTS (
                          SELECT 1 FROM design_case_asset pa
                          JOIN asset p ON p.id = pa.asset_id AND p.deleted = FALSE AND p.tenant_id = 0
                          WHERE pa.case_version_id = v.id AND pa.asset_role = 'FLOOR_PLAN'
                            AND pa.deleted = FALSE AND pa.tenant_id = 0
                            AND p.upload_status = 'ACCEPTED'
                            AND p.security_scan_status = 'PASSED' AND p.moderation_status = 'PASSED'
                            AND EXISTS (
                                SELECT 1 FROM asset_rights_grant pg
                                WHERE pg.asset_id = pa.asset_id AND pg.tenant_id = 0
                                  AND pg.scope = 'PUBLIC_DISPLAY' AND pg.status = 'ACTIVE'
                                  AND pg.deleted = FALSE AND pg.effective_at <= now()
                                  AND (pg.expires_at IS NULL OR pg.expires_at > now())
                            )
                      )
                      AND NOT EXISTS (
                          SELECT 1 FROM design_case_asset pa
                          LEFT JOIN asset p ON p.id = pa.asset_id AND p.deleted = FALSE AND p.tenant_id = 0
                          WHERE pa.case_version_id = v.id AND pa.asset_role = 'FLOOR_PLAN'
                            AND pa.deleted = FALSE
                            AND (pa.tenant_id <> 0 OR p.id IS NULL
                              OR p.upload_status IS DISTINCT FROM 'ACCEPTED'
                              OR p.security_scan_status IS DISTINCT FROM 'PASSED'
                              OR p.moderation_status IS DISTINCT FROM 'PASSED'
                              OR NOT EXISTS (
                                  SELECT 1 FROM asset_rights_grant pg
                                  WHERE pg.asset_id = pa.asset_id AND pg.tenant_id = 0
                                    AND pg.scope = 'PUBLIC_DISPLAY' AND pg.status = 'ACTIVE'
                                    AND pg.deleted = FALSE AND pg.effective_at <= now()
                                    AND (pg.expires_at IS NULL OR pg.expires_at > now())
                              ))
                      )
                    ORDER BY match_distance ASC, v.building_area ASC, c.id ASC
                    LIMIT ?
                )
                SELECT mc.*, pa.asset_id AS plan_asset_id, pa.floor_no
                FROM matched_cases mc
                JOIN design_case_asset pa ON pa.case_version_id = mc.case_version_id
                  AND pa.asset_role = 'FLOOR_PLAN' AND pa.deleted = FALSE AND pa.tenant_id = 0
                ORDER BY mc.match_distance ASC, mc.building_area ASC, mc.case_id ASC,
                         pa.floor_no NULLS LAST, pa.id
                """;
        List<DimensionCaseRow> rows = jdbcTemplate.query(sql,
                (rs, i) -> new DimensionCaseRow(
                        rs.getLong("case_id"), rs.getLong("case_version_id"), rs.getString("title"),
                        rs.getString("source_type"), rs.getBigDecimal("face_width"), rs.getBigDecimal("depth"),
                        rs.getString("dimension_basis"), rs.getInt("floor_count"), rs.getInt("building_area"),
                        rs.getString("cover_asset_id"), rs.getBoolean("generation_allowed"),
                        rs.getString("plan_asset_id"), (Integer) rs.getObject("floor_no")),
                width, depthChecked, basis.name(),
                width.subtract(ONE_METER), width.add(ONE_METER),
                depthChecked.subtract(ONE_METER), depthChecked.add(ONE_METER),
                Math.min(Math.max(limit, 0), 50));
        Map<Long, List<DimensionCaseRow>> grouped = new LinkedHashMap<>();
        for (DimensionCaseRow row : rows) {
            grouped.computeIfAbsent(row.caseVersionId(), ignored -> new ArrayList<>()).add(row);
        }
        List<DimensionCase> list = grouped.values().stream().map(caseRows -> {
            DimensionCaseRow first = caseRows.get(0);
            List<FloorPlan> plans = caseRows.stream()
                    .map(row -> new FloorPlan(row.planAssetId(), row.floorNo()))
                    .toList();
            return new DimensionCase(first.caseId(), first.caseVersionId(), first.title(), first.sourceType(),
                    first.faceWidth(), first.depth(), first.dimensionBasis(), first.floorCount(),
                    first.buildingArea(), first.coverAssetId(), plans.get(0).assetId(),
                    plans, first.generationAllowed());
        }).toList();
        return new DimensionPage(list, null);
    }

    /** 详情：仅 PUBLISHED 对外可见（后台管理另行查询） */
    public Optional<CaseDetail> getPublishedCase(long caseId) {
        return getCaseDetail(caseId, true);
    }

    public Optional<CaseDetail> getAdminCase(long caseId) {
        return getCaseDetail(caseId, false);
    }

    private Optional<CaseDetail> getCaseDetail(long caseId, boolean publishedOnly) {
        List<CaseDetail> rows = jdbcTemplate.query(
                "SELECT c.id, v.id AS version_row_id, v.title, v.description, c.source_type, "
                        + "v.style_code, v.floor_count, v.building_area, v.face_width, v.depth, "
                        + "v.version, c.publication_status, v.rooms, v.tags, v.dimension_basis, "
                        + "c.uploader_name, c.uploader_company FROM design_case c "
                        + "JOIN design_case_version v ON v.id = c.current_version_id "
                        + "WHERE c.id = ? AND c.deleted = FALSE AND v.deleted = FALSE"
                        + (publishedOnly ? " AND c.publication_status = 'PUBLISHED'" : ""),
                (rs, i) -> {
                    long vid = rs.getLong("version_row_id");
                    return new CaseDetail(caseId, rs.getString("title"), rs.getString("description"),
                            rs.getString("source_type"), rs.getString("style_code"),
                            rs.getInt("floor_count"), rs.getInt("building_area"),
                            rs.getBigDecimal("face_width"), rs.getBigDecimal("depth"),
                            queryAssetIds(vid, "FLOOR_PLAN"),
                            querySingleAsset(vid, "COVER"),
                            querySingleAsset(vid, "ELEVATION"),
                            querySingleAsset(vid, "PDF"),
                            rs.getLong("version"), rs.getString("publication_status"),
                            fromJson(rs.getString("rooms")), fromJsonStrings(rs.getString("tags")),
                            vid, rs.getString("dimension_basis"),
                            rs.getString("uploader_name"), rs.getString("uploader_company"));
                },
                caseId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public record FloorPlan(String assetId, Integer floorNo) {}

    /** Caller already checked case visibility; always read assets from the returned version. */
    public List<FloorPlan> getFloorPlansForVersion(long caseVersionId) {
        return jdbcTemplate.query("SELECT asset_id, floor_no FROM design_case_asset "
                        + "WHERE case_version_id = ? AND deleted = FALSE AND asset_role = 'FLOOR_PLAN' "
                        + "ORDER BY floor_no NULLS FIRST, id",
                (rs, i) -> new FloorPlan(rs.getString("asset_id"), (Integer) rs.getObject("floor_no")), caseVersionId);
    }

    /** 保留实际楼层编号；未分层的方案图不得伪装为第一层。调用方先校验案例可见性。 */
    public List<FloorPlan> getFloorPlans(long caseId) {
        return jdbcTemplate.query("SELECT a.asset_id, a.floor_no FROM design_case_asset a "
                        + "JOIN design_case c ON c.current_version_id = a.case_version_id "
                        + "WHERE c.id = ? AND c.deleted = FALSE AND a.deleted = FALSE "
                        + "AND a.asset_role = 'FLOOR_PLAN' ORDER BY a.floor_no NULLS FIRST, a.id",
                (rs, i) -> new FloorPlan(rs.getString("asset_id"), (Integer) rs.getObject("floor_no")), caseId);
    }

    /** 与生成时的最终授权校验保持同一口径；入口提示不能替代任务创建时的校验。 */
    public boolean canUseForGeneration(long caseId) {
        return findGenerationAllowedCaseIds(List.of(caseId)).contains(caseId);
    }

    /** 列表授权提示批量读取：一次查询返回仍可用于生成的案例，最终任务创建仍会再次校验授权。 */
    public Set<Long> findGenerationAllowedCaseIds(List<Long> caseIds) {
        if (caseIds == null || caseIds.isEmpty()) {
            return Set.of();
        }
        List<Long> distinctIds = caseIds.stream().distinct().toList();
        String placeholders = String.join(",", Collections.nCopies(distinctIds.size(), "?"));
        List<Long> allowed = jdbcTemplate.queryForList("SELECT c.id FROM design_case c "
                        + "JOIN design_case_asset a ON a.case_version_id = c.current_version_id "
                        + "WHERE c.id IN (" + placeholders + ") "
                        + "AND c.deleted = FALSE AND c.publication_status = 'PUBLISHED' "
                        + "AND a.asset_role = 'FLOOR_PLAN' AND a.deleted = FALSE "
                        + "GROUP BY c.id HAVING count(*) > 0 AND bool_and(EXISTS ("
                        + "SELECT 1 FROM asset_rights_grant g WHERE g.asset_id = a.asset_id "
                        + "AND g.scope = 'GENERATION_REFERENCE' AND g.status = 'ACTIVE' "
                        + "AND g.effective_at <= now() AND (g.expires_at IS NULL OR g.expires_at > now()) "
                        + "AND g.deleted = FALSE))",
                Long.class, distinctIds.toArray());
        return Collections.unmodifiableSet(new LinkedHashSet<>(allowed));
    }

    private static final String ADMIN_CASE_FROM =
            " FROM design_case c LEFT JOIN design_case_version v ON v.id = c.current_version_id ";

    /** 管理端分页：只检索当前版本标题，计数与分页共用同一参数化过滤条件。 */
    public long countCasesForAdmin(String sourceType, String publicationStatus) {
        return countCasesForAdmin(sourceType, publicationStatus, null);
    }

    public long countCasesForAdmin(String sourceType, String publicationStatus, String keyword) {
        AdminCaseFilter filter = adminFilter(sourceType, publicationStatus, keyword);
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*)" + ADMIN_CASE_FROM + "WHERE c.deleted = FALSE" + filter.sql(),
                Long.class, filter.arguments());
        return n == null ? 0 : n;
    }

    public List<CaseSummary> pageCasesForAdmin(String sourceType, String publicationStatus,
                                               int pageNo, int pageSize) {
        return pageCasesForAdmin(sourceType, publicationStatus, null, pageNo, pageSize);
    }

    public List<CaseSummary> pageCasesForAdmin(String sourceType, String publicationStatus,
                                               String keyword, int pageNo, int pageSize) {
        AdminCaseFilter filter = adminFilter(sourceType, publicationStatus, keyword);
        return jdbcTemplate.query(
                "SELECT c.id, v.title, c.source_type, v.style_code, v.floor_count, v.building_area, "
                        + "v.version, c.publication_status, c.update_time, c.uploader_name, c.uploader_company, "
                        + "c.home_featured, v.face_width, v.depth, NULL AS cover_asset_id "
                        + ADMIN_CASE_FROM + "WHERE c.deleted = FALSE" + filter.sql()
                        + " ORDER BY c.id DESC LIMIT ? OFFSET ?",
                (rs, i) -> new CaseSummary(rs.getLong("id"), rs.getString("title"),
                        rs.getString("source_type"), rs.getString("style_code"),
                        rs.getInt("floor_count"), rs.getInt("building_area"),
                        rs.getString("cover_asset_id"), rs.getLong("version"),
                        rs.getString("publication_status"),
                        rs.getTimestamp("update_time") == null ? null
                                : rs.getTimestamp("update_time").toInstant(),
                        rs.getString("uploader_name"), rs.getString("uploader_company"),
                        rs.getBoolean("home_featured"),
                        rs.getBigDecimal("face_width"), rs.getBigDecimal("depth")),
                toPagedArgs(filter.arguments(), pageNo, pageSize));
    }

    private record AdminCaseFilter(String sql, Object[] arguments) { }

    private AdminCaseFilter adminFilter(String sourceType, String publicationStatus, String keyword) {
        StringBuilder sb = new StringBuilder();
        List<Object> args = new ArrayList<>();
        if (sourceType != null && !sourceType.isBlank()) {
            sb.append(" AND c.source_type = ?");
            args.add(sourceType);
        }
        if (publicationStatus != null && !publicationStatus.isBlank()) {
            sb.append(" AND c.publication_status = ?");
            args.add(publicationStatus);
        }
        if (keyword != null && !keyword.isBlank()) {
            // STRPOS treats quotes, '%' and '_' literally; input never becomes SQL or a LIKE pattern.
            sb.append(" AND STRPOS(COALESCE(v.title, ''), ?) > 0");
            args.add(keyword.strip());
        }
        return new AdminCaseFilter(sb.toString(), args.toArray());
    }

    private Object[] toPagedArgs(Object[] args, int pageNo, int pageSize) {
        Object[] result = new Object[args.length + 2];
        System.arraycopy(args, 0, result, 0, args.length);
        result[args.length] = Math.min(pageSize, 100);
        result[args.length + 1] = (long) Math.max(pageNo - 1, 0) * pageSize;
        return result;
    }

    // ========== 收藏 ==========

    /** 收藏（并发幂等）：返回是否新增 */
    public boolean favorite(long userId, long caseId) {
        int inserted = jdbcTemplate.update(
                "INSERT INTO case_favorite (id, user_id, case_id) VALUES (?, ?, ?) "
                        + "ON CONFLICT (user_id, case_id) DO NOTHING",
                IdWorker.getId(), userId, caseId);
        return inserted == 1;
    }

    public boolean unfavorite(long userId, long caseId) {
        return jdbcTemplate.update(
                "DELETE FROM case_favorite WHERE user_id = ? AND case_id = ?", userId, caseId) == 1;
    }

    /** 收藏列表：仅 PUBLISHED，按收藏时间倒序游标（id DESC） */
    public CursorPage listFavorites(long userId, String cursor, int limit) {
        long[] cursorTuple = decodeCursor(cursor);
        StringBuilder where = new StringBuilder(
                " WHERE f.user_id = ? AND c.publication_status = 'PUBLISHED' AND c.deleted = FALSE ");
        List<Object> args = new ArrayList<>();
        args.add(userId);
        if (cursorTuple != null) {
            where.append(" AND f.id < ?");
            args.add(cursorTuple[1]);
        }
        args.add(Math.min(limit, 50));
        List<Map<String, Object>> favRows = jdbcTemplate.queryForList(
                "SELECT f.id AS fav_id, c.id AS case_id, v.title, c.source_type, v.style_code, "
                        + "v.floor_count, v.building_area, v.version, c.publication_status "
                        + "FROM case_favorite f "
                        + "JOIN design_case c ON c.id = f.case_id "
                        + "JOIN design_case_version v ON v.id = c.current_version_id "
                        + where + " ORDER BY f.id DESC LIMIT ?",
                args.toArray());
        List<CaseSummary> list = favRows.stream().map(row -> new CaseSummary(
                ((Number) row.get("case_id")).longValue(),
                (String) row.get("title"), (String) row.get("source_type"),
                (String) row.get("style_code"), ((Number) row.get("floor_count")).intValue(),
                ((Number) row.get("building_area")).intValue(), null,
                ((Number) row.get("version")).longValue(),
                (String) row.get("publication_status"), null, null, null, false, null, null)).toList();
        String nextCursor = list.size() == Math.min(limit, 50) && !list.isEmpty()
                ? encodeCursor(0, ((Number) favRows.get(favRows.size() - 1).get("fav_id")).longValue())
                : null;
        return new CursorPage(list, nextCursor);
    }

    // ========== 内部 ==========

    private String querySingleAsset(long versionId, String role) {
        List<String> ids = jdbcTemplate.query(
                "SELECT asset_id FROM design_case_asset WHERE case_version_id = ? AND asset_role = ? "
                        + "AND deleted = FALSE LIMIT 1",
                (rs, i) -> String.valueOf(rs.getLong("asset_id")), versionId, role);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private List<String> queryAssetIds(long versionId, String role) {
        return jdbcTemplate.query(
                "SELECT asset_id FROM design_case_asset WHERE case_version_id = ? AND asset_role = ? "
                        + "AND deleted = FALSE ORDER BY floor_no NULLS FIRST, id",
                (rs, i) -> String.valueOf(rs.getLong("asset_id")), versionId, role);
    }

    private static String toJson(List<Map<String, Object>> value) {
        try {
            return value == null ? null
                    : new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String toJsonStrings(List<String> value) {
        try {
            return value == null ? null
                    : new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Map<String, Object>> fromJson(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {
                    });
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> fromJsonStrings(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                    });
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 游标：Base64(JSON {a: 面积, i: 案例 ID})；收藏列表只用 i */
    static String encodeCursor(long area, long caseId) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"a\":" + area + ",\"i\":" + caseId + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static long[] decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String json = new String(Base64.getUrlDecoder().decode(cursor),
                    java.nio.charset.StandardCharsets.UTF_8);
            long area = Long.parseLong(json.replaceAll(".*\"a\":(-?[0-9]+).*", "$1"));
            long id = Long.parseLong(json.replaceAll(".*\"i\":(-?[0-9]+).*", "$1"));
            return new long[]{area, id};
        } catch (Exception e) {
            throw new ServiceException(400, "游标无效");
        }
    }

}
